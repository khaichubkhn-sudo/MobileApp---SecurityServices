package com.example.securityalarm

import android.content.Context

/** Tiny wrapper around SharedPreferences for all user settings. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    /** content:// URI of the audio file the user picked (null = none, default alarm tone is used). */
    var soundUri: String?
        get() = sp.getString("sound_uri", null)
        set(v) { sp.edit().putString("sound_uri", v).apply() }

    var soundName: String
        get() = sp.getString("sound_name", "") ?: ""
        set(v) { sp.edit().putString("sound_name", v).apply() }

    /** Alarm volume as % of the phone's maximum alarm volume (10..100). */
    var volumePct: Int
        get() = sp.getInt("volume_pct", 100)
        set(v) { sp.edit().putInt("volume_pct", v.coerceIn(10, 100)).apply() }

    /** Play through the built-in speaker even if headphones / Bluetooth are connected. */
    var forceSpeaker: Boolean
        get() = sp.getBoolean("force_speaker", true)
        set(v) { sp.edit().putBoolean("force_speaker", v).apply() }

    /** Also accept "1" + green call button inside the emergency dialer screen. */
    var dialerPath: Boolean
        get() = sp.getBoolean("dialer_path", true)
        set(v) { sp.edit().putBoolean("dialer_path", v).apply() }

    /** Comma separated words that identify the lock-screen "Emergency call" button (lower-case match). */
    var keywords: String
        get() = sp.getString("keywords", "emergency") ?: "emergency"
        set(v) { sp.edit().putString("keywords", v).apply() }
}
