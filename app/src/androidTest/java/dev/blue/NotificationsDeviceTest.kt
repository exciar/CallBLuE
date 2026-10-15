package dev.blue

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.blue.call.CallController
import dev.blue.call.CallSession
import dev.blue.protocol.Crypto
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's notification policy using the real service, without a remote Bluetooth phone. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class NotificationsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test
    @SdkSuppress(minSdkVersion = 36)
    fun standaloneOngoingCallRequiresForegroundAssociation() {
        assumeTrue("Do not interfere with an existing call", CallController.call.value == null)
        grantRuntimePermissions()
        Notifications.createChannels(context)
        // This ID is deliberately separate from the notification owned by startForeground().
        val standaloneId = 0xB1E
        try {
            val ongoing = Notifications.ongoing(context, "Test peer")
            val error = assertThrows(IllegalArgumentException::class.java) {
                notifications.notify(standaloneId, ongoing)
            }
            assertTrue(error.message.orEmpty().contains("CallStyle"))
            assertTrue(error.message.orEmpty().contains("foreground service"))
            assertFalse(notifications.activeNotifications.any { it.id == standaloneId })
        } finally {
            notifications.cancel(standaloneId)
        }
    }

    @Test
    fun remoteAnswerPublishesForegroundCallThatSurvivesIncomingCancellationAndCanHangUp() =
        exerciseCall(Crypto.Mode.XIAOMI, CallSession.Role.CALLER)

    @Test
    fun secureRemoteAnswerPublishesForegroundCallAndCanHangUp() =
        exerciseCall(Crypto.Mode.SECURE, CallSession.Role.CALLER)

    @Test
    fun incomingXiaomiCallCanAnswerAndHangUpFromNotifications() =
        exerciseCall(Crypto.Mode.XIAOMI, CallSession.Role.CALLEE)

    @Test
    fun incomingSecureCallCanAnswerAndHangUpFromNotifications() =
        exerciseCall(Crypto.Mode.SECURE, CallSession.Role.CALLEE)

    private fun exerciseCall(mode: Crypto.Mode, role: CallSession.Role) {
        assumeTrue("Do not interrupt an existing call", CallController.call.value == null)
        assumeTrue("The app requires Bluetooth hardware", context.getSystemService(BluetoothManager::class.java).adapter != null)
        grantRuntimePermissions()
        val prefs = Prefs(context)
        val originalEnabled = prefs.listeningEnabled
        val originalStarted = prefs.listeningStarted
        val originalRunning = BlueService.instance != null
        var activity: Activity? = null
        var controller: CallController? = null
        var testSession: CallSession? = null
        var testEpoch: Long? = null
        prefs.listeningEnabled = true
        try {
            val testActivity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            activity = testActivity
            eventually("The listening service did not start") { BlueService.controller != null && serviceNotification() != null }
            val activeController = BlueService.controller!!
            controller = activeController
            val listening = serviceNotification()!!
            assertTrue(listening.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertTrue(listening.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertFalse(isCallStyle(listening))

            // Building the actual incoming notification also checks that Android preserves its actions.
            val incoming = Notifications.incoming(context, "Test caller")
            assertTrue(isCallStyle(incoming))
            assertNotNull(incoming.fullScreenIntent)
            assertAction(incoming, BlueService.ACTION_ANSWER)
            assertAction(incoming, BlueService.ACTION_DECLINE)

            // Incoming calls must remain answerable after leaving the app: listening itself never
            // acquires microphone access. The actual notification action must acquire it on Answer.
            if (role == CallSession.Role.CALLEE) {
                instrumentation.runOnMainSync { testActivity.finish() }
                eventually("The activity did not finish before the incoming call") {
                    testActivity.isDestroyed && !BlueService.isUiVisible
                }
            }

            // Queue both directions so attach() has installed its session before the first reply.
            val toRemote = ConcurrentLinkedQueue<ByteArray>()
            val toLocal = ConcurrentLinkedQueue<ByteArray>()
            val remoteRole = if (role == CallSession.Role.CALLER) CallSession.Role.CALLEE else CallSession.Role.CALLER
            val remote = CallSession(remoteRole, mode, "09998887777",
                { frame, audio -> if (!audio) toLocal.add(frame) }, object : CallSession.Events {})
            lateinit var local: CallSession
            instrumentation.runOnMainSync {
                local = checkNotNull(activeController.attach(role,
                    { frame, audio -> if (!audio) toRemote.add(frame) }, xiaomi = mode == Crypto.Mode.XIAOMI))
                testSession = local
                testEpoch = activeController.connectionEpoch
                if (remoteRole == CallSession.Role.CALLER) remote.start()
                exchange(local, remote, toRemote, toLocal)
                assertEquals(CallSession.State.RINGING, if (role == CallSession.Role.CALLEE) local.state else remote.state)
            }
            if (role == CallSession.Role.CALLEE) {
                eventually("The incoming call notification was not published") {
                    notifications.activeNotifications.any { it.id == Notifications.CALL_ID }
                }
                val ringing = notifications.activeNotifications.single { it.id == Notifications.CALL_ID }.notification
                assertTrue(isCallStyle(ringing))
                assertAction(ringing, BlueService.ACTION_ANSWER)
                ringing.actions.single { it.actionIntent == action(BlueService.ACTION_ANSWER) }.actionIntent.send()
            } else {
                instrumentation.runOnMainSync {
                    remote.answer()
                    // Exercise receive -> onAnswered -> goForeground: a notification-policy failure
                    // here would otherwise surface during processing of a valid peer answer.
                    exchange(local, remote, toRemote, toLocal)
                    assertEquals(CallSession.State.ACTIVE, local.state)
                }
            }

            eventually("Android did not retain the ongoing foreground call notification") {
                serviceNotification()?.let(::isCallStyle) == true
            }
            val ongoing = serviceNotification()!!
            instrumentation.runOnMainSync {
                exchange(local, remote, toRemote, toLocal)
                assertEquals(CallSession.State.ACTIVE, local.state)
                assertEquals(CallSession.State.ACTIVE, remote.state)
            }
            assertTrue(ongoing.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(Notification.CATEGORY_CALL, ongoing.category)
            val title = ongoing.extras.getCharSequence(Notification.EXTRA_TITLE)
            assertNotNull(title)
            assertTrue(title!!.isNotBlank())
            assertAction(ongoing, BlueService.ACTION_HANG_UP)

            Notifications.cancelCall(context)
            // Cross the notification manager boundary again: cancelling the ringing notification
            // must leave the service's call notification published with a usable Hang up action.
            assertTrue(isCallStyle(checkNotNull(serviceNotification())))
            ongoing.actions.single { it.actionIntent == action(BlueService.ACTION_HANG_UP) }.actionIntent.send()
            eventually("The notification's Hang up action did not end the call") {
                serviceNotification()?.let { !isCallStyle(it) } == true
            }
            instrumentation.runOnMainSync {
                assertEquals(CallSession.State.ENDED, local.state)
                exchange(local, remote, toRemote, toLocal)
                assertEquals(CallSession.State.ENDED, remote.state)
            }
        } finally {
            instrumentation.runOnMainSync {
                // A real call may arrive after the synthetic call ends; cleanup owns only its epoch.
                if (testSession?.state != null && testSession?.state != CallSession.State.ENDED &&
                    controller?.connectionEpoch == testEpoch) controller?.hangUp()
                activity?.finish()
            }
            activity?.let { finished -> eventually("The test activity did not finish") { finished.isDestroyed } }
            // Leave an unsolicited real call alone, including the availability needed to answer it.
            if (CallController.call.value == null) {
                if (!originalRunning || !originalEnabled) {
                    instrumentation.runOnMainSync { context.stopService(Intent(context, BlueService::class.java)) }
                    eventually("The test listener did not stop") { BlueService.instance == null && serviceNotification() == null }
                }
                prefs.listeningEnabled = originalEnabled
                prefs.listeningStarted = originalStarted
                BlueService.syncAvailability(context)
                if (originalRunning && originalEnabled && BlueService.instance == null) {
                    instrumentation.runOnMainSync { BlueService.start(context) }
                    eventually("The original listener was not restored") { BlueService.instance != null }
                }
            }
        }
    }

    private fun grantRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
    }

    private fun serviceNotification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == Notifications.SERVICE_ID }?.notification

    private fun isCallStyle(notification: Notification) =
        notification.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name

    private fun assertAction(notification: Notification, actionName: String) =
        assertTrue("Missing $actionName notification action", notification.actions?.any { it.actionIntent == action(actionName) } == true)

    private fun action(action: String) = PendingIntent.getService(context, action.hashCode(),
        Intent(context, BlueService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    private fun exchange(local: CallSession, remote: CallSession,
        toRemote: ConcurrentLinkedQueue<ByteArray>, toLocal: ConcurrentLinkedQueue<ByteArray>) {
        var frames = 0
        while (toRemote.isNotEmpty() || toLocal.isNotEmpty()) {
            while (true) {
                val frame = toRemote.poll() ?: break
                check(++frames <= 100) { "Signalling did not settle" }
                remote.receive(frame)
            }
            while (true) {
                val frame = toLocal.poll() ?: break
                check(++frames <= 100) { "Signalling did not settle" }
                local.receive(frame)
            }
        }
    }
}
