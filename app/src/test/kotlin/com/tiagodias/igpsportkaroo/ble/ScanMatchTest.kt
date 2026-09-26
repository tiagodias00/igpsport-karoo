package com.tiagodias.igpsportkaroo.ble

import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMatchTest {
    @Test
    fun `matches by model name`() {
        assertTrue(ScanMatch.isIgpsLight("VS1200S", emptyList()))
        assertTrue(ScanMatch.isIgpsLight("vs1800s", emptyList()))
        assertTrue(ScanMatch.isIgpsLight("TL30", emptyList()))
        assertTrue(ScanMatch.isIgpsLight("iGPSPORT Light", emptyList()))
    }

    @Test
    fun `matches by advertised marker uuid, any case`() {
        assertTrue(ScanMatch.isIgpsLight(null, listOf(IgpsProtocol.ADVERT_MARKER.uppercase())))
    }

    @Test
    fun `ignores the secondary _U identity and other devices`() {
        assertFalse(ScanMatch.isIgpsLight("VS1800S_U", listOf(IgpsProtocol.ADVERT_MARKER)))
        assertFalse(ScanMatch.isIgpsLight("Garmin HRM", emptyList()))
        assertFalse(ScanMatch.isIgpsLight("VSX", emptyList()))
        assertFalse(ScanMatch.isIgpsLight(null, emptyList()))
    }
}
