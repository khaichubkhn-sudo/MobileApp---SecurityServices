package com.example.securityalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * The "armed" state of the app IS this service running.
 *  - Started only by the user (ARM or PLAY button). Nothing starts it at boot.
 *  - stopWithTask=true + onTaskRemoved: swiping the app away / "Close all" stops it => disarmed.
 *  - Plays the sound on the ALARM audio stream at a fixed volume, re-enforced continuously.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_ARM = "com.example.securityalarm.ARM"
        const val ACTION_PLAY = "com.example.securityalarm.PLAY"
        private const val CHANNEL_ID = "alarm_status"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: AlarmService? = null
            private set

        val isArmed: Boolean get() = instance != null
        val isPlaying: Boolean get() = instance?.player != null
    }

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var savedFilter = -1
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs

    /** Re-applies the chosen volume 4x per second while the sound is playing. */
    private val volumeGuard = object : Runnable {
        override fun run() {
            if (player != null) {
                enforceVolume()
                handler.postDelayed(this, 250)
            }
        }
    }

    /** Fires immediately when anybody (volume keys, settings, other apps) changes a volume. */
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (player != null) enforceVolume()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = getSystemService(AudioManager::class.java)
        prefs = Prefs(this)
        EventLog.clear()
        EventLog.add("armed")
        createChannel()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PLAY) startAlarm()
        return START_NOT_STICKY // never restarted automatically => never "armed" behind the user's back
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Backup for OEMs that deliver this callback: closing the app disarms it.
        disarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSound(updateNotification = false)
        try {
            contentResolver.unregisterContentObserver(volumeObserver)
        } catch (e: Exception) {
        }
        EventLog.clear()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- public controls

    /** Start (or restart) the alarm sound. Safe to call repeatedly. */
    fun startAlarm() {
        stopSound(updateNotification = false)
        overrideDoNotDisturb()
        enforceVolume()
        requestFocus()

        val chosen = prefs.soundUri?.let { Uri.parse(it) }
        val ok = chosen != null && play(chosen)
        if (!ok) {
            // Never stay silent: fall back to the phone's default alarm tone.
            play(defaultAlarmUri())
        }
        handler.post(volumeGuard)
        refreshNotification()
        EventLog.add("SOUND STARTED")
    }

    fun stopSound(updateNotification: Boolean = true) {
        handler.removeCallbacks(volumeGuard)
        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        player = null
        abandonFocus()
        restoreDoNotDisturb()
        if (updateNotification) refreshNotification()
    }

    fun disarm() {
        stopSound(updateNotification = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- audio

    private fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun defaultAlarmUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun play(uri: Uri): Boolean {
        var mp: MediaPlayer? = null
        return try {
            mp = MediaPlayer()
            mp.setAudioAttributes(attrs())
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, uri)
            mp.isLooping = true
            mp.setVolume(1f, 1f) // player-level volume always 100%; loudness is set on the alarm stream
            if (prefs.forceSpeaker && Build.VERSION.SDK_INT >= 28) {
                speaker()?.let { mp.setPreferredDevice(it) }
            }
            mp.setOnErrorListener { _, _, _ ->
                handler.post { onPlayerError() }
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            try {
                mp?.release()
            } catch (e2: Exception) {
            }
            false
        }
    }

    private fun onPlayerError() {
        player?.let {
            try {
                it.release()
            } catch (e: Exception) {
            }
        }
        player = null
        play(defaultAlarmUri())
    }

    private fun speaker(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Forces the ALARM stream to the user's chosen level and un-mutes it. */
    fun enforceVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * prefs.volumePct / 100f).roundToInt().coerceIn(1, max)
            if (audio.isStreamMute(AudioManager.STREAM_ALARM)) {
                audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != target) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        } catch (e: SecurityException) {
            // Some phones refuse volume changes while Do Not Disturb is active and no DND access was granted.
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs())
            .setOnAudioFocusChangeListener { } // ignore: the alarm never pauses or ducks
            .build()
        focusRequest = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ---------------------------------------------------------------- Do Not Disturb

    private fun overrideDoNotDisturb() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) {
                val cur = nm.currentInterruptionFilter
                if (cur != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    cur != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                ) {
                    savedFilter = cur
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun restoreDoNotDisturb() {
        if (savedFilter == -1) return
        try {
            getSystemService(NotificationManager::class.java).setInterruptionFilter(savedFilter)
        } catch (e: Exception) {
        }
        savedFilter = -1
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Alarm status", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = player != null
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (playing) "ALARM SOUNDING" else "Security alarm ARMED")
            .setContentText(
                if (playing) "Unlock the phone and open the app to stop it"
                else "Swipe the app away in Recents to disarm"
            )
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET) // hidden on the lock screen
            .setContentIntent(open)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
