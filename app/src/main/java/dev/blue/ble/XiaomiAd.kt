package dev.blue.ble

/**
 * The 84-byte service-data payload (UUID 0xFD2D) that Xiaomi Offline Communication phones broadcast
 * and scan for. Numbers are XOR-ed with a fixed public key, so this hides nothing from anyone nearby.
 */
data class XiaomiAd(
    val number1: String,
    val number2: String,
    val visibleToAll: Boolean,
    val longitude: String? = null,
    val latitude: String? = null,
) {
    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        HEADER.copyInto(out)
        if (visibleToAll) out[FLAGS] = (out[FLAGS].toInt() or VISIBLE_TO_ALL).toByte()
        putNumber(out, NUMBER1, number1)
        putNumber(out, NUMBER2, number2)
        out[LOCATION] = 0x14
        out[LOCATION + 1] = 0x04
        val lng = longitude?.toByteArray()
        val lat = latitude?.toByteArray()
        if (lng != null && lat != null && lng.size <= MAX_LNG && lat.size <= MAX_LAT) {
            out[LNG] = lng.size.toByte(); lng.copyInto(out, LNG + 1)
            out[LAT] = lat.size.toByte(); lat.copyInto(out, LAT + 1)
        }
        return out
    }

    companion object {
        /** Magic (7) + `03 02` + version `01` + flags `C0` + `33 03`. Unknown bytes are copied verbatim. */
        private val HEADER = byteArrayOf(3, 5, 1, 1, 0, 2, 0, 3, 2, 1, 0xC0.toByte(), 0x33, 3)
        val MAGIC: ByteArray = HEADER.copyOf(7)
        private val KEY = "miuiVirtualCall".toByteArray()
        private const val SIZE = 84
        private const val FLAGS = 10
        private const val VISIBLE_TO_ALL = 0x20
        private const val NUMBER1 = 13
        private const val NUMBER2 = 38
        private const val MAX_NUMBER = 24
        private const val LOCATION = 63
        private const val LNG = 65
        private const val LAT = 75
        private const val MAX_LNG = 9
        private const val MAX_LAT = 8

        fun decode(bytes: ByteArray): XiaomiAd? {
            if (bytes.size < LOCATION || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
            var lng: String? = null
            var lat: String? = null
            if (bytes.size >= SIZE && bytes[LOCATION].toInt() == 0x14 && bytes[LOCATION + 1].toInt() == 0x04) {
                lng = field(bytes, LNG, MAX_LNG)
                lat = field(bytes, LAT, MAX_LAT)
            }
            return XiaomiAd(number(bytes, NUMBER1), number(bytes, NUMBER2), bytes[FLAGS].toInt() and VISIBLE_TO_ALL != 0, lng, lat)
        }

        private fun putNumber(out: ByteArray, at: Int, n: String) {
            val b = n.toByteArray()
            require(b.size <= MAX_NUMBER) { "number longer than $MAX_NUMBER bytes" }
            out[at] = b.size.toByte()
            xor(b).copyInto(out, at + 1)
        }

        private fun number(b: ByteArray, at: Int): String {
            val len = (b[at].toInt() and 0xFF).coerceAtMost(MAX_NUMBER)
            return String(xor(b.copyOfRange(at + 1, at + 1 + len)))
        }

        private fun field(b: ByteArray, at: Int, max: Int): String? {
            val len = b[at].toInt()
            return if (len in 1..max) String(b, at + 1, len) else null
        }

        private fun xor(b: ByteArray) = ByteArray(b.size) { (b[it].toInt() xor KEY[it % KEY.size].toInt()).toByte() }
    }
}
