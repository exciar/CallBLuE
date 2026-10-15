package dev.blue.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.UUID

/** Local profiles and per-central subscriptions. All mutable state belongs to [handler]. */
@SuppressLint("MissingPermission")
internal class GattServer(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(device: BluetoothDevice, profile: GattProfile, send: (ByteArray, Boolean) -> Unit): Boolean
        fun onFrame(device: BluetoothDevice, profile: GattProfile, frame: ByteArray)
        fun onClosed(device: BluetoothDevice, profile: GattProfile?)
    }

    private data class Notification(val characteristic: BluetoothGattCharacteristic, val bytes: ByteArray, val audio: Boolean)
    private class Central(val device: BluetoothDevice) {
        var mtu = 23
        var profile: GattProfile? = null
        val subscriptions = mutableSetOf<UUID>()
        var ready = false
        var closing = false
        var pending: Notification? = null
        val queue = ArrayDeque<Notification>()
        var timeout: Runnable? = null
        var drainTimeout: Runnable? = null
    }

    private var server: BluetoothGattServer? = null
    private var xiaomiEnabled = false
    private var adding: BluetoothGattService? = null
    private val services = ArrayDeque<BluetoothGattService>()
    private val centrals = mutableMapOf<String, Central>()

    fun start(xiaomi: Boolean) {
        xiaomiEnabled = xiaomi
        if (server == null) {
            server = context.getSystemService(BluetoothManager::class.java).openGattServer(context, callback)
            if (server == null) { Log.e(TAG, "could not open GATT server"); return }
            services.add(service(GattProfile.APP))
        }
        // Removing a service under a live link invalidates its handles. Gate new Xiaomi links
        // immediately, and leave its handles installed until the server itself is closed.
        if (xiaomi && server?.getService(GattProfile.XIAOMI.service) == null &&
            adding?.uuid != GattProfile.XIAOMI.service && services.none { it.uuid == GattProfile.XIAOMI.service }) {
            services.add(service(GattProfile.XIAOMI))
        }
        addNextService()
    }

    private fun addNextService() {
        if (adding != null || services.isEmpty()) return
        adding = services.removeFirst()
        if (server?.addService(adding) != true) {
            Log.e(TAG, "could not register service ${adding?.uuid}")
            adding = null
            addNextService()
        }
    }

    fun closeWhenIdle(device: BluetoothDevice) {
        handler.post {
            val central = centrals[device.address] ?: return@post
            if (central.closing) return@post
            central.closing = true
            central.queue.removeAll { it.audio }
            central.drainTimeout = Runnable { if (centrals[device.address] === central) disconnect(central) }
                .also { handler.postDelayed(it, 2_000) }
            if (central.pending == null && central.queue.isEmpty()) disconnect(central)
        }
    }

    fun disconnect(device: BluetoothDevice) {
        centrals[device.address]?.let(::disconnect)
    }

    fun close() {
        centrals.values.toList().forEach(::disconnect)
        server?.close()
        server = null
        services.clear()
        adding = null
    }

    private fun disconnect(central: Central) {
        if (centrals.remove(central.device.address) !== central) return
        central.timeout?.let(handler::removeCallbacks)
        central.drainTimeout?.let(handler::removeCallbacks)
        central.queue.clear()
        // Server callbacks also report shared LE links opened by our own GATT client.
        // Only cancel connections that actually selected one of our profiles.
        if (central.profile != null) runCatching { server?.cancelConnection(central.device) }
        Log.i(TAG, "central closed")
        listener.onClosed(central.device, central.profile)
    }

    private fun arm(central: Central, delay: Long, action: () -> Unit) {
        central.timeout?.let(handler::removeCallbacks)
        central.timeout = Runnable { if (centrals[central.device.address] === central) action() }
            .also { handler.postDelayed(it, delay) }
    }

    private fun select(central: Central, profile: GattProfile): Boolean {
        if (central.profile == null && profile == GattProfile.XIAOMI && !xiaomiEnabled) return false
        if (central.profile != null && central.profile != profile) return false
        central.profile = profile
        return true
    }

    private fun ready(central: Central) {
        val profile = central.profile ?: return
        if (central.ready || profile.cmdNotify !in central.subscriptions) return
        if (profile == GattProfile.APP && profile.audioNotify !in central.subscriptions) return
        central.ready = true
        central.timeout?.let(handler::removeCallbacks)
        central.timeout = null
        if (!listener.onReady(central.device, profile) { bytes, audio ->
                val copy = bytes.copyOf()
                handler.post { if (centrals[central.device.address] === central) send(central, copy, audio) }
            }) disconnect(central)
        else Log.i(TAG, "callee ready xiaomi=${profile == GattProfile.XIAOMI} mtu=${central.mtu}")
    }

    private fun send(central: Central, bytes: ByteArray, audio: Boolean) {
        if (!central.ready || central.closing) return
        val profile = central.profile ?: return
        val uuid = if (audio) profile.audioNotify else profile.cmdNotify
        // Stock Xiaomi registers audio locally but can lose its descriptor write to BUSY.
        if (uuid !in central.subscriptions && !(audio && profile == GattProfile.XIAOMI)) {
            disconnect(central); return
        }
        val characteristic = server?.getService(profile.service)?.getCharacteristic(uuid) ?: return
        val frames = try { GattFrames.fit(bytes, central.mtu) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "outgoing frame cannot fit MTU ${central.mtu}")
            disconnect(central); return
        }
        if (audio && central.queue.count { it.audio } >= 4) central.queue.remove(central.queue.first { it.audio })
        if (central.queue.size + frames.size > 64) { disconnect(central); return }
        frames.forEach { central.queue.add(Notification(characteristic, it, audio)) }
        pump(central)
    }

    @Suppress("DEPRECATION")
    private fun pump(central: Central) {
        if (central.pending != null) return
        val item = central.queue.removeFirstOrNull()
        if (item == null) {
            if (central.closing) disconnect(central)
            return
        }
        central.pending = item
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(central.device, item.characteristic, false, item.bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            item.characteristic.value = item.bytes
            server?.notifyCharacteristicChanged(central.device, item.characteristic, false) == true
        }
        if (!accepted) { Log.w(TAG, "notification rejected"); disconnect(central); return }
        arm(central, if (central.closing) 2_000 else 10_000) {
            Log.w(TAG, "notification timed out"); disconnect(central)
        }
    }

    private fun profile(characteristic: BluetoothGattCharacteristic): GattProfile? = when (characteristic.service?.uuid) {
        GattProfile.APP.service -> GattProfile.APP
        GattProfile.XIAOMI.service -> GattProfile.XIAOMI
        else -> null
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (server == null) return@post
                Log.i(TAG, "service ${service.uuid} registered status=$status")
                adding = null
                addNextService()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (server == null) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (centrals.containsKey(device.address)) return@post
                    val central = Central(device)
                    centrals[device.address] = central
                    Log.i(TAG, "incoming LE connection")
                    arm(central, 30_000) { Log.w(TAG, "central setup timed out"); disconnect(central) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    centrals[device.address]?.let(::disconnect)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                centrals[device.address]?.mtu = mtu
                Log.i(TAG, "central MTU $mtu")
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            handler.post {
                val enabled = descriptor.characteristic.uuid in (centrals[device.address]?.subscriptions ?: emptySet())
                val value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                val status = if (offset in 0..value.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
                server?.sendResponse(device, requestId, status, offset, if (status == 0) value.copyOfRange(offset, value.size) else null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val bytes = value.copyOf()
            handler.post {
                val central = centrals[device.address]
                val p = profile(descriptor.characteristic)
                val validDescriptor = p != null && ((descriptor.characteristic.uuid == p.cmdNotify && descriptor.uuid == p.cmdCccd) ||
                    (descriptor.characteristic.uuid == p.audioNotify && descriptor.uuid == p.audioCccd))
                val status = when {
                    preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    !validDescriptor || central == null || p == null || !select(central, p) -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    !bytes.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) &&
                        !bytes.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (status == BluetoothGatt.GATT_SUCCESS && central != null) {
                    if (bytes.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) central.subscriptions.add(descriptor.characteristic.uuid)
                    else central.subscriptions.remove(descriptor.characteristic.uuid)
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
                Log.i(TAG, "subscribe ${descriptor.characteristic.uuid} status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS && central != null) ready(central)
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val bytes = value.copyOf()
            handler.post {
                val central = centrals[device.address]
                val p = profile(characteristic)
                val status = when {
                    preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    central == null || p == null || !select(central, p) ||
                        (characteristic.uuid != p.audioWrite && characteristic.uuid != p.cmdWrite) -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
                if (status == BluetoothGatt.GATT_SUCCESS && central != null && p != null) {
                    ready(central)
                    if (centrals[device.address] === central && central.ready && !central.closing) listener.onFrame(device, p, bytes)
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            handler.post { server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null) }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post { server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                val central = centrals[device.address] ?: return@post
                if (central.pending == null) return@post
                central.timeout?.let(handler::removeCallbacks)
                central.timeout = null
                central.pending = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "notification failed status=$status"); disconnect(central)
                } else pump(central)
            }
        }
    }

    private fun service(p: GattProfile) = BluetoothGattService(p.service, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        fun write(uuid: UUID, properties: Int) = BluetoothGattCharacteristic(uuid, properties, BluetoothGattCharacteristic.PERMISSION_WRITE)
        fun notify(uuid: UUID, cccd: UUID) = BluetoothGattCharacteristic(uuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
            addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        addCharacteristic(write(p.audioWrite, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
        addCharacteristic(notify(p.audioNotify, p.audioCccd))
        addCharacteristic(write(p.cmdWrite, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
        addCharacteristic(notify(p.cmdNotify, p.cmdCccd))
    }

    private companion object { const val TAG = "BLuE.GattServer" }
}
