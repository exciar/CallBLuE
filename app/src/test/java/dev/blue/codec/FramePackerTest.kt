package dev.blue.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramePackerTest {

    private fun frame(b: Int) = ByteArray(10) { b.toByte() }

    @Test
    fun emitsAPacketEveryNinthFrame() {
        val p = FramePacker()
        repeat(8) { assertNull(p.add(frame(it))) }
        val packet = p.add(frame(8))!!
        assertEquals(90, packet.size)
        assertArrayEquals(frame(0), packet.copyOfRange(0, 10))
        assertArrayEquals(frame(8), packet.copyOfRange(80, 90))
        assertNull(p.add(frame(9))) // starts a new packet
    }

    @Test
    fun splitsAPacketIntoFrames() {
        val frames = FramePacker.split(ByteArray(90) { (it / 10).toByte() })
        assertEquals(9, frames.size)
        assertArrayEquals(frame(4), frames[4])
    }

    @Test
    fun splitIgnoresATrailingPartialFrame() {
        assertEquals(2, FramePacker.split(ByteArray(25)).size)
    }
}
