package dev.blue

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.blue.ble.AppAd
import dev.blue.ble.Discovery
import dev.blue.ble.XiaomiAd
import dev.blue.call.CallController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Foreground service: keeps the phone discoverable, lists nearby phones and owns the call controller. */
class BlueService : Service() {

    private lateinit var adapter: BluetoothAdapter
    private var discovery: Discovery? = null
    private val seen = LinkedHashMap<String, Pair<Discovery.Peer, Long>>()

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { _bluetoothOn.value = true; startDiscovery() }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    _bluetoothOn.value = false
                    discovery?.stop()
                    discovery = null
                    _peers.value = emptyList()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        goForeground(callActive = false)
        controller = CallController(this) { active -> goForeground(active) }
        adapter = getSystemService(BluetoothManager::class.java).adapter
        _bluetoothOn.value = adapter.isEnabled
        ContextCompat.registerReceiver(this, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        startDiscovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> controller?.answer()
            ACTION_DECLINE -> controller?.reject()
        }
        return START_STICKY
    }

    /** Re-advertises, e.g. after the name changed. */
    fun startDiscovery() {
        if (!adapter.isEnabled) return
        val prefs = Prefs(this)
        val me = AppAd(prefs.id, prefs.name, adapter.isLeCodedPhySupported, adapter.isLeExtendedAdvertisingSupported)
        // visibleToAll: stock Xiaomi phones otherwise list us only if our number is in their contacts.
        val xiaomi = if (prefs.xiaomiMode && xiaomiModeAvailable(this)) XiaomiAd(prefs.xiaomiNumber, "", visibleToAll = true) else null
        discovery?.stop()
        synchronized(this) { seen.clear(); _peers.value = emptyList() }
        discovery = Discovery(adapter, ::onPeer).also { it.start(me, xiaomi) }
    }

    @Synchronized
    private fun onPeer(peer: Discovery.Peer) {
        val now = System.currentTimeMillis()
        seen[peer.key] = peer to now
        seen.entries.removeAll { now - it.value.second > STALE_MS }
        _peers.value = seen.values.map { it.first }
    }

    private fun goForeground(callActive: Boolean) {
        var types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (callActive && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        val text = if (callActive) "In an offline call" else "Listening for nearby calls"
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.service(this, text), types)
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothState)
        discovery?.stop()
        controller?.hangUp()
        controller = null
        instance = null
        _bluetoothOn.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_ANSWER = "dev.blue.ANSWER"
        const val ACTION_DECLINE = "dev.blue.DECLINE"
        private const val STALE_MS = 30_000L

        private val _peers = MutableStateFlow<List<Discovery.Peer>>(emptyList())
        val peers: StateFlow<List<Discovery.Peer>> = _peers

        private val _bluetoothOn = MutableStateFlow<Boolean?>(null)

        /** Null while the service isn't running (e.g. permissions not granted yet). */
        val bluetoothOn: StateFlow<Boolean?> = _bluetoothOn

        @Volatile var instance: BlueService? = null
            private set
        @Volatile var controller: CallController? = null
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, BlueService::class.java))

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
