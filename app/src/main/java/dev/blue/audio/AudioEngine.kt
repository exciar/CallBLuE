package dev.blue.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import dev.blue.codec.FramePacker
import dev.blue.codec.Speex
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Microphone → Speex → 90-byte packets via [onPacket]; [play] takes packets back to the speaker.
 * 8 kHz mono, voice-call audio path with echo cancellation and noise suppression when available.
 */
class AudioEngine(private val onPacket: (ByteArray) -> Unit) {

    private val speex = Speex()
    private val packer = FramePacker()
    private val incoming = LinkedBlockingQueue<ByteArray>(32)
    @Volatile private var running = false

    /** Sends silence instead of the microphone, keeping the packet rhythm the peer expects. */
    @Volatile var muted = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val workers = mutableListOf<Thread>()

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before a call can start
    fun start() {
        if (running) return
        val minIn = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200))
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true

        val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val out = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minOut, 6400))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        record = rec
        track = out
        running = true
        rec.startRecording()
        out.play()
        workers += thread(name = "BLuE-mic") {
            val pcm = ShortArray(speex.frameSize)
            while (running) {
                if (rec.read(pcm, 0, pcm.size) != pcm.size) continue
                if (muted) pcm.fill(0)
                packer.add(speex.encode(pcm))?.let(onPacket)
            }
        }
        workers += thread(name = "BLuE-speaker") {
            while (running) {
                val packet = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                FramePacker.split(packet).forEach { f -> speex.decode(f)?.let { out.write(it, 0, it.size) } }
            }
        }
    }

    /** Queues a received audio packet; drops it if playback has fallen far behind. */
    fun play(packet: ByteArray) {
        if (running) incoming.offer(packet)
    }

    fun stop() {
        if (!running) return
        running = false
        workers.forEach { it.join(500) }
        workers.clear()
        record?.run { stop(); release() }
        track?.run { stop(); release() }
        record = null
        track = null
        incoming.clear()
        speex.close()
    }

    private companion object {
        const val RATE = 8000
    }
}
