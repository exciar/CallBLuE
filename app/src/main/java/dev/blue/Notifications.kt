package dev.blue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/** Notification channels, the persistent service notification and the ringing call. */
object Notifications {
    const val SERVICE_ID = 1
    const val CALL_ID = 2
    private const val SERVICE_CHANNEL = "service"
    private const val CALL_CHANNEL = "calls"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Listening for calls", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CALL_CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // the Ringer plays a looping ringtone instead
        })
    }

    fun service(context: Context, text: String) = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("BLuE")
        .setContentText(text)
        .setContentIntent(openApp(context))
        .setOngoing(true)
        .addAction(0, "Go offline", action(context, BlueService.ACTION_GO_OFFLINE))
        .build()

    /** Full-screen incoming-call notification with Answer/Decline. */
    fun incoming(context: Context, caller: String) = NotificationCompat.Builder(context, CALL_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
        .setContentTitle(caller)
        .setContentText("Incoming offline call")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setOngoing(true)
        .setFullScreenIntent(openApp(context), true)
        .setStyle(NotificationCompat.CallStyle.forIncomingCall(
            Person.Builder().setName(caller).build(),
            action(context, BlueService.ACTION_DECLINE),
            action(context, BlueService.ACTION_ANSWER),
        ))
        .build()

    /** Shown for the whole active call; Android 14+ expects a call notification while a VoIP call is ongoing. */
    fun ongoing(context: Context, peer: String) = NotificationCompat.Builder(context, CALL_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
        .setContentTitle(peer)
        .setContentText("Offline call")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context))
        .setStyle(NotificationCompat.CallStyle.forOngoingCall(
            Person.Builder().setName(peer).build(),
            action(context, BlueService.ACTION_HANG_UP),
        ))
        .build()

    fun cancelCall(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(CALL_ID)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String) = PendingIntent.getService(
        context, action.hashCode(), Intent(context, BlueService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Looping ringtone while an incoming call rings. */
class Ringer(private val context: Context) {
    private var tone: Ringtone? = null

    fun start() {
        if (tone != null) return
        tone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
    }

    fun stop() {
        tone?.stop()
        tone = null
    }
}
