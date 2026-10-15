# CallBLuE

**BLuE** is an Android app for offline voice calls over Bluetooth Low Energy: no SIM, Wi-Fi or mobile network needed. It also talks to Xiaomi's built-in "Offline Communication".

> Independent project, not affiliated with Xiaomi. It contains no Xiaomi code; the protocol was documented through interoperability analysis.

- **Calls between BLuE phones:** long range on phones with LE Coded PHY, standard Bluetooth range otherwise. Encrypted, with a 4-digit check code on both screens.
- **Xiaomi compatibility:** optional and off by default. Xiaomi's format broadcasts your phone number openly, and BLuE warns about this.
- **Native Android calls:** ringing, lock screen, headsets, Mute and Speaker.
- **Stay available:** after opening BLuE and allowing Bluetooth, advertising and incoming calls continue off-screen. Listening resumes after unlocking following a reboot, or after an app update; the availability switch or notification's **Go offline** action turns it off. Nearby scanning runs while the app is open, with a short grace period when leaving.

Requires Android 8.0+ with Bluetooth Low Energy.

Keep Bluetooth on and allow call notifications to receive calls while the phone is locked. **Background settings** opens your phone's controls: on Xiaomi/POCO, enable BLuE under **Settings → Apps → Background autostart**; on other phones, allow background activity if their battery manager stops BLuE. Android's **Force stop** requires opening BLuE again.

## Build

Install Android Studio with **NDK (Side by side)**, then run `gradlew assembleDebug`. Run tests with `gradlew testDebugUnitTest`.

## Docs

- [Protocol](docs/protocol.md)

## Licence

MIT. Bundles libspeex under its BSD licence (`app/src/main/cpp/speex/COPYING`).
