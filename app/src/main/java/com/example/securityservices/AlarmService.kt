package com.example.securityservices

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import android.telephony.SmsManager
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
        const val ACTION_ARM = "com.example.securityservices.ARM"
        const val ACTION_PLAY = "com.example.securityservices.PLAY"
        const val ACTION_STOP_RECORDING = "com.example.securityservices.STOP_RECORDING"
        private const val CHANNEL_ID = "alarm_status"
        private const val NOTIF_ID = 1001

        /**
         * Runtime permission Android 11+ needs before a foreground service may run with the
         * microphone foreground type (declared in AndroidManifest.xml). Without it, starting
         * the foreground service with the microphone type fails on targetSdk >= 30 devices.
         */
        private const val PERMISSION_FOREGROUND_MICROPHONE = "android.permission.FOREGROUND_SERVICE_MICROPHONE"

        /** How long Volume Down must be held (fallback volume-based detection). */
        private const val HOLD_MS = 2_000L

        /**
         * Longest pause between volume decreases that still counts as one continuous hold.
         * Must be comfortably larger than the OS key-repeat delay (~500 ms) but smaller than HOLD_MS.
         */
        private const val HOLD_GAP_MS = 800L
        private const val LOCATION_TIMEOUT_MS = 30_000L
        private const val LOCATION_TRIGGER_DEBOUNCE_MS = 3_000L

        /** Streams whose volume the phone's volume keys may adjust. */
        private val VOLUME_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION
        )

        @Volatile
        var instance: AlarmService? = null
            private set

        val isArmed: Boolean get() = instance != null
        val isPlaying: Boolean get() = instance?.player != null
        val isRecording: Boolean get() = instance?.recorder != null

        /** True when the running foreground service includes the microphone type (only true when the
         *  runtime permission was granted before the service started). Recording needs this type. */
        val hasMicrophoneForegroundType: Boolean get() = instance?.recordingForeground ?: false
    }

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingUri: Uri? = null
    private var recordingFd: android.os.ParcelFileDescriptor? = null
    /** Microphone type included in the foreground service type. Set once at service start. */
    private var recordingForeground = false
    private val recordingLimitBytes = 300L * 1024L * 1024L
    private var focusRequest: AudioFocusRequest? = null
    private var savedFilter = -1
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private lateinit var locationManager: LocationManager
    private var locationRequestActive = false
    private var locationListener: LocationListener? = null
    private var lastLocationTriggerAt = 0L

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

    // ------------------------------------------- volume control (only while an action is running)

    /**
     * True while the app is doing something that uses its own volume setting: the alarm is sounding
     * or a voice recording is running. Only then does the app touch the phone's volumes; otherwise
     * the volumes are left completely alone so the user can adjust them.
     */
    private val actionRunning: Boolean get() = player != null || recorder != null

    /**
     * True once the phone's volumes have been captured for the running action, so they can be put
     * back the moment the last action stops.
     */
    private var actionVolumesSaved = false

    /**
     * Identifies this service instance inside the on-disk volume record. A re-arm can stop this
     * instance after a new one has already written its own record, so the restore below only clears
     * the record when it is the one this instance wrote.
     */
    private val volumeSessionId =
        System.currentTimeMillis().toString() + "-" + System.identityHashCode(this)
    private var origAlarmVolume = -1
    private var origMusicVolume = -1
    private var origRingVolume = -1
    private var origAlarmMuted = false
    private var origMusicMuted = false
    private var origRingMuted = false

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

    /** Re-applies the chosen volume 4x per second while an action is running. */
    private val volumeGuard = object : Runnable {
        override fun run() {
            if (actionRunning) {
                enforceVolume()
                handler.postDelayed(this, 250)
            }
        }
    }

    /** Fires immediately when anybody (volume keys, settings, other apps) changes a volume. */
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (actionRunning) {
                // An action is running: keep the app's own volume setting applied.
                enforceVolume()
            } else {
                // Nothing running: the volume belongs to the user, and a Volume Down hold is the trigger.
                detectVolumeDownHold()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = getSystemService(AudioManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        prefs = Prefs(this)
        EventLog.clear()
        EventLog.add("armed")
        lastVolumes = snapshotVolumes()
        createChannel()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        // The app keeps its hands off the phone's volume while no action runs, so nothing is applied
        // here. If a previous run was killed while an action was still running, put the phone's real
        // volumes back right away.
        restoreSavedVolumesFromDisk()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // goForeground() decides whether the phone lets us run with the microphone type: it
            // always asks for it optimistically and falls back to media playback only if the runtime
            // permission is not granted yet (the type can never be added later). The app re-requests
            // the permission and re-arms automatically once the user allows the dialog in the GUI.
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
        // Closing the app (swipe away in Recents / "Close all") stops any running action, and stopping
        // the last action puts the phone's volumes back to the levels they had before it started (see
        // endActionVolumes()). Doing it through disarm() also covers vendors that deliver this callback
        // but not a clean onDestroy().
        EventLog.add("app closed")
        disarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSound(updateNotification = false)
        stopRecording(updateNotification = false)
        resetVolumeHold()
        // stopSound()/stopRecording() above already gave the volumes back if they were the last
        // action; this is a safety net for an action that ended without either being called.
        handler.removeCallbacks(volumeGuard)
        finishLocationRequest()
        restoreActionVolumes()
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
        beginActionVolumes() // remember the user's levels before the app changes them
        enforceVolume()
        requestFocus()

        val ok = playBestAvailable()
        if (player != null) {
            handler.post(volumeGuard) // keep the chosen volume applied while it sounds
        } else {
            endActionVolumes() // nothing started: leave the phone's volumes exactly as they were
        }
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
        // The alarm sound is over: give the volumes back to the user (unless a recording still runs).
        endActionVolumes()
        if (updateNotification) refreshNotification()
    }

    /** True when the runtime microphone permissions are granted, so [startRecording] can capture. */
    fun recordingPermissionsReady(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(PERMISSION_FOREGROUND_MICROPHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    /** Starts microphone capture directly to a file; MediaRecorder performs the streaming writes. */
    fun startRecording() {
        if (recorder != null) return
        // The app treats the microphone access as granted: the permission is requested automatically
        // each time the app opens, so these are informational only and never block recording. Any
        // real denial surfaces below as a precise MediaRecorder error in Troubleshooting.
        if (!recordingPermissionsReady()) {
            EventLog.add("microphone permission not granted yet - the app requests it automatically when it opens")
        }
        if (Build.VERSION.SDK_INT >= 29 && !recordingForeground) {
            // The phone only lets a foreground service capture the microphone from the locked
            // screen while the service runs with the microphone type. Try to add that type to the
            // already-running service (allowed from Android 11 on, once the runtime permission is
            // granted); if the phone refuses, recording still works while the app is in the
            // foreground and the app re-arms automatically the next time the permission is allowed.
            try {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                recordingForeground = true
                EventLog.add("microphone type added to the running service")
            } catch (e: Exception) {
                EventLog.add("could not add the microphone type: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        var activeRecorder: MediaRecorder? = null
        try {
            // The service already runs in the foreground; only the notification is refreshed at the
            // end (see the microphone-type handling above for the one deliberate exception).
            val output = createRecordingOutput()
            activeRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            activeRecorder.setAudioEncodingBitRate(128_000)
            activeRecorder.setAudioSamplingRate(44_100)
            activeRecorder.setOutputFile(output)
            activeRecorder.prepare()
            activeRecorder.start()
            recorder = activeRecorder
            beginActionVolumes() // remember the user's levels before the app changes them
            enforceVolume()
            handler.post(volumeGuard)
            handler.post(recordingSizeCheck)
            EventLog.add("VOICE RECORDING STARTED")
            refreshNotification()
        } catch (e: Exception) {
            EventLog.add("recording failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            try {
                activeRecorder?.reset()
            } catch (ignored: Exception) {
            }
            try {
                activeRecorder?.release()
            } catch (ignored: Exception) {
            }
            recorder = null
            try {
                recordingFd?.close()
            } catch (ignored: Exception) {
            }
            recordingFd = null
            recordingFile = null
            try {
                recordingUri?.let { contentResolver.delete(it, null, null) }
            } catch (ignored: Exception) {
            }
            recordingUri = null
        }
    }

    /** Stops and finalises the current recording. Safe to call when nothing is recording. */
    fun stopRecording(updateNotification: Boolean = true) {
        handler.removeCallbacks(recordingSizeCheck)
        val wasRecording = recorder != null
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
        if (wasRecording) EventLog.add("VOICE RECORDING STOPPED")
        // The recording is over: give the volumes back to the user (unless the alarm still sounds).
        endActionVolumes()
        if (updateNotification && instance != null) {
            refreshNotification()
        }
    }

    private fun createRecordingOutput(): java.io.FileDescriptor {
        val name = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
        // 1) The player-chosen folder (Storage Access Framework tree), if one was picked.
        val treeUri = prefs.recordingTreeUri?.let { Uri.parse(it) }
        if (treeUri != null) {
            try {
                val uri = DocumentsContract.createDocument(contentResolver, treeUri, "audio/mp4", name)
                    ?: throw IllegalStateException("createDocument returned null")
                val fd = contentResolver.openFileDescriptor(uri, "w")
                    ?: throw IllegalStateException("openFileDescriptor returned null")
                recordingUri = uri
                recordingFd = fd
                return fd.fileDescriptor
            } catch (e: Exception) {
                EventLog.add("recording output failed on chosen folder: ${e.message}; using default location")
                recordingUri = null
                recordingFd = null
            }
        }
        // 2) Default location through MediaStore. Some Wear OS builds have no writable "Music"
        //    collection, so any failure here falls through to app-specific storage below instead
        //    of silently stopping recording.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/Security Services")
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert returned null")
                val fd = contentResolver.openFileDescriptor(uri, "w")
                    ?: throw IllegalStateException("openFileDescriptor returned null")
                recordingUri = uri
                recordingFd = fd
                return fd.fileDescriptor
            } catch (e: Exception) {
                EventLog.add("recording output failed on Music/MediaStore: ${e.message}; using app storage")
                recordingUri = null
                recordingFd = null
            }
        }
        // 3) Public Recordings storage as a plain folder: WRITE_EXTERNAL_STORAGE is auto-granted to this
        //    targetSdk-34 build (and was requested up front on older API levels), so recording
        //    still works even if the MediaStore collection itself rejected the insert.
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS),
            "Security Services"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create ${directory.absolutePath}")
        }
        val file = File(directory, name)
        recordingFile = file
        recordingFd = android.os.ParcelFileDescriptor.open(
            file,
            android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        EventLog.add("recording saved to ${file.absolutePath}")
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
        if (!playBestAvailable()) endActionVolumes()
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

        // Volume Up cancels a pending hold - but only when it is not accompanied by a decrease in the
        // same callback, because the app's own volume pinning can raise a stream at the same time.
        if (up && !down) {
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
        when (prefs.volumeDownAction) {
            Prefs.ACTION_LOCATION -> sendLocationIfConfigured()
            Prefs.ACTION_RECORD -> startRecording()
            else -> if (!isPlaying) startAlarm()
        }
    }

    /** Gets one valid location, then sends one Google Maps link to each configured number. */
    private fun sendLocationIfConfigured() {
        if (!prefs.sendLocationOnVolumeDown) return
        val numbers = prefs.locationPhoneNumbers
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (numbers.isEmpty()) {
            EventLog.add("GPS sharing enabled, but no phone numbers were configured")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("GPS sharing skipped: location permission is not granted")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("GPS sharing skipped: SMS permission is not granted")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (locationRequestActive || now - lastLocationTriggerAt < LOCATION_TRIGGER_DEBOUNCE_MS) {
            EventLog.add("GPS sharing ignored duplicate trigger")
            return
        }
        lastLocationTriggerAt = now
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            EventLog.add("GPS sharing skipped: Location is turned off on the phone")
            return
        }

        locationRequestActive = true
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finishLocationRequest()
                val mapsUrl = "https://www.google.com/maps/search/?api=1&query=" +
                    "${location.latitude},${location.longitude}"
                val locationData = "Security Services location: $mapsUrl " +
                    "(accuracy ${location.accuracy.roundToInt()}m)"
                val prefix = prefs.locationSmsPrefix.trim()
                val message = if (prefix.isEmpty()) locationData else "$prefix\n$locationData"
                prefs.lastLocationMessage = message
                EventLog.add("GPS DATA: ${location.latitude},${location.longitude}")
                EventLog.add("GPS SMS test data: $mapsUrl")
                val sms = SmsManager.getDefault()
                numbers.forEach { number ->
                    try {
                        sms.sendTextMessage(number, null, message, null, null)
                        EventLog.add("GPS SMS sent to $number")
                    } catch (e: Exception) {
                        EventLog.add("GPS SMS failed for $number: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
        locationListener = listener
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { locationManager.isProviderEnabled(it) }
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
            EventLog.add("waiting for a valid GPS location before sending SMS")
            handler.postDelayed({
                if (locationRequestActive) {
                    finishLocationRequest()
                    EventLog.add("GPS sharing timed out before a valid location was available")
                }
            }, LOCATION_TIMEOUT_MS)
        } catch (e: Exception) {
            finishLocationRequest()
            EventLog.add("GPS sharing failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun finishLocationRequest() {
        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
            }
        }
        locationListener = null
        locationRequestActive = false
    }

    private fun resetVolumeHold() {
        volHoldStartAt = 0L
        volLastDownAt = 0L
        handler.removeCallbacks(volHoldTrigger)
    }

    /**
     * Captures the phone's current volumes/mute states just before an action changes them, so they
     * can be put back the moment that action stops. The values are also written to disk, so a run
     * that is killed while the action is still going puts the phone's levels back on the next start.
     */
    private fun beginActionVolumes() {
        if (actionVolumesSaved) return
        // A record left behind by a run that was killed mid-action means the phone is still at the
        // app's levels: put the user's real ones back first, then capture them for this action.
        restoreSavedVolumesFromDisk()
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
        actionVolumesSaved = true
        persistOriginals()
        EventLog.add(
            "volumes saved (alarm=$origAlarmVolume music=$origMusicVolume ring=$origRingVolume)"
        )
    }

    /** Writes the pre-action volume state to disk so it survives an unclean shutdown. */
    private fun persistOriginals() {
        prefs.savedVolumes = volumeSessionId + ";" + listOf(
            origAlarmVolume, origMusicVolume, origRingVolume,
            if (origAlarmMuted) 1 else 0,
            if (origMusicMuted) 1 else 0,
            if (origRingMuted) 1 else 0
        ).joinToString(",")
    }

    /**
     * Puts back the levels written by an earlier run that was killed while its action was still
     * running (see [persistOriginals]) and clears that record. The caller then captures the restored
     * levels as this action's own starting point, so the phone always ends up back at the user's
     * volumes.
     */
    private fun restoreSavedVolumesFromDisk() {
        val saved = prefs.savedVolumes ?: return
        prefs.savedVolumes = null
        val values = saved.substringAfter(';', "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
        if (values.size != 6) return
        restoreStream(AudioManager.STREAM_ALARM, values[0], values[3] == 1)
        restoreStream(AudioManager.STREAM_MUSIC, values[1], values[4] == 1)
        restoreStream(AudioManager.STREAM_RING, values[2], values[5] == 1)
        EventLog.add("volumes from the previous run were put back (it was killed mid-action)")
    }

    /**
     * Puts the phone's volumes back once no action is running any more. Called when the alarm sound
     * stops and when a recording stops; it does nothing while the other action is still using the
     * volume, so the levels only return when the app is really finished with them.
     */
    private fun endActionVolumes() {
        if (actionRunning) return
        restoreActionVolumes()
    }

    /**
     * Puts the phone's volumes/mute states back to the levels they had before the running action
     * started, and drops the on-disk record. Safe to call twice; never runs while an action is still
     * using the volume.
     */
    private fun restoreActionVolumes() {
        if (!actionVolumesSaved || actionRunning) return
        restoreStream(AudioManager.STREAM_ALARM, origAlarmVolume, origAlarmMuted)
        restoreStream(AudioManager.STREAM_MUSIC, origMusicVolume, origMusicMuted)
        restoreStream(AudioManager.STREAM_RING, origRingVolume, origRingMuted)
        actionVolumesSaved = false
        // The app just wrote the volumes back: restart the Volume Down detection from a clean
        // baseline so the restore is never mistaken for a Volume Up press.
        lastVolumes = snapshotVolumes()
        // Clear the on-disk record only when it is the one this instance wrote: a newer instance may
        // already have replaced it while this (re-armed) instance was shutting down.
        if (prefs.savedVolumes?.startsWith("$volumeSessionId;") == true) prefs.savedVolumes = null
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
        val recording = recorder != null
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when {
                    playing -> "ALARM SOUNDING"
                    recording -> "RECORDING VOICE"
                    else -> "Security Services ARMED"
                }
            )
            .setContentText(
                when {
                    playing -> "Unlock the phone and open the app to stop it"
                    recording -> "Open the app and tap STOP RECORDING to finish the file"
                    else -> "Swipe the app away in Recents to disarm"
                }
            )
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET) // hidden on the lock screen
            .setContentIntent(open)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            // Treat the microphone access as granted: optimistically include the microphone type
            // (needed for voice recording while the screen is locked). If the phone refuses it
            // because the runtime permission is not granted yet, fall back to media playback only -
            // the alarm still works, and the app re-arms automatically once the dialog is allowed.
            recordingForeground = try {
                startForeground(
                    NOTIF_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                true
            } catch (e: Exception) {
                false
            }
            if (!recordingForeground) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
