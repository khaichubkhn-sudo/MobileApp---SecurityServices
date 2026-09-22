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
        const val RED = 0xFFC62828.toInt()
        const val GREEN = 0xFF2E7D32.toInt()
        const val BLUE = 0xFF1565C0.toInt()
        const val GREY = 0xFF546E7A.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Permission name as declared in AndroidManifest.xml (kept as a literal for compile safety). */
        const val PERMISSION_FOREGROUND_MICROPHONE = "android.permission.FOREGROUND_SERVICE_MICROPHONE"
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

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ UI construction

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!AlarmService.isArmed) AlarmService.resetSmsQuotaForAppStart()
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
        setContentView(scroll)

        root.addView(tv("Security Services", 26f, true))

        // ---- status
        val statusCard = card()
        statusView = tv("", 20f, true)
        soundView = tv("", 14f)
        statusCard.addView(statusView)
        statusCard.addView(soundView)
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
        actionCard.addView(tv("Choose what starts after the Volume Down button is held for 2 seconds.", 14f))
        val actionGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val alarmOption = RadioButton(this@MainActivity).apply {
                text = "Play the alarm sound"
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
            addView(alarmOption)
            addView(recordOption)
            addView(locationOption)
            addView(callOption)
            check(
                when (prefs.volumeDownAction) {
                    Prefs.ACTION_RECORD -> 2
                    Prefs.ACTION_LOCATION -> 3
                    Prefs.ACTION_CALL -> 4
                    else -> 1
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                prefs.volumeDownAction = when (checkedId) {
                    2 -> Prefs.ACTION_RECORD
                    3 -> Prefs.ACTION_LOCATION
                    4 -> Prefs.ACTION_CALL
                    else -> Prefs.ACTION_ALARM
                }
                prefs.sendLocationOnVolumeDown = checkedId == 3
                if (checkedId == 2) requestRecordingPermissions()
                if (checkedId == 3) {
                    requestLocationSmsPermissions()
                    notifyIfGpsDisabled()
                }
                if (checkedId == 4) requestCallPermission()
                if (checkedId == 1) requestFlashlightPermission()
                refresh()
                rearm()
            }
        }
        actionCard.addView(actionGroup)
        val locationNumbers = EditText(this).apply {
            hint = "Phone numbers (semicolon separated)"
            filters = arrayOf(android.text.InputFilter.LengthFilter(50))
            setText(prefs.locationPhoneNumbers)
            inputType = android.text.InputType.TYPE_CLASS_PHONE or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
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
            "Recordings are saved continuously as M4A audio. The default is the phone's Recordings/Security Services folder. " +
                "The app requests the microphone permission automatically when it opens - allow the dialog. If you grant " +
                "it while armed, the app re-arms the alarm itself. Android also shows a small foreground-service status " +
                "notification while the microphone is active (hidden on the lock screen).",
            12f, false, GREY
        ))
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
            "While ARMED, press and hold the Volume Down button for 2 seconds. " +
                "The alarm sounds when it has been held for 2 seconds - locked, unlocked or with the screen off.",
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

        requestNotificationPermission()
        // Automatically request the microphone permissions on every launch (no button, no extra
        // taps): the alarm's foreground service includes the microphone type whenever the
        // permission is available. Delayed slightly so a pending notification-permission dialog
        // does not swallow it on first run.
        ui.postDelayed({ requestRecordingPermissions() }, 400)
        // Always armed. Arm only after the microphone dialog settles (see onRequestPermissionsResult)
        // so the very first arm already includes the microphone type - without it the phone refuses
        // to record from the locked screen. When nothing is missing, arm straight away.
        if (missingRecordingPermissions().isEmpty()) {
            ui.postDelayed({ rearm() }, 600)
        } else {
            armAfterPermission = true
        }
        // If the Volume Down trigger service is off, open its settings page when the app starts.
        ui.postDelayed({ autoOpenAccessibilitySettings() }, 900)
        if (prefs.volumeDownAction == Prefs.ACTION_CALL) {
            ui.postDelayed({ requestCallPermission() }, 700)
        }
        if (prefs.volumeDownAction == Prefs.ACTION_ALARM) {
            ui.postDelayed({ requestFlashlightPermission() }, 700)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ actions

    private fun rearm() {
        AlarmService.instance?.disarm()
        ui.postDelayed({ doArm() }, 150)
    }

    private fun doArm() {
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

    /** True when the runtime microphone permissions needed for voice recording are granted. */
    private fun microphonePermissionsReady(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(PERMISSION_FOREGROUND_MICROPHONE) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    /** Permissions currently missing for voice recording, in the order they should be requested. */
    private fun missingRecordingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        // Without this, starting the foreground service with the microphone type fails on
        // Android 11+ (and is mandatory for targetSdk 34): "Starting FGS with type microphone
        // ... requires permissions". Declaring it in the manifest is not enough on modern
        // Android; the user must also grant the permission at runtime.
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(PERMISSION_FOREGROUND_MICROPHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(PERMISSION_FOREGROUND_MICROPHONE)
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

    private fun requestFlashlightPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
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
        if (requestCode == REQ_CALL_PHONE) {
            EventLog.add(
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    "phone call permission granted"
                } else {
                    "phone call permission denied - calls are unavailable"
                }
            )
            refresh()
            return
        }
        if (requestCode == REQ_LOCATION_SMS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                EventLog.add("location and SMS permissions granted")
            } else {
                EventLog.add("location/SMS permission denied - location messages are unavailable")
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
        val armed = AlarmService.isArmed
        val playing = AlarmService.isPlaying

        when {
            playing -> { statusView.text = "🔊  ALARM SOUNDING"; statusView.setTextColor(RED) }
            AlarmService.isRecording -> { statusView.text = "●  RECORDING VOICE"; statusView.setTextColor(RED) }
            armed -> { statusView.text = "🛡  ARMED - ready"; statusView.setTextColor(GREEN) }
            else -> { statusView.text = "○  Not armed - reopen the app to re-arm"; statusView.setTextColor(GREY) }
        }
        soundView.text = "Sound: " + prefs.soundName.ifEmpty { "(none chosen - the phone's default alarm tone will play)" }
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
