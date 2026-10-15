package dev.blue.codec

/** Groups encoded Speex frames into audio packets of [Speex.FRAMES_PER_PACKET] frames (90 bytes). */
class FramePacker {
    private var pending = ByteArray(0)
    private var count = 0

    /** Returns a full packet on every ninth frame, otherwise null. */
    fun add(frame: ByteArray): ByteArray? {
        pending += frame
        if (++count < Speex.FRAMES_PER_PACKET) return null
        return pending.also { pending = ByteArray(0); count = 0 }
    }

    companion object {
        fun split(packet: ByteArray): List<ByteArray> =
            (0 until packet.size / Speex.FRAME_BYTES).map { packet.copyOfRange(it * Speex.FRAME_BYTES, (it + 1) * Speex.FRAME_BYTES) }
    }
}
