package dev.blue.ble

import dev.blue.protocol.Frame
import dev.blue.protocol.Opcode
import dev.blue.protocol.Reassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GattFramesTest {

    @Test
    fun normalNegotiatedMtusPreserveMaximumProtocolChunk() {
        val frame = Frame.encode(Frame.TYPE_CMD, 7, Opcode.QUERY_AES_KEY, 0, ByteArray(Frame.MAX_CHUNK))

        for (mtu in listOf(512, 517)) {
            val fitted = GattFrames.fit(frame, mtu)
            assertEquals(1, fitted.size)
            assertArrayEquals(frame, fitted.single())
            assertNotSame(frame, fitted.single())
        }
    }

    @Test
    fun exactAttCapacityFitsWithoutChangingTheFrame() {
        val frame = Frame.encode(Frame.TYPE_CMD, 1, Opcode.SET_CODEC, 0, ByteArray(11) { it.toByte() })

        val fitted = GattFrames.fit(frame, mtu = 23)

        assertEquals(20, frame.size)
        assertEquals(1, fitted.size)
        assertArrayEquals(frame, fitted.single())
    }

    @Test
    fun exceedingAttCapacityByOneSplitsIntoCompleteProtocolFrames() {
        val payload = ByteArray(12) { it.toByte() }
        val frame = Frame.encode(Frame.TYPE_CMD, 254, Opcode.QUERY_AES_KEY, 0, payload)

        val fitted = GattFrames.fit(frame, mtu = 23)
        val packets = fitted.map { Frame.parse(it)!! }

        assertEquals(listOf(20, 10), fitted.map { it.size })
        assertEquals(listOf(1, 0), packets.map { it.md })
        assertTrue(packets.all { it.type == Frame.TYPE_CMD && it.seq == 254 && it.opcode == Opcode.QUERY_AES_KEY })
        assertArrayEquals(payload, packets.flatMap { it.payload.asIterable() }.toByteArray())
    }

    @Test
    fun splittingAContinuationNeverEndsTheOriginalMessage() {
        val frame = Frame.encode(Frame.TYPE_NOTIFY, 3, Opcode.NOTIFY_AES_KEY, 1, ByteArray(Frame.MAX_CHUNK))

        val fitted = GattFrames.fit(frame, mtu = 64)

        assertTrue(fitted.size > 1)
        assertTrue(fitted.all { it.size <= 61 })
        assertTrue(fitted.map { Frame.parse(it)!! }.all { it.md == 1 })
    }

    @Test
    fun rechunkingAnExistingMessageRoundTripsThroughTheProtocolReassembler() {
        val payload = ByteArray(1_000) { (it * 37).toByte() }
        val original = Frame.chunk(Frame.TYPE_NOTIFY, 3, Opcode.NOTIFY_AES_KEY, payload)

        for (mtu in listOf(23, 64, 247, 512, 517)) {
            val fitted = original.flatMap { GattFrames.fit(it, mtu) }
            val packets = fitted.map { Frame.parse(it)!! }
            val reassembler = Reassembler()
            val received = packets.mapNotNull(reassembler::push)

            assertTrue("MTU $mtu", fitted.all { it.size <= minOf(mtu - 3, 512) })
            assertEquals("MTU $mtu", List(packets.size - 1) { 1 } + 0, packets.map { it.md })
            assertEquals("MTU $mtu", 1, received.size)
            assertEquals(Frame.TYPE_NOTIFY, received.single().type)
            assertEquals(3, received.single().seq)
            assertEquals(Opcode.NOTIFY_AES_KEY, received.single().opcode)
            assertArrayEquals("MTU $mtu", payload, received.single().payload)
        }
    }

    @Test
    fun audioFitsAtExactAttCapacityWithoutSplitting() {
        val frame = Frame.audio(ByteArray(90) { it.toByte() })

        val fitted = GattFrames.fit(frame, mtu = 102)

        assertEquals(1, fitted.size)
        assertArrayEquals(frame, fitted.single())
    }

    @Test
    fun audioThatExceedsAttCapacityIsRejected() {
        val frame = Frame.audio(ByteArray(90))

        assertThrows(IllegalArgumentException::class.java) { GattFrames.fit(frame, mtu = 101) }
    }

    @Test
    fun mtuWithoutRoomForPayloadIsRejected() {
        val frame = Frame.encode(Frame.TYPE_CMD, 1, Opcode.SET_CODEC, 0, byteArrayOf(1))

        assertThrows(IllegalArgumentException::class.java) { GattFrames.fit(frame, mtu = 12) }
    }
}
