package dev.blue.ble

import java.nio.ByteBuffer

/**
 * BLuE's own discovery payload, carried as manufacturer data in a legacy scan response so
 * phones without extended advertising can use it: `[version][caps][id: 4 bytes][name: UTF-8]`.
 */
data class AppAd(val id: Int, val name: String, val coded: Boolean, val extended: Boolean) {

    fun encode(): ByteArray {
        val caps = (if (coded) CAP_CODED else 0) or (if (extended) CAP_EXTENDED else 0)
        return ByteBuffer.allocate(HEAD).put(VERSION.toByte()).put(caps.toByte()).putInt(id).array() + truncatedName()
    }

    private fun truncatedName(): ByteArray {
        var out = ByteArray(0)
        for (cp in name.codePoints().toArray()) {
            val b = String(Character.toChars(cp)).toByteArray()
            if (out.size + b.size > MAX_NAME) return out
            out += b
        }
        return out
    }

    companion object {
        /** SIG test company id; acceptable for a hobby project, swap for an assigned id if one is ever registered. */
        const val COMPANY_ID = 0xFFFF
        private const val VERSION = 1
        private const val CAP_CODED = 1
        private const val CAP_EXTENDED = 2
        private const val HEAD = 6
        private const val MAX_NAME = 27 - HEAD

        fun decode(bytes: ByteArray): AppAd? {
            if (bytes.size < HEAD || bytes[0].toInt() != VERSION) return null
            val caps = bytes[1].toInt()
            return AppAd(
                id = ByteBuffer.wrap(bytes, 2, 4).int,
                name = String(bytes, HEAD, bytes.size - HEAD),
                coded = caps and CAP_CODED != 0,
                extended = caps and CAP_EXTENDED != 0,
            )
        }
    }
}
