package dev.blue.codec

/**
 * Speex narrowband (8 kHz mono) via libspeex. Quality 1 gives 160-sample frames of 10 bytes,
 * the format the reference implementation uses.
 */
class Speex(quality: Int = 1) : AutoCloseable {

    private var handle = nativeCreate(quality)

    /** Samples per frame (160). */
    val frameSize: Int = nativeFrameSize(handle)

    /** Encodes exactly [frameSize] samples. */
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size >= frameSize)
        val out = ByteArray(64)
        return out.copyOf(nativeEncode(handle, pcm, out))
    }

    /** Decodes one encoded frame; returns null if the data is corrupt. */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size): ShortArray? {
        val pcm = ShortArray(frameSize)
        return if (nativeDecode(handle, data, offset, length, pcm) < 0) null else pcm
    }

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeCreate(quality: Int): Long
    private external fun nativeFrameSize(handle: Long): Int
    private external fun nativeEncode(handle: Long, pcm: ShortArray, out: ByteArray): Int
    private external fun nativeDecode(handle: Long, data: ByteArray, offset: Int, length: Int, pcm: ShortArray): Int
    private external fun nativeDestroy(handle: Long)

    companion object {
        /** Bytes per encoded frame at quality 1, and frames per audio packet. */
        const val FRAME_BYTES = 10
        const val FRAMES_PER_PACKET = 9

        init {
            System.loadLibrary("bluespeex")
        }
    }
}
