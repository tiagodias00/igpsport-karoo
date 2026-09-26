package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.SmartConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SmartFeaturesTest {
    /** The VS1200S, in the order the light declares them (docs/vs1200s-findings.md). */
    private val vs1200s = linkedMapOf(5 to 0, 3 to 1, 9 to 1, 4 to 1, 13 to 1, 15 to 1)

    @Test
    fun `features follow the app page order, not the light's`() {
        assertEquals(
            listOf(
                SmartConfig.AUTO_LIGHT, SmartConfig.AUTO_SLEEP,
                SmartConfig.AUTO_LOW, SmartConfig.AUTO_LOWBAT, SmartConfig.SYNC_OFF,
            ),
            SmartFeatures.visible(vs1200s).map { it.id },
        )
    }

    @Test
    fun `speed-based brightness is not offered`() {
        // It needs speed from an iGPSPORT computer; on a Karoo it does nothing (user decision, 2026-09-26).
        assertEquals(emptyList<SmartFeatures.Feature>(), SmartFeatures.visible(mapOf(SmartConfig.LUMEN_VARY to 1)))
    }

    @Test
    fun `only declared features are shown`() {
        val some = mapOf(SmartConfig.AUTO_SLEEP to 1, SmartConfig.AUTO_LIGHT to 0)
        assertEquals(listOf(SmartConfig.AUTO_LIGHT, SmartConfig.AUTO_SLEEP), SmartFeatures.visible(some).map { it.id })
    }

    @Test
    fun `unknown configs are hidden`() {
        assertEquals(listOf(SmartConfig.AUTO_LIGHT), SmartFeatures.visible(mapOf(99 to 1, 3 to 1)).map { it.id })
        assertEquals(emptyList<SmartFeatures.Feature>(), SmartFeatures.visible(emptyMap()))
    }

    @Test
    fun `each feature has its own label, and the ones that need explaining a subtitle`() {
        val features = SmartFeatures.visible(vs1200s)
        assertEquals(features.size, features.map { it.label }.toSet().size)
        val explained = setOf(SmartConfig.AUTO_LIGHT, SmartConfig.AUTO_SLEEP, SmartConfig.SYNC_OFF)
        features.forEach { if (it.id in explained) assertNotNull(it.subtitle) else assertNull(it.subtitle) }
    }
}
