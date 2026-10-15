package dev.blue.ble

import dev.blue.protocol.Frame

/** Preserve protocol chunks when a peer negotiates less than the requested ATT MTU. */
internal object GattFrames {
    fun fit(frame: ByteArray, mtu: Int): List<ByteArray> {
        val capacity = minOf(mtu - 3, 512)
        if (frame.size <= capacity) return listOf(frame.copyOf())
        val packet = requireNotNull(Frame.parse(frame)) { "invalid outgoing frame" }
        // Audio is a complete codec packet; CallSession deliberately does not reassemble it.
        require(packet.seq != Frame.SEQ_AUDIO) { "MTU too small for audio" }
        val payloadSize = capacity - Frame.HEADER
        require(payloadSize > 0) { "MTU too small for a frame" }
        return (packet.payload.indices step payloadSize).map { offset ->
            val end = minOf(offset + payloadSize, packet.payload.size)
            Frame.encode(packet.type, packet.seq, packet.opcode,
                if (end == packet.payload.size) packet.md else 1,
                packet.payload.copyOfRange(offset, end))
        }
    }
}
