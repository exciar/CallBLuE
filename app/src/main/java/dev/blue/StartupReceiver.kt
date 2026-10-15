package dev.blue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the user's availability after a reboot or an app update. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.listeningEnabled && prefs.listeningStarted) BlueService.start(context)
    }
}
