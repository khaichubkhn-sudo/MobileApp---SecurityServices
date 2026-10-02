package com.example.securityservices

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences for all user settings.
 *
 * @author Chu Quang Khai (Khai Chu)
 */
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

    var sendLocationOnVolumeDown: Boolean
        get() = sp.getBoolean("send_location_on_volume_down", false)
        set(v) { sp.edit().putBoolean("send_location_on_volume_down", v).apply() }

    var locationPhoneNumbers: String
        get() = (sp.getString("location_phone_numbers", "") ?: "").take(50)
        set(v) { sp.edit().putString("location_phone_numbers", v.take(50)).apply() }

    var locationSmsPrefix: String
        get() = sp.getString("location_sms_prefix", "") ?: ""
        set(v) { sp.edit().putString("location_sms_prefix", v).apply() }

    var lastLocationMessage: String
        get() = sp.getString("last_location_message", "") ?: ""
        set(v) { sp.edit().putString("last_location_message", v).apply() }

    /** Recipient of the "capture one photo and email it" Volume Down action. */
    var emailPhotoRecipient: String
        get() = sp.getString("email_photo_recipient", "") ?: ""
        set(v) { sp.edit().putString("email_photo_recipient", v.trim()).apply() }

    /** Sender address, which is also the SMTP account the photo is sent from. */
    var emailPhotoSender: String
        get() = sp.getString("email_photo_sender", "") ?: ""
        set(v) { sp.edit().putString("email_photo_sender", v.trim()).apply() }

    /** SMTP password; email providers usually require an app-specific password here. */
    var emailPhotoPassword: String
        get() = sp.getString("email_photo_password", "") ?: ""
        set(v) { sp.edit().putString("email_photo_password", v).apply() }

    /** SMTP server host name, e.g. smtp.gmail.com. */
    var emailPhotoSmtpHost: String
        get() = sp.getString("email_photo_smtp_host", DEFAULT_EMAIL_SMTP_HOST) ?: DEFAULT_EMAIL_SMTP_HOST
        set(v) { sp.edit().putString("email_photo_smtp_host", v.trim()).apply() }

    /** SMTP server port: 465 for SSL/TLS, 587 for STARTTLS. */
    var emailPhotoSmtpPort: Int
        get() = sp.getInt("email_photo_smtp_port", DEFAULT_EMAIL_SMTP_PORT)
        set(v) { sp.edit().putInt("email_photo_smtp_port", v.coerceIn(1, 65535)).apply() }

    /** true = STARTTLS (usually port 587); false = implicit SSL/TLS (usually port 465). */
    var emailPhotoStartTls: Boolean
        get() = sp.getBoolean("email_photo_start_tls", false)
        set(v) { sp.edit().putBoolean("email_photo_start_tls", v).apply() }

    /** Result of the last photo / recording email; shown as a notification on the app's main screen. */
    var lastEmailNotice: String
        get() = sp.getString("last_email_notice", "") ?: ""
        set(v) { sp.edit().putString("last_email_notice", v).apply() }

    /** true when [lastEmailNotice] describes a success, false when it describes a failure. */
    var lastEmailNoticeOk: Boolean
        get() = sp.getBoolean("last_email_notice_ok", false)
        set(v) { sp.edit().putBoolean("last_email_notice_ok", v).apply() }

    /** Persisted Storage Access Framework tree URI for recordings, or null for the Music folder. */
    var recordingTreeUri: String?
        get() = sp.getString("recording_tree_uri", null)
        set(v) { sp.edit().putString("recording_tree_uri", v).apply() }

    /**
     * The phone's volume/mute state captured just before the app's own volume setting is applied -
     * i.e. when the alarm sound starts or a voice recording starts - encoded as
     * "alarm,music,ring,alarmMuted,musicMuted,ringMuted" (each mute flag is 1 or 0). Kept on disk so
     * the levels can be put back even if the app is killed while the action is still running.
     */
    var savedVolumes: String?
        get() = sp.getString("saved_volumes", null)
        set(v) { sp.edit().putString("saved_volumes", v).apply() }

    companion object {
        const val ACTION_ALARM = "alarm"
        const val ACTION_RECORD = "record"
        const val ACTION_LOCATION = "location"
        const val ACTION_CALL = "call"
        const val ACTION_EMAIL_PHOTO = "email_photo"

        /** Sensible defaults for the photo-email SMTP settings (Gmail with SSL/TLS). */
        const val DEFAULT_EMAIL_SMTP_HOST = "smtp.gmail.com"
        const val DEFAULT_EMAIL_SMTP_PORT = 465
    }

}
