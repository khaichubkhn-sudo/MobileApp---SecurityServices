package com.example.securityservices

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Main settings and control screen for Security Services.
 *
 * @author Chu Quang Khai (Khai Chu)
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK = 100
        const val REQ_NOTIF = 101
        const val REQ_RECORDING_FOLDER = 102
        const val REQ_MICROPHONE = 103
        const val REQ_STORAGE = 104
        const val REQ_LOCATION_SMS = 105
        const val REQ_CALL_PHONE = 106
        const val REQ_CAMERA = 107
        const val REQ_VIDEO_PERMS = 108
        const val RED = 0xFFC62828.toInt()
        const val GREEN = 0xFF2E7D32.toInt()
        const val BLUE = 0xFF1565C0.toInt()
        const val GREY = 0xFF546E7A.toInt()
        /** Watchdog interval: re-check the current option's permissions this often. */
        const val PERM_POPUP_CHECK_MS = 2_000L
        /** Quiet period after the user taps "Later" before the popup may appear again. */
        const val PERM_POPUP_SNOOZE_MS = 30_000L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    /** How many times a dropped permission may be re-requested in one dialog flow (Wear OS quirk). */
    private var reRequestsRemaining = 0

    /** Bounds the automatic accessibility-settings popup waits per launch. */
    private var a11yPopupTries = 0

    /** True while the app waits for the microphone dialog before its first (re-)arm. */
    private var armAfterPermission = false

    /** Last recording state applied to the recording buttons (avoids re-styling on every tick). */
    private var recordingUiState: Boolean? = null

    private lateinit var statusView: TextView
    private lateinit var soundView: TextView
    private lateinit var recordingView: TextView
    private lateinit var micPermView: TextView
    private lateinit var volLabel: TextView
    private lateinit var playBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var startRecBtn: Button
    private lateinit var stopRecBtn: Button
    private lateinit var a11yView: TextView
    private lateinit var dndView: TextView
    private lateinit var logView: TextView
    private lateinit var emailConfigView: LinearLayout
    private lateinit var sendNoticeView: TextView
    /** Red banner for the GPS-location SMS result (e.g. the per-app-start message quota). */
    private lateinit var smsNoticeView: TextView
    /**
     * Red permission banner tied to the current Volume Down option (e.g. camera needed for the
     * 1st option's flash + photo). Tapping it re-requests the missing permission, or opens the
     * app's Settings page when the user permanently denied it.
     */
    private lateinit var permNoticeView: TextView

    /** Root of the normal settings UI — hidden until the app lock is passed. */
    private lateinit var mainContent: ScrollView
    /** Full-screen lock gate shown over everything until unlocked. */
    private lateinit var lockGate: LinearLayout
    private lateinit var lockTitle: TextView
    private lateinit var lockHint: TextView
    private lateinit var lockInput1: EditText
    private lateinit var lockInput2: EditText
    private lateinit var lockError: TextView
    private lateinit var lockPrimaryBtn: Button
    private lateinit var lockResetBtn: Button

    /** True once the lock gate was passed for this visible session. Reset when backgrounded. */
    private var lockUnlocked = false
    /** Set in onStop so onResume knows the screen was really left (vs a permission popup). */
    private var wasStopped = false
    /** True after onCreate finished building BOTH the main UI and the lock gate. */
    private var lockUiReady = false
    /** Defers permission dialogs / auto-arm / auto-settings until the user unlocks. */
    private var pendingStartupFlows = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    /** Repeats every 2s while unlocked: pops a reminder while the current option lacks permission. */
    private val permWatchdog = object : Runnable {
        override fun run() {
            maybeShowPermissionPopup()
            ui.postDelayed(this, PERM_POPUP_CHECK_MS)
        }
    }

    /** Currently shown permission popup, if any (never stack a second one on top). */
    private var permPopup: android.app.AlertDialog? = null
    /** When the popup was last dismissed via "Later" (used for the quiet period between popups). */
    private var permPopupSnoozedUntil = 0L

    // ------------------------------------------------------------------ UI construction

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Lock gate starts engaged: nothing below runs (no arm, no permission dialogs)
        // until applyLockState() unlocks after the password check.
        lockUnlocked = false
        pendingStartupFlows = true
        val frame = android.widget.FrameLayout(this)
        if (prefs.sendLocationOnVolumeDown && prefs.volumeDownAction != Prefs.ACTION_LOCATION) {
            prefs.volumeDownAction = Prefs.ACTION_LOCATION
        }
        prefs.sendLocationOnVolumeDown = prefs.volumeDownAction == Prefs.ACTION_LOCATION

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF2F2F2.toInt())
            addView(root)
        }
        mainContent = scroll
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        buildLockGate(frame)
        // Hide the window background flash of the settings until the gate decides.
        setContentView(frame)
        lockUiReady = true
        applyLockState()

        root.addView(tv("Security Services", 26f, true))

        // ---- status
        val statusCard = card()
        statusView = tv("", 20f, true)
        soundView = tv("", 14f)
        sendNoticeView = tv("", 14f, true)
        smsNoticeView = tv("", 14f, true)
        permNoticeView = tv("", 14f, true).apply {
            setTextColor(RED)
            visibility = android.view.View.GONE
            setOnClickListener { onPermissionBannerTap() }
        }
        statusCard.addView(statusView)
        statusCard.addView(soundView)
        statusCard.addView(permNoticeView)
        statusCard.addView(sendNoticeView)
        statusCard.addView(smsNoticeView)
        root.addView(statusCard)

        // ---- sound + volume
        val soundCard = card()
        soundCard.addView(tv("1. Sound and volume", 16f, true))
        soundCard.addView(button("Choose sound file…", BLUE) { pickSound() }.also { gap(it) })
        volLabel = tv("", 15f)
        gap(volLabel)
        soundCard.addView(volLabel)
        val seek = SeekBar(this).apply {
            max = 100
            min = 10
            progress = prefs.volumePct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.volumePct = p
                    updateVolLabel()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                // Apply the chosen volume to a fresh, always-armed service.
                override fun onStopTrackingTouch(sb: SeekBar?) { rearm() }
            })
        }
        soundCard.addView(seek)
        soundCard.addView(tv(
            "The alarm plays on the phone's ALARM volume channel at this level, re-applied several times per " +
                "second while it sounds. This level is only applied while the alarm is sounding or a voice " +
                "recording is running: the moment that action stops, the phone's volumes go back to the levels " +
                "they had before it started, and while nothing is running you can adjust the volume freely. " +
                "Use 100% for maximum.",
            12f, false, GREY
        ))
        soundCard.addView(Switch(this).apply {
            text = "Force phone speaker (ignore headphones / Bluetooth)"
            isChecked = prefs.forceSpeaker
            setOnCheckedChangeListener { _, c ->
                prefs.forceSpeaker = c
                rearm()
            }
            gap(this)
        })
        root.addView(soundCard)

        // ---- Volume Down action
        val actionCard = card()
        actionCard.addView(tv("2. Volume Down action", 16f, true))
        actionCard.addView(tv("Choose what starts after the Volume Down button is held for 1 second.", 14f))
        val actionGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val alarmOption = RadioButton(this@MainActivity).apply {
                text = "Play the alarm sound + capture one photo and email it"
                id = 1
            }
            val recordOption = RadioButton(this@MainActivity).apply {
                text = "Start microphone voice recording"
                id = 2
            }
            val locationOption = RadioButton(this@MainActivity).apply {
                text = "Send current GPS location by SMS"
                id = 3
            }
            val callOption = RadioButton(this@MainActivity).apply {
                text = "Call the first phone number"
                id = 4
            }
            val photoMailOption = RadioButton(this@MainActivity).apply {
                text = "Capture one photo and email it"
                id = 5
            }
            val videoMailOption = RadioButton(this@MainActivity).apply {
                text = "Capture video and email it (hold again to stop)"
                id = 6
            }
            addView(alarmOption)
            addView(recordOption)
            addView(locationOption)
            addView(callOption)
            addView(photoMailOption)
            addView(videoMailOption)
            check(
                when (prefs.volumeDownAction) {
                    Prefs.ACTION_RECORD -> 2
                    Prefs.ACTION_LOCATION -> 3
                    Prefs.ACTION_CALL -> 4
                    Prefs.ACTION_EMAIL_PHOTO -> 5
                    Prefs.ACTION_EMAIL_VIDEO -> 6
                    else -> 1
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                prefs.volumeDownAction = when (checkedId) {
                    2 -> Prefs.ACTION_RECORD
                    3 -> Prefs.ACTION_LOCATION
                    4 -> Prefs.ACTION_CALL
                    5 -> Prefs.ACTION_EMAIL_PHOTO
                    6 -> Prefs.ACTION_EMAIL_VIDEO
                    else -> Prefs.ACTION_ALARM
                }
                prefs.sendLocationOnVolumeDown = checkedId == 3
                // New option = new permission set: drop any stale popup/snooze and re-check in 2s.
                permPopupSnoozedUntil = 0L
                dismissPermissionPopup()
                ui.removeCallbacks(permWatchdog)
                ui.postDelayed(permWatchdog, PERM_POPUP_CHECK_MS)
                requestPermissionsForCurrentOption()
                updateEmailConfigVisibility()
                refresh()
                rearm()
            }
        }
        actionCard.addView(actionGroup)
        val locationNumbers = EditText(this).apply {
            hint = "Phone numbers (semicolon separated, up to 100 characters)"
            filters = arrayOf(android.text.InputFilter.LengthFilter(Prefs.MAX_PHONE_NUMBERS_LENGTH))
            setText(prefs.locationPhoneNumbers)
            // One line only: no newline key, and the text scrolls horizontally while it is longer
            // than the box (the single-line flag turns on the horizontal scroll itself).
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            maxLines = 1
            isSingleLine = true
            setHorizontallyScrolling(true)
            // Show a horizontal scrollbar whenever the text is wider than the box.
            isHorizontalScrollBarEnabled = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.locationPhoneNumbers = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        gap(locationNumbers)
        actionCard.addView(locationNumbers)
        val locationSmsPrefix = EditText(this).apply {
            hint = "SMS message content before GPS data"
            setText(prefs.locationSmsPrefix)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.locationSmsPrefix = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        gap(locationSmsPrefix)
        actionCard.addView(locationSmsPrefix)
        val locationSmsInterval = EditText(this).apply {
            hint = "Repeat GPS SMS every N minutes (0 = send once only)"
            setText(prefs.locationSmsIntervalMinutes.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val raw = s?.toString()?.trim() ?: ""
                    if (raw.isEmpty()) {
                        prefs.locationSmsIntervalMinutes = 0
                        return
                    }
                    raw.toIntOrNull()?.let { prefs.locationSmsIntervalMinutes = it.coerceAtLeast(0) }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        gap(locationSmsInterval)
        actionCard.addView(locationSmsInterval)
        actionCard.addView(tv(
            "The app waits for a valid location before sending one Google Maps link per number. " +
                "Location must be enabled on the phone; Android does not allow apps to silently switch it on.",
            12f, false, GREY
        ))
        // Microphone permission state (informational only: the permission is requested
        // automatically, so there is no button and no extra tap needed).
        micPermView = tv("", 14f, true)
        gap(micPermView)
        actionCard.addView(micPermView)
        recordingView = tv("", 14f)
        gap(recordingView)
        actionCard.addView(recordingView)
        actionCard.addView(recordButton("Choose recording folder", BLUE) { pickRecordingFolder() }.also { gap(it) })
        stopRecBtn = recordButton("STOP RECORDING", GREY) {
            val svc = AlarmService.instance
            val wasRecording = AlarmService.isRecording
            if (svc == null) {
                Toast.makeText(this, "Alarm service not running", Toast.LENGTH_LONG).show()
            } else {
                svc.stopRecording()
                // Guard against a stop racing a start: make sure the recorder really is gone.
                if (AlarmService.isRecording) {
                    ui.postDelayed({ svc.stopRecording() }, 300)
                }
                Toast.makeText(
                    this,
                    if (wasRecording) "Recording stopped and saved" else "No recording is running",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        }
        actionCard.addView(stopRecBtn.also { gap(it) })
        startRecBtn = recordButton("START RECORDING now (test)", GREEN) {
            val s = AlarmService.instance
            if (s == null) {
                Toast.makeText(this, "Alarm service is starting - try again in a moment", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Starting recording - check Troubleshooting for the result", Toast.LENGTH_LONG).show()
                s.startRecording()
            }
        }
        actionCard.addView(startRecBtn.also { gap(it) })
        actionCard.addView(tv(
            "Recordings are saved continuously as AAC audio (.aac). The default is the phone's Recordings/Security Services folder. " +
                "The app requests the microphone permission automatically when it opens - allow the dialog. If you grant " +
                "it while armed, the app re-arms the alarm itself. Android also shows a small foreground-service status " +
                "notification while the microphone is active (hidden on the lock screen).",
            12f, false, GREY
        ))
        // Photo/recording/video email settings. Always shown, so the recipient, SMTP account and
        // password can be set at any time (the recording and video actions reuse exactly the same
        // settings).
        emailConfigView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emailConfigView.addView(tv("Photo / recording / video email settings", 15f, true).also { gap(it) })

        val emailTo = EditText(this).apply {
            hint = "Send photo / recording to (up to 10 email addresses, separated by ;)"
            filters = arrayOf(android.text.InputFilter.LengthFilter(Prefs.MAX_EMAIL_RECIPIENTS_LENGTH))
            setText(prefs.emailPhotoRecipient)
            // Multi-line box so up to 10 addresses stay readable; typed text wraps instead
            // of scrolling horizontally. ';' is plain text, so keep a text (not email)
            // input type: the email-address variation would hide ';' on some keyboards.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 5
            isSingleLine = false
            setHorizontallyScrolling(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.emailPhotoRecipient = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        gap(emailTo)
        emailConfigView.addView(emailTo)

        val emailFrom = editField(
            "Your email address (SMTP account / sender)",
            prefs.emailPhotoSender,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        ) { prefs.emailPhotoSender = it }
        gap(emailFrom)
        emailConfigView.addView(emailFrom)

        val emailPassword = editField(
            "Email app password (not your normal password)",
            prefs.emailPhotoPassword,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        ) { prefs.emailPhotoPassword = it }
        gap(emailPassword)
        emailConfigView.addView(emailPassword)

        val emailHost = editField(
            "SMTP server (e.g. smtp.gmail.com)",
            prefs.emailPhotoSmtpHost,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        ) { prefs.emailPhotoSmtpHost = it }
        gap(emailHost)
        emailConfigView.addView(emailHost)

        val emailPort = editField(
            "SMTP port (465 for SSL/TLS, 587 for STARTTLS)",
            prefs.emailPhotoSmtpPort.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER
        ) { text -> text.trim().toIntOrNull()?.let { prefs.emailPhotoSmtpPort = it } }
        gap(emailPort)
        emailConfigView.addView(emailPort)

        emailConfigView.addView(Switch(this).apply {
            text = "Use STARTTLS (port 587). Off = SSL/TLS (port 465)"
            isChecked = prefs.emailPhotoStartTls
            setOnCheckedChangeListener { _, c -> prefs.emailPhotoStartTls = c }
            gap(this)
        })
        emailConfigView.addView(tv(
            "Emails are sent straight from the phone through your own email account, so no server " +
                "is needed. An app password is usually required: for Gmail, turn on 2-Step Verification, " +
                "then create an App password (Google Account > Security > App passwords) and paste it above. " +
                "Example: server smtp.gmail.com, port 465, STARTTLS off. The same account is used for both " +
                "the photo and for voice recordings. Grant the camera permission when Android asks. " +
                "The first Volume Down option (alarm sound + photo) and the photo-only option each take " +
                "one photo and send it per 1-second hold (at most one every 2 seconds); " +
                "when a voice recording finishes, it is emailed too. A recording (voice or video) is stopped " +
                "automatically once it reaches 1 GB. The recording is AAC (.aac), split into " +
                "numbered parts of at most 16 MB each so every email stays under the 25 MB limit email providers " +
                "enforce, and the total emailed for one recording is capped at 500 MB; concatenate the received " +
                "parts in number order to replay the whole recording, and the " +
                "parts you already have still play even if a later part is missing. The phone must have internet: " +
                "if no connection is available within 2 minutes " +
                "of the trigger, the photo or recording is not sent, and it is NOT sent later when internet returns.",
            12f, false, GREY
        ))
        gap(emailConfigView)
        actionCard.addView(emailConfigView)
        updateEmailConfigVisibility()
        root.addView(actionCard)

        // ---- alarm sound play
        val armCard = card()
        armCard.addView(tv("3. Alarm sound play", 16f, true))
        armCard.addView(tv("Test the chosen sound file, or stop it once sounding.", 14f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = button("▶  PLAY", RED) { playNow() }
        stopBtn = button("■  STOP", GREY) { AlarmService.instance?.stopSound() }
        row.addView(playBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        row.addView(stopBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        gap(row)
        armCard.addView(row)
        armCard.addView(tv(
            "The app is ALWAYS ARMED while it is open - there is no arm/disarm button. It starts armed when you " +
                "open it, re-arms after every settings change, and stays armed when you press Home / Back or lock the " +
                "phone. It only switches off if you swipe the app away in Recents / use \"Close all\", and it never " +
                "starts by itself when the phone boots. Stopping a sounding alarm needs the phone to be unlocked.",
            12f, false, GREY
        ))
        root.addView(armCard)

        // ---- Volume Down trigger
        val lockCard = card()
        lockCard.addView(tv("4. Volume Down trigger", 16f, true))
        lockCard.addView(tv(
            "While ARMED, press and hold the Volume Down button for 1 second. " +
                "The alarm sounds when it has been held for 1 second - locked, unlocked or with the screen off.",
            14f
        ))
        a11yView = tv("", 14f, true)
        gap(a11yView)
        lockCard.addView(a11yView)
        lockCard.addView(button("Open Accessibility settings", BLUE) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.also { gap(it) })
        lockCard.addView(tv(
            "Turn on \"Security Services Volume Down trigger\" there (Installed / Downloaded apps).",
            12f, false, GREY
        ))
        root.addView(lockCard)

        // ---- Do Not Disturb
        val dndCard = card()
        dndCard.addView(tv("5. Do Not Disturb (optional)", 16f, true))
        dndView = tv("", 14f)
        dndCard.addView(dndView)
        dndCard.addView(button("Allow override of Do Not Disturb", BLUE) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }.also { gap(it) })
        dndCard.addView(tv(
            "With this allowed, the alarm briefly switches Do Not Disturb off while it sounds, then restores it.",
            12f, false, GREY
        ))
        root.addView(dndCard)

        // ---- troubleshooting
        val logCard = card()
        logCard.addView(tv("Troubleshooting: what the trigger service saw", 14f, true))
        logView = tv("", 11f, false, GREY).apply { typeface = Typeface.MONOSPACE }
        logCard.addView(logView)
        root.addView(logCard)

        // Everything below needs the screen unlocked: permission dialogs, auto-arm and
        // auto-settings must never appear above the lock gate. runStartupFlows() executes
        // them once applyLockState() unlocks the gate.
        if (lockUnlocked) runStartupFlows() else pendingStartupFlows = true
    }

    override fun onResume() {
        super.onResume()
        // Re-engage the gate whenever the app screen is viewed again after being
        // backgrounded (Recents/Home/another app/screen off-on). Permission popups and
        // Settings pages only pause — they do not stop — so they never re-lock mid-flow.
        if (lockUiReady && wasStopped && prefs.lockEnrolled) {
            wasStopped = false
            lockUnlocked = false
            applyLockState()
        } else if (lockUiReady && !prefs.lockEnrolled && !lockUnlocked) {
            applyLockState()
        }
        if (lockUnlocked) ui.post(ticker)
        if (lockUnlocked) {
            ui.removeCallbacks(permWatchdog)
            ui.postDelayed(permWatchdog, PERM_POPUP_CHECK_MS)
        }
    }

    override fun onStop() {
        wasStopped = true
        super.onStop()
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(permWatchdog)
        dismissPermissionPopup()
        super.onPause()
    }

    /** Startup block from onCreate, deferred until after unlock. Runs once per process. */
    private fun runStartupFlows() {
        if (!pendingStartupFlows) return
        pendingStartupFlows = false
        requestNotificationPermission()
        ui.postDelayed({ if (lockUnlocked) requestRecordingPermissions() }, 400)
        if (AlarmService.instance == null && !AlarmService.isArmed) {
            if (missingRecordingPermissions().isEmpty()) {
                ui.postDelayed({ if (lockUnlocked) rearm() }, 600)
            } else {
                armAfterPermission = true
            }
        }
        ui.postDelayed({ if (lockUnlocked) autoOpenAccessibilitySettings() }, 900)
        ui.postDelayed({ if (lockUnlocked) requestPermissionsForCurrentOption() }, 700)
    }

    /**
     * Asks for whatever runtime permission the current Volume Down option needs, so switching
     * options (or reopening the app on option 1 with the camera still denied) always reminds the
     * user instead of silently running without flash/photo. No-op when nothing is missing.
     */
    private fun requestPermissionsForCurrentOption() {
        when (prefs.volumeDownAction) {
            Prefs.ACTION_ALARM -> requestCameraPermission()
            Prefs.ACTION_RECORD -> { /* requested at startup; banner covers the rest */ }
            Prefs.ACTION_LOCATION -> {
                requestLocationSmsPermissions()
                notifyIfGpsDisabled()
            }
            Prefs.ACTION_CALL -> requestCallPermission()
            Prefs.ACTION_EMAIL_PHOTO -> requestCameraPermission()
            Prefs.ACTION_EMAIL_VIDEO -> requestVideoPermissions()
            else -> requestCameraPermission()
        }
    }

    private fun buildLockGate(frame: android.widget.FrameLayout) {
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        lockGate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F2F2.toInt())
            setPadding(dp(24), dp(72), dp(24), dp(32))
        }
        lockTitle = tv("Security Services", 24f, true)
        lockHint = tv("", 14f, false, GREY)
        lockError = tv("", 14f, true, RED).apply { visibility = android.view.View.GONE }
        val passType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        lockInput1 = EditText(this).apply {
            hint = "App password"
            inputType = passType
        }
        lockInput2 = EditText(this).apply {
            hint = "Confirm app password"
            inputType = passType
        }
        lockPrimaryBtn = button("Unlock", BLUE) { onLockPrimary() }
        lockResetBtn = Button(this).apply {
            text = "Forgot password? Reset app (erases everything)"
            isAllCaps = false
            textSize = 13f
            setTextColor(RED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onLockReset() }
        }
        lockGate.addView(lockTitle)
        lockGate.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(10))
        })
        lockGate.addView(lockHint)
        lockGate.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(16))
        })
        lockGate.addView(lockInput1)
        lockGate.addView(lockInput2.also { gap(it) })
        lockGate.addView(lockError)
        lockGate.addView(lockPrimaryBtn.also { gap(it) })
        lockGate.addView(lockResetBtn)
        frame.addView(lockGate, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Setup mode (first run) vs unlock mode (every view after). Main GUI is GONE while locked. */
    private fun applyLockState() {
        if (!lockUiReady) return
        if (!prefs.lockEnrolled) {
            lockUnlocked = false
            lockTitle.text = "Set an app password"
            lockHint.text = "Choose a password to protect this app. " +
                "It is stored only as an encrypted hash on this phone. " +
                "If you forget it, the app must be reinstalled and ALL data is lost."
            lockInput1.hint = "New app password (min 4 characters)"
            lockInput1.text.clear()
            lockInput2.visibility = android.view.View.VISIBLE
            lockInput2.text.clear()
            lockError.visibility = android.view.View.GONE
            lockPrimaryBtn.text = "Set password"
            lockResetBtn.visibility = android.view.View.GONE
            mainContent.visibility = android.view.View.GONE
            lockGate.visibility = android.view.View.VISIBLE
            ui.removeCallbacks(ticker)
            return
        }
        if (lockUnlocked) {
            lockGate.visibility = android.view.View.GONE
            mainContent.visibility = android.view.View.VISIBLE
            ui.removeCallbacks(ticker)
            ui.post(ticker)
            ui.removeCallbacks(permWatchdog)
            ui.postDelayed(permWatchdog, PERM_POPUP_CHECK_MS)
            refresh()
            runStartupFlows()
        } else {
            lockTitle.text = "Security Services"
            lockHint.text = "Enter your app password to view the settings. " +
                "Forgot it? Only reinstalling the app resets it (all data is erased)."
            lockInput1.hint = "App password"
            lockInput1.text.clear()
            lockInput2.visibility = android.view.View.GONE
            lockInput2.text.clear()
            lockError.visibility = android.view.View.GONE
            lockPrimaryBtn.text = "Unlock"
            lockResetBtn.visibility = android.view.View.VISIBLE
            mainContent.visibility = android.view.View.GONE
            lockGate.visibility = android.view.View.VISIBLE
            ui.removeCallbacks(ticker)
            ui.removeCallbacks(permWatchdog)
            dismissPermissionPopup()
        }
    }

    private fun onLockPrimary() {
        if (!prefs.lockEnrolled) {
            val p1 = lockInput1.text.toString()
            val p2 = lockInput2.text.toString()
            if (p1.length < 4) {
                lockError.text = "Use at least 4 characters."
                lockError.visibility = android.view.View.VISIBLE
                return
            }
            if (p1 != p2) {
                lockError.text = "Passwords do not match."
                lockError.visibility = android.view.View.VISIBLE
                return
            }
            val salt = Crypto.newSalt()
            val hash = Crypto.hashPassword(p1, salt)
            if (salt.isEmpty() || hash.isEmpty()) {
                lockError.text = "Could not save the password. Try again."
                lockError.visibility = android.view.View.VISIBLE
                return
            }
            prefs.lockSalt = salt
            prefs.lockHash = hash
            lockInput1.text.clear()
            lockInput2.text.clear()
            lockUnlocked = true
            EventLog.add("app password set")
            Toast.makeText(this, "Password set. It cannot be recovered.", Toast.LENGTH_LONG).show()
            applyLockState()
        } else {
            val ok = Crypto.verifyPassword(lockInput1.text.toString(), prefs.lockSalt ?: "", prefs.lockHash ?: "")
            if (ok) {
                lockInput1.text.clear()
                lockUnlocked = true
                applyLockState()
            } else {
                lockError.text = "Wrong password."
                lockError.visibility = android.view.View.VISIBLE
                lockInput1.text.clear()
            }
        }
    }

    /** No recovery path: one-way hash. Double-tap wipes ALL settings and stops the service. */
    private fun onLockReset() {
        val btn = lockResetBtn
        if (btn.tag != "confirm") {
            btn.tag = "confirm"
            btn.text = "Tap again to ERASE everything and start over"
            Toast.makeText(this, "Erases all settings and passwords.", Toast.LENGTH_LONG).show()
            ui.postDelayed({
                if (btn.tag == "confirm") {
                    btn.tag = null
                    btn.text = "Forgot password? Reset app (erases everything)"
                }
            }, 5000)
            return
        }
        try { AlarmService.instance?.stopSound(false) } catch (e: Exception) { }
        try { AlarmService.instance?.stopRecording(false) } catch (e: Exception) { }
        try { AlarmService.instance?.disarm() } catch (e: Exception) { }
        stopService(Intent(this, AlarmService::class.java))
        prefs.clearAllForLockReset()
        EventLog.add("app reset from lock screen (forgotten password)")
        Toast.makeText(this, "All data erased. Set a new password.", Toast.LENGTH_LONG).show()
        lockUnlocked = false
        pendingStartupFlows = true
        applyLockState()
    }

    // ------------------------------------------------------------------ actions

    private fun rearm() {
        // Never arm while the lock gate is engaged (e.g. a picker result racing a relock).
        if (!lockUnlocked) {
            pendingStartupFlows = true
            return
        }
        // Never interrupt an active voice recording: restarting the armed service would
        // stop the recorder (and email an unfinished file). While recording, the service
        // is already armed with the correct foreground type, so just leave it alone; the
        // changed setting takes effect on the next arm. isRecording is a snapshot that can
        // lag a RECORD trigger racing this call (service is up, recorder not yet assigned),
        // so also bail out when a recording was very recently started - but only when the
        // service is actually alive. If the service died while the app was in the background
        // there is no recording to protect and the app must re-arm here.
        val alive = AlarmService.instance != null
        if (alive && (AlarmService.isRecording || AlarmService.recordStartedRecently())) {
            EventLog.add("re-arm skipped: recording in progress")
            refresh()
            return
        }
        AlarmService.instance?.disarm()
        ui.postDelayed({ doArm() }, 150)
    }

    private fun doArm() {
        // A pending arm from before a recording started must not restart the service
        // mid-capture (see rearm()) - unless the service is gone, in which case there is
        // nothing to protect and arming must proceed.
        if (AlarmService.instance != null &&
            (AlarmService.isRecording || AlarmService.recordStartedRecently())
        ) {
            EventLog.add("re-arm skipped: recording in progress")
            refresh()
            return
        }
        try {
            startForegroundService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_ARM))
        } catch (e: Exception) {
            // Can be thrown while a permission dialog is still up; retry shortly so arming sticks.
            EventLog.add("arm retry: ${e.message}")
            ui.postDelayed({ doArm() }, 1200)
        }
        ui.postDelayed({ refresh() }, 300)
    }

    /** Opens the accessibility settings if the Volume Down trigger service is currently off. */
    private fun autoOpenAccessibilitySettings() {
        if (isAccessibilityOn()) return
        // Don't cover the microphone permission dialog with the settings page: wait until the user
        // finished the dialog, but give up after a few tries so the app never nags forever.
        if (missingRecordingPermissions().isNotEmpty() && a11yPopupTries < 6) {
            a11yPopupTries++
            ui.postDelayed({ autoOpenAccessibilitySettings() }, 1500)
            return
        }
        EventLog.add("Volume Down trigger service is off - opening accessibility settings")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
    }

    private fun playNow() {
        startForegroundService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_PLAY))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun pickRecordingFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_RECORDING_FOLDER)
    }

    /** True when the runtime microphone permission needed for voice recording is granted. */
    private fun microphonePermissionsReady(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        // NOTE: FOREGROUND_SERVICE_MICROPHONE is a manifest-only (install-time) permission, NOT a
        // runtime permission: it can never be granted via a dialog and checkSelfPermission() on it
        // does not reflect user choice, so it must NOT be part of the "is everything granted" check.
        // Including it here made micMissing() always true -> the option-6 popup kept reappearing
        // even after the user granted Camera + Microphone.
        return true
    }

    /** Runtime permissions currently missing for voice recording, in the order they should be requested. */
    private fun missingRecordingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return missing
    }

    /**
     * Requests the runtime microphone permissions automatically, showing the Wear OS permission
     * dialog whenever a permission is missing.
     */
    private fun requestRecordingPermissions() {
        val missing = missingRecordingPermissions()
        if (missing.isEmpty()) {
            refresh()
            return
        }
        // Some Wear OS builds only show the FIRST permission of a bundle per dialog. onRequestPermissionsResult
        // below re-requests anything that is still missing after the dialog closes (bounded, in case
        // the user keeps dismissing it), so every needed permission eventually gets its own dialog.
        reRequestsRemaining = 2
        EventLog.add("requesting microphone permissions")
        requestPermissions(missing.toTypedArray(), REQ_MICROPHONE)
    }

    private fun requestLocationSmsPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.SEND_SMS)
            }
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_LOCATION_SMS)
    }

    private fun notifyIfGpsDisabled() {
        val locationManager = getSystemService(android.location.LocationManager::class.java)
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            Toast.makeText(
                this,
                "GPS is turned off. Enable Location/GPS before triggering location SMS.",
                Toast.LENGTH_LONG
            ).show()
            EventLog.add("GPS action selected while GPS is turned off")
        }
    }

    private fun requestCallPermission() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CALL_PHONE)
            }
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_CALL_PHONE)
    }

    /** Requests the camera permission (used by the alarm flashlight and the photo-email action). */
    private fun requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    /**
     * Requests Camera + Microphone together for option 6 (video). A single combined system
     * dialog avoids the old camera-then-mic sequence, where the 2s permission watchdog could
     * re-show the reminder popup between the two grants and look like an endless loop.
     * No-op when nothing is missing.
     */
    private fun requestVideoPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA)
        }
        missing.addAll(missingRecordingPermissions())
        if (missing.isEmpty()) return
        dismissPermissionPopup()
        requestPermissions(missing.toTypedArray(), REQ_VIDEO_PERMS)
        reRequestsRemaining = 2
        pendingStartupFlows = false
    }

    /**
     * Human-readable reminder for the permission the current Volume Down option still needs, or
     * null when everything it needs is already granted. Option 1 (alarm sound + photo) needs the
     * camera for both the flashing flashlight and the photo capture.
     */
    private fun missingPermissionReminder(): String? {
        fun cameraMissing() =
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        fun micMissing() = missingRecordingPermissions().isNotEmpty()
        fun locationSmsMissing(): Boolean {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) return true
            return checkSelfPermission(Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED
        }
        fun callMissing(): Boolean {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) !=
                PackageManager.PERMISSION_GRANTED
            ) return true
            return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
        }
        return when (prefs.volumeDownAction) {
            Prefs.ACTION_ALARM ->
                if (cameraMissing()) "Camera permission is OFF - tap here to enable it. " +
                    "The 1st option needs it for the flashing flashlight and the photo email."
                else null
            Prefs.ACTION_RECORD ->
                if (micMissing()) "Microphone permission is OFF - tap here to enable it. " +
                    "Voice recording needs it."
                else null
            Prefs.ACTION_LOCATION ->
                if (locationSmsMissing()) "Location/SMS permission is OFF - tap here to enable " +
                    "it. Location SMS needs GPS and SMS access (plus Location switched on)."
                else null
            Prefs.ACTION_CALL ->
                if (callMissing()) "Phone permission is OFF - tap here to enable it. " +
                    "Calling needs phone access."
                else null
            Prefs.ACTION_EMAIL_PHOTO ->
                if (cameraMissing()) "Camera permission is OFF - tap here to enable it. " +
                    "Photo email needs it."
                else null
            Prefs.ACTION_EMAIL_VIDEO ->
                if (cameraMissing() || micMissing()) "Camera/microphone permission is OFF - " +
                    "tap here to enable it. Video capture needs both."
                else null
            else -> null
        }
    }

    /**
     * Tapping the permission banner either re-shows the system dialog (first denial) or opens the
     * app's Settings page (permanent "Don't ask again" denial), requesting exactly what the current
     * Volume Down option needs.
     */
    private fun onPermissionBannerTap() {
        when (prefs.volumeDownAction) {
            Prefs.ACTION_ALARM, Prefs.ACTION_EMAIL_PHOTO -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    refresh()
                    return
                }
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                    !prefs.cameraPermissionDeniedBefore
                ) {
                    requestCameraPermission()
                } else {
                    openAppSettings()
                }
            }
            Prefs.ACTION_RECORD -> requestRecordingPermissions()
            Prefs.ACTION_LOCATION -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS) ||
                    !prefs.locationSmsPermissionDeniedBefore
                ) {
                    requestLocationSmsPermissions()
                } else {
                    openAppSettings()
                }
            }
            Prefs.ACTION_CALL -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CALL_PHONE) ||
                    !prefs.callPermissionDeniedBefore
                ) {
                    requestCallPermission()
                } else {
                    openAppSettings()
                }
            }
            Prefs.ACTION_EMAIL_VIDEO -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED ||
                    missingRecordingPermissions().isNotEmpty()
                ) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                        shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ||
                        !prefs.cameraPermissionDeniedBefore
                    ) {
                        requestVideoPermissions()
                    } else {
                        openAppSettings()
                    }
                    return
                }
            }
            else -> refresh()
        }
    }

    /** Opens this app's page in the system Settings so a permanently-denied permission can be flipped. */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            Toast.makeText(
                this,
                "Enable the permission in Settings, then return here",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            EventLog.add("open app settings failed: ${e.message}")
        }
    }

    /**
     * 2-second watchdog body: while the screen is unlocked and visible, re-check the current
     * Volume Down option's permissions and pop a reminder dialog when something it needs is still
     * disabled. Never stacks (one dialog at a time), never fires while locked/backgrounded, and
     * stays quiet for [PERM_POPUP_SNOOZE_MS] after the user taps "Later".
     */
    private fun maybeShowPermissionPopup() {
        if (!lockUnlocked) return
        if (!lockUiReady) return
        if (permPopup?.isShowing == true) return
        if (System.currentTimeMillis() < permPopupSnoozedUntil) return
        val reminder = missingPermissionReminder() ?: return
        EventLog.add("permission popup: $reminder")
        permPopup = android.app.AlertDialog.Builder(this)
            .setTitle("Permission needed")
            .setMessage(reminder)
            .setCancelable(false)
            .setPositiveButton("Enable") { d, _ ->
                d.dismiss()
                permPopup = null
                onPermissionBannerTap()
            }
            .setNegativeButton("Later") { d, _ ->
                d.dismiss()
                permPopup = null
                permPopupSnoozedUntil = System.currentTimeMillis() + PERM_POPUP_SNOOZE_MS
            }
            .setOnDismissListener { permPopup = null }
            .create()
        try {
            permPopup?.show()
        } catch (e: Exception) {
            EventLog.add("permission popup failed: ${e.message}")
            permPopup = null
        }
    }

    /** Dismisses the permission popup without snoozing (pause/lock/option fixed). */
    private fun dismissPermissionPopup() {
        try { permPopup?.dismiss() } catch (_: Exception) { }
        permPopup = null
    }

    private fun pickSound() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available on this phone", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // A picker must never complete while the gate is engaged: it would return to a
        // locked screen. Keep the URI safe (persisted below) and defer re-arm to unlock.
        if (requestCode == REQ_RECORDING_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            prefs.recordingTreeUri = uri.toString()
            refresh()
            rearm()
            return
        }
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            prefs.soundUri = uri.toString()
            prefs.soundName = displayName(uri)
            refresh()
            rearm()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (!lockUnlocked) {
            // A permission answered while the gate re-engaged: defer the arm until unlock.
            if (requestCode == REQ_MICROPHONE || requestCode == REQ_VIDEO_PERMS) armAfterPermission = true
            pendingStartupFlows = true
            return
        }
        if (requestCode == REQ_CALL_PHONE) {
            EventLog.add(
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    "phone call permission granted"
                } else {
                    "phone call permission denied - calls are unavailable"
                }
            )
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                prefs.callPermissionDeniedBefore = true
            }
            refresh()
            return
        }
        if (requestCode == REQ_LOCATION_SMS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                EventLog.add("location and SMS permissions granted")
            } else {
                EventLog.add("location/SMS permission denied - location messages are unavailable")
                prefs.locationSmsPermissionDeniedBefore = true
            }
            refresh()
            return
        }
        if (requestCode == REQ_CAMERA) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            EventLog.add(
                if (granted) {
                    "camera permission granted"
                } else {
                    "camera permission denied - flashlight and photo email are unavailable"
                }
            )
            if (!granted) prefs.cameraPermissionDeniedBefore = true
            // The armed service needs the camera foreground type to capture from a locked screen, so
            // re-arm once the permission is granted and a camera action is selected. OPTION 1
            // (alarm + photo) needs it too - that was the missing reminder/re-arm bug.
            if (granted && (prefs.volumeDownAction == Prefs.ACTION_ALARM ||
                    prefs.volumeDownAction == Prefs.ACTION_EMAIL_PHOTO ||
                    prefs.volumeDownAction == Prefs.ACTION_EMAIL_VIDEO)) {
                rearm()
            }
            if (granted && missingPermissionReminder() == null) {
                permPopupSnoozedUntil = 0L
                dismissPermissionPopup()
            }
            refresh()
            return
        }
        if (requestCode == REQ_VIDEO_PERMS) {
            // Combined Camera + Microphone answer for option 6 (video): log each outcome, mark
            // a denial so a permanently-denied permission routes to Settings next time, and only
            // re-request (bounded) the REMAINING runtime permissions - never FOREGROUND ones.
            val denied = permissions.indices.filter {
                grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED
            }.map { permissions[it] }
            if (denied.isEmpty()) {
                EventLog.add("camera and microphone permissions granted")
            } else {
                EventLog.add("video permission denied (${denied.joinToString()}) - video capture may be unavailable")
                prefs.cameraPermissionDeniedBefore = true
            }
            val stillMissing = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                stillMissing.add(Manifest.permission.CAMERA)
            }
            stillMissing.addAll(missingRecordingPermissions())
            if (stillMissing.isNotEmpty() && reRequestsRemaining > 0) {
                reRequestsRemaining--
                requestPermissions(stillMissing.toTypedArray(), REQ_VIDEO_PERMS)
                return
            }
            if (stillMissing.isEmpty()) {
                if (prefs.volumeDownAction == Prefs.ACTION_EMAIL_VIDEO) rearm()
                permPopupSnoozedUntil = 0L
                dismissPermissionPopup()
            }
            refresh()
            return
        }
        if (requestCode != REQ_MICROPHONE) return
        // Some Wear OS builds only grant the first permission of a bundle; request the rest again
        // (bounded) so each gets its own dialog instead of being silently dropped.
        val stillMissing = missingRecordingPermissions()
        if (stillMissing.isNotEmpty() && reRequestsRemaining > 0) {
            reRequestsRemaining--
            requestPermissions(stillMissing.toTypedArray(), REQ_MICROPHONE)
            return
        }
        if (stillMissing.isEmpty()) {
            EventLog.add("microphone permissions granted")
        } else {
            // The dialog was dismissed or denied: treat the access as granted anyway and let the OS
            // enforcement surface any failure at recording time (logged in Troubleshooting).
            EventLog.add("microphone permission dialog dismissed - voice recording may be unavailable")
        }
        // Always armed: (re)arm now that the dialog settled, so the service starts WITH the
        // microphone type. This also covers granting the permission while an older service was
        // armed without it - that service must be restarted, its foreground type cannot change.
        val needsMicType = AlarmService.isArmed && !AlarmService.hasMicrophoneForegroundType &&
            prefs.volumeDownAction == Prefs.ACTION_RECORD
        if (armAfterPermission || needsMicType) {
            armAfterPermission = false
            EventLog.add("arming the service with the microphone type")
            rearm()
        }
        refresh()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    // ------------------------------------------------------------------ state -> UI

    private fun refresh() {
        if (!lockUnlocked) return
        val armed = AlarmService.isArmed
        val playing = AlarmService.isPlaying

        when {
            playing -> { statusView.text = "🔊  ALARM SOUNDING"; statusView.setTextColor(RED) }
            AlarmService.isRecording -> { statusView.text = "●  RECORDING VOICE"; statusView.setTextColor(RED) }
            armed -> { statusView.text = "🛡  ARMED - ready"; statusView.setTextColor(GREEN) }
            else -> { statusView.text = "○  Not armed - reopen the app to re-arm"; statusView.setTextColor(GREY) }
        }
        soundView.text = "Sound: " + prefs.soundName.ifEmpty { "(none chosen - the phone's default alarm tone will play)" }
        // Permission banner: persistent reminder tied to the current Volume Down option, so a
        // denied camera (option 1 needs it) is never silent. Tapping re-asks or opens Settings.
        val permReminder = missingPermissionReminder()
        permNoticeView.visibility =
            if (permReminder == null) android.view.View.GONE else android.view.View.VISIBLE
        permNoticeView.text = if (permReminder == null) "" else "⚠  $permReminder"
        // Email notification banner: the result of the last photo / recording send, shown on this screen.
        val emailNotice = prefs.lastEmailNotice
        sendNoticeView.visibility = if (emailNotice.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        sendNoticeView.text = if (emailNotice.isEmpty()) "" else "Email notification: $emailNotice"
        sendNoticeView.setTextColor(if (prefs.lastEmailNoticeOk) GREEN else RED)
        // GPS-SMS banner: the result of the last Volume Down hold's location send. The service clears it
        // as soon as another hold starts, so it only ever reports on the send it belongs to.
        val smsNotice = prefs.lastSmsNotice
        smsNoticeView.visibility = if (smsNotice.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        smsNoticeView.text = if (smsNotice.isEmpty()) "" else "SMS notification: $smsNotice"
        smsNoticeView.setTextColor(if (prefs.lastSmsNoticeOk) GREEN else RED)
        recordingView.text = "Recording folder: " + if (prefs.recordingTreeUri == null) {
            "Recordings/Security Services (default)"
        } else {
            "Custom folder selected"
        }
        // No permission nagging here: the app asks for the microphone by itself, so only confirm it
        // once the permission is actually granted.
        val micReady = microphonePermissionsReady()
        micPermView.text = if (micReady) "Microphone permission: granted ✓" else ""
        micPermView.setTextColor(GREEN)
        micPermView.visibility = if (micReady) android.view.View.VISIBLE else android.view.View.GONE
        updateVolLabel()

        stopBtn.isEnabled = playing
        stopBtn.alpha = if (playing) 1f else 0.4f

        // Recording controls: highlight the action that applies right now and dim the opposite one.
        val recording = AlarmService.isRecording
        if (recordingUiState != recording) {
            recordingUiState = recording
            styleRecordButton(startRecBtn, active = !recording, color = GREEN)
            styleRecordButton(stopRecBtn, active = recording, color = RED)
        }

        a11yView.text = if (isAccessibilityOn()) "Trigger service: ON ✓" else "Trigger service: OFF - enable it below"
        a11yView.setTextColor(if (isAccessibilityOn()) GREEN else RED)

        val nm = getSystemService(NotificationManager::class.java)
        dndView.text = if (nm.isNotificationPolicyAccessGranted) "Override allowed ✓" else "Not allowed (alarm still sounds in normal Do Not Disturb)"

        logView.text = EventLog.dump()
        if (prefs.lastLocationMessage.isNotEmpty()) {
            logView.text = "Last GPS test data:\n${prefs.lastLocationMessage}\n\n${logView.text}"
        }
    }

    private fun updateVolLabel() {
        volLabel.text = "Alarm volume: ${prefs.volumePct}%"
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, AlarmAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: uri.lastPathSegment ?: "audio file"
    }

    // ------------------------------------------------------------------ view helpers

    /** A single-line input that writes every keystroke straight to the settings. */
    private fun editField(
        hintText: String,
        initialValue: String,
        fieldInputType: Int,
        onChange: (String) -> Unit
    ) = EditText(this).apply {
        hint = hintText
        inputType = fieldInputType
        setText(initialValue)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onChange(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /** The email settings stay visible at all times, so the recipient and SMTP account can be set any time. */
    private fun updateEmailConfigVisibility() {
        if (!::emailConfigView.isInitialized) return
        emailConfigView.visibility = android.view.View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun gap(v: android.view.View) {
        v.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
    }

    private fun tv(s: String, sp: Float, bold: Boolean = false, color: Int = 0xFF212121.toInt()) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
    }

    private fun setBg(b: Button, color: Int) {
        b.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        minHeight = dp(52)
        setBg(this, color)
        setOnClickListener { onClick() }
    }

    /**
     * The three voice-recording controls share one identical look - same font, same size and a
     * single-line height - so the recording section reads as one coherent group.
     */
    private fun recordButton(label: String, color: Int, onClick: () -> Unit) =
        button(label, color, onClick).apply {
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    /** Highlights a recording button while its action applies, and dims the opposite one. */
    private fun styleRecordButton(b: Button, active: Boolean, color: Int) {
        b.isEnabled = active
        b.alpha = if (active) 1f else 0.35f
        b.setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        setBg(b, if (active) color else GREY)
    }
}
