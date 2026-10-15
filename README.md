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
| Audio engine, Android call integration (core-telecom), service, UI | installed and running; calls untested until the connection layer exists |
| **GATT connection layer** | **missing**: see below |

### The missing piece

Calls need a GATT server and client implementing the profiles in [`docs/protocol.md`](docs/protocol.md) (`GattProfile.APP` and `GattProfile.XIAOMI`). Plug it into `CallController` (`app/src/main/java/dev/blue/call/CallController.kt`):

- Set `dialer` to open a connection to a `Discovery.Peer`. Use the Xiaomi profile when `peer.xiaomi` is true. For long range, connect over Coded PHY and prefer S=8 coding.
- When frames can flow, call `attach(role, send, xiaomi)` and feed every received frame to the returned session's `receive`.
- Call `detached()` when the connection drops.

### Xiaomi compatibility

Turn on **Talk to Xiaomi phones** to make a long-range phone visible to stock Xiaomi Offline Communication. Xiaomi's format identifies phones by phone number and broadcasts it openly. BLuE warns about this and keeps the mode off by default. On Xiaomi phones the switch is disabled, because the system already provides the feature.

## Building

1. Install Android Studio, then add **NDK (Side by side)** in the SDK Manager.
2. Open this folder in Android Studio and press Run, or from a terminal run `gradlew assembleDebug`.
3. Tests: `gradlew testDebugUnitTest` on the PC; `gradlew connectedDebugAndroidTest` with a phone connected.

Requirements: Android 8.0+ and a phone with Bluetooth Low Energy. Long range needs LE Coded PHY support.

## Licence

MIT (see `LICENSE`). Bundles libspeex under its BSD licence (`app/src/main/cpp/speex/COPYING`).
