package dev.blue.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Registers BLuE calls with Android's call system (self-managed calls), which handles audio
 * routing, Bluetooth headsets, car kits and the system's call state.
 */
class Telecom(context: Context) {

    private val calls = CallsManager(context).apply { registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var control: CallControlScope? = null
    @Volatile private var endpoints: List<CallEndpointCompat> = emptyList()
    @Volatile private var lastNonSpeaker: CallEndpointCompat? = null

    /**
     * [onSystemAnswer] runs when the call is answered outside our UI (headset button, car kit);
     * [onSystemHangUp] when the system ends it; [onSystemMute] when the system's mute state changes;
     * [onSpeaker] whenever call audio moves to or from the loudspeaker.
     */
    fun begin(
        name: String,
        incoming: Boolean,
        onSystemAnswer: () -> Unit,
        onSystemHangUp: () -> Unit,
        onSystemMute: (Boolean) -> Unit = {},
        onSpeaker: (Boolean) -> Unit = {},
    ) {
        val attrs = CallAttributesCompat(
            displayName = name,
            address = Uri.fromParts("blue", name, null),
            direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
        )
        scope.launch {
            runCatching {
                calls.addCall(
                    attrs,
                    onAnswer = { onSystemAnswer() },
                    onDisconnect = { control = null; onSystemHangUp() },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    control = this
                    launch { isMuted.collect { onSystemMute(it) } }
                    launch { availableEndpoints.collect { endpoints = it } }
                    launch {
                        currentCallEndpoint.collect { e ->
                            if (e.type != CallEndpointCompat.TYPE_SPEAKER) lastNonSpeaker = e
                            onSpeaker(e.type == CallEndpointCompat.TYPE_SPEAKER)
                        }
                    }
                }
            }
        }
    }

    fun answer() = scope.launch { control?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }

    fun setActive() = scope.launch { control?.setActive() }

    /** Routes call audio to the loudspeaker, or back to the earpiece/headset that was in use before. */
    fun setSpeaker(on: Boolean) = scope.launch {
        val c = control ?: return@launch
        val available = endpoints
        val target = if (on) {
            available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        } else {
            lastNonSpeaker?.takeIf { it in available }
                ?: available.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                ?: available.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
        }
        target?.let { c.requestEndpointChange(it) }
    }

    fun end(local: Boolean) = scope.launch {
        control?.disconnect(DisconnectCause(if (local) DisconnectCause.LOCAL else DisconnectCause.REMOTE))
        control = null
        endpoints = emptyList()
        lastNonSpeaker = null
    }
}
