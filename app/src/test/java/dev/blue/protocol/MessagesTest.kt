package dev.blue.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagesTest {

    @Test
    fun callRequestPutsLengthPrefixedNumbersAtOffsets0And25() {
        val p = Messages.callRequest("09171234567", "0998")
        assertEquals(50, p.size)
        assertEquals(11, p[0].toInt())
        assertEquals("09171234567", String(p, 1, 11))
        assertEquals(4, p[25].toInt())
        assertEquals("0998", String(p, 26, 4))
    }

    @Test
    fun callRequestRoundTrips() {
        assertEquals("09171234567" to "", Messages.parseCallRequest(Messages.callRequest("09171234567", "")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun callRequestRejectsNumbersLongerThan24Bytes() {
        Messages.callRequest("1".repeat(25), "")
    }

    @Test
    fun answerAndDisconnectUse1111() {
        assertArrayEquals(byteArrayOf(0x11, 0x11), Messages.ACK)
    }
}
