# CallBLuE

**BLuE** is an Android app for offline voice calls over Bluetooth Low Energy: no SIM, Wi-Fi or mobile network needed. It also talks to Xiaomi's built-in "Offline Communication".

> Independent project, not affiliated with Xiaomi. It contains no Xiaomi code; the protocol was documented through interoperability analysis.

- **Calls between BLuE phones:** long range on phones with LE Coded PHY, standard Bluetooth range otherwise. Encrypted, with a 4-digit check code on both screens.
- **Xiaomi compatibility:** optional and off by default. Xiaomi's format broadcasts your phone number openly, and BLuE warns about this.
- **Native Android calls:** ringing, lock screen, headsets, Mute and Speaker.

Requires Android 8.0+ with Bluetooth Low Energy.

## Build

Install Android Studio with **NDK (Side by side)**, then run `gradlew assembleDebug`. Run tests with `gradlew testDebugUnitTest`.

## Docs

- [Protocol](docs/protocol.md)
- [Call validation](docs/call-validation.md)

## Licence

MIT. Bundles libspeex under its BSD licence (`app/src/main/cpp/speex/COPYING`).
