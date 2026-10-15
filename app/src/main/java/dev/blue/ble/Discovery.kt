package dev.blue.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

/**
 * Makes this phone visible and lists phones nearby.
 * - Every BLE phone: legacy advert (service UUID) with [AppAd] in the scan response.
 * - Long-range phones: the same [AppAd] in an extended advert on LE Coded PHY.
 * - Xiaomi mode: the stock [XiaomiAd] payload on Coded PHY, and scanning for Xiaomi phones.
 */
@SuppressLint("MissingPermission") // callers hold BLUETOOTH_SCAN/ADVERTISE before start()
class Discovery(private val adapter: BluetoothAdapter, private val onPeer: (Peer) -> Unit) {

    /** [key] stays stable while the peer's Bluetooth address rotates. */
    data class Peer(val device: BluetoothDevice, val key: String, val name: String, val rssi: Int, val longRange: Boolean, val xiaomi: Boolean)

    val longRangeCapable: Boolean
        get() = adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported

    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = Log.w(TAG, "legacy advertising failed: $errorCode").let {}
    }
    private val longRangeCallback = setCallback("long-range")
    private val xiaomiCallback = setCallback("xiaomi")
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanningWith: BluetoothLeScanner? = null
    private var scanFilters: List<ScanFilter>? = null
    private var scanSettings: ScanSettings? = null
    private var scanningRequested = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanRetryAt = 0L
    private val recentScanStarts = ArrayDeque<Long>()
    private val retryScan = Runnable {
        scanRetryAt = 0L
        if (scanningRequested && scanFilters != null && scanSettings != null) startScanning()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanningRequested || scanningWith == null) return
            val record = result.scanRecord ?: return
            record.getServiceData(XIAOMI_UUID)?.let(XiaomiAd::decode)?.let { ad ->
                onPeer(Peer(result.device, "mi:${ad.number1}", ad.number1, result.rssi, longRange = true, xiaomi = true))
                return
            }
            record.getManufacturerSpecificData(AppAd.COMPANY_ID)?.let(AppAd::decode)?.let { ad ->
                onPeer(Peer(result.device, "app:${ad.id}", ad.name, result.rssi, longRange = ad.coded && longRangeCapable, xiaomi = false))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // A failed attempt does not cancel the caller's request to discover peers.
            stopScanning()
            Log.w(TAG, "scan failed: $errorCode")
            if (errorCode == SCAN_THROTTLED) scheduleScanRetry(SCAN_RETRY_MS)
        }
    }

    /**
     * [xiaomi] non-null enables Xiaomi mode; ignored on phones without long range.
     * Advertising continues until [stop], independently of whether [scan] is enabled.
     */
    fun start(me: AppAd, xiaomi: XiaomiAd?, scan: Boolean = true) {
        stop()
        val appService = ParcelUuid(GattProfile.APP.service)
        val supportsLongRange = attempt("read long-range support") { longRangeCapable } == true
        val supportsExtendedAdvertising = attempt("read extended advertising support") { adapter.isLeExtendedAdvertisingSupported } == true
        val useXiaomi = xiaomi != null && supportsLongRange
        scanFilters = mutableListOf(ScanFilter.Builder().setServiceUuid(appService).build()).apply {
            if (useXiaomi) add(ScanFilter.Builder().setServiceData(XIAOMI_UUID, XiaomiAd.MAGIC, ByteArray(XiaomiAd.MAGIC.size) { -1 }).build())
        }
        scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).apply {
            // Filtered scans can run with the screen off when explicitly requested.
            // Also report extended adverts, on every PHY the controller supports (Coded included).
            if (supportsExtendedAdvertising) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }.build()

        advertiser = attempt("get advertiser") { adapter.bluetoothLeAdvertiser }
        val currentAdvertiser = advertiser
        if (currentAdvertiser != null) {
            attempt("start legacy advertising") {
                currentAdvertiser.startAdvertising(
                    AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(true)
                        .build(),
                    AdvertiseData.Builder().addServiceUuid(appService).build(),
                    AdvertiseData.Builder().addManufacturerData(AppAd.COMPANY_ID, me.encode()).build(),
                    legacyCallback,
                )
            }
        }
        if (currentAdvertiser != null && supportsLongRange) {
            attempt("start long-range advertising") {
                currentAdvertiser.startAdvertisingSet(
                    codedParams(),
                    AdvertiseData.Builder().addServiceUuid(appService).addManufacturerData(AppAd.COMPANY_ID, me.encode()).build(),
                    null, null, null, longRangeCallback,
                )
            }
        }
        if (currentAdvertiser != null && useXiaomi) {
            attempt("start xiaomi advertising") {
                currentAdvertiser.startAdvertisingSet(
                    codedParams(),
                    AdvertiseData.Builder().setIncludeTxPowerLevel(true).addServiceData(XIAOMI_UUID, xiaomi.encode()).build(),
                    null, null, null, xiaomiCallback,
                )
            }
        }

        setScanning(scan)
    }

    /** Starts or stops discovery of nearby peers without restarting this phone's adverts. */
    fun setScanning(enabled: Boolean) {
        scanningRequested = enabled
        if (!enabled) {
            scanHandler.removeCallbacks(retryScan)
            scanRetryAt = 0L
            stopScanning()
            return
        }
        if (scanRetryAt == 0L) startScanning()
    }

    private fun startScanning() {
        if (!scanningRequested || scanningWith != null) return
        val filters = scanFilters ?: return
        val settings = scanSettings ?: return
        val now = SystemClock.elapsedRealtime()
        while (recentScanStarts.isNotEmpty() && now - recentScanStarts.first() >= SCAN_WINDOW_MS) {
            recentScanStarts.removeFirst()
        }
        if (recentScanStarts.size >= MAX_SCAN_STARTS) {
            // Older Android versions can silently suppress scans instead of reporting error 6.
            scheduleScanRetry(recentScanStarts.first() + SCAN_WINDOW_MS - now + 100L)
            return
        }
        val scanner = attempt("get scanner") { adapter.bluetoothLeScanner } ?: return
        scanningWith = scanner
        val started = attempt("start scan") {
            // Failed attempts count too. Keep this bounded history across scan pause/resume.
            recentScanStarts.addLast(SystemClock.elapsedRealtime())
            scanner.startScan(filters, settings, scanCallback)
            true
        } == true
        if (!started) scanningWith = null
    }

    private fun stopScanning() {
        val currentScanner = scanningWith ?: return
        scanningWith = null
        attempt("stop scan") { currentScanner.stopScan(scanCallback) }
    }

    private fun scheduleScanRetry(delayMs: Long) {
        if (!scanningRequested || scanFilters == null || scanSettings == null) return
        val retryAt = SystemClock.elapsedRealtime() + delayMs
        // Keep one main-thread retry. A reported throttle can postpone an earlier cooldown retry.
        if (scanRetryAt >= retryAt) return
        scanHandler.removeCallbacks(retryScan)
        scanRetryAt = retryAt
        scanHandler.postDelayed(retryScan, delayMs)
    }

    fun stop() {
        setScanning(false)
        scanFilters = null
        scanSettings = null
        val currentAdvertiser = advertiser
        advertiser = null
        if (currentAdvertiser != null) {
            attempt("stop legacy advertising") { currentAdvertiser.stopAdvertising(legacyCallback) }
            attempt("stop long-range advertising") { currentAdvertiser.stopAdvertisingSet(longRangeCallback) }
            attempt("stop xiaomi advertising") { currentAdvertiser.stopAdvertisingSet(xiaomiCallback) }
        }
    }

    private inline fun <T> attempt(operation: String, block: () -> T): T? =
        runCatching(block).onFailure { Log.w(TAG, "$operation failed (${it.javaClass.simpleName})") }.getOrNull()

    /** Extended, connectable, Coded PHY for both primary and secondary channels, 250 ms interval. */
    private fun codedParams() = AdvertisingSetParameters.Builder()
        .setLegacyMode(false)
        .setConnectable(true)
        .setScannable(false)
        .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
        .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
        .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        .build()

    private fun setCallback(name: String) = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status != ADVERTISE_SUCCESS) Log.w(TAG, "$name advertising failed: $status") else Log.i(TAG, "$name advertising at $txPower dBm")
        }
    }

    private companion object {
        const val TAG = "BLuE.Discovery"
        // Android reports this code on older releases too; the public constant was added later.
        const val SCAN_THROTTLED = 6
        const val SCAN_RETRY_MS = 30_000L
        const val SCAN_WINDOW_MS = 30_000L
        const val MAX_SCAN_STARTS = 4
        val XIAOMI_UUID: ParcelUuid = ParcelUuid(GattProfile.XIAOMI.service)
    }
}
