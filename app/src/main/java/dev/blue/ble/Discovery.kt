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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log

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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            record.getServiceData(XIAOMI_UUID)?.let(XiaomiAd::decode)?.let { ad ->
                onPeer(Peer(result.device, "mi:${ad.number1}", ad.number1, result.rssi, longRange = true, xiaomi = true))
                return
            }
            record.getManufacturerSpecificData(AppAd.COMPANY_ID)?.let(AppAd::decode)?.let { ad ->
                onPeer(Peer(result.device, "app:${ad.id}", ad.name, result.rssi, longRange = ad.coded && longRangeCapable, xiaomi = false))
            }
        }

        override fun onScanFailed(errorCode: Int) = Log.w(TAG, "scan failed: $errorCode").let {}
    }

    /** [xiaomi] non-null enables Xiaomi mode; ignored on phones without long range. */
    fun start(me: AppAd, xiaomi: XiaomiAd?) {
        stop()
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val appService = ParcelUuid(GattProfile.APP.service)
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build(),
            AdvertiseData.Builder().addServiceUuid(appService).build(),
            AdvertiseData.Builder().addManufacturerData(AppAd.COMPANY_ID, me.encode()).build(),
            legacyCallback,
        )
        val useXiaomi = xiaomi != null && longRangeCapable
        if (longRangeCapable) {
            advertiser.startAdvertisingSet(
                codedParams(),
                AdvertiseData.Builder().addServiceUuid(appService).addManufacturerData(AppAd.COMPANY_ID, me.encode()).build(),
                null, null, null, longRangeCallback,
            )
        }
        if (useXiaomi) {
            advertiser.startAdvertisingSet(
                codedParams(),
                AdvertiseData.Builder().setIncludeTxPowerLevel(true).addServiceData(XIAOMI_UUID, xiaomi!!.encode()).build(),
                null, null, null, xiaomiCallback,
            )
        }

        val filters = mutableListOf(ScanFilter.Builder().setServiceUuid(appService).build())
        if (useXiaomi) filters += ScanFilter.Builder().setServiceData(XIAOMI_UUID, XiaomiAd.MAGIC, ByteArray(XiaomiAd.MAGIC.size) { -1 }).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).apply {
            // Also report extended adverts, on every PHY the controller supports (Coded included).
            if (adapter.isLeExtendedAdvertisingSupported) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }.build()
        adapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    fun stop() {
        val advertiser = adapter.bluetoothLeAdvertiser
        runCatching { advertiser?.stopAdvertising(legacyCallback) }
        runCatching { advertiser?.stopAdvertisingSet(longRangeCallback) }
        runCatching { advertiser?.stopAdvertisingSet(xiaomiCallback) }
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
    }

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
        val XIAOMI_UUID: ParcelUuid = ParcelUuid(GattProfile.XIAOMI.service)
    }
}
