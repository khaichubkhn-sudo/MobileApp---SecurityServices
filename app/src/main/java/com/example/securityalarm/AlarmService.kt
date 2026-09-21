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
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        const val ACTION_STOP_RECORDING = "com.example.securityalarm.STOP_RECORDING"
        private const val CHANNEL_ID = "alarm_status"
        private const val NOTIF_ID = 1001

        /** How long Volume Down must be held (fallback volume-based detection). */
        private const val HOLD_MS = 2_000L

        /**
         * Longest pause between volume decreases that still counts as one continuous hold.
         * Must be comfortably larger than the OS key-repeat delay (~500 ms) but smaller than HOLD_MS.
         */
        private const val HOLD_GAP_MS = 800L

        /** Streams whose volume the phone's volume keys may adjust. */
        private val VOLUME_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION
        )

        /** How often the volume pin re-applies the preset / non-zero volumes while armed. */
        private const val VOLUME_PIN_MS = 500L

        @Volatile
        var instance: AlarmService? = null
            private set

        val isArmed: Boolean get() = instance != null
        val isPlaying: Boolean get() = instance?.player != null
        val isRecording: Boolean get() = instance?.recorder != null
    }

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingUri: Uri? = null
    private var recordingFd: android.os.ParcelFileDescriptor? = null
    private var recordingForeground = false
    private val recordingLimitBytes = 300L * 1024L * 1024L
    private var focusRequest: AudioFocusRequest? = null
    private var savedFilter = -1
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs

    private val recordingSizeCheck = object : Runnable {
        override fun run() {
            if (recorder == null) return
            if (recordingSize() >= recordingLimitBytes) {
                EventLog.add("recording stopped at 300 MB")
                stopRecording()
            } else {
                handler.postDelayed(this, 5_000L)
            }
        }
    }

    // ------------------------------------------------- volume pinning (never silent while armed)
    private var volumesSaved = false
    private var origAlarmVolume = -1
    private var origMusicVolume = -1
    private var origRingVolume = -1
    private var origAlarmMuted = false
    private var origMusicMuted = false
    private var origRingMuted = false

    /**
     * While armed, keeps the preset sound volume applied on the alarm/media streams and makes sure
     * the streams the volume keys adjust are never zero, so a Volume Down press is always detected.
     * Unarmed run over by [onDestroy] where the original volumes are restored.
     */
    private val volumePin = object : Runnable {
        override fun run() {
            // Skip while the alarm is sounding (the volume guard pins the alarm stream instead),
            // but always re-schedule so pinning resumes once the sound stops.
            if (player == null) {
                pinVolumes()
            }
            handler.postDelayed(this, VOLUME_PIN_MS)
        }
    }

    // ------------------------------------------------- volume-down fallback detection
    private var lastVolumes = intArrayOf()
    private var volHoldStartAt = 0L
    private var volLastDownAt = 0L

    /** Fires the alarm once the volume-based Volume Down hold has lasted HOLD_MS. */
    private val volHoldTrigger = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val stillHolding = volHoldStartAt != 0L && (now - volLastDownAt) <= HOLD_GAP_MS
            if (stillHolding && player == null) {
                triggerVolumeHold()
            } else {
                resetVolumeHold()
            }
        }
    }

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
            if (player != null) {
                // Alarm sounding: keep the chosen volume pinned.
                enforceVolume()
            } else {
                // Armed and silent: Volume Down fallback detection.
                detectVolumeDownHold()
            }
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
        lastVolumes = snapshotVolumes()
        createChannel()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        // Apply the preset volume right away (even before any sound plays) and keep it non-zero.
        saveOriginalsIfNeeded()
        pinVolumes()
        handler.post(volumePin)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> startAlarm()
            ACTION_STOP_RECORDING -> stopRecording()
        }
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
        stopRecording(updateNotification = false)
        resetVolumeHold()
        handler.removeCallbacks(volumePin)
        restoreVolumes()
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
        stopRecording(updateNotification = false)
        stopSound(updateNotification = false)
        resetVolumeHold() // no stale hold detection once the alarm is sounding
        overrideDoNotDisturb()
        enforceVolume()
        requestFocus()

        val ok = playBestAvailable()
        handler.post(volumeGuard)
        refreshNotification()
        EventLog.add(if (ok) "SOUND STARTED" else "SOUND FAILED")
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

    /** Starts microphone capture directly to a file; MediaRecorder performs the streaming writes. */
    fun startRecording() {
        if (recorder != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            EventLog.add("recording failed: microphone permission missing")
            return
        }
        try {
            recordingForeground = true
            goForeground()
            val output = createRecordingOutput()
            val activeRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            activeRecorder.setAudioEncodingBitRate(128_000)
            activeRecorder.setAudioSamplingRate(44_100)
            activeRecorder.setOutputFile(output.fileDescriptor)
            activeRecorder.prepare()
            activeRecorder.start()
            recorder = activeRecorder
            handler.post(recordingSizeCheck)
            EventLog.add("VOICE RECORDING STARTED")
            refreshNotification()
        } catch (e: Exception) {
            EventLog.add("recording failed: ${e.javaClass.simpleName}")
            recorder?.release()
            recorder = null
            recordingFd?.close()
            recordingFd = null
            recordingFile = null
            recordingUri?.let { contentResolver.delete(it, null, null) }
            recordingUri = null
            recordingForeground = false
            goForeground()
        }
    }

    fun stopRecording(updateNotification: Boolean = true) {
        handler.removeCallbacks(recordingSizeCheck)
        recorder?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        val uri = recordingUri
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            try {
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
            }
        }
        recordingFd?.close()
        recorder = null
        recordingFd = null
        recordingFile = null
        recordingUri = null
        recordingForeground = false
        if (updateNotification && instance != null) {
            goForeground()
            EventLog.add("VOICE RECORDING STOPPED")
            refreshNotification()
        }
    }

    private fun createRecordingOutput(): java.io.FileDescriptor {
        val name = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
        val treeUri = prefs.recordingTreeUri?.let { Uri.parse(it) }
        if (treeUri != null) {
            val uri = DocumentsContract.createDocument(contentResolver, treeUri, "audio/mp4", name)
                ?: throw IllegalStateException("Could not create recording file")
            recordingUri = uri
            recordingFd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open recording file")
            return recordingFd!!.fileDescriptor
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Security Services")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            recordingUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Music recording")
            recordingFd = contentResolver.openFileDescriptor(recordingUri!!, "w")
                ?: throw IllegalStateException("Could not open Music recording")
            return recordingFd!!.fileDescriptor
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Security Services")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Could not create Music folder")
        val file = File(directory, name)
        recordingFile = file
        recordingFd = android.os.ParcelFileDescriptor.open(
            file,
            android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        return recordingFd!!.fileDescriptor
    }

    private fun recordingSize(): Long {
        recordingFile?.let { return it.length() }
        val uri = recordingUri ?: return 0L
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
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

    /** The tone bundled inside the APK, so a sound is guaranteed even if every other source fails. */
    private fun bundledAlarmUri(): Uri =
        Uri.parse("android.resource://$packageName/${R.raw.alarm_fallback}")

    /**
     * Tries the user's chosen sound, then the phone's default alarm tone, then the bundled tone.
     * Returns true as soon as one of them starts playing, so the alarm is never silent.
     */
    private fun playBestAvailable(): Boolean {
        val candidates = listOfNotNull(
            prefs.soundUri?.let { Uri.parse(it) },
            defaultAlarmUri(),
            bundledAlarmUri()
        )
        for (uri in candidates) {
            if (play(uri)) return true
            EventLog.add("sound source failed, trying next")
        }
        return false
    }

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
        // Try another source; the bundled tone is the guaranteed last resort.
        playBestAvailable()
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

    // ------------------------------------------------- volume-down fallback detection

    /** Reads the current volume of every stream the volume keys may adjust. */
    private fun snapshotVolumes(): IntArray {
        val out = IntArray(VOLUME_STREAMS.size)
        for (i in VOLUME_STREAMS.indices) {
            out[i] = audio.getStreamVolume(VOLUME_STREAMS[i])
        }
        return out
    }

    /**
     * Fallback trigger that needs no accessibility permission: it watches the volume settings.
     * Holding Volume Down makes the system lower the volume repeatedly (key auto-repeat), and the
     * ContentObserver fires on each change. This detects a sustained Volume Down hold of HOLD_MS.
     */
    private fun detectVolumeDownHold() {
        val now = SystemClock.elapsedRealtime()
        val current = snapshotVolumes()

        var down = false
        var up = false
        if (lastVolumes.size == current.size) {
            for (i in current.indices) {
                val prev = lastVolumes[i]
                if (current[i] < prev) down = true
                else if (current[i] > prev) up = true
            }
        }
        lastVolumes = current

        // Volume Up cancels a pending hold.
        if (up) {
            resetVolumeHold()
            return
        }
        if (!down) return

        // A Volume Down step. A long gap means a fresh press; otherwise the hold continues.
        if (volHoldStartAt == 0L || now - volLastDownAt > HOLD_GAP_MS) {
            volHoldStartAt = now
        }
        volLastDownAt = now

        EventLog.add("volume down detected (hold ${(now - volHoldStartAt) / 1000}s)")

        if (now - volHoldStartAt >= HOLD_MS) {
            triggerVolumeHold()
            return
        }

        handler.removeCallbacks(volHoldTrigger)
        handler.postDelayed(volHoldTrigger, volHoldStartAt + HOLD_MS - now)
    }

    private fun triggerVolumeHold() {
        EventLog.add("TRIGGER: Volume Down held 2s (volume detection)")
        triggerVolumeAction()
        resetVolumeHold()
    }

    /** Applies the user's selected action for a completed Volume Down hold. */
    fun triggerVolumeAction() {
        if (prefs.volumeDownAction == Prefs.ACTION_RECORD) {
            startRecording()
        } else if (!isPlaying) {
            startAlarm()
        }
    }

    private fun resetVolumeHold() {
        volHoldStartAt = 0L
        volLastDownAt = 0L
        handler.removeCallbacks(volHoldTrigger)
    }

    // ------------------------------------------------- volume pinning

    private fun preferredVolume(stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream)
        if (max <= 0) return 1
        return (max * prefs.volumePct / 100f).roundToInt().coerceIn(1, max)
    }

    /** Copies the current volumes/mute states once, so they can be restored when the app closes. */
    private fun saveOriginalsIfNeeded() {
        if (volumesSaved) return
        origAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        origMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        origRingVolume = audio.getStreamVolume(AudioManager.STREAM_RING)
        origAlarmMuted = try {
            audio.isStreamMute(AudioManager.STREAM_ALARM)
        } catch (e: Exception) {
            false
        }
        origMusicMuted = try {
            audio.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            false
        }
        origRingMuted = try {
            audio.isStreamMute(AudioManager.STREAM_RING)
        } catch (e: Exception) {
            false
        }
        volumesSaved = true
        EventLog.add(
            "volumes saved (alarm=$origAlarmVolume music=$origMusicVolume ring=$origRingVolume)"
        )
    }

    /**
     * 1) Applies the preset sound volume immediately (even before the alarm plays).
     * 2) Keeps the streams the volume keys adjust away from zero, so a Volume Down
     *    press can always be detected. Skipped while a hold is in progress, otherwise the hold's own
     *    volume decrease would be undone and the detection would never see 2 seconds.
     */
    private fun pinVolumes() {
        saveOriginalsIfNeeded()
        try {
            // Skipped entirely while a Volume Down hold is in progress: the hold's own volume
            // decrease must not be undone, otherwise the 2-second hold would never be detected.
            if (volHoldStartAt == 0L) {
                enforceVolume() // alarm stream = preset volume, un-muted
                // Media is what the volume keys adjust on modern Android: keep it at
                // least as loud as the preset volume (never below the current level, never zero).
                pinStreamToPreset(AudioManager.STREAM_MUSIC)
                // Ringer, for devices that route the volume keys there: at least one step.
                pinStreamNonZero(AudioManager.STREAM_RING)
            }
        } catch (e: SecurityException) {
            // Some phones refuse volume changes while Do Not Disturb is active and no DND access was granted.
        }
    }

    /** Raises a stream to at least the preset volume percentage (never below its current level). */
    private fun pinStreamToPreset(stream: Int) {
        try {
            val target = preferredVolume(stream)
            if (audio.isStreamMute(stream)) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (audio.getStreamVolume(stream) < target) {
                audio.setStreamVolume(stream, target, 0)
            }
        } catch (e: Exception) {
        }
    }

    /** Makes sure a stream is not muted and at least one step loud (never zero). */
    private fun pinStreamNonZero(stream: Int) {
        try {
            if (audio.isStreamMute(stream)) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (audio.getStreamVolume(stream) <= 0) {
                audio.setStreamVolume(stream, 1, 0)
            }
        } catch (e: Exception) {
        }
    }

    /** Restores the volumes/mute states that were in place before the app was armed. */
    private fun restoreVolumes() {
        if (!volumesSaved) return
        restoreStream(AudioManager.STREAM_ALARM, origAlarmVolume, origAlarmMuted)
        restoreStream(AudioManager.STREAM_MUSIC, origMusicVolume, origMusicMuted)
        restoreStream(AudioManager.STREAM_RING, origRingVolume, origRingMuted)
        volumesSaved = false
        EventLog.add(
            "volumes restored (alarm=$origAlarmVolume music=$origMusicVolume ring=$origRingVolume)"
        )
    }

    private fun restoreStream(stream: Int, volume: Int, wasMuted: Boolean) {
        try {
            if (volume >= 0) audio.setStreamVolume(stream, volume, 0)
            val mutedNow = audio.isStreamMute(stream)
            when {
                wasMuted && !mutedNow -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                !wasMuted && mutedNow -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (e: Exception) {
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
            .setContentTitle(if (playing) "ALARM SOUNDING" else "Security Services - KC ARMED")
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
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (recordingForeground) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
