package com.example.securityservices

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.Environment
import android.telephony.SmsManager
import android.telecom.TelecomManager
import android.provider.Settings
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The "armed" state of the app IS this service running.
 *  - Started only by the user (ARM or PLAY button). Nothing starts it at boot.
 *  - stopWithTask=true + onTaskRemoved: swiping the app away / "Close all" stops it => disarmed.
 *  - Plays the sound on the ALARM audio stream at a fixed volume, re-enforced continuously.
 *
 * @author Chu Quang Khai (Khai Chu)
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_ARM = "com.example.securityservices.ARM"
        const val ACTION_PLAY = "com.example.securityservices.PLAY"
        const val ACTION_STOP_RECORDING = "com.example.securityservices.STOP_RECORDING"
        const val ACTION_STOP_VIDEO = "com.example.securityservices.STOP_VIDEO"
        private const val CHANNEL_ID = "alarm_status"
        private const val NOTIF_ID = 1001

        /**
         * Runtime permission Android 11+ needs before a foreground service may run with the
         * microphone foreground type (declared in AndroidManifest.xml). Without it, starting
         * the foreground service with the microphone type fails on targetSdk >= 30 devices.
         */
        private const val PERMISSION_FOREGROUND_MICROPHONE = "android.permission.FOREGROUND_SERVICE_MICROPHONE"

        /** How long Volume Down must be held (fallback volume-based detection). */
        private const val HOLD_MS = 1_000L

        /**
         * Longest pause between volume decreases that still counts as one continuous hold.
         * Must be comfortably larger than the OS key-repeat delay (~500 ms) but smaller than HOLD_MS.
         * At 1s HOLD_MS the margin is tight, so keep gap small to avoid false single-press triggers.
         */
        private const val HOLD_GAP_MS = 600L
        private const val LOCATION_TIMEOUT_MS = 30_000L
        private const val LOCATION_TRIGGER_DEBOUNCE_MS = 3_000L
        private const val CALL_TRIGGER_DEBOUNCE_MS = 3_000L

        /**
         * Most SMS a single Volume Down hold may send. Every detected hold that starts a location
         * request gets this allowance again, so a long recipient list is spread over several holds
         * instead of one hold flooding the inbox (each message is billed separately to the user).
         */
        private const val MAX_SMS_MESSAGES_PER_TRIGGER = 10
        private const val FLASH_BLINK_MS = 500L

        /** Minimum wait between two photo emails, so a repeated hold cannot spam the inbox. */
        private const val EMAIL_PHOTO_DEBOUNCE_MS = 2_000L

        /**
         * Largest raw recording part sent in a single email (16 MB). Base64 encoding plus the MIME
         * line wrapping adds about 37 %, so 16 MB of audio becomes roughly 22 MB of email data -
         * comfortably under the 25 MB limit most email providers enforce. The recording is stored as
         * AAC ADTS, which is streamable, so these parts can be rejoined (and played) even if a later
         * part is missing or failed to send.
         */
        private const val MAX_EMAIL_ATTACHMENT_BYTES = 16L * 1024L * 1024L

        /**
         * How far the splitter looks ahead for an AAC ADTS frame boundary when finishing a part. Ending
         * every part on a frame boundary makes each part a valid, playable .aac file on its own; the
         * look-ahead never drops bytes (they are carried into the next part).
         */
        private const val ADTS_ALIGN_WINDOW_BYTES = 4 * 1024

        /** MPEG-2 TS packet size; video email parts always end on a packet border. */
        private const val TS_PACKET_BYTES = 188
        /** Video emails are always truncated into parts of at most 15 MB each. */
        private const val VIDEO_PART_BYTES = 15L * 1024L * 1024L

        /**
         * Largest single recording - voice or video - the app captures before it stops the capture
         * on its own (1 GB). Whichever comes first of this size or the user's second hold / STOP
         * ends the recording; reaching the cap finalises the file (and emails it) exactly like a
         * manual stop. The 5 s size poll means the file may overshoot this by a few seconds' worth.
         */
        private const val RECORDING_LIMIT_BYTES = 1024L * 1024L * 1024L

        /**
         * Largest TOTAL amount of video/audio data emailed for one Volume Down recording (500 MB).
         * Each individual email attachment (16 MB for audio, 15 MB for video) already stays well
         * under the provider's 25 MB per-message limit; this cap bounds the SUM of all parts so a
         * long recording cannot flood the inbox - the remainder past 500 MB is simply not emailed.
         */
        private const val MAX_EMAIL_TOTAL_BYTES = 500L * 1024L * 1024L

        /** Name of the video folder, created next to the recordings folder. */
        private const val VIDEO_DIR_NAME = "Security Services Videos"

        /**
         * How long the app waits for an internet connection before giving up on a send. If the phone
         * only gets internet after this window, the photo / recording is NOT sent afterwards.
         */
        private const val INTERNET_WAIT_MS = 120_000L

        /** How often the internet connection is re-checked while waiting (must be well under the limit). */
        private const val INTERNET_POLL_MS = 3_000L

        /**
         * SMS already handed to the radio for the Volume Down hold currently being handled. There is
         * no per-app-start limit any more: the counter is reset by [sendLocationIfConfigured] for every
         * detected hold, so each hold may send up to [MAX_SMS_MESSAGES_PER_TRIGGER] messages.
         */
        @Volatile
        private var smsMessagesSentForTrigger = 0

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

        /**
         * True when a fresh Volume Down hold should be ignored because the alarm is already
         * sounding. The combined alarm+photo action is the exception: a repeat hold must still
         * reach [AlarmService.triggerVolumeAction] so another photo is captured and emailed
         * (the sound part is a no-op while playing; the photo part has its own 2-second guard).
         */
        val suppressRepeatHoldWhilePlaying: Boolean get() =
            isPlaying && instance?.currentVolumeDownAction != Prefs.ACTION_ALARM

        /** True when the running foreground service includes the microphone type (only true when the
         *  runtime permission was granted before the service started). Recording needs this type. */
        val hasMicrophoneForegroundType: Boolean get() = instance?.recordingForeground ?: false

        /** Elapsed-realtime timestamp of the last recording start (0 = none yet this process). */
        @Volatile
        private var lastRecordStartAt = 0L

        /**
         * True when a recording was (re)started very recently. Covers the race where a Volume
         * Down RECORD trigger fired but the recorder field is not assigned yet: without this,
         * a re-arm queued before the trigger could still restart the service mid-capture.
         */
        fun recordStartedRecently(): Boolean =
            android.os.SystemClock.elapsedRealtime() - lastRecordStartAt < 10_000L

        /** Marks the moment a recording capture begins (called before MediaRecorder.start()). */
        private fun markRecordStarted() {
            lastRecordStartAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingUri: Uri? = null
    private var recordingFd: android.os.ParcelFileDescriptor? = null
    /** Microphone type included in the foreground service type. Set once at service start. */
    private var recordingForeground = false

    /**
     * True once the running foreground service includes the camera type. Android 11+ requires this
     * type before an app in the background may open the camera, so it is set at service start when
     * possible and added on demand right before the first photo is captured.
     */
    private var cameraForeground = false
    /** Cap on a single voice recording; reaching it stops the capture automatically (see [RECORDING_LIMIT_BYTES]). */
    private val recordingLimitBytes = RECORDING_LIMIT_BYTES
    /**
     * Photo emails (capture + SMTP send) still in flight. Closing the app (swipe away) must wait
     * for these to finish instead of killing them mid-send (see [pendingShutdownAfterPhotoSend]).
     */
    private var pendingPhotoSends = 0
    /**
     * True once the task was removed (app swiped away) while photo sends were still running. The
     * sound is stopped immediately, but stopSelf() is deferred until [pendingPhotoSends] drops to
     * zero so the email is not lost.
     */
    private var pendingShutdownAfterPhotoSend = false
    private var photoSendWakeLock: PowerManager.WakeLock? = null
    /** Delay after switching the torch off before opening the camera (lets the camera settle). */
    private val photoAfterTorchDelayMs = 700L
    /** Delay before retrying a photo capture that failed while the torch was just released. */
    private val photoRetryDelayMs = 1_500L
    private var focusRequest: AudioFocusRequest? = null
    private var savedFilter = -1
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    /** Action selected for the Volume Down hold; exposed for the trigger's repeat-hold check. */
    private val currentVolumeDownAction: String get() = prefs.volumeDownAction
    private lateinit var locationManager: LocationManager
    private lateinit var cameraManager: CameraManager
    private var locationRequestActive = false
    private var locationListener: LocationListener? = null
    private var lastLocationTriggerAt = 0L
    private var lastCallTriggerAt = 0L
    private var lastPhotoEmailAt = 0L
    private var lastVideoEmailAt = 0L
    private var videoCapture: VideoCapture? = null
    private var videoFile: File? = null
    private var videoIsTs = true
    private var flashlightCameraId: String? = null
    private var flashlightOn = false

    private val flashlightBlink = object : Runnable {
        override fun run() {
            if (player == null) {
                stopFlashlightBlinking()
                return
            }
            setFlashlight(!flashlightOn)
            handler.postDelayed(this, FLASH_BLINK_MS)
        }
    }

    private val recordingSizeCheck = object : Runnable {
        override fun run() {
            if (recorder == null) return
            if (recordingSize() >= recordingLimitBytes) {
                EventLog.add("recording stopped at ${recordingLimitBytes / 1024 / 1024} MB")
                stopRecording()
            } else {
                handler.postDelayed(this, 5_000L)
            }
        }
    }

    /**
     * Stops the video capture once the file on disk reaches [RECORDING_LIMIT_BYTES] (1 GB) so a
     * single video can never grow past the cap. Routes through [stopVideoRecordingAndSend], so a
     * capped video is finalised, saved and emailed exactly like a manual stop. The 5 s cadence
     * matches the voice cap and lets the file overshoot the limit by a few seconds' worth.
     */
    private val videoSizeCheck = object : Runnable {
        override fun run() {
            if (videoCapture == null) return
            val file = videoFile
            if (file != null && file.length() >= RECORDING_LIMIT_BYTES) {
                EventLog.add("video recording stopped at ${RECORDING_LIMIT_BYTES / 1024 / 1024} MB")
                stopVideoRecordingAndSend()
            } else {
                handler.postDelayed(this, 5_000L)
            }
        }
    }

    // ------------------------------------------- volume control (only while an action is running)

    /**
     * True while the app is doing something that uses its own volume setting: the alarm is sounding,
     * a voice recording is running, or a video capture is starting up or running (the capture object
     * exists from the moment it is requested, so the startup window is covered too). Only then does
     * the app touch the phone's volumes; otherwise the volumes are left completely alone so the user
     * can adjust them.
     */
    private val actionRunning: Boolean get() = player != null || recorder != null ||
        videoCapture != null

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
    /** Device-wide microphone mute captured before recording, so it can be put back afterwards. */
    private var origMicMuted = false

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
        cameraManager = getSystemService(CameraManager::class.java)
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
        // Never touch the foreground state while a voice recording is running: re-issuing
        // startForeground() (possibly with a different type set) can throw and the catch
        // below would stopSelf() - killing the recorder mid-capture just because the app
        // was re-opened. An ARM intent carries no action, so there is nothing else to do.
        if (recorder == null) {
            try {
                // goForeground() decides whether the phone lets us run with the microphone type: it
                // always asks for it optimistically and falls back to media playback only if the runtime
                // permission is not granted yet (the type can never be added later). The app re-requests
                // the permission and re-arms automatically once the user allows the dialog in the GUI.
                // (Recording is never interrupted here: an ARM intent carries no action, so the
                // command below leaves the recorder untouched; re-arming while recording is blocked
                // in MainActivity.rearm()/doArm() instead.)
                goForeground()
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            refreshNotification()
        }
        when (intent?.action) {
            ACTION_PLAY -> startAlarm()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_STOP_VIDEO -> toggleVideoRecording()
        }
        return START_NOT_STICKY // never restarted automatically => never "armed" behind the user's back
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the app (swipe away in Recents / "Close all") stops any running action, and stopping
        // the last action puts the phone's volumes back to the levels they had before it started (see
        // endActionVolumes()). (An in-app re-arm deliberately keeps a running recording alive via
        // disarm(), which is a no-op for the service itself while recording - only a real task
        // removal shuts everything down here.)
        EventLog.add("app closed")
        stopSound(updateNotification = false)
        stopRecording(updateNotification = false)
        stopVideoRecordingAndSend(updateNotification = false)
        // A photo capture / email that is still running must be allowed to finish: keep the service
        // alive until the last send completes instead of killing it mid-send.
        if (pendingPhotoSends > 0) {
            pendingShutdownAfterPhotoSend = true
            refreshNotification()
            EventLog.add("app closed: finishing $pendingPhotoSends photo email(s) before stopping")
            super.onTaskRemoved(rootIntent)
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // NOTE: returning early does NOT veto destruction - the system still destroys the service.
        // The guard below avoids actively tearing down (volumes/flash/notification/instance) while a
        // photo send is in flight; survival itself comes from stopWithTask=false + the foreground
        // notification + the wake lock (see beginPhotoSend), and the last send performs the deferred
        // shutdown via finishPhotoSend.
        if (pendingPhotoSends <= 0) {
            stopSound(updateNotification = false)
            stopRecording(updateNotification = false)
            stopVideoRecordingAndSend(updateNotification = false, waitForInternet = false)
            resetVolumeHold()
            // stopSound()/stopRecording() above already gave the volumes back if they were the last
            // action; this is a safety net for an action that ended without either being called.
            handler.removeCallbacks(volumeGuard)
            stopFlashlightBlinking()
            finishLocationRequest()
            restoreActionVolumes()
            try {
                contentResolver.unregisterContentObserver(volumeObserver)
            } catch (e: Exception) {
            }
            EventLog.clear()
            instance = null
        } else {
            EventLog.add("service destroy with $pendingPhotoSends photo email(s) in flight")
        }
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
            if (prefs.volumeDownAction == Prefs.ACTION_ALARM) startFlashlightBlinking()
        } else {
            endActionVolumes() // nothing started: leave the phone's volumes exactly as they were
        }
        refreshNotification()
        EventLog.add(if (ok) "SOUND STARTED" else "SOUND FAILED")
    }

    fun stopSound(updateNotification: Boolean = true) {
        handler.removeCallbacks(volumeGuard)
        stopFlashlightBlinking()
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
        // removeCallbacks() above killed the guard loop even if a recording or video capture is
        // still running: restart it so its volume/mic re-assertion keeps going for that action.
        if (actionRunning) handler.post(volumeGuard)
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

    /** First hold starts video capture; the next hold stops, saves and emails it. */
    fun toggleVideoRecording() {
        if (videoCapture != null) {
            stopVideoRecordingAndSend()
        } else {
            startVideoRecording()
        }
    }

    /** True while the camera is recording video for the video-email action. */
    fun isVideoRecording(): Boolean = videoCapture?.isRecording == true

    private fun startVideoRecording() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoEmailAt < EMAIL_PHOTO_DEBOUNCE_MS) {
            EventLog.add("video email ignored: only one every ${EMAIL_PHOTO_DEBOUNCE_MS / 1000}s")
            return
        }
        // Also blocks a second camera open while the first one is still starting up.
        if (videoCapture != null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("video email skipped: camera permission is not granted")
            return
        }
        if (photoEmailConfig() == null) return
        val withAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        bringCameraToForeground()
        if (withAudio) bringMicToForegroundSafe()
        // Save the user's levels/mute BEFORE changing anything, then force the microphone path on
        // for the whole capture (see ensureMicMaxed) so the video is recorded at full mic level no
        // matter what the phone's mute/volume state or the app's own volume setting was before.
        // endActionVolumes() puts it all back when this action ends.
        beginActionVolumes()
        if (withAudio) ensureMicMaxed("video recording")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tmp = File(cacheDir, "video_$stamp.tmp")
        lastVideoEmailAt = now
        val cap = VideoCapture(this, cameraManager)
        videoCapture = cap
        videoFile = tmp
        EventLog.add("VIDEO RECORDING STARTED")
        refreshNotification()
        handler.post(volumeGuard) // re-assert alarm volume + mic un-mute 4x/second during the capture
        handler.postDelayed(videoSizeCheck, 5_000L) // stop + email automatically once the file hits 1 GB
        cap.start(tmp, withAudio,
            onStarted = {
                handler.post {
                    videoIsTs = cap.startedWithTs
                    EventLog.add("video recording capturing")
                    refreshNotification()
                }
            },
            onError = { reason ->
                handler.post {
                    EventLog.add("video recording failed: $reason")
                    videoCapture = null
                    videoFile = null
                    endActionVolumes() // never started: hand the saved volumes/mute straight back
                    refreshNotification()
                }
            })
    }

    fun startRecording() {
        // Claim the "recording" state up front so a re-arm racing this call still sees it and
        // backs off instead of restarting the service mid-capture (see recordStartedRecently()).
        // If a recording is already running this is a no-op (never restarts the capture).
        markRecordStarted()
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
        // Max the microphone path BEFORE capture starts, regardless of the user's mute setting.
        // The pre-recording mute state is captured inside beginActionVolumes() below and put back
        // when the last action stops, so the user's setting is respected outside the recording.
        beginActionVolumes() // remember the user's levels/mute BEFORE the app changes them
        ensureMicMaxed("record-start")
        var activeRecorder: MediaRecorder? = null
        try {
            // The service already runs in the foreground; only the notification is refreshed at the
            // end (see the microphone-type handling above for the one deliberate exception).
            val output = createRecordingOutput()
            activeRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            // AAC in the ADTS container (.aac): unlike MPEG-4/M4A it keeps no index/"moov" atom at
            // the end of the file, so any byte prefix of the recording is itself playable. That is
            // what lets the email parts be rejoined and replayed even when a later part is missing.
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            activeRecorder.setAudioEncodingBitRate(128_000)
            activeRecorder.setAudioSamplingRate(44_100)
            activeRecorder.setOutputFile(output)
            activeRecorder.prepare()
            activeRecorder.start()
            recorder = activeRecorder
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
        val file = recordingFile
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
        if (wasRecording) {
            EventLog.add("VOICE RECORDING STOPPED")
            // Email the finished recording to the same address, on the same SMTP account as the photo.
            // The send happens on its own thread so it never blocks the service.
            if (uri != null || file != null) {
                val baseName = recordingBaseName(uri, file)
                Thread({ emailRecording(uri, file, baseName) }, "recording-email").start()
            }
        }
        // The recording is over: give the volumes back to the user (unless the alarm still sounds).
        endActionVolumes()
        if (updateNotification && instance != null) {
            refreshNotification()
        }
    }
    fun stopVideoRecordingAndSend(
        updateNotification: Boolean = true,
        waitForInternet: Boolean = true
    ) {
        handler.removeCallbacks(videoSizeCheck)
        val cap = videoCapture ?: return
        videoCapture = null
        cap.stop { file ->
            handler.post { onVideoFileReady(file, updateNotification, waitForInternet) }
        }
    }

    private fun onVideoFileReady(
        file: File?,
        updateNotification: Boolean,
        waitForInternet: Boolean
    ) {
        videoFile = null
        endActionVolumes()
        if (updateNotification && instance != null) refreshNotification()
        if (file == null || !file.exists() || file.length() <= 0) {
            EventLog.add("video recording stopped: no video captured")
            if (updateNotification) setSendNotice("Video failed: no video captured", false)
            return
        }
        EventLog.add("VIDEO RECORDING STOPPED (${file.length()} bytes)")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (videoIsTsCompat(file)) "ts" else "mp4"
        val source = saveVideoToLibrary(file, stamp, ext) ?: file
        Thread({
            emailVideoFile(source, stamp, ext, waitForInternet)
            // When the library save kept the temp copy as the email source, it is removed
            // after the send; a saved public copy (Movies/...) is intentionally kept.
            if (source == file) {
                try { file.delete() } catch (_: Exception) { }
            }
        }, "video-email").start()
    }

    private fun videoIsTsCompat(file: File): Boolean {
        if (videoIsTs) return true
        return try {
            file.extension.equals("ts", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }


    private fun createRecordingOutput(): java.io.FileDescriptor {
        val name = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.aac"
        // 1) The player-chosen folder (Storage Access Framework tree), if one was picked.
        val treeUri = prefs.recordingTreeUri?.let { Uri.parse(it) }
        if (treeUri != null) {
            try {
                val uri = DocumentsContract.createDocument(contentResolver, treeUri, "audio/aac", name)
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
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
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
    private fun saveVideoToLibrary(tmp: File, stamp: String, ext: String): File? {
        val name = "video_$stamp.$ext"
        val mime = if (ext == "ts") "video/mp2t" else "video/mp4"
        // Sibling of the alarm-sound/voice folder picked by the user (SAF tree).
        val treeStr = prefs.recordingTreeUri
        if (treeStr != null) {
            try {
                val tree = Uri.parse(treeStr)
                val parent = DocumentsContract.getTreeDocumentId(tree)
                    .substringBeforeLast('/', missingDelimiterValue = "")
                    .substringBeforeLast(':', missingDelimiterValue = "")
                var targetParent = tree
                if (parent.isNotEmpty()) {
                    try {
                        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                            tree, parent
                        )
                        targetParent = DocumentsContract.buildChildDocumentsUriUsingTree(
                            tree, DocumentsContract.getDocumentId(parentDoc)
                        )
                    } catch (_: Exception) { }
                }
                val videoDir = findOrCreateChildDir(targetParent, VIDEO_DIR_NAME)
                if (videoDir != null) {
                    val doc = DocumentsContract.createDocument(
                        contentResolver, videoDir, mime, name
                    )
                    if (doc != null) {
                        contentResolver.openOutputStream(doc, "w")?.use { out ->
                            tmp.inputStream().use { it.copyTo(out) }
                        }
                        EventLog.add("video saved to $VIDEO_DIR_NAME/$name")
                        // Keep the temp file: it is still the source the email is read from and
                        // is deleted once the send finished (see onVideoFileReady).
                        return tmp
                    }
                }
            } catch (e: Exception) {
                EventLog.add("video save to chosen folder failed: ${e.message}")
            }
        }
        // Default: device Movies library next to the Recordings voice folder.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/Security Services Videos")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri, "w")?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                    val done = android.content.ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    try { contentResolver.update(uri, done, null, null) } catch (_: Exception) { }
                    EventLog.add("video saved to Movies/Security Services Videos/$name")
                    // Keep the temp file as the email source; onVideoFileReady deletes it after
                    // the send finished.
                    return tmp
                }
            } catch (e: Exception) {
                EventLog.add("video save to Movies failed: ${e.message}")
            }
        }
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Security Services Videos"
            )
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, name)
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            EventLog.add("video saved to ${dest.absolutePath}")
            return dest
        } catch (e: Exception) {
            EventLog.add("video save failed: ${e.message}")
            return if (tmp.exists()) tmp else null
        }
    }

    private fun findOrCreateChildDir(parentChildrenUri: Uri, name: String): Uri? {
        try {
            contentResolver.query(
                parentChildrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            parentChildrenUri, id
                        )
                    }
                }
            }
        } catch (_: Exception) { }
        return try {
            DocumentsContract.createDocument(
                contentResolver, parentChildrenUri,
                DocumentsContract.Document.MIME_TYPE_DIR, name
            )
        } catch (_: Exception) {
            null
        }
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
        // Disarming must never cut a voice recording short: it is only the alarm sound that
        // belongs to the armed service lifecycle. (The recorder is stopped explicitly by the
        // user, the 1 GB size limit, or a process shutdown.) If a recording is running, keep
        // the service (and its foreground notification) alive - otherwise a re-arm triggered
        // by simply re-opening the app would stop the recorder mid-capture via onDestroy().
        stopSound(updateNotification = false)
        if (recorder != null || videoCapture != null) {
            // A running (or still starting) video capture belongs to the armed service too: a
            // re-arm from simply re-opening the app must not stop it mid-recording (it ends via
            // the second hold, onDestroy or a real task removal instead).
            refreshNotification()
            return
        }
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
        // A recording (voice or video-with-audio) is running: keep the microphone path at full gain
        // too. See ensureMicMaxed() for why this is an un-mute guard (there is no mic-volume API).
        if (recorder != null || videoCapture?.recordsAudio == true) ensureMicMaxed("guard")
    }

    /**
     * Keeps the microphone capture at maximum gain while a voice recording or a video capture with
     * audio runs.
     *
     * Android exposes NO "microphone volume" API: capture gain is fixed by the audio HAL and
     * AudioManager stream volumes only affect playback, so user volume settings can never lower
     * a MediaRecorder capture in the first place. The single setting that CAN silence or cripple
     * the capture is the device-wide microphone mute ([AudioManager.isMicrophoneMute] /
     * [AudioManager.setMicrophoneMute], allowed by the MODIFY_AUDIO_SETTINGS permission this app
     * already holds). "Always maxed regardless of user setting" therefore means: force that mute
     * off right before capture starts and keep forcing it off for the whole recording (called from
     * [startRecording], from [startVideoRecording], and from [enforceVolume], which runs 4x/second
     * via volumeGuard plus on every system volume change). The previous mute state is captured in
     * [beginActionVolumes] and put back in [restoreActionVolumes] when the last action stops.
     *
     * [reason] only selects the troubleshooting log text (any reason other than "guard" logs once
     * at capture start; "guard" only logs when it actually had to un-mute mid-recording to avoid
     * spamming the log 4x/second).
     */
    private fun ensureMicMaxed(reason: String) {
        try {
            if (!audio.isMicrophoneMute()) return
            audio.setMicrophoneMute(false)
            // setMicrophoneMute is asynchronous on some phones: verify, retry once.
            if (audio.isMicrophoneMute()) audio.setMicrophoneMute(false)
            if (reason != "guard") {
                EventLog.add("microphone un-muted for recording (was muted)")
            } else if (!audio.isMicrophoneMute()) {
                EventLog.add("microphone re-un-muted during recording (something muted it)")
            }
        } catch (e: Exception) {
            if (reason != "guard") {
                EventLog.add("could not un-mute microphone: ${e.message ?: e.javaClass.simpleName}")
            }
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
        EventLog.add("TRIGGER: Volume Down held 1s (volume detection)")
        triggerVolumeAction()
        resetVolumeHold()
    }

    /** Applies the user's selected action for a completed Volume Down hold. */
    fun triggerVolumeAction() {
        when (prefs.volumeDownAction) {
            Prefs.ACTION_LOCATION -> sendLocationIfConfigured()
            Prefs.ACTION_CALL -> callFirstNumberIfConfigured()
            Prefs.ACTION_RECORD -> startRecording()
            Prefs.ACTION_EMAIL_PHOTO -> captureAndEmailPhoto()
            Prefs.ACTION_EMAIL_VIDEO -> toggleVideoRecording()
            else -> startAlarmWithPhoto()
        }
    }

    /**
     * First Volume Down option: plays the alarm sound and flashes (see [startAlarm]) while also
     * capturing one photo and emailing it (see [captureAndEmailPhoto]). The photo part reuses the
     * same "capture one photo and email it" settings and its 2-second inbox-spam guard, so a second
     * hold while the sound is already playing still emails another photo (at most one every
     * 2 seconds).
     */
    private fun startAlarmWithPhoto() {
        if (!isPlaying) startAlarm()
        captureAndEmailPhoto()
    }

    /** Places one direct call to the first semicolon-separated number in the current input. */
    private fun callFirstNumberIfConfigured() {
        val number = prefs.locationPhoneNumbers
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        if (number == null) {
            EventLog.add("phone call skipped: no phone number was configured")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("phone call skipped: CALL_PHONE permission is not granted")
            return
        }
        val telecom = getSystemService(TelecomManager::class.java)
            ?: run {
                EventLog.add("phone call skipped: telecom service unavailable")
                return
            }
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("phone call skipped: phone-state permission is not granted")
            return
        }
        try {
            if (telecom.isInCall) {
                EventLog.add("phone call skipped: a call is already in progress")
                return
            }
        } catch (e: SecurityException) {
            EventLog.add("phone call skipped: could not check call state")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCallTriggerAt < CALL_TRIGGER_DEBOUNCE_MS) {
            EventLog.add("phone call ignored duplicate trigger")
            return
        }
        lastCallTriggerAt = now
        try {
            telecom.placeCall(Uri.parse("tel:${Uri.encode(number)}"), Bundle())
            EventLog.add("phone call started to $number")
        } catch (e: Exception) {
            EventLog.add("phone call failed for $number: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Captures one photo with the phone camera and emails it to the address the user configured.
     * Only ever runs after a completed Volume Down hold; a second trigger that arrives within
     * EMAIL_PHOTO_DEBOUNCE_MS of the previous one is ignored so the inbox cannot be spammed.
     */
    fun captureAndEmailPhoto() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPhotoEmailAt < EMAIL_PHOTO_DEBOUNCE_MS) {
            EventLog.add("photo email ignored: only one every ${EMAIL_PHOTO_DEBOUNCE_MS / 1000}s")
            return
        }
        lastPhotoEmailAt = now

        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("photo email skipped: camera permission is not granted")
            setSendNotice("Photo NOT sent: camera permission is not granted", false)
            return
        }
        // Reserve the "in flight" slot before the background internet wait: closing the app during
        // the 2-minute window must still wait for this photo instead of killing it unseen.
        beginPhotoSend()
        val config = photoEmailConfig()
        if (config == null) {
            finishPhotoSend()
            return
        }

        // The camera and the send only run when the phone has internet inside the 2-minute window.
        // If internet only shows up after that window, the photo is never taken and never sent.
        Thread({
            if (!awaitInternetConnection()) {
                val message = "Photo NOT sent: no internet within 2 minutes"
                EventLog.add("photo email skipped: no internet connection within 2 minutes")
                setSendNotice(message, false)
                handler.post { finishPhotoSend() }
                return@Thread
            }
            handler.post {
                bringCameraToForeground()
                // The alarm flashlight uses the camera's torch mode, which can block opening the
                // camera for the photo on some phones: pause the blinking (torch off), give the
                // camera a moment to settle, then capture. A quick retry covers the case where the
                // driver still reports the camera as busy right after the torch went off.
                val alarmBlinking = player != null &&
                    prefs.volumeDownAction == Prefs.ACTION_ALARM
                if (alarmBlinking) pauseFlashlightForPhoto()
                EventLog.add("photo email: capturing one photo")
                if (alarmBlinking) handler.postDelayed(
                    { attemptPhotoCapture(config, alarmBlinking, false) },
                    photoAfterTorchDelayMs
                )
                else attemptPhotoCapture(config, alarmBlinking, false)
            }
        }, "photo-internet-check").start()
    }

    /**
     * Runs one photo-capture attempt for [captureAndEmailPhoto] on the main thread. When the alarm
     * flashlight was just switched off the camera driver can still report "camera in use", so the
     * first attempt gets one delayed retry ([retried] guards it). A failed capture releases its
     * [finishPhotoSend] slot; a success hands the file to [sendPhotoEmail], which releases the slot
     * once the SMTP send finished. Callers must invoke this on the main thread.
     */
    private fun attemptPhotoCapture(
        config: SmtpMailer.Config,
        alarmBlinking: Boolean,
        retried: Boolean
    ) {
        PhotoCapture(applicationContext, cameraManager).capture { file ->
            // PhotoCapture reports back on its own camera thread: hop to the main thread so the
            // pending-send counter stays synchronous with onTaskRemoved/onDestroy.
            handler.post {
                if (file == null && alarmBlinking && !retried) {
                    EventLog.add("photo email: camera busy right after torch off - retrying once")
                    handler.postDelayed(
                        { attemptPhotoCapture(config, alarmBlinking, true) },
                        photoRetryDelayMs
                    )
                    return@post
                }
                if (alarmBlinking) resumeFlashlightAfterPhoto()
                if (file == null) {
                    EventLog.add("photo email failed: the camera returned no photo")
                    setSendNotice("Photo email failed: the camera returned no photo", false)
                    finishPhotoSend()
                    return@post
                }
                EventLog.add("photo email: photo captured (${file.length()} bytes) - sending")
                sendPhotoEmail(config, file)
            }
        }
    }

    /**
     * Marks a photo capture/send as in flight: keeps the CPU awake and the service in the
     * foreground so swiping the app away (or Doze) cannot kill it mid-send. Every [beginPhotoSend]
     * must be paired with exactly one [finishPhotoSend].
     */
    private fun beginPhotoSend() {
        pendingPhotoSends++
        try {
            if (photoSendWakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                photoSendWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SecurityServices:photo-send"
                ).apply { setReferenceCounted(false) }
            }
            if (photoSendWakeLock?.isHeld != true) {
                photoSendWakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            EventLog.add("photo email: wake lock unavailable (${e.message})")
        }
        // The service must be in the foreground while a send is in flight: if the app was swiped
        // away and the process only survives because of the pending send, (re-)assert foreground
        // state so the system does not kill the SMTP thread mid-send.
        // NOTE: always called on the main thread (captureAndEmailPhoto -> handler.post), so the
        // counter stays synchronous with onTaskRemoved/onDestroy - never post this to the handler.
        try { goForeground() } catch (_: Exception) { }
        refreshNotification()
    }

    /**
     * Releases one [beginPhotoSend] slot. When the app was swiped away while sends were running
     * ([pendingShutdownAfterPhotoSend]), the last send performs the deferred shutdown so the email
     * is never cut off: volumes are restored, the foreground state removed, and the service stopped.
     * Safe to run after the service was destroyed: every step is guarded and the temp-file delete in
     * sendPhotoEmail already ran, so nothing is lost.
     */
    private fun finishPhotoSend() {
        pendingPhotoSends = (pendingPhotoSends - 1).coerceAtLeast(0)
        if (pendingPhotoSends == 0) {
            try { photoSendWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
            photoSendWakeLock = null
        }
        if (pendingShutdownAfterPhotoSend && pendingPhotoSends == 0) {
            pendingShutdownAfterPhotoSend = false
            EventLog.add("photo email(s) finished after app close - stopping service")
            try { stopSound(updateNotification = false) } catch (_: Exception) { }
            try { stopRecording(updateNotification = false) } catch (_: Exception) { }
            try {
                stopVideoRecordingAndSend(updateNotification = false, waitForInternet = false)
            } catch (_: Exception) { }
            try { resetVolumeHold() } catch (_: Exception) { }
            try { handler.removeCallbacks(volumeGuard) } catch (_: Exception) { }
            try { stopFlashlightBlinking() } catch (_: Exception) { }
            try { finishLocationRequest() } catch (_: Exception) { }
            try { restoreActionVolumes() } catch (_: Exception) { }
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            try { stopSelf() } catch (_: Exception) { }
            return
        }
        try { refreshNotification() } catch (_: Exception) { }
    }

    /** The SMTP account shared by the photo email and the recording email. */
    private data class EmailDetails(
        val host: String,
        val port: Int,
        val startTls: Boolean,
        val username: String,
        val password: String,
        val from: String,
        val recipient: String
    )

    /** Reads and validates the shared email settings, returning null (and logging) when one is missing. */
    private fun emailDetailsOrNull(what: String): EmailDetails? {
        val recipient = prefs.emailPhotoRecipient.trim()
        if (recipient.isEmpty()) {
            EventLog.add("$what skipped: no recipient address was set")
            return null
        }
        val sender = prefs.emailPhotoSender.trim()
        if (sender.isEmpty()) {
            EventLog.add("$what skipped: no sender address was set")
            return null
        }
        val password = prefs.emailPhotoPassword
        if (password.isEmpty()) {
            EventLog.add("$what skipped: no email app password was set")
            return null
        }
        val host = prefs.emailPhotoSmtpHost.trim()
        if (host.isEmpty()) {
            EventLog.add("$what skipped: no SMTP server was set")
            return null
        }
        return EmailDetails(
            host = host,
            port = prefs.emailPhotoSmtpPort,
            startTls = prefs.emailPhotoStartTls,
            username = sender,
            password = password,
            from = sender,
            recipient = recipient
        )
    }

    /** Builds one SMTP message from [details] with the given subject and body. */
    private fun mailConfig(details: EmailDetails, subject: String, body: String) = SmtpMailer.Config(
        host = details.host,
        port = details.port,
        startTls = details.startTls,
        username = details.username,
        password = details.password,
        from = details.from,
        recipient = details.recipient,
        subject = subject,
        body = body
    )

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** The message settings for the "capture one photo and email it" action. */
    private fun photoEmailConfig(): SmtpMailer.Config? {
        val details = emailDetailsOrNull("photo email") ?: return null
        val body = "A photo was captured automatically by Security Services on the Volume Down " +
            "trigger.\nDevice: ${Build.MODEL}\nTime: ${timestamp()}"
        return mailConfig(details, "Security Services photo", body)
    }

    /** Sends the captured photo on a background thread and removes the temporary file afterwards. */
    private fun sendPhotoEmail(config: SmtpMailer.Config, file: File) {
        Thread({
            try {
                SmtpMailer.send(config, file)
                EventLog.add("photo email sent to ${config.recipient}")
                setSendNotice("Photo email sent to ${config.recipient}", true)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                EventLog.add("photo email failed: $reason")
                setSendNotice("Photo email failed: $reason", false)
            } finally {
                file.delete()
                handler.post { finishPhotoSend() }
            }
        }, "photo-email").start()
    }
    private fun videoEmailConfig(ext: String, capped: Boolean): SmtpMailer.Config? {
        val details = emailDetailsOrNull("video email") ?: return null
        // .ts parts are cut at keyframes and open with picture and sound on their own;
        // byte-split .mp4 parts only play after being rejoined into one file.
        val standalone = if (ext == "ts")
            "Every .ts part starts at its own keyframe, so each part - and any prefix of " +
                "parts - opens with both picture and sound on its own, even if later parts " +
                "are missing. "
        else ""
        val capNote = if (capped)
            "Only the first ${MAX_EMAIL_TOTAL_BYTES / 1024 / 1024} MB of the recording is emailed " +
                "(the app caps each recording's emailed data at this size); the remainder stays on " +
                "the phone. "
        else ""
        val body = "A video was recorded automatically by Security Services on the Volume Down " +
            "trigger.\nDevice: ${Build.MODEL}\nTime: ${timestamp()}\n" +
            "The video is attached in numbered parts of at most 15 MB " +
            "(video_<time>.part01-of-03.$ext, ...). " +
            standalone +
            capNote +
            "Rejoin the parts in part-number order into one file " +
            "(Windows: copy /b part01+part02+... video.$ext; " +
            "Android/Linux: cat part* > video.$ext)."
        return mailConfig(details, "Security Services video", body)
    }

    private fun emailVideoFile(file: File, stamp: String, ext: String, waitForInternet: Boolean) {
        val details = emailDetailsOrNull("video email") ?: run {
            setSendNotice("Video NOT emailed: the email settings are incomplete", false)
            return
        }
        val total = file.length()
        if (total <= 0L) {
            EventLog.add("video email skipped: the video is empty")
            setSendNotice("Video NOT emailed: the video file is empty", false)
            return
        }
        if (waitForInternet && !awaitInternetConnection()) {
            EventLog.add("video email skipped: no internet connection within 2 minutes")
            setSendNotice("Video NOT sent: no internet within 2 minutes", false)
            return
        }
        // The TOTAL data emailed for one video is capped at MAX_EMAIL_TOTAL_BYTES (500 MB): only
        // the first 500 MB of the recording is sent, the rest stays on the phone. For a .ts stream
        // the cap is pulled back to a 188-byte packet border so every emailed part stays aligned.
        var emailTotal = minOf(total, MAX_EMAIL_TOTAL_BYTES)
        if (ext == "ts") emailTotal = (emailTotal / TS_PACKET_BYTES) * TS_PACKET_BYTES
        val capped = emailTotal < total
        if (capped) EventLog.add(
            "video email capped at ${emailTotal / 1024 / 1024} MB of ${total / 1024 / 1024} MB"
        )
        val config = videoEmailConfig(ext, capped) ?: run {
            setSendNotice("Video NOT emailed: the email settings are incomplete", false)
            return
        }
        val mime = if (ext == "ts") "video/mp2t" else "video/mp4"
        val baseName = "video_$stamp"
        // Work out every cut before sending so the parts are numbered with their real total.
        // A .ts stream is cut on keyframe boundaries (TsSplit) so each part starts with its own
        // PAT/PMT tables and a keyframe - without that, later parts would play sound with no
        // picture. A stream TsSplit cannot parse - or a non-.ts file - falls back to plain cuts
        // (packet-aligned for .ts), which still keeps every part at or below 15 MB. Cuts past the
        // 500 MB email cap are dropped, so the last part ends on the cap.
        val cuts = ((if (ext == "ts") TsSplit.cuts(file, VIDEO_PART_BYTES) else null)
            ?: plainVideoCuts(emailTotal, ext == "ts")).filter { it < emailTotal }
        val totalParts = (cuts.size + 1).coerceAtLeast(1)
        var start = 0L
        var produced = 0
        var sent = 0
        for (end in cuts + emailTotal) {
            produced++
            val partFile = File.createTempFile("secvid", ".part", cacheDir)
            try {
                copyRange(file, start, end, partFile)
                val name = videoPartFileName(baseName, totalParts, produced, ext)
                SmtpMailer.send(config, partFile, name, mime)
                sent++
                EventLog.add("video email: part $produced/$totalParts sent")
            } catch (e: Exception) {
                EventLog.add("video email part $produced/$totalParts failed: ${e.message}")
            }
            try { partFile.delete() } catch (_: Exception) { }
            start = end
        }
        when {
            produced == 0 -> setSendNotice("Video NOT emailed: video could not be read", false)
            sent == 0 -> setSendNotice("Video email FAILED (0/$produced parts sent)", false)
            sent < produced -> setSendNotice(
                "Video email incomplete: $sent/$produced parts sent to ${details.recipient}",
                false
            )
            capped -> setSendNotice(
                "Video emailed to ${details.recipient} (first ${emailTotal / 1024 / 1024} MB, " +
                    "$produced part${if (produced > 1) "s" else ""})",
                true
            )
            else -> setSendNotice(
                "Video emailed to ${details.recipient} ($produced part${if (produced > 1) "s" else ""})",
                true
            )
        }
    }

    private fun copyRange(src: File, start: Long, end: Long, dest: File) {
        java.io.RandomAccessFile(src, "r").use { raf ->
            dest.outputStream().use { out ->
                raf.seek(start)
                var left = end - start
                val buf = ByteArray(256 * 1024)
                while (left > 0) {
                    val want = minOf(buf.size.toLong(), left).toInt()
                    val n = raf.read(buf, 0, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    /**
     * Plain interior cut points every [VIDEO_PART_BYTES] - on a 188-byte packet border for .ts
     * files - used when [TsSplit] cannot parse the stream (or the file is not a .ts).
     */
    private fun plainVideoCuts(total: Long, tsAligned: Boolean): List<Long> {
        val cuts = ArrayList<Long>()
        var start = 0L
        while (start + VIDEO_PART_BYTES < total) {
            var end = start + VIDEO_PART_BYTES
            if (tsAligned) end = (end / TS_PACKET_BYTES) * TS_PACKET_BYTES
            if (end <= start) break
            cuts.add(end)
            start = end
        }
        return cuts
    }

    private fun videoPartFileName(base: String, total: Int, part: Int, ext: String): String {
        val width = total.toString().length.coerceAtLeast(2)
        val num = part.toString().padStart(width, '0')
        val tot = total.toString().padStart(width, '0')
        return "$base.part$num-of-$tot.$ext"
    }


    // ------------------------------------------------------------------ recording email

    /**
     * Emails a finished voice recording to the same address, on the same SMTP account, as the photo.
     * The recording is split into numbered parts of at most [MAX_EMAIL_ATTACHMENT_BYTES] (16 MB) each
     * so every email stays under the provider's 25 MB limit. The TOTAL emailed for one recording is
     * also capped at [MAX_EMAIL_TOTAL_BYTES] (500 MB): once that much has been placed into parts the
     * remaining parts are not produced, so a long recording cannot flood the inbox. The recording is
     * AAC ADTS, so the parts can be concatenated in part-number order and played again, and the parts
     * that did arrive are still playable if a later part is missing or failed to send. Runs on a
     * background thread and only sends when the phone has internet inside the 2-minute window -
     * otherwise nothing is sent and the recording is never retried later.
     */
    private fun emailRecording(uri: Uri?, file: File?, baseName: String) {
        val details = emailDetailsOrNull("recording email") ?: run {
            setSendNotice("Recording NOT emailed: the email settings are incomplete", false)
            return
        }
        val total = recordingOutputSize(uri, file)
        if (total <= 0L) {
            EventLog.add("recording email skipped: the recording is empty")
            setSendNotice("Recording NOT emailed: the recording file is empty", false)
            return
        }
        if (!awaitInternetConnection()) {
            EventLog.add("recording email skipped: no internet connection within 2 minutes")
            setSendNotice("Recording NOT emailed: no internet within 2 minutes", false)
            return
        }

        EventLog.add("recording email: ${total / 1024} KB, split into parts of at most " +
            "${MAX_EMAIL_ATTACHMENT_BYTES / 1024 / 1024} MB, total emailed capped at " +
            "${MAX_EMAIL_TOTAL_BYTES / 1024 / 1024} MB")
        var sent = 0
        var produced = 0
        var exhausted = false
        var delivered = 0L // bytes already placed into parts, bounded by MAX_EMAIL_TOTAL_BYTES
        var input: InputStream? = null
        try {
            val stream = openRecordingInput(uri, file) ?: throw IOException("cannot open the recording file")
            input = stream
            val buffer = ByteArray(64 * 1024)
            val window = ByteArray(ADTS_ALIGN_WINDOW_BYTES)
            var carry = ByteArray(0)
            // Parts are produced until the stream ends or the 500 MB total email cap is reached. A
            // failing part is skipped (its bytes are simply missing from the joined file) but the
            // following parts are still sent, so a missing or failed part never stops the rest of
            // the recording from being delivered.
            while (!exhausted) {
                // Stop once the total already placed into parts has reached the cap. The last part
                // before that is shortened so the sum never exceeds MAX_EMAIL_TOTAL_BYTES.
                if (delivered >= MAX_EMAIL_TOTAL_BYTES) {
                    EventLog.add("recording email capped at ${MAX_EMAIL_TOTAL_BYTES / 1024 / 1024} MB")
                    break
                }
                val partCap = minOf(MAX_EMAIL_ATTACHMENT_BYTES, MAX_EMAIL_TOTAL_BYTES - delivered)
                val partFileName = recordingPartFileName(baseName, produced + 1)
                val partFile = File(cacheDir, partFileName)
                var written = 0L
                FileOutputStream(partFile).use { out ->
                    if (carry.isNotEmpty()) {
                        out.write(carry)
                        written += carry.size
                        carry = ByteArray(0)
                    }
                    while (written < partCap) {
                        val want = minOf(buffer.size.toLong(), partCap - written).toInt()
                        val read = stream.read(buffer, 0, want)
                        if (read <= 0) {
                            exhausted = true
                            break
                        }
                        out.write(buffer, 0, read)
                        written += read
                    }
                    if (!exhausted && written >= partCap) {
                        // End the part on an AAC frame boundary so every part is a valid .aac on its own.
                        // The look-ahead bytes that belong to the next part are carried over, never dropped.
                        val winLen = readFully(stream, window)
                        if (winLen <= 0) {
                            exhausted = true
                        } else {
                            val cut = adtsFrameStart(window, winLen)
                            val start = if (cut < 0) 0 else cut
                            if (start > 0) {
                                out.write(window, 0, start)
                                written += start
                            }
                            carry = window.copyOfRange(start, winLen)
                        }
                    }
                }
                if (written == 0L) {
                    partFile.delete()
                    break
                }
                produced++
                delivered += written
                val subject = "Security Services voice recording part $produced"
                val body = "Audio recorded automatically by Security Services on the Volume Down trigger.\n" +
                    "This is part $produced of the recording \"$baseName\" (about ${total / 1024} KB in total).\n" +
                    "Device: ${Build.MODEL}\nTime: ${timestamp()}\n" +
                    "Each part is raw AAC (.aac) audio. To replay the whole recording, save every part you " +
                    "received and concatenate them in part-number order into one .aac file (Windows example: " +
                    "copy /b ${baseName}.part01.aac+${baseName}.part02.aac+${baseName}.part03.aac ${baseName}.aac). " +
                    "AAC is a streaming format, so the parts that did arrive can still be concatenated and " +
                    "played even if a later part is missing or failed to be delivered - the audio then simply " +
                    "stops at the last part you received."
                try {
                    SmtpMailer.send(
                        mailConfig(details, subject, body),
                        partFile,
                        partFileName,
                        "application/octet-stream"
                    )
                    sent++
                    EventLog.add("recording email: part $produced sent")
                } catch (e: Exception) {
                    EventLog.add("recording email part $produced failed: ${e.message ?: e.javaClass.simpleName}")
                }
                partFile.delete()
            }
        } catch (e: Exception) {
            EventLog.add("recording email stopped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                input?.close()
            } catch (e: Exception) {
            }
        }

        when {
            produced == 0 -> setSendNotice("Recording NOT emailed: the recording could not be read", false)
            sent == 0 -> setSendNotice("Recording email FAILED (0/$produced parts sent)", false)
            sent < produced -> setSendNotice(
                "Recording email incomplete: $sent/$produced parts sent to ${details.recipient}",
                false
            )
            else -> {
                val capNote = if (delivered >= MAX_EMAIL_TOTAL_BYTES)
                    ", first ${MAX_EMAIL_TOTAL_BYTES / 1024 / 1024} MB only" else ""
                setSendNotice(
                    "Recording emailed to ${details.recipient} " +
                        "($produced part${if (produced > 1) "s" else ""}$capNote)",
                    true
                )
            }
        }
    }

    /**
     * The numbered, rejoinable file name of one recording part, e.g. voice_20250101_120000.part03.aac.
     * The .aac extension is kept so each part is recognised as audio and the rejoined file plays as-is.
     */
    private fun recordingPartFileName(baseName: String, part: Int): String =
        "${baseName}.part" + part.toString().padStart(2, '0') + ".aac"

    /** The recording's display name without its extension, used as the base of the part file names. */
    private fun recordingBaseName(uri: Uri?, file: File?): String {
        val name = file?.name ?: uri?.let { displayNameOf(it) } ?: "voice_recording"
        val base = name.substringBeforeLast('.')
        return if (base.isBlank()) "voice_recording" else base
    }

    private fun displayNameOf(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The byte size of the finished recording, whether it is a plain file or a content:// URI. */
    private fun recordingOutputSize(uri: Uri?, file: File?): Long {
        file?.let { return it.length() }
        val u = uri ?: return 0L
        return try {
            contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Opens the finished recording for reading, from either its content:// URI or its plain file. */
    private fun openRecordingInput(uri: Uri?, file: File?): InputStream? = try {
        if (uri != null) contentResolver.openInputStream(uri) else file?.inputStream()
    } catch (e: Exception) {
        null
    }

    /** Fills [buffer] from [input] (a stream may return fewer bytes than asked); returns bytes read. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Returns the index of the first AAC ADTS frame start in [data] (first [length] bytes), or -1 when
     * there is none. A frame starts with the 12-bit sync 0xFFF, layer 00, a valid sampling-frequency
     * index and a plausible frame length - the checks keep random audio bytes from looking like a frame.
     */
    private fun adtsFrameStart(data: ByteArray, length: Int): Int {
        var i = 0
        while (i + 7 <= length) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xF6) == 0xF0) {
                val frequencyIndex = (data[i + 2].toInt() and 0x3C) shr 2
                val frameLength = ((data[i + 3].toInt() and 0x03) shl 11) or
                    ((data[i + 4].toInt() and 0xFF) shl 3) or
                    ((data[i + 5].toInt() and 0xE0) shr 5)
                if (frequencyIndex < 13 && frameLength >= 7) return i
            }
            i++
        }
        return -1
    }

    // ---------------------------------------------------------------- internet check

    /** True when the phone currently has an internet-capable network (Wi-Fi or mobile data). */
    private fun hasInternetConnection(): Boolean {
        return try {
            val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
            val network = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Waits up to [INTERNET_WAIT_MS] (2 minutes) for an internet connection. Returns true as soon as
     * one is available, false when the whole window passed without internet. Blocks, so it must NOT
     * run on the main thread. A false result means the caller must give up - the app never resends the
     * file later, even if internet returns after the 2-minute window.
     */
    private fun awaitInternetConnection(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + INTERNET_WAIT_MS
        while (true) {
            if (hasInternetConnection()) {
                EventLog.add("internet connection available - sending")
                return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                EventLog.add("no internet connection within 2 minutes")
                return false
            }
            try {
                Thread.sleep(INTERNET_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** Stores the last email result so the app's main screen can show it as a notification banner. */
    private fun setSendNotice(message: String, ok: Boolean) {
        prefs.lastEmailNotice = message
        prefs.lastEmailNoticeOk = ok
        EventLog.add("NOTICE: $message")
    }

    /**
     * Stores the last GPS-location SMS result so the app's main screen can show it as a banner.
     * Used for the per-hold message limit, which the user otherwise only ever sees as a single
     * Troubleshooting line that every re-arm wipes; the banner stays until the next Volume Down hold.
     */
    private fun setSmsNotice(message: String, ok: Boolean) {
        prefs.lastSmsNotice = message
        prefs.lastSmsNoticeOk = ok
        EventLog.add("NOTICE: $message")
    }

    /**
     * Makes sure the running foreground service includes the camera type, which Android 11+ requires
     * before an app in the background may open the camera. Mirrors how the microphone type is added
     * in [startRecording]; a failure is only logged, the capture attempt itself still runs.
     */
    private fun bringCameraToForeground() {
        if (cameraForeground) return
        if (Build.VERSION.SDK_INT < 29) {
            cameraForeground = true
            return
        }
        try {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (recordingForeground) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, buildNotification(), types)
            cameraForeground = true
            EventLog.add("camera type added to the running service")
        } catch (e: Exception) {
            EventLog.add("could not add the camera type: ${e.message ?: e.javaClass.simpleName}")
        }
    }
    private fun bringMicToForegroundSafe() {
        if (Build.VERSION.SDK_INT < 29 || recordingForeground) return
        try {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (cameraForeground) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIF_ID, buildNotification(), types)
            recordingForeground = true
            EventLog.add("microphone type added to the running service")
        } catch (e: Exception) {
            EventLog.add("could not add the microphone type: ${e.message ?: e.javaClass.simpleName}")
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
        // This hold is accepted as a new trigger, so it starts with a full SMS allowance: clear the
        // counter the send loop below reads, and drop the banner about the previous hold (that banner
        // only ever reports on the hold being handled, and its limit no longer applies to this one).
        smsMessagesSentForTrigger = 0
        prefs.lastSmsNotice = ""
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
                val locationData = "My location: $mapsUrl " +
                    "(accuracy ${location.accuracy.roundToInt()}m)"
                val prefix = prefs.locationSmsPrefix.trim()
                val message = if (prefix.isEmpty()) locationData else "$prefix\n$locationData"
                prefs.lastLocationMessage = message
                EventLog.add("GPS DATA: ${location.latitude},${location.longitude}")
                EventLog.add("GPS SMS test data: $mapsUrl")
                val sms = SmsManager.getDefault()
                var sent = 0
                var blocked = 0
                numbers.forEach { number ->
                    val slot = synchronized(AlarmService::class.java) {
                        if (smsMessagesSentForTrigger >= MAX_SMS_MESSAGES_PER_TRIGGER) {
                            false
                        } else {
                            smsMessagesSentForTrigger++
                            true
                        }
                    }
                    if (!slot) {
                        blocked++
                        EventLog.add(
                            "GPS SMS maximum limit reached: " +
                                "$MAX_SMS_MESSAGES_PER_TRIGGER messages per Volume Down hold"
                        )
                        return@forEach
                    }
                    try {
                        sms.sendTextMessage(number, null, message, null, null)
                        sent++
                        EventLog.add("GPS SMS sent to $number")
                    } catch (e: Exception) {
                        EventLog.add("GPS SMS failed for $number: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                // The single log line above is easy to miss - every re-arm clears the log - so whenever
                // this hold hit the cap, also raise the red banner on the app's main screen announcing
                // that the maximum limit was reached. It is cleared at the start of the next Volume Down
                // hold (see above), which is exactly when this limit stops applying to the next send.
                if (blocked > 0) {
                    setSmsNotice(
                        "GPS SMS maximum limit reached: only $MAX_SMS_MESSAGES_PER_TRIGGER messages " +
                            "can be sent per Volume Down hold - $sent sent, $blocked number" +
                            (if (blocked > 1) "s were" else " was") +
                            " not sent. Hold Volume Down again for a new allowance.",
                        false
                    )
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

    private fun startFlashlightBlinking() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("flashlight skipped: camera permission is not granted")
            return
        }
        if (flashlightCameraId == null) {
            flashlightCameraId = try {
                cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            } catch (e: Exception) {
                EventLog.add("flashlight unavailable: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }
        if (flashlightCameraId == null) {
            EventLog.add("flashlight unavailable: no camera flash found")
            return
        }
        handler.removeCallbacks(flashlightBlink)
        handler.post(flashlightBlink)
        EventLog.add("flashlight blinking with alarm")
    }

    private fun setFlashlight(enabled: Boolean) {
        val id = flashlightCameraId ?: return
        try {
            cameraManager.setTorchMode(id, enabled)
            flashlightOn = enabled
        } catch (e: Exception) {
            EventLog.add("flashlight failed: ${e.message ?: e.javaClass.simpleName}")
            stopFlashlightBlinking()
        }
    }

    private fun stopFlashlightBlinking() {
        handler.removeCallbacks(flashlightBlink)
        if (flashlightOn) setFlashlight(false)
        flashlightOn = false
    }

    /**
     * Frees the camera for the combined alarm+photo action: stops the blink loop and switches the
     * torch off so [PhotoCapture] can open the camera. Called on the main thread just before the
     * capture starts.
     */
    private fun pauseFlashlightForPhoto() {
        handler.removeCallbacks(flashlightBlink)
        if (flashlightOn) setFlashlight(false)
    }

    /**
     * Restarts the alarm blink loop after the combined alarm+photo capture finished. Only resumes
     * while the alarm is still sounding, so stopping the sound mid-capture never restarts the flash.
     * Called on the camera callback thread (same thread [PhotoCapture] reports back on).
     */
    private fun resumeFlashlightAfterPhoto() {
        handler.post {
            if (player != null && prefs.volumeDownAction == Prefs.ACTION_ALARM) {
                handler.removeCallbacks(flashlightBlink)
                handler.post(flashlightBlink)
            }
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
     * The device-wide microphone mute is captured too, so a recording (which forces the mic
     * un-muted for maximum gain - see [ensureMicMaxed]) can return it to the user's setting.
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
        origMicMuted = try {
            audio.isMicrophoneMute()
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
            if (origRingMuted) 1 else 0,
            if (origMicMuted) 1 else 0
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
        // Older installs wrote 6 values (no mic-mute entry); current installs write 7.
        if (values.size != 6 && values.size != 7) return
        restoreStream(AudioManager.STREAM_ALARM, values[0], values[3] == 1)
        restoreStream(AudioManager.STREAM_MUSIC, values[1], values[4] == 1)
        restoreStream(AudioManager.STREAM_RING, values[2], values[5] == 1)
        if (values.size == 7) restoreMicMute(values[6] == 1)
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
     * using the volume. Also puts back the microphone mute the recording forced off, so the user's
     * setting is respected again once the capture is finished.
     */
    private fun restoreActionVolumes() {
        if (!actionVolumesSaved || actionRunning) return
        restoreStream(AudioManager.STREAM_ALARM, origAlarmVolume, origAlarmMuted)
        restoreStream(AudioManager.STREAM_MUSIC, origMusicVolume, origMusicMuted)
        restoreStream(AudioManager.STREAM_RING, origRingVolume, origRingMuted)
        // The guard below avoids touching the mic mute during a pure-alarm run (also muted then ==
        // muted now is the common case and restoreMicMute early-outs there anyway), while a just
        // finished recording always leaves muted==false and restores a previously-muted mic.
        restoreMicMute(origMicMuted)
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

    /** Puts back the device-wide microphone mute captured in [beginActionVolumes]. */
    private fun restoreMicMute(wasMuted: Boolean) {
        try {
            if (audio.isMicrophoneMute() != wasMuted) audio.setMicrophoneMute(wasMuted)
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
        val filming = videoCapture?.isRecording == true
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when {
                    playing -> "ALARM SOUNDING"
                    recording -> "RECORDING VOICE"
                    filming -> "RECORDING VIDEO"
                    pendingPhotoSends > 0 -> "SENDING PHOTO EMAIL"
                    else -> "Security Services ARMED"
                }
            )
            .setContentText(
                when {
                    playing -> "Unlock the phone and open the app to stop it"
                    recording -> "Open the app and tap STOP RECORDING to finish the file"
                    filming -> "Hold Volume Down again to stop, save and email the video"
                    pendingPhotoSends > 0 -> "Finishing the photo email - the service stops itself after"
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
            // The richest foreground type the phone can run with is media playback + microphone +
            // camera. A type is only accepted when its runtime permission (microphone / camera) is
            // granted, so the phone may refuse the whole call. Fall back step by step: the alarm
            // must keep working even before the microphone or camera dialog has been allowed.
            var started = try {
                startForeground(
                    NOTIF_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
                recordingForeground = true
                cameraForeground = true
                true
            } catch (e: Exception) {
                false
            }
            if (!started) {
                // Microphone type only (needed for voice recording while the screen is locked).
                started = try {
                    startForeground(
                        NOTIF_ID,
                        n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    recordingForeground = true
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!started) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else {
            startForeground(NOTIF_ID, n)
            // Before Android 10 there are no foreground-service types to declare.
            cameraForeground = true
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
