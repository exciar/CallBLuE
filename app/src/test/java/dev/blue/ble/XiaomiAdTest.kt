package dev.blue.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiAdTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun encodesTo84BytesWithTheMagicHeader() {
        val b = XiaomiAd("0917", "", visibleToAll = false).encode()
        assertEquals(84, b.size)
        assertArrayEquals(hex("03 05 01 01 00 02 00 03 02 01 C0 33 03"), b.copyOfRange(0, 13))
    }

    @Test
    fun visibleToAllSetsFlag0x20() {
        assertEquals(0xE0.toByte(), XiaomiAd("1", "", visibleToAll = true).encode()[10])
    }

    @Test
    fun numbersAreLengthPrefixedAndXoredWithTheFixedKey() {
        val b = XiaomiAd("09", "", visibleToAll = true).encode()
        assertEquals(2, b[13].toInt())
        assertArrayEquals(hex("5D 50"), b.copyOfRange(14, 16)) // '0'^'m', '9'^'i'
        assertEquals(0, b[38].toInt())
    }

    @Test
    fun secondNumberStartsAtOffset38() {
        val b = XiaomiAd("1", "09", visibleToAll = true).encode()
        assertEquals(2, b[38].toInt())
        assertArrayEquals(hex("5D 50"), b.copyOfRange(39, 41))
    }

    @Test
    fun locationBlockFollowsTheNumbers() {
        val b = XiaomiAd("1", "", true, longitude = "121.0437", latitude = "14.5764").encode()
        assertArrayEquals(hex("14 04"), b.copyOfRange(63, 65))
        assertEquals(8, b[65].toInt())
        assertEquals("121.0437", String(b, 66, 8))
        assertEquals(7, b[75].toInt())
        assertEquals("14.5764", String(b, 76, 7))
    }

    @Test
    fun roundTrips() {
        val ad = XiaomiAd("09171234567", "09998887777", visibleToAll = true, longitude = "121.0437", latitude = "14.5764")
        assertEquals(ad, XiaomiAd.decode(ad.encode()))
    }

    @Test
    fun rejectsWrongMagic() {
        assertNull(XiaomiAd.decode(XiaomiAd("1", "", true).encode().also { it[0] = 4 }))
    }

    @Test
    fun rejectsShortData() {
        assertNull(XiaomiAd.decode(XiaomiAd("1", "", true).encode().copyOf(62)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNumbersLongerThan24Bytes() {
        XiaomiAd("1".repeat(25), "", true).encode()
    }
}
