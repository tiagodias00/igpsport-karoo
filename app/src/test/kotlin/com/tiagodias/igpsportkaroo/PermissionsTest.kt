package com.tiagodias.igpsportkaroo

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsTest {
    @Test
    fun `android 12 and later need scan and connect`() {
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            Permissions.required(31),
        )
    }

    @Test
    fun `older android (Karoo 2) needs fine location`() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), Permissions.required(27))
    }
}
