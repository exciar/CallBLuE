package dev.blue.ble

import java.util.UUID

/** GATT layout shared by both modes: an audio channel and a command channel, each with a write and a notify characteristic. */
data class GattProfile(
    val service: UUID,
    val audioWrite: UUID,
    val audioNotify: UUID,
    val cmdWrite: UUID,
    val cmdNotify: UUID,
    val audioCccd: UUID,
    val cmdCccd: UUID,
) {
    companion object {
        private fun app(n: Int) = UUID.fromString("7b1e000$n-3c2f-4d8a-9e51-0f6b2a9d4c10")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** BLuE-to-BLuE profile. */
        val APP = GattProfile(app(1), app(5), app(6), app(7), app(8), CCCD, CCCD)

        private fun mi(s: String) = UUID.fromString(s)

        /** Stock Xiaomi Offline Communication profile; note its non-standard notify descriptors. */
        val XIAOMI = GattProfile(
            service = mi("0000fd2d-0000-1000-8000-00805f9b34fb"),
            audioWrite = mi("0000bf05-0000-0000-0000-000000000000"),
            audioNotify = mi("0000bf06-0000-0000-0000-000000000000"),
            cmdWrite = mi("0000bf07-0000-0000-0000-000000000000"),
            cmdNotify = mi("0000bf08-0000-0000-0000-000000000000"),
            audioCccd = mi("11200000-0000-0000-0000-000000000000"),
            cmdCccd = mi("22300000-0000-0000-0000-000000000000"),
        )
    }
}
