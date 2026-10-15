package dev.blue.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
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

    /**
     * [onSystemAnswer] runs when the call is answered outside our UI (headset button, car kit);
     * [onSystemHangUp] when the system ends it.
     */
    fun begin(name: String, incoming: Boolean, onSystemAnswer: () -> Unit, onSystemHangUp: () -> Unit) {
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
                ) { control = this }
            }
        }
    }

    fun answer() = scope.launch { control?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }

    fun setActive() = scope.launch { control?.setActive() }

    fun end(local: Boolean) = scope.launch {
        control?.disconnect(DisconnectCause(if (local) DisconnectCause.LOCAL else DisconnectCause.REMOTE))
        control = null
    }
}
