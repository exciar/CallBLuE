package dev.blue.protocol

/** Payload layouts of the call commands. */
object Messages {
    /** Payload of CALL_ANSWER and NOTIFY_DISCONNECT. */
    val ACK = byteArrayOf(0x11, 0x11)

    private const val SLOT = 25 // length byte + up to 24 number bytes

    /** CALL_REQUEST: two length-prefixed numbers at offsets 0 and 25 in a 50-byte block. */
    fun callRequest(number1: String, number2: String): ByteArray {
        val out = ByteArray(2 * SLOT)
        listOf(number1, number2).forEachIndexed { i, n ->
            val b = n.toByteArray()
            require(b.size < SLOT) { "number longer than ${SLOT - 1} bytes" }
            out[i * SLOT] = b.size.toByte()
            b.copyInto(out, i * SLOT + 1)
        }
        return out
    }

    fun parseCallRequest(payload: ByteArray): Pair<String, String> {
        require(payload.size >= 2 * SLOT) { "CALL_REQUEST shorter than ${2 * SLOT} bytes" }
        fun at(o: Int) = String(payload, o + 1, (payload[o].toInt() and 0xFF).coerceAtMost(SLOT - 1))
        return at(0) to at(SLOT)
    }
}
