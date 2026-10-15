package dev.blue.protocol

/** Opcodes of the HyperCall command channel (see docs/protocol.md). */
object Opcode {
    const val QUERY_AES_KEY = 35
    const val NOTIFY_AES_KEY = 36
    const val QUERY_BLACK_LIST = 40
    const val QUERY_CAPACITY_LIST = 41
    const val REPLY_CAPACITY_LIST = 42
    const val SET_CODEC = 43
    const val CALL_REQUEST = 101
    const val CALL_ANSWER = 102
    const val CALL_REJECT = 103
    const val DISCONNECT_THIRD = 104
    const val NOTIFY_DISCONNECT = 105
}

class Packet(val type: Int, val seq: Int, val opcode: Int, val md: Int, val payload: ByteArray)

object Frame {
    const val TYPE_NOTIFY = 0
    const val TYPE_CMD_NO_RSP = 1
    const val TYPE_CMD = 2
    const val TYPE_RSP = 3
    const val SEQ_AUDIO = 0xFF
    const val MAX_CHUNK = 480
    const val HEADER = 9

    private val LEAD = byteArrayOf(0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

    /** `CC BB AA | len(2, BE) | type | seq | opcode | md | payload`. */
    fun encode(type: Int, seq: Int, opcode: Int, md: Int, payload: ByteArray): ByteArray =
        LEAD + byteArrayOf(
            (payload.size ushr 8).toByte(), payload.size.toByte(),
            type.toByte(), seq.toByte(), opcode.toByte(), md.toByte(),
        ) + payload

    /** Splits [payload] into ≤480-byte frames sharing [seq]; md=1 on all but the last. */
    fun chunk(type: Int, seq: Int, opcode: Int, payload: ByteArray): List<ByteArray> {
        val parts = (payload.indices step MAX_CHUNK).map { payload.copyOfRange(it, minOf(it + MAX_CHUNK, payload.size)) }
            .ifEmpty { listOf(ByteArray(0)) }
        return parts.mapIndexed { i, part -> encode(type, seq, opcode, if (i == parts.lastIndex) 0 else 1, part) }
    }

    fun response(seq: Int, opcode: Int, result: Int): ByteArray =
        encode(TYPE_RSP, seq, opcode, 0, byteArrayOf(result.toByte()))

    fun audio(payload: ByteArray): ByteArray = encode(TYPE_NOTIFY, SEQ_AUDIO, 0xFF, 0, payload)

    /** Mirrors the reference parser: the declared length is ignored, the rest of the buffer is the payload. */
    fun parse(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER || !bytes.copyOfRange(0, 3).contentEquals(LEAD)) return null
        val type = bytes[5].toInt()
        if (type !in TYPE_NOTIFY..TYPE_RSP) return null
        return Packet(type, bytes[6].toInt() and 0xFF, bytes[7].toInt() and 0xFF, bytes[8].toInt(), bytes.copyOfRange(HEADER, bytes.size))
    }
}

/** Joins chunked command frames: md=1 appends, md=2 discards, md=0 completes. */
class Reassembler {
    private var buffer = ByteArray(0)

    fun push(p: Packet): Packet? {
        when (p.md) {
            1 -> buffer += p.payload
            0 -> return Packet(p.type, p.seq, p.opcode, 0, buffer + p.payload).also { buffer = ByteArray(0) }
            else -> buffer = ByteArray(0)
        }
        return null
    }
}
