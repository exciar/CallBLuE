package dev.blue.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.ArrayDeque

/** One outgoing LE link. Setup and writes share a single, callback-driven GATT operation queue. */
@SuppressLint("MissingPermission") // The service starts this transport only after Bluetooth permission is granted.
@Suppress("DEPRECATION") // Android 8–12 need the characteristic/descriptor value setters.
internal class GattClient(
    context: Context,
    private val handler: Handler,
    val peer: Discovery.Peer,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(client: GattClient)
        fun onFrame(client: GattClient, frame: ByteArray)
        fun onClosed(client: GattClient)
    }

    private enum class Phase { NEW, CONNECTING, MTU, SERVICES, AUDIO_CCCD, CMD_CCCD, READY, CLOSED }
    private data class Write(val frame: ByteArray, val audio: Boolean)

    private val appContext = context.applicationContext
    private val profile = if (peer.xiaomi) GattProfile.XIAOMI else GattProfile.APP
    private var phase = Phase.NEW
    private var gatt: BluetoothGatt? = null
    private var mtu = 23
    private var audioWrite: BluetoothGattCharacteristic? = null
    private var cmdWrite: BluetoothGattCharacteristic? = null
    private var audioNotify: BluetoothGattCharacteristic? = null
    private var cmdNotify: BluetoothGattCharacteristic? = null
    private var commandWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private val writes = ArrayDeque<Write>()
    private var inFlight: Write? = null
    private var draining = false
    private var operationTimeout: Runnable? = null
    private val connectionTimeout = Runnable { fail("connection timeout") }
    private val drainTimeout = Runnable { close() }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            onHandler(link) {
                Log.i(TAG, "connection state=$newState status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    close()
                } else if (newState == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECTING) {
                    // These preferences do not occupy the ATT operation queue.
                    runCatching { link.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    if (peer.longRange) runCatching {
                        link.setPreferredPhy(BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S8)
                    }
                    phase = Phase.MTU
                    armOperationTimeout("MTU")
                    if (!begin("MTU") { link.requestMtu(512) }) discoverServices()
                }
            }
        }

        override fun onMtuChanged(link: BluetoothGatt, value: Int, status: Int) {
            onHandler(link) {
                Log.i(TAG, "MTU status=$status mtu=$value")
                if (status == BluetoothGatt.GATT_SUCCESS && value >= 23) mtu = value
                if (phase == Phase.MTU) discoverServices()
            }
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            onHandler(link) {
                if (phase != Phase.SERVICES) return@onHandler
                Log.i(TAG, "services status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) return@onHandler fail("service discovery failed")
                val service = link.getService(profile.service) ?: return@onHandler fail("missing service")
                audioWrite = service.getCharacteristic(profile.audioWrite)
                cmdWrite = service.getCharacteristic(profile.cmdWrite)
                audioNotify = service.getCharacteristic(profile.audioNotify)
                cmdNotify = service.getCharacteristic(profile.cmdNotify)
                val audio = audioWrite ?: return@onHandler fail("missing audio write")
                val command = cmdWrite ?: return@onHandler fail("missing command write")
                if (audio.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
                    return@onHandler fail("unsupported audio write")
                }
                // Stock Xiaomi exposes BF07 as write-without-response, whereas BLuE supports writes with response.
                commandWriteType = when {
                    command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else -> return@onHandler fail("unsupported command write")
                }
                subscribe(audio = true)
            }
        }

        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onHandler(link) {
                val audio = phase == Phase.AUDIO_CCCD
                if (!audio && phase != Phase.CMD_CCCD) return@onHandler
                val expectedCharacteristic = if (audio) profile.audioNotify else profile.cmdNotify
                val expectedDescriptor = if (audio) profile.audioCccd else profile.cmdCccd
                if (descriptor.characteristic.uuid != expectedCharacteristic || descriptor.uuid != expectedDescriptor) return@onHandler
                Log.i(TAG, "subscription audio=$audio status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) return@onHandler fail("subscription failed")
                if (audio) {
                    subscribe(audio = false)
                } else {
                    clearOperationTimeout()
                    handler.removeCallbacks(connectionTimeout)
                    phase = Phase.READY
                    Log.i(TAG, "ready xiaomi=${peer.xiaomi}")
                    listener.onReady(this@GattClient)
                    pump()
                }
            }
        }

        override fun onCharacteristicWrite(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onHandler(link) {
                val current = inFlight ?: return@onHandler
                val expected = if (current.audio) profile.audioWrite else profile.cmdWrite
                if (characteristic.uuid != expected) return@onHandler
                clearOperationTimeout()
                inFlight = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "write audio=${current.audio} status=$status")
                    fail("write failed")
                } else {
                    pump()
                }
            }
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            received(link, characteristic, value.copyOf())
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // API 33+ invokes the value-taking overload; copy the mutable legacy value before posting.
            if (Build.VERSION.SDK_INT < 33) received(link, characteristic, characteristic.value?.copyOf() ?: return)
        }

        override fun onPhyUpdate(link: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            onHandler(link) { Log.i(TAG, "PHY tx=$txPhy rx=$rxPhy status=$status") }
        }
    }

    fun start() {
        if (phase != Phase.NEW) return
        phase = Phase.CONNECTING
        Log.i(TAG, "connecting xiaomi=${peer.xiaomi} coded=${peer.longRange}")
        handler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
        gatt = try {
            peer.device.connectGatt(
                appContext, false, callback, BluetoothDevice.TRANSPORT_LE,
                if (peer.longRange) BluetoothDevice.PHY_LE_CODED_MASK else BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "connect failed ${error.javaClass.simpleName}")
            null
        }
        if (gatt == null) fail("connect unavailable")
    }

    /** Session/audio callbacks can run on any thread; preserve their order on the transport handler. */
    fun send(frame: ByteArray, audio: Boolean) {
        val copy = frame.copyOf()
        handler.post {
            if (phase != Phase.READY || draining) return@post
            val fitted = try {
                GattFrames.fit(copy, mtu)
            } catch (_: IllegalArgumentException) {
                fail("frame exceeds MTU")
                return@post
            }
            for (part in fitted) {
                if (audio) {
                    // Keep latency bounded: an old unsent audio packet is less useful than the newest one.
                    while (writes.count { it.audio } >= MAX_QUEUED_AUDIO || writes.size >= MAX_QUEUED_WRITES) {
                        val oldest = writes.firstOrNull { it.audio } ?: break
                        writes.remove(oldest)
                    }
                    if (writes.size >= MAX_QUEUED_WRITES) continue
                } else {
                    while (writes.size >= MAX_QUEUED_WRITES) {
                        val oldest = writes.firstOrNull { it.audio } ?: break
                        writes.remove(oldest)
                    }
                    if (writes.size >= MAX_QUEUED_WRITES) {
                        fail("command queue full")
                        return@post
                    }
                }
                writes.addLast(Write(part, audio))
            }
            pump()
        }
    }

    /** Posting puts this behind a final disconnect/reject frame posted by the session's send callback. */
    fun closeWhenIdle() {
        handler.post {
            if (phase == Phase.CLOSED || draining) return@post
            draining = true
            writes.removeAll { it.audio }
            handler.postDelayed(drainTimeout, DRAIN_TIMEOUT_MS)
            if (phase != Phase.READY) close() else pump()
        }
    }

    fun close() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        clearOperationTimeout()
        handler.removeCallbacks(connectionTimeout)
        handler.removeCallbacks(drainTimeout)
        writes.clear()
        inFlight = null
        val old = gatt
        gatt = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        Log.i(TAG, "closed")
        listener.onClosed(this)
    }

    private fun discoverServices() {
        val link = gatt ?: return
        phase = Phase.SERVICES
        armOperationTimeout("services")
        if (!begin("services") { link.discoverServices() }) fail("service discovery not started")
    }

    private fun subscribe(audio: Boolean) {
        val link = gatt ?: return
        val characteristic = (if (audio) audioNotify else cmdNotify) ?: return fail("missing notify characteristic")
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) return fail("unsupported notification")
        val descriptor = characteristic.getDescriptor(if (audio) profile.audioCccd else profile.cmdCccd)
            ?: return fail("missing subscription descriptor")
        phase = if (audio) Phase.AUDIO_CCCD else Phase.CMD_CCCD
        if (!begin("local notification") { link.setCharacteristicNotification(characteristic, true) }) return fail("notification not enabled")
        armOperationTimeout("subscription")
        val started = begin("subscription") {
            if (Build.VERSION.SDK_INT >= 33) {
                link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                link.writeDescriptor(descriptor)
            }
        }
        if (!started) fail("subscription not started")
    }

    private fun pump() {
        if (phase != Phase.READY || inFlight != null) return
        val current = writes.pollFirst()
        if (current == null) {
            if (draining) close()
            return
        }
        val link = gatt ?: return
        val characteristic = (if (current.audio) audioWrite else cmdWrite) ?: return fail("missing write characteristic")
        val writeType = if (current.audio) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else commandWriteType
        inFlight = current
        if (!current.audio) Log.d(TAG, "send opcode=${current.frame.getOrNull(7)?.toInt()?.and(0xff)}")
        armOperationTimeout("write")
        val started = begin("write") {
            if (Build.VERSION.SDK_INT >= 33) {
                link.writeCharacteristic(characteristic, current.frame, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = current.frame
                link.writeCharacteristic(characteristic)
            }
        }
        // Android reports onCharacteristicWrite for both write types; never overlap ATT writes.
        if (!started) fail("write not started")
    }

    private fun received(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val uuid = characteristic.uuid
        onHandler(link) {
            if (phase != Phase.READY || (uuid != profile.audioNotify && uuid != profile.cmdNotify)) return@onHandler
            if (uuid == profile.cmdNotify) Log.d(TAG, "receive opcode=${value.getOrNull(7)?.toInt()?.and(0xff)}")
            listener.onFrame(this@GattClient, value)
        }
    }

    private fun onHandler(link: BluetoothGatt, action: () -> Unit) {
        handler.post { if (phase != Phase.CLOSED && gatt === link) action() }
    }

    private fun begin(operation: String, action: () -> Boolean): Boolean = try {
        action()
    } catch (error: RuntimeException) {
        Log.w(TAG, "$operation failed ${error.javaClass.simpleName}")
        false
    }

    private fun armOperationTimeout(operation: String) {
        clearOperationTimeout()
        operationTimeout = Runnable { fail("$operation timeout") }.also { handler.postDelayed(it, OPERATION_TIMEOUT_MS) }
    }

    private fun clearOperationTimeout() {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
    }

    private fun fail(reason: String) {
        if (phase == Phase.CLOSED) return
        Log.w(TAG, "$reason phase=$phase")
        close()
    }

    private companion object {
        const val TAG = "BLuE.GattClient"
        const val CONNECTION_TIMEOUT_MS = 30_000L
        const val OPERATION_TIMEOUT_MS = 10_000L
        const val DRAIN_TIMEOUT_MS = 2_000L
        const val MAX_QUEUED_WRITES = 64
        const val MAX_QUEUED_AUDIO = 8
    }
}
