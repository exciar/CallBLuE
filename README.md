# BLuE

Offline voice calls between Android phones over Bluetooth Low Energy: no SIM, Wi-Fi or mobile network. BLuE is interoperable with the protocol behind Xiaomi's "Offline Communication" feature.

> BLuE is an independent project. It is not affiliated with, endorsed by or connected to Xiaomi. It contains no Xiaomi code; the protocol was documented through interoperability analysis.

## Status

| Part | State |
|---|---|
| Protocol: frames, opcodes, call messages | done, unit-tested |
| Key exchange and encryption (secure app mode, Xiaomi-compatible mode) | done, unit-tested |
| Call state machine | done, unit-tested |
| Speex codec (libspeex 1.2.1 through the NDK) | done; on-device test in `androidTest` |
| Xiaomi broadcast format, Xiaomi call sequence (block-list query) | done, unit-tested |
| Nearby-phone discovery: standard, long range (Coded PHY), Xiaomi-compatible | working on phones (Xiaomi 2510DPC44G, OnePlus 7) |
| Audio engine, Android call integration (core-telecom), service, UI | two-way calls verified on phones; microphone mute and ongoing Hang up notification |
| GATT connection layer | implemented: client/server, both profiles, MTU negotiation, Coded S=8, queued writes and notifications |

### Connections and validation

`BlueService` owns the GATT connection layer and its worker thread. It connects discovery peers using `GattProfile.APP` or `GattProfile.XIAOMI`, attaches the appropriate caller/callee session after channel setup, and detaches on disconnection. Writes and notifications are serialized, with bounded audio queues and a short signaling drain before local disconnect.

BLuE-to-BLuE calls have been checked for matching check codes and two-way speech. Stock Xiaomi interoperability has been exercised in both directions with a Samsung S25 Ultra. See [`docs/call-validation.md`](docs/call-validation.md) for the evidence and remaining checks.

### Xiaomi compatibility

Turn on **Talk to Xiaomi phones** to make a long-range phone visible to stock Xiaomi Offline Communication. Xiaomi's format identifies phones by phone number and broadcasts it openly. BLuE warns about this and keeps the mode off by default. On Xiaomi phones the switch is disabled, because the system already provides the feature.

## Building

1. Install Android Studio, then add **NDK (Side by side)** in the SDK Manager.
2. Open this folder in Android Studio and press Run, or from a terminal run `gradlew assembleDebug`.
3. Tests: `gradlew testDebugUnitTest` on the PC; `gradlew connectedDebugAndroidTest` with a phone connected.

Requirements: Android 8.0+ and a phone with Bluetooth Low Energy. Long range needs LE Coded PHY support.

## Licence

MIT (see `LICENSE`). Bundles libspeex under its BSD licence (`app/src/main/cpp/speex/COPYING`).
