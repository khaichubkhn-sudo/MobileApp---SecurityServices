package com.example.securityservices

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

    /** Action performed after the Volume Down hold reaches two seconds. */
    var volumeDownAction: String
        get() = sp.getString("volume_down_action", ACTION_ALARM) ?: ACTION_ALARM
        set(v) { sp.edit().putString("volume_down_action", v).apply() }

    /** Persisted Storage Access Framework tree URI for recordings, or null for the Music folder. */
    var recordingTreeUri: String?
        get() = sp.getString("recording_tree_uri", null)
        set(v) { sp.edit().putString("recording_tree_uri", v).apply() }

    /**
     * The phone's volume/mute state captured when the alarm service armed, encoded as
     * "alarm,music,ring,alarmMuted,musicMuted,ringMuted" (each mute flag is 1 or 0). Kept on disk so
     * the original levels can be put back even if the app is killed without a clean shutdown.
     */
    var savedVolumes: String?
        get() = sp.getString("saved_volumes", null)
        set(v) { sp.edit().putString("saved_volumes", v).apply() }

    companion object {
        const val ACTION_ALARM = "alarm"
        const val ACTION_RECORD = "record"
    }

}
