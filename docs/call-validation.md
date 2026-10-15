# Call validation, 2026-10-15

## Answer-time disconnect investigation

The two outgoing attempts in `research/s25_mute.log` (20:17) receive and authenticate `CALL_ANSWER`, enter Android Telecom's active state, then throw `IllegalArgumentException` while processing the answer. No exception stack was captured in that log. `GattConnection.receive` also invokes local call callbacks, so its former "invalid peer frame" message did not establish a wire-protocol problem.

Despite its name, `research/s25_stack.log` contains no BLuE exception stack. Its early attempts fail with GATT status 147 or a missing service. Its final attempt is active from 20:32:50, receives over 100 audio packets, and receives Xiaomi's disconnect opcode at 20:33:20.

The cause is publishing a separate ongoing `CallStyle` notification outside `startForeground`. The S25 instrumentation reproduces the rejection, and the Xiaomi's recovered crash buffer provides the missing production stack: at 20:56:08 and 20:56:18, `CallController.startAudio` calls `NotificationManager.notify` for notification ID 2, and Android throws `IllegalArgumentException` because that CallStyle notification is not associated with a foreground service/job and has no full-screen intent. The Xiaomi still had the 20:12:45 build installed. Its outgoing attempts also logged the same misleading "invalid peer frame" error after answering.

Android requires that notification itself to qualify as a foreground-service/job notification or have a full-screen intent. An already-running foreground service with another notification does not qualify the separately posted notification. Android 16's [notification policy](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java#9116) and [foreground ID association](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java#3035) establish those conditions. The current `BlueService.goForeground` uses the ongoing call notification as `SERVICE_ID`, preserving the user's correction made after the failures. Installing that current build on both phones removes the crash. Protocol, encryption, and call sequence are unchanged.

The catch now reports the opcode and current session state as a frame-handling failure, retaining the exception stack without attributing local notification/audio failures to malformed peer bytes.

The local GATT server also observes outgoing LE connections. Its unselected-central setup timeout only removes that observation: it neither cancels the physical connection nor detaches the outgoing client session.

## Phone evidence

| Check | Evidence |
|---|---|
| BLuE to BLuE | Earlier Xiaomi/OnePlus call passed. After updating both phones, S25 to Xiaomi at 21:02 stays active for about 39 seconds, receives over 200 audio packets on each side, and user confirms matching check codes and two-way speech. Xiaomi to S25 also reaches active audio without the old exception. |
| Stock Xiaomi to BLuE | S25: steady two-way audio for about 2.5 minutes and clean hang-up in `research/s25_calltest.log`; fresh 20:52:18–20:54:09 call receives over 600 packets, speech confirmed both ways, Xiaomi sends Hang up |
| BLuE to stock Xiaomi | S25: fresh calls at 20:47 and 20:48 carry continuing audio; user confirms speech in both directions |
| Mute / Unmute | User confirms S25 speech alone is silenced and is restored on Unmute |
| Ongoing notification | Android reports `SERVICE_ID=1`, ongoing + foreground-service `CallStyle`, and a Hang up action during the live call |
| Notification actions | Production Answer/Hang up PendingIntents work in the Android regressions on both phones. A manual S25 notification Hang up at 20:58:40 also closes the link left after the old Xiaomi build crashed. |

One live call was interrupted at 20:49:52 by an APK reinstall (`installPackageLI` force-stop), rather than a GATT disconnect. Avoid replacing the APK during a call when measuring stability.

## Automated checks

`gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest` passes, including all 58 JVM tests. All seven instrumentation tests pass on both S25 and Xiaomi when run with `adb -s <serial> shell am instrument -w dev.blue.test/androidx.test.runner.AndroidJUnitRunner`:

- Four actual-service call regressions: secure/Xiaomi mode as local caller/callee, including notification Answer and Hang up, audio startup, and retained foreground notification after incoming-notification cancellation.
- An Android 16 policy regression reproducing rejection of a separately posted ongoing CallStyle notification.
- Both native Speex codec tests.

The current APK is installed on both test phones. Full local evidence is saved in `research/xiaomi_blue_crash_investigation.log`, `research/s25_investigation_current.log`, and `research/s25_investigation_validation.log`.

Logs and reverse-engineering material remain in the ignored `research/` directory; none is bundled into the app.
