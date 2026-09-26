package com.tiagodias.igpsportkaroo.ble

import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol

object ScanMatch {
    private val MODEL_NAME = Regex("^(VS|TL)\\d.*", RegexOption.IGNORE_CASE)

    fun isIgpsLight(name: String?, serviceUuids: List<String>): Boolean {
        // The light also advertises a second identity "<model>_U" at address+1; it is not the control endpoint.
        if (name != null && name.endsWith("_U", ignoreCase = true)) return false
        if (serviceUuids.any { it.equals(IgpsProtocol.ADVERT_MARKER, ignoreCase = true) }) return true
        return name != null && (MODEL_NAME.matches(name) || name.contains("IGPSPORT", ignoreCase = true))
    }
}
