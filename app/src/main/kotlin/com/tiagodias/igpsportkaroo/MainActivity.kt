package com.tiagodias.igpsportkaroo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.tiagodias.igpsportkaroo.automation.RideStart
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.SmartConfig
import com.tiagodias.igpsportkaroo.ui.FieldUi
import com.tiagodias.igpsportkaroo.ui.SmartFeatures
import com.tiagodias.igpsportkaroo.ui.buttonBackground
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The app page: BLE permissions (a Karoo extension is a service with no UI, so they are requested here), the
 * ride field's controls, the light's automatic features and the ride settings. The light part is rendered from
 * the same [FieldUi] model as the ride field.
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private var refreshJob: Job? = null
    private lateinit var settings: Settings

    private lateinit var permissionStatus: TextView
    private lateinit var headline: TextView
    private lateinit var footer: TextView
    private lateinit var autoNote: TextView
    private val controls = linkedMapOf<FieldUi.Kind, Button>()
    private lateinit var retry: Button
    private lateinit var features: LinearLayout
    private val featureSwitches = mutableMapOf<Int, Switch>()
    private val rideStartChoices = RideStart.choices(Settings.CHOOSABLE_MODES)
    private lateinit var rideStartLabels: ArrayAdapter<String>

    /** What is on screen: views are only updated when the state changes, the switch list when the features do. */
    private var renderedState: LightState? = null
    private var renderedFeatures: List<SmartFeatures.Feature>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val content = pageColumn()
        permissionStatus = text(size = 14f).also(content::addView)
        headline = text(size = 26f, bold = true).also(content::addView)
        footer = text(size = 16f).also(content::addView)
        autoNote = text(size = 13f).apply { alpha = 0.7f }.also(content::addView)
        addControls(content)
        retry = Button(this).apply {
            setText(R.string.retry_connection)
            setOnClickListener { LightHub.session?.reconnectNow() }
        }.also(content::addView)

        content.addView(section(R.string.section_features))
        features = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(content::addView)

        content.addView(
            Button(this).apply {
                setText(R.string.customise_modes)
                textSize = 18f
                isAllCaps = false
                setOnClickListener { startActivity(Intent(this@MainActivity, CustomModesActivity::class.java)) }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) },
        )

        content.addView(section(R.string.section_ride))
        addRideSettings(content)

        content.addView(section(R.string.section_about))
        addAboutSection(content)

        setContentView(ScrollView(this).apply { addView(content) })

        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        updatePermissionStatus()
    }

    /** Polls the light's state only while the page is visible. */
    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                render(LightHub.session?.state?.value ?: LightState())
                delay(REFRESH_MS)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        onScreen = true
    }

    override fun onPause() {
        onScreen = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        permissionStatus.setText(if (Permissions.missing(this).isEmpty()) R.string.perm_granted else R.string.perm_needed)
    }

    /** SOLID / FLASH / AUTO / OFF in a 2×2 grid, doing what the ride field's buttons do. */
    private fun addControls(content: LinearLayout) {
        FieldUi.Kind.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { kind ->
                val button = Button(this).apply {
                    textSize = 20f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    minHeight = dp(72)
                    setOnClickListener { tap(kind) }
                }
                row.addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                controls[kind] = button
            }
            content.addView(row)
        }
    }

    private fun tap(kind: FieldUi.Kind) {
        val session = LightHub.session
        val sent = when (kind) {
            FieldUi.Kind.SOLID -> session?.selectSolid()
            FieldUi.Kind.FLASH -> session?.selectFlash()
            FieldUi.Kind.AUTO -> session?.selectAuto()
            FieldUi.Kind.OFF -> session?.selectMode(LightModes.OFF)
        } ?: false
        Timber.i("App page tap: %s (sent=%b)", kind, sent)
    }

    private fun addRideSettings(content: LinearLayout) {
        content.addView(text(size = 16f).apply { setText(R.string.settings_ride_start) })
        val choices = rideStartChoices
        rideStartLabels = ArrayAdapter(this, android.R.layout.simple_spinner_item, rideStartLabels(emptyMap()).toMutableList()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        content.addView(
            Spinner(this).apply {
                adapter = rideStartLabels
                setSelection(choices.indexOf(settings.rideStart).coerceAtLeast(0), false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val choice = choices[position]
                        if (choice != settings.rideStart) settings.rideStart = choice
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            },
        )
        content.addView(settingSwitch(R.string.off_at_ride_end, settings.offAtRideEnd) { settings.offAtRideEnd = it })
        content.addView(settingSwitch(R.string.low_battery_alerts, settings.lowBatteryAlerts) { settings.lowBatteryAlerts = it })
    }

    /** The ride-start choices' labels; an edited custom slot says what it now does ("C1 30%") instead of "LOW". */
    private fun rideStartLabels(customs: Map<Int, CustomModeConfig>): List<String> = rideStartChoices.map {
        when (it) {
            RideStart.None -> getString(R.string.none)
            RideStart.Auto -> getString(R.string.ride_start_auto)
            is RideStart.Mode -> LightModes.label(it.mode, customs)
        }
    }

    /** Relabels the ride-start choices once a custom slot's config is known; the last known labels stay otherwise. */
    private fun renderRideStartLabels(state: LightState) {
        if (state.customModes.isEmpty()) return
        val labels = rideStartLabels(state.customModes)
        if ((0 until rideStartLabels.count).map(rideStartLabels::getItem) == labels) return
        rideStartLabels.setNotifyOnChange(false)
        rideStartLabels.clear()
        rideStartLabels.addAll(labels)
        rideStartLabels.notifyDataSetChanged()
    }

    /** Small, dim credits at the bottom of the app page: version, author (tappable), protocol research, disclaimer. */
    private fun addAboutSection(content: LinearLayout) {
        content.addView(dimText(getString(R.string.about_version, BuildConfig.VERSION_NAME)))
        content.addView(dimText(getString(R.string.about_author)).apply { setOnClickListener { openGithub() } })
        content.addView(dimText(getString(R.string.about_protocol)))
        content.addView(dimText(getString(R.string.about_disclaimer)))
    }

    private fun dimText(value: String) = text(size = 12f).apply {
        text = value
        alpha = 0.6f
    }

    private fun openGithub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        } catch (e: ActivityNotFoundException) {
            Timber.w("No browser available to open %s", GITHUB_URL)
        }
    }

    private fun render(state: LightState) {
        if (state == renderedState) return
        renderedState = state
        val ui = FieldUi.from(state)
        headline.text = ui.headline
        // While searching the headline says so; the field's "tap to retry" footer is the Retry button here.
        footer.text = if (ui.connected) ui.footer else ""
        // The light doesn't report when auto light dims or switches off its output (seen on a VS1200S), so this
        // is the only place that warns about it, instead of showing unreliable numbers on the buttons.
        val showAutoNote = ui.connected && state.autoLightOn
        autoNote.text = if (showAutoNote) getString(R.string.auto_light_note) else ""
        autoNote.visibility = if (showAutoNote) View.VISIBLE else View.GONE
        ui.buttons.forEach { b ->
            val view = controls.getValue(b.kind)
            view.text = if (b.detail.isEmpty()) b.label else "${b.label}\n${b.detail}"
            view.isEnabled = b.available && ui.connected
            view.alpha = if (view.isEnabled) 1f else DISABLED_ALPHA
            view.setBackgroundColor(buttonBackground(b).toArgb())
        }
        retry.visibility = if (ui.reconnectable) View.VISIBLE else View.GONE
        renderFeatures(state)
        renderRideStartLabels(state)
    }

    private fun renderFeatures(state: LightState) {
        val visible = SmartFeatures.visible(state.smartConfigs)
        if (visible != renderedFeatures) {
            renderedFeatures = visible
            features.removeAllViews()
            featureSwitches.clear()
            if (visible.isEmpty()) features.addView(text(size = 14f).apply { setText(R.string.features_unknown) })
            visible.forEach { feature ->
                featureSwitches[feature.id] = Switch(this).apply {
                    setText(feature.label)
                    textSize = 18f
                    setPadding(0, dp(8), 0, 0)
                }.also(features::addView)
                feature.subtitle?.let { features.addView(text(size = 13f).apply { setText(it); alpha = 0.7f }) }
            }
        }
        featureSwitches.forEach { (id, switch) ->
            // Detached while refreshing, so showing the light's state never writes it back.
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = state.smartConfigs[id] == SmartConfig.ON
            switch.isEnabled = state.connected
            switch.setOnCheckedChangeListener { _, checked ->
                val sent = LightHub.session?.setSmartConfig(id, checked) ?: false
                Timber.i("App page: smart config %d -> %b (sent=%b)", id, checked, sent)
                // Not sent: the state won't change, so force a refresh to put the switch back.
                if (!sent) renderedState = null
            }
        }
    }

    private fun settingSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        setText(label)
        textSize = 16f
        setPadding(0, dp(12), 0, 0)
        isChecked = checked
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun section(title: Int) = pageSection(title)

    private fun text(size: Float, bold: Boolean = false) = pageText(size, bold)

    companion object {
        /** True while the app page is resumed: the extension checks it after asking Android to open the page. */
        @Volatile
        var onScreen = false
            private set

        private const val REQUEST_PERMISSIONS = 1
        private const val REFRESH_MS = 500L
        private const val DISABLED_ALPHA = 0.4f
        private const val GITHUB_URL = "https://github.com/tiagodias00"
    }
}
