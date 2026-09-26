package com.tiagodias.igpsportkaroo

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.protocol.CustomChange
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.ui.CustomEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The custom light-mode editor, opened from the app page. It draws [CustomEditor]'s model and sends its changes
 * to the session: a slider writes when it is released, a pattern when it is tapped (plan decision D1).
 */
class CustomModesActivity : Activity() {
    private val scope = MainScope()
    private var refreshJob: Job? = null
    private lateinit var settings: Settings

    private lateinit var status: TextView
    private lateinit var custom: LinearLayout
    private var customSlot: Int? = null

    /** What is on screen: views are only updated when the state changes (or a redraw is forced by clearing it). */
    private var renderedState: LightState? = null

    /** What the editor was built for (slot, reading, pattern list, slider keys): rebuilt only when it changes. */
    private var renderedShape: Any? = null
    private val seekBars = mutableMapOf<CustomEditor.Key, Pair<SeekBar, TextView>>()
    private val patternButtons = mutableMapOf<Int, RadioButton>()
    private var slotSpinner: Spinner? = null
    private lateinit var preview: Button
    private lateinit var restore: Button

    /** The slider the user is dragging: never overwritten from the light's state until it is released. */
    private var dragging: CustomEditor.Key? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val content = pageColumn()
        content.addView(
            Button(this).apply {
                setText(R.string.custom_back)
                textSize = 16f
                isAllCaps = false
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        content.addView(pageText(size = 26f, bold = true).apply { setText(R.string.custom_title) })
        status = pageText(size = 16f).apply { setText(R.string.custom_searching) }.also(content::addView)
        custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(content::addView)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    /** Polls the light's state only while the page is visible, like the app page. */
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
        dragging = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: LightState) {
        if (state == renderedState) return
        renderedState = state
        status.visibility = if (state.connected) View.GONE else View.VISIBLE
        // Snapshot before any edit control exists, so the first config kept is the one read from the light (D3).
        state.customModes.values.forEach(settings::rememberCustomOriginal)
        val editor = CustomEditor.from(state, customSlot, customSlot?.let(settings::customOriginal))
        customSlot = editor.slot
        // While searching with no slot known yet, the status line says so instead of "no custom modes".
        val unknown = editor.slot == null && !state.connected
        val shape = listOf(unknown, editor.slots, editor.slot, editor.reading, editor.patterns.map { it.subtype }, editor.sliders.map { it.key })
        if (shape != renderedShape) {
            renderedShape = shape
            build(editor, unknown)
        }
        slotSpinner?.isEnabled = state.connected
        editor.patterns.forEach { p ->
            patternButtons[p.subtype]?.apply {
                isChecked = p.selected
                isEnabled = state.connected
            }
        }
        editor.sliders.forEach { s ->
            val (bar, value) = seekBars[s.key] ?: return@forEach
            if (dragging != s.key) bar.progress = s.value
            value.text = "${s.label}: ${if (dragging == s.key) bar.progress else s.value}${s.unit}"
            bar.isEnabled = state.connected
        }
        if (editor.slot != null && !editor.reading) {
            preview.isEnabled = editor.canPreview
            restore.visibility = if (editor.canRestore) View.VISIBLE else View.GONE
        }
    }

    private fun build(editor: CustomEditor, unknown: Boolean) {
        custom.removeAllViews()
        seekBars.clear()
        patternButtons.clear()
        slotSpinner = null
        dragging = null
        val slot = editor.slot
        if (slot == null) {
            if (!unknown) custom.addView(pageText(size = 14f).apply { setText(R.string.custom_none) })
            return
        }
        if (editor.slots.size > 1) {
            slotSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@CustomModesActivity, android.R.layout.simple_spinner_item, editor.slots.map { it.label }).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(editor.slots.indexOfFirst { it.mode == slot }, false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val mode = editor.slots[position].mode
                        if (mode == customSlot) return
                        customSlot = mode
                        renderedState = null // redraw for the new slot
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }.also(custom::addView)
        } else {
            custom.addView(pageText(size = 20f, bold = true).apply {
                text = editor.slots.single().label
                setPadding(0, dp(16), 0, dp(4))
            })
        }
        if (editor.reading) {
            custom.addView(pageText(size = 14f).apply { setText(R.string.custom_reading) })
            return
        }
        if (editor.patterns.size > 1) {
            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            editor.patterns.forEach { p ->
                patternButtons[p.subtype] = RadioButton(this).apply {
                    text = p.label
                    textSize = 18f
                    setOnClickListener { send { cfg -> CustomChange.Pattern(p.subtype).takeIf { cfg.selected != p.subtype } } }
                }.also(group::addView)
            }
            custom.addView(group)
        }
        editor.sliders.forEach { s ->
            val value = pageText(size = 16f).apply { setPadding(0, dp(12), 0, 0) }.also(custom::addView)
            val bar = SeekBar(this).apply {
                min = s.range.first
                max = s.range.last
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) value.text = "${s.label}: $progress${s.unit}"
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {
                        dragging = s.key
                    }

                    override fun onStopTrackingTouch(bar: SeekBar) {
                        dragging = null
                        send { cfg -> CustomEditor.changeFor(cfg, s.key, bar.progress) }
                    }
                })
            }
            custom.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            seekBars[s.key] = bar to value
        }
        custom.addView(pageText(size = 13f).apply {
            setText(R.string.custom_note)
            alpha = 0.7f
            setPadding(0, dp(12), 0, 0)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = Button(this).apply {
            setText(R.string.custom_preview)
            isAllCaps = false
            setOnClickListener {
                val sent = LightHub.session?.selectManualMode(slot) ?: false
                Timber.i("Custom modes page: show custom %d (sent=%b)", slot, sent)
            }
        }
        row.addView(preview, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        restore = Button(this).apply {
            setText(R.string.custom_restore)
            isAllCaps = false
            setOnClickListener {
                val original = settings.customOriginal(slot) ?: return@setOnClickListener
                val sent = LightHub.session?.restoreCustomMode(original) ?: false
                Timber.i("Custom modes page: restore custom %d (sent=%b)", slot, sent)
                if (!sent) renderedState = null
            }
        }
        row.addView(restore, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        custom.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** Sends the change [build] makes from the current slot's config (none = nothing to do); snaps back if not sent. */
    private fun send(build: (CustomModeConfig) -> CustomChange?) {
        val slot = customSlot ?: return
        val session = LightHub.session
        val config = session?.state?.value?.customModes?.get(slot)
        val change = config?.let(build)
        val sent = change != null && session != null && session.changeCustomMode(slot, change)
        Timber.i("Custom modes page: custom %d %s (sent=%b)", slot, change, sent)
        // Not sent (or nothing to send): the state won't change, so force a redraw to put the control back.
        if (!sent) renderedState = null
    }

    companion object {
        private const val REFRESH_MS = 500L
    }
}
