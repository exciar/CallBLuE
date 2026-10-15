package dev.blue.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun encodesCommandHeaderBeforePayload() {
        val bytes = Frame.encode(Frame.TYPE_CMD, seq = 7, opcode = Opcode.CALL_ANSWER, md = 0, payload = hex("11 11"))
        assertArrayEquals(hex("CC BB AA 00 02 02 07 66 00 11 11"), bytes)
    }

    @Test
    fun responseMatchesXiaomiLayout() {
        assertArrayEquals(hex("CC BB AA 00 01 03 05 65 00 00"), Frame.response(seq = 5, opcode = Opcode.CALL_REQUEST, result = 0))
    }

    @Test
    fun audioUsesSeqAndOpcodeFF() {
        val frame = Frame.audio(ByteArray(90))
        assertArrayEquals(hex("CC BB AA 00 5A 00 FF FF 00"), frame.copyOfRange(0, 9))
        assertEquals(99, frame.size)
    }

    @Test
    fun chunksLongPayloadWithMoreDataFlags() {
        val chunks = Frame.chunk(Frame.TYPE_NOTIFY, seq = 3, opcode = Opcode.NOTIFY_AES_KEY, payload = ByteArray(1000))
        assertEquals(listOf(480, 480, 40), chunks.map { it.size - Frame.HEADER })
        assertEquals(listOf(1, 1, 0), chunks.map { it[8].toInt() })
        assertEquals(listOf(3, 3, 3), chunks.map { it[6].toInt() })
    }

    @Test
    fun parseReadsHeaderFields() {
        val p = Frame.parse(hex("CC BB AA 00 02 02 07 66 00 11 11"))!!
        assertEquals(Frame.TYPE_CMD, p.type)
        assertEquals(7, p.seq)
        assertEquals(Opcode.CALL_ANSWER, p.opcode)
        assertArrayEquals(hex("11 11"), p.payload)
    }

    @Test
    fun parseRejectsBadLeadCode() {
        assertNull(Frame.parse(hex("CC BB AB 00 02 02 07 66 00 11 11")))
    }

    @Test
    fun parseRejectsUnknownType() {
        assertNull(Frame.parse(hex("CC BB AA 00 00 04 07 66 00")))
    }

    @Test
    fun parseRejectsShortInput() {
        assertNull(Frame.parse(hex("CC BB AA 00 00 02 07 66")))
    }

    @Test
    fun reassemblerJoinsChunksUntilLast() {
        val payload = ByteArray(1000) { it.toByte() }
        val r = Reassembler()
        val out = Frame.chunk(Frame.TYPE_NOTIFY, 3, Opcode.NOTIFY_AES_KEY, payload).map { r.push(Frame.parse(it)!!) }
        assertNull(out[0])
        assertNull(out[1])
        assertArrayEquals(payload, out[2]!!.payload)
    }

    @Test
    fun reassemblerDropsBufferOnTruncatedFlag() {
        val r = Reassembler()
        r.push(Frame.parse(Frame.encode(Frame.TYPE_NOTIFY, 1, Opcode.NOTIFY_AES_KEY, 1, hex("01 02")))!!)
        r.push(Frame.parse(Frame.encode(Frame.TYPE_NOTIFY, 1, Opcode.NOTIFY_AES_KEY, 2, ByteArray(0)))!!)
        val done = r.push(Frame.parse(Frame.encode(Frame.TYPE_NOTIFY, 2, Opcode.NOTIFY_AES_KEY, 0, hex("09")))!!)
        assertArrayEquals(hex("09"), done!!.payload)
    }
}
