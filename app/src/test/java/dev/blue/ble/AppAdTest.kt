package dev.blue.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAdTest {

    @Test
    fun roundTripsIdNameAndCapabilities() {
        val ad = AppAd(id = 0x01020304, name = "Note 8", coded = false, extended = false)
        assertEquals(ad, AppAd.decode(ad.encode()))
    }

    @Test
    fun fitsInALegacyScanResponse() {
        // 31-byte AD budget: length + type + 2-byte company id leaves 27 bytes.
        assertTrue(AppAd(1, "x".repeat(100), true, true).encode().size <= 27)
    }

    @Test
    fun truncatesNamesOnACharacterBoundary() {
        val name = AppAd.decode(AppAd(1, "ééééééééééééééé", false, false).encode())!!.name // 2 bytes per é
        assertEquals("éééééééééé", name)
    }

    @Test
    fun rejectsUnknownVersion() {
        val bytes = AppAd(1, "a", false, false).encode().also { it[0] = 99 }
        assertNull(AppAd.decode(bytes))
    }

    @Test
    fun rejectsShortData() {
        assertNull(AppAd.decode(byteArrayOf(1, 0, 0)))
    }
}
