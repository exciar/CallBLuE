package dev.blue

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.blue.ble.AppAd
import dev.blue.ble.Discovery
import dev.blue.ble.GattConnection
import dev.blue.ble.XiaomiAd
import dev.blue.call.CallController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Foreground service: keeps the phone discoverable, lists nearby phones and owns the call controller. */
class BlueService : Service() {

    private var adapter: BluetoothAdapter? = null
    private var discovery: Discovery? = null
    private var connection: GattConnection? = null
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private val stopScanning = Runnable { discovery?.setScanning(false) }
    @Volatile private var destroying = false
    @Volatile private var callPrepared = false
    private val seen = LinkedHashMap<String, Pair<Discovery.Peer, Long>>()

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { _bluetoothOn.value = true; startDiscovery(); refreshNotification() }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    _bluetoothOn.value = false
                    discovery?.stop()
                    discovery = null
                    connection?.close()
                    connection = null
                    clearPeers()
                    refreshNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        _available.value = prefs.listeningEnabled
        adapter = getSystemService(BluetoothManager::class.java).adapter
        if (!prefs.listeningEnabled || !hasBluetoothPermissions(this) || adapter == null) {
            stopSelf()
            return
        }
        instance = this
        Notifications.createChannels(this)
        _bluetoothOn.value = adapter?.isEnabled
        goForeground(callActive = false)
        controller = CallController(this, onCallActive = { active ->
            if (!active) callPrepared = false
            goForeground(active)
        }, onPrepareCall = ::prepareCall)
        // Bluetooth broadcasts may come from a privileged Bluetooth UID rather than the system UID.
        ContextCompat.registerReceiver(this, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        prefs.listeningStarted = true
        startDiscovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance !== this || !Prefs(this).listeningEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_ANSWER -> controller?.answer()
            ACTION_DECLINE -> controller?.reject()
            ACTION_HANG_UP -> controller?.hangUp()
            ACTION_GO_OFFLINE -> {
                setAvailable(this, false)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /** Re-advertises, e.g. after the name changed. */
    fun startDiscovery() {
        val adapter = adapter ?: return
        if (!adapter.isEnabled) return
        if (!hasBluetoothPermissions(this)) return
        val prefs = Prefs(this)
        val me = AppAd(prefs.id, prefs.name, adapter.isLeCodedPhySupported, adapter.isLeExtendedAdvertisingSupported)
        // visibleToAll: stock Xiaomi phones otherwise list us only if our number is in their contacts.
        val xiaomi = if (prefs.xiaomiMode && xiaomiModeAvailable(this)) XiaomiAd(prefs.xiaomiNumber, "", visibleToAll = true) else null
        if (connection == null) connection = controller?.let { GattConnection(this, it) }
        connection?.start(xiaomi != null)
        discovery?.stop()
        clearPeers()
        discovery = Discovery(adapter, ::onPeer).also { it.start(me, xiaomi, scan = isUiVisible) }
    }

    @Synchronized
    private fun clearPeers() {
        seen.clear()
        _peers.value = emptyList()
    }

    private fun setScanning(enabled: Boolean) {
        handler.removeCallbacks(stopScanning)
        if (enabled) {
            clearPeers()
            discovery?.setScanning(true)
        } else {
            // Short activity transitions should not repeatedly consume Android's scan-start quota.
            handler.postDelayed(stopScanning, 5_000)
        }
    }

    @Synchronized
    private fun onPeer(peer: Discovery.Peer) {
        if (!isUiVisible || destroying) return
        val now = System.currentTimeMillis()
        seen[peer.key] = peer to now
        seen.entries.removeAll { now - it.value.second > STALE_MS }
        _peers.value = seen.values.map { it.first }
    }

    /** Acquire microphone eligibility during Dial/Answer, before asynchronous Bluetooth negotiation. */
    private fun prepareCall(): Boolean {
        if (destroying) return false
        if (callPrepared) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow microphone access in BLuE's app settings to call.", Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            callPrepared = true
            refreshNotification()
            true
        } catch (e: RuntimeException) {
            callPrepared = false
            Log.w(TAG, "Could not prepare call foreground service", e)
            false
        }
    }

    private fun refreshNotification() = goForeground(CallController.call.value?.state == dev.blue.call.CallSession.State.ACTIVE)

    private fun goForeground(callActive: Boolean) {
        if (destroying) return
        var types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if ((callActive || callPrepared) && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        // Android only accepts a CallStyle notification from a foreground service (or with a full-screen
        // intent), so during a call the service's own notification becomes the ongoing-call one.
        val peer = CallController.call.value?.peer?.ifBlank { null } ?: "Offline call"
        val status = if (_bluetoothOn.value == false) "Turn on Bluetooth to receive calls" else "Available for nearby calls"
        val notification = if (callActive) Notifications.ongoing(this, peer) else Notifications.service(this, status)
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, notification, types)
    }

    override fun onDestroy() {
        destroying = true
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(bluetoothState)
        discovery?.stop()
        if (instance === this) controller?.hangUp()
        connection?.close()
        connection = null
        if (instance === this) {
            controller = null
            instance = null
            _bluetoothOn.value = null
            clearPeers()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_ANSWER = "dev.blue.ANSWER"
        const val ACTION_DECLINE = "dev.blue.DECLINE"
        const val ACTION_HANG_UP = "dev.blue.HANG_UP"
        const val ACTION_GO_OFFLINE = "dev.blue.GO_OFFLINE"
        private const val TAG = "BLuE.Service"
        private const val STALE_MS = 30_000L

        private val _peers = MutableStateFlow<List<Discovery.Peer>>(emptyList())
        val peers: StateFlow<List<Discovery.Peer>> = _peers

        private val _available = MutableStateFlow(true)
        val available: StateFlow<Boolean> = _available

        @Volatile internal var isUiVisible = false
            private set

        fun setUiVisible(visible: Boolean) {
            if (isUiVisible == visible) return
            isUiVisible = visible
            instance?.setScanning(visible)
        }

        fun syncAvailability(context: Context) {
            _available.value = Prefs(context).listeningEnabled
        }

        fun setAvailable(context: Context, enabled: Boolean) {
            Prefs(context).listeningEnabled = enabled
            _available.value = enabled
            if (enabled) start(context) else context.stopService(Intent(context, BlueService::class.java))
        }

        private val _bluetoothOn = MutableStateFlow<Boolean?>(null)

        /** Null while the service isn't running (e.g. permissions not granted yet). */
        val bluetoothOn: StateFlow<Boolean?> = _bluetoothOn

        @Volatile var instance: BlueService? = null
            private set
        @Volatile var controller: CallController? = null
            private set

        fun start(context: Context) {
            syncAvailability(context)
            if (!_available.value || !hasBluetoothPermissions(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, BlueService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Android deferred listening startup; open BLuE to resume", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Listening startup requires Bluetooth permission", e)
            }
        }

        fun hasBluetoothPermissions(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        /** Xiaomi phones whose system already speaks this protocol; running it twice would clash. */
        fun hasBuiltInXiaomiFeature(context: Context) =
            runCatching { context.packageManager.getPackageInfo("com.xiaomi.bluetooth", 0) }.isSuccess

        /** Xiaomi mode needs long range, and is pointless (and conflicting) on Xiaomi's own phones. */
        fun xiaomiModeAvailable(context: Context): Boolean {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return false
            return adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported && !hasBuiltInXiaomiFeature(context)
        }
    }
}
