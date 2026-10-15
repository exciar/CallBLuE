package dev.blue.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.blue.call.CallController
import dev.blue.call.CallSession
import dev.blue.protocol.Frame

/** One call across either GATT role; owned and stopped by the foreground service. */
internal class GattConnection(context: Context, private val controller: CallController) : GattClient.Listener, GattServer.Listener {
    private val thread = HandlerThread("BLuE-GATT").apply { start() }
    private val handler = Handler(thread.looper)
    private val server = GattServer(context, handler, this)
    private val appContext = context.applicationContext
    private class Link(var epoch: Long, val client: GattClient? = null, val device: BluetoothDevice? = null, val profile: GattProfile? = null) {
        var session: CallSession? = null
        var receivedAudio = 0
        var closing = false
    }
    private var link: Link? = null
    private var stopped = false
    private var handshakeTimeout: Runnable? = null

    init {
        controller.dialer = { peer ->
            val epoch = controller.connectionEpoch
            handler.post {
                if (controller.connectionEpoch != epoch || CallController.call.value == null) return@post
                if (stopped) { controller.detached(epoch); return@post }
                val old = link
                if (old != null) {
                    if (old.closing) closeNow(old)
                    else { Log.w(TAG, "call connection already in use"); controller.detached(epoch); return@post }
                }
                val client = GattClient(appContext, handler, peer, this)
                link = Link(epoch, client = client)
                client.start()
            }
        }
        controller.disconnect = { epoch ->
            handler.post {
                val current = link ?: return@post
                if (current.epoch != epoch) return@post
                current.closing = true
                cancelHandshakeTimeout()
                current.client?.closeWhenIdle() ?: current.device?.let(server::closeWhenIdle)
            }
        }
    }

    fun start(xiaomi: Boolean) {
        handler.post { if (!stopped) server.start(xiaomi) }
    }

    fun close() {
        controller.dialer = null
        controller.disconnect = null
        handler.post {
            stopped = true
            cancelHandshakeTimeout()
            link?.let(::closeNow)
            server.close()
            thread.quitSafely()
        }
    }

    override fun onReady(client: GattClient) {
        val current = link
        if (current?.client !== client || current.closing) { client.close(); return }
        current.session = controller.attach(CallSession.Role.CALLER, client::send, client.peer.xiaomi)
        if (current.session == null) closeNow(current) else watchHandshake(current)
    }

    override fun onReady(device: BluetoothDevice, profile: GattProfile, send: (ByteArray, Boolean) -> Unit): Boolean {
        if (stopped || link != null) return false
        val current = Link(controller.connectionEpoch, device = device, profile = profile)
        link = current
        current.session = controller.attach(CallSession.Role.CALLEE, send, profile == GattProfile.XIAOMI)
        if (current.session == null) { link = null; return false }
        current.epoch = controller.connectionEpoch
        watchHandshake(current)
        return true
    }

    override fun onFrame(client: GattClient, frame: ByteArray) {
        link?.takeIf { it.client === client }?.let { receive(it, frame) }
    }

    override fun onFrame(device: BluetoothDevice, profile: GattProfile, frame: ByteArray) {
        link?.takeIf { it.device == device && it.profile == profile }?.let { receive(it, frame) }
    }

    private fun receive(current: Link, frame: ByteArray) {
        if (current.closing) return
        val packet = Frame.parse(frame) ?: return
        if (packet.seq == Frame.SEQ_AUDIO) {
            current.receivedAudio++
            if (current.receivedAudio == 1 || current.receivedAudio % 100 == 0) Log.d(TAG, "received audio packets=${current.receivedAudio}")
        } else Log.d(TAG, "received opcode=${packet.opcode} md=${packet.md} bytes=${packet.payload.size}")
        try {
            current.session?.receive(frame)
            if (current.session?.state != CallSession.State.CONNECTED) cancelHandshakeTimeout()
        } catch (e: Exception) {
            // receive() also invokes local notification/audio callbacks. Keep their stack traces
            // and the call state: a local Android failure does not imply malformed peer bytes.
            Log.w(TAG, "frame handling failed opcode=${packet.opcode} state=${current.session?.state} (${e.javaClass.simpleName})", e)
            closeNow(current)
        }
    }

    override fun onClosed(client: GattClient) {
        link?.takeIf { it.client === client }?.let(::detached)
    }

    override fun onClosed(device: BluetoothDevice, profile: GattProfile?) {
        link?.takeIf { it.device == device && it.profile == profile }?.let(::detached)
    }

    private fun detached(current: Link) {
        if (link !== current) return
        link = null
        cancelHandshakeTimeout()
        controller.detached(current.epoch)
    }

    private fun closeNow(current: Link) {
        current.client?.close() ?: current.device?.let(server::disconnect)
    }

    private fun watchHandshake(current: Link) {
        cancelHandshakeTimeout()
        handshakeTimeout = Runnable {
            if (link === current && current.session?.state == CallSession.State.CONNECTED) {
                Log.w(TAG, "call handshake timed out")
                closeNow(current)
            }
        }.also { handler.postDelayed(it, 30_000) }
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimeout?.let(handler::removeCallbacks)
        handshakeTimeout = null
    }

    private companion object { const val TAG = "BLuE.Gatt" }
}
