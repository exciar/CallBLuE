package dev.blue.call

import dev.blue.protocol.Crypto
import dev.blue.protocol.Frame
import dev.blue.protocol.Messages
import dev.blue.protocol.Opcode
import dev.blue.protocol.Packet
import dev.blue.protocol.Reassembler

/**
 * One call over one link, in the reference order:
 * capability query → key exchange → CALL_REQUEST → answer/reject → audio → disconnect.
 * Transport-agnostic: [send] writes a frame to the peer (audio or command channel).
 */
class CallSession(
    val role: Role,
    private val mode: Crypto.Mode,
    private val myNumber: String,
    private val send: (frame: ByteArray, audio: Boolean) -> Unit,
    private val events: Events,
) {
    enum class Role { CALLER, CALLEE }
    enum class State { CONNECTED, SECURED, RINGING, ACTIVE, ENDED }

    interface Events {
        fun onSecured(checkCode: String) {}
        fun onIncomingCall(number: String) {}
        fun onAnswered() {}
        fun onRejected() {}
        fun onEnded() {}
        fun onAudio(frames: ByteArray) {}
    }

    var state = State.CONNECTED
        private set

    private var requester: Crypto.Requester? = null
    private var channel: Crypto.Channel? = null
    private val reassembler = Reassembler()
    private var seq = 0

    @Synchronized
    fun start() {
        check(role == Role.CALLER) { "only the caller starts" }
        sendPlain(Opcode.QUERY_CAPACITY_LIST, LOCAL_CAPS.toByteArray())
        // Xiaomi callees check "number1:number2" against their block list before handing out a key.
        if (mode == Crypto.Mode.XIAOMI) sendPlain(Opcode.QUERY_BLACK_LIST, "$myNumber:".toByteArray())
        val r = Crypto.Requester()
        requester = r // store before sending: the reply can arrive before send() returns
        sendPlain(Opcode.QUERY_AES_KEY, r.publicKey.toByteArray())
    }

    @Synchronized
    fun receive(bytes: ByteArray) {
        val p = Frame.parse(bytes) ?: return
        if (p.seq == Frame.SEQ_AUDIO) {
            if (state == State.ACTIVE) openAudio(p.payload)?.let(events::onAudio)
            return
        }
        reassembler.push(p)?.let(::handle)
    }

    @Synchronized
    fun answer() {
        if (role != Role.CALLEE || state != State.RINGING) return
        state = State.ACTIVE
        sendSealed(Opcode.CALL_ANSWER, Messages.ACK)
    }

    @Synchronized
    fun reject() {
        if (role != Role.CALLEE || state != State.RINGING) return
        sendSealed(Opcode.CALL_REJECT, Messages.ACK)
        end()
    }

    @Synchronized
    fun hangUp() {
        if (state == State.ENDED) return
        if (channel != null) sendSealed(Opcode.NOTIFY_DISCONNECT, Messages.ACK)
        end()
    }

    @Synchronized
    fun sendAudio(frames: ByteArray) {
        if (state != State.ACTIVE) return
        val payload = if (mode == Crypto.Mode.XIAOMI) frames else channel!!.seal(frames)
        send(Frame.audio(payload), true)
    }

    private fun handle(m: Packet) {
        when (m.opcode) {
            Opcode.QUERY_CAPACITY_LIST -> sendPlain(Opcode.REPLY_CAPACITY_LIST, LOCAL_CAPS.toByteArray())
            Opcode.REPLY_CAPACITY_LIST -> sendPlain(Opcode.SET_CODEC, SPEEX_CODEC.toByteArray())
            Opcode.SET_CODEC -> Unit // Speex is the only codec we speak
            Opcode.QUERY_BLACK_LIST -> Unit // BLuE has no block list yet; every caller may continue
            Opcode.QUERY_AES_KEY -> if (role == Role.CALLEE && channel == null) {
                val (notify, ch) = Crypto.respond(String(m.payload), mode)
                channel = ch
                secured() // state first: the peer may answer before send() returns
                Frame.chunk(Frame.TYPE_NOTIFY, nextSeq(), Opcode.NOTIFY_AES_KEY, notify.toByteArray()).forEach { send(it, false) }
            }
            Opcode.NOTIFY_AES_KEY -> requester?.let {
                channel = it.accept(String(m.payload), mode)
                requester = null
                secured()
                sendSealed(Opcode.CALL_REQUEST, Messages.callRequest(myNumber, ""))
            }
            else -> channel?.open(m.payload)?.let { handleSealed(m.opcode, it) }
        }
    }

    private fun handleSealed(opcode: Int, plain: ByteArray) {
        when (opcode) {
            Opcode.CALL_REQUEST -> if (role == Role.CALLEE && state == State.SECURED) {
                state = State.RINGING
                events.onIncomingCall(Messages.parseCallRequest(plain).first)
            }
            Opcode.CALL_ANSWER -> if (role == Role.CALLER && state == State.SECURED) {
                state = State.ACTIVE
                events.onAnswered()
            }
            Opcode.CALL_REJECT, Opcode.DISCONNECT_THIRD -> if (state != State.ENDED) {
                events.onRejected()
                end()
            }
            Opcode.NOTIFY_DISCONNECT -> end()
        }
    }

    private fun secured() {
        state = State.SECURED
        events.onSecured(channel!!.checkCode)
    }

    private fun end() {
        if (state == State.ENDED) return
        state = State.ENDED
        events.onEnded()
    }

    private fun openAudio(payload: ByteArray) = if (mode == Crypto.Mode.XIAOMI) payload else channel?.open(payload)

    private fun sendPlain(opcode: Int, payload: ByteArray) =
        Frame.chunk(Frame.TYPE_CMD, nextSeq(), opcode, payload).forEach { send(it, false) }

    private fun sendSealed(opcode: Int, plain: ByteArray) =
        Frame.chunk(Frame.TYPE_NOTIFY, nextSeq(), opcode, channel!!.seal(plain)).forEach { send(it, false) }

    /** 1..254 then wraps to 0, like the reference counter. */
    private fun nextSeq(): Int {
        seq = if (seq == 254) 0 else seq + 1
        return seq
    }

    companion object {
        /** "codec id, version, ..." entries; we only offer Speex. */
        const val LOCAL_CAPS = "0,2,0"
        const val SPEEX_CODEC = "SPEEX,VERSION=1"
    }
}
