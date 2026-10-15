package dev.blue.call

import android.content.Context
import android.util.Log
import dev.blue.Notifications
import dev.blue.Prefs
import dev.blue.Ringer
import dev.blue.audio.AudioEngine
import dev.blue.ble.Discovery
import dev.blue.ble.GattProfile
import dev.blue.protocol.Crypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs one call at a time: ties a [CallSession] to Android's call system, audio, ringing and the UI state.
 *
 * Connection-layer hook: set [dialer] to open a connection to a peer (using [GattProfile.XIAOMI] when
 * `peer.xiaomi`, otherwise [GattProfile.APP]), call [attach] once frames can flow (as CALLER for connections
 * we opened, CALLEE for ones we accepted; `xiaomi = true` on the Xiaomi profile), pass every received frame
 * to the returned session's `receive`, and call [detached] when the connection drops.
 */
class CallController(private val context: Context, private val onCallActive: (Boolean) -> Unit) : CallSession.Events {

    data class CallUi(val peer: String, val state: CallSession.State, val incoming: Boolean, val checkCode: String? = null)

    /** Opens a connection to a peer; supplied by the connection layer. */
    var dialer: ((Discovery.Peer) -> Unit)? = null

    private val prefs = Prefs(context)
    private val telecom = Telecom(context)
    private val ringer = Ringer(context)
    private var session: CallSession? = null
    private var audio: AudioEngine? = null
    private var checkCode: String? = null

    /** Returns false when calling isn't possible (busy, or no connection layer installed). */
    @Synchronized
    fun dial(peer: Discovery.Peer): Boolean {
        if (session != null || _call.value != null) return false
        val connect = dialer ?: return false.also { Log.w(TAG, "no connection layer installed") }
        _call.value = CallUi(peer.name, CallSession.State.CONNECTED, incoming = false)
        telecom.begin(peer.name, incoming = false, onSystemAnswer = {}, onSystemHangUp = ::hangUp)
        connect(peer)
        return true
    }

    /**
     * Returns null when busy with another call. Xiaomi peers get Xiaomi's (weak) encryption mode and
     * see our phone number; BLuE peers get the secure mode and our display name.
     */
    @Synchronized
    fun attach(role: CallSession.Role, send: (ByteArray, Boolean) -> Unit, xiaomi: Boolean = false): CallSession? {
        if (session != null) return null
        val mode = if (xiaomi) Crypto.Mode.XIAOMI else Crypto.Mode.SECURE
        val me = if (xiaomi) prefs.xiaomiNumber else prefs.name
        return CallSession(role, mode, me, send, this).also {
            session = it
            if (role == CallSession.Role.CALLER) it.start()
        }
    }

    @Synchronized
    fun detached() {
        if (session != null || _call.value != null) finish(local = false)
    }

    /** Answer from our own UI or notification. */
    fun answer() {
        telecom.answer()
        answerLocally()
    }

    fun reject() {
        session?.reject() ?: finish(local = true)
    }

    fun hangUp() {
        session?.hangUp() ?: finish(local = true)
    }

    private fun answerLocally() {
        val s = session ?: return
        ringer.stop()
        Notifications.cancelCall(context)
        s.answer()
        if (s.state == CallSession.State.ACTIVE) startAudio()
    }

    // --- CallSession.Events (called from Bluetooth threads) ---

    override fun onSecured(checkCode: String) {
        this.checkCode = checkCode // the callee has no CallUi yet; onIncomingCall picks this up
        update { it.copy(state = CallSession.State.SECURED, checkCode = checkCode) }
    }

    override fun onIncomingCall(number: String) {
        _call.value = CallUi(number, CallSession.State.RINGING, incoming = true, checkCode = checkCode)
        telecom.begin(number, incoming = true, onSystemAnswer = ::answerLocally, onSystemHangUp = ::hangUp)
        ringer.start()
        context.getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifications.CALL_ID, Notifications.incoming(context, number))
    }

    override fun onAnswered() {
        telecom.setActive()
        startAudio()
    }

    override fun onRejected() = Unit // onEnded follows

    override fun onEnded() = finish(local = false)

    override fun onAudio(frames: ByteArray) {
        audio?.play(frames)
    }

    private fun startAudio() {
        update { it.copy(state = CallSession.State.ACTIVE) }
        onCallActive(true)
        audio = AudioEngine { packet -> session?.sendAudio(packet) }.also { it.start() }
    }

    @Synchronized
    private fun finish(local: Boolean) {
        ringer.stop()
        Notifications.cancelCall(context)
        audio?.stop()
        audio = null
        telecom.end(local)
        session = null
        checkCode = null
        _call.value = null
        onCallActive(false)
    }

    private fun update(f: (CallUi) -> CallUi) {
        _call.value?.let { _call.value = f(it) }
    }

    companion object {
        private const val TAG = "BLuE.Call"
        private val _call = MutableStateFlow<CallUi?>(null)

        /** The current call, if any (one controller per process). */
        val call: StateFlow<CallUi?> = _call
    }
}
