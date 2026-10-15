package dev.blue

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.blue.call.CallController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real idle foreground service, notification action, and activity lifecycle.
 * Receiver tests dispatch onReceive directly; they do not simulate a reboot or prove an OEM's
 * automatic-start policy permits delivery of the system broadcast.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class AvailabilityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = Prefs(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var activity: Activity? = null
    private var restoreState = false
    private var originalEnabled = false
    private var originalStarted = false
    private var originalRunning = false
    private var originalVisible = false

    @Before
    fun saveStateAndStopListener() {
        assumeNoCall()
        grantRuntimePermissions()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue("These device checks require enabled Bluetooth", adapter?.isEnabled == true)
        originalEnabled = prefs.listeningEnabled
        originalStarted = prefs.listeningStarted
        originalRunning = BlueService.instance != null
        originalVisible = BlueService.isUiVisible
        restoreState = true
        stopListener()
    }

    @After
    fun restoreListenerAndPreferences() {
        if (!restoreState) return
        finishActivity()
        // An unsolicited real call must never be ended by test cleanup.
        if (CallController.call.value == null) stopListener()
        prefs.listeningEnabled = originalEnabled
        prefs.listeningStarted = originalStarted
        if (originalVisible) {
            launchActivity()
        } else if (originalRunning && originalEnabled && CallController.call.value == null) {
            instrumentation.runOnMainSync { BlueService.start(context) }
            eventually("The original listener was not restored") { BlueService.instance != null }
        }
    }

    @Test
    fun finishingActivityKeepsConnectedDeviceForegroundListener() {
        prefs.listeningEnabled = true
        launchActivity()
        eventually("The activity did not start the listener") { BlueService.instance != null && serviceNotification() != null }
        val listener = checkNotNull(BlueService.instance)
        assertTrue("A successful foreground start must be remembered", prefs.listeningStarted)
        assertIdleForegroundListener()

        finishActivity()
        assertFalse(BlueService.isUiVisible)
        assertSame("Finishing the activity must retain the existing listener", listener, BlueService.instance)
        assertIdleForegroundListener()
        assertStays("The listener stopped after the activity finished") {
            BlueService.instance === listener && serviceNotification() != null
        }
    }

    @Test
    fun notificationOfflineActionStopsListenerAndPersistsAcrossReopening() {
        prefs.listeningEnabled = true
        launchActivity()
        eventually("The listener did not publish its notification") { serviceNotification() != null }
        finishActivity()
        assumeNoCall()
        val offlineAction = checkNotNull(serviceNotification()).actions
            ?.singleOrNull { it.actionIntent == offlinePendingIntent() }
        assertNotNull("The idle notification must offer Go offline", offlineAction)
        offlineAction!!.actionIntent.send()

        eventually("Go offline did not stop the listener and save the choice") {
            !prefs.listeningEnabled && BlueService.instance == null && serviceNotification() == null
        }
        launchActivity()
        assertFalse("Opening BLuE must preserve the explicit offline choice", Prefs(context).listeningEnabled)
        assertStays("Opening BLuE silently restarted an offline listener") {
            BlueService.instance == null && serviceNotification() == null
        }
    }

    @Test
    fun receiverDispatchSkipsDisabledListener() {
        prefs.listeningEnabled = false
        prefs.listeningStarted = true
        restoreActions.forEach(::dispatchReceiver)
        assertFalse(prefs.listeningEnabled)
        assertStays("Restore broadcasts restarted a deliberately offline listener") {
            BlueService.instance == null && serviceNotification() == null
        }
    }

    @Test
    fun receiverDispatchSkipsFirstRunAndUnrelatedActions() {
        prefs.listeningEnabled = true
        prefs.listeningStarted = false
        restoreActions.forEach(::dispatchReceiver)
        assertFalse("Receiver dispatch must not mark a first run as successfully started", prefs.listeningStarted)
        assertStays("Restore broadcasts started listening before the first app launch") {
            BlueService.instance == null && serviceNotification() == null
        }

        prefs.listeningStarted = true
        dispatchReceiver(Intent.ACTION_TIME_CHANGED)
        assertStays("An unrelated broadcast started the listener") {
            BlueService.instance == null && serviceNotification() == null
        }
    }

    @Test
    fun receiverDispatchRestoresPreviouslyStartedListenerWithConnectedDeviceTypeOnly() {
        prefs.listeningEnabled = true
        launchActivity()
        eventually("The initial listener did not start") { BlueService.instance != null && prefs.listeningStarted }
        finishActivity()
        assertFalse(BlueService.isUiVisible)
        stopListener()

        restoreActions.forEach { action ->
            dispatchReceiver(action)
            eventually("Receiver dispatch did not restore the listener for $action") {
                BlueService.instance != null && serviceNotification() != null
            }
            assertFalse("Receiver dispatch must not open the activity", BlueService.isUiVisible)
            assertIdleForegroundListener()
            stopListener()
            assertTrue("Stopping this test instance must preserve availability", prefs.listeningEnabled)
            assertTrue(prefs.listeningStarted)
        }
    }

    private fun assertIdleForegroundListener() {
        val service = checkNotNull(BlueService.instance)
        // Query the active type registered with Android, not just the manifest's allowed types.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        val notification = checkNotNull(serviceNotification())
        assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name)
        assertTrue(notification.actions?.any { it.actionIntent == offlinePendingIntent() } == true)
        assertTrue(prefs.listeningEnabled)
    }

    private fun launchActivity() {
        assumeNoCall()
        check(activity == null)
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        eventually("The activity did not report that it is visible") { BlueService.isUiVisible }
    }

    private fun finishActivity() {
        val current = activity ?: return
        instrumentation.runOnMainSync { current.finish() }
        activity = null
        eventually("The finished activity did not leave the foreground") { current.isDestroyed && !BlueService.isUiVisible }
    }

    private fun stopListener() {
        assumeNoCall()
        instrumentation.runOnMainSync { context.stopService(Intent(context, BlueService::class.java)) }
        eventually("The previous listener did not stop") { BlueService.instance == null && serviceNotification() == null }
    }

    private fun dispatchReceiver(action: String) {
        assumeNoCall()
        instrumentation.runOnMainSync { StartupReceiver().onReceive(context, Intent(action)) }
        instrumentation.waitForIdleSync()
    }

    private fun serviceNotification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == Notifications.SERVICE_ID }?.notification

    private fun offlinePendingIntent() = PendingIntent.getService(
        context, BlueService.ACTION_GO_OFFLINE.hashCode(),
        Intent(context, BlueService::class.java).setAction(BlueService.ACTION_GO_OFFLINE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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

    private fun assumeNoCall() = assumeTrue("Do not interfere with an existing call", CallController.call.value == null)

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    private fun assertStays(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 500
        do {
            assertTrue(message, condition())
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private val restoreActions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
}
