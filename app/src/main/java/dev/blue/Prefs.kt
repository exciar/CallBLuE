package dev.blue

import android.content.Context
import kotlin.random.Random

/** The few user settings, in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("blue", Context.MODE_PRIVATE)

    /** Stay reachable off-screen until the user explicitly goes offline. */
    var listeningEnabled: Boolean
        get() = sp.getBoolean("listening_enabled", true)
        set(v) = sp.edit().putBoolean("listening_enabled", v).apply()

    /** Boot/update restoration begins only after the user has opened and started BLuE. */
    var listeningStarted: Boolean
        get() = sp.getBoolean("listening_started", false)
        set(v) = sp.edit().putBoolean("listening_started", v).apply()

    /** Name shown to nearby phones and sent in call requests (≤24 bytes on the wire). */
    var name: String
        get() = sp.getString("name", null) ?: android.os.Build.MODEL
        set(v) = sp.edit().putString("name", v.trim()).apply()

    /** Speak the stock Xiaomi Offline Communication protocol as well (long-range phones only). */
    var xiaomiMode: Boolean
        get() = sp.getBoolean("xiaomi_mode", false)
        set(v) = sp.edit().putBoolean("xiaomi_mode", v).apply()

    /** Phone number Xiaomi phones see; their format broadcasts it openly. */
    var xiaomiNumber: String
        get() = sp.getString("xiaomi_number", "") ?: ""
        set(v) = sp.edit().putString("xiaomi_number", v.filter { it.isDigit() || it == '+' }.take(24)).apply()

    /** Random per-install id so a phone stays one entry while its Bluetooth address rotates. */
    val id: Int
        get() = sp.getInt("id", 0).takeIf { it != 0 } ?: Random.nextInt().also { sp.edit().putInt("id", it).apply() }
}
