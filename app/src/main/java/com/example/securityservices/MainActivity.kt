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

class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK = 100
        const val REQ_NOTIF = 101
        const val REQ_RECORDING_FOLDER = 102
        const val REQ_MICROPHONE = 103
        const val REQ_STORAGE = 104
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

    private lateinit var statusView: TextView
    private lateinit var soundView: TextView
    private lateinit var recordingView: TextView
    private lateinit var volLabel: TextView
    private lateinit var armBtn: Button
    private lateinit var playBtn: Button
    private lateinit var stopBtn: Button
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
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        soundCard.addView(seek)
        soundCard.addView(tv(
            "The alarm plays on the phone's ALARM volume channel at this level, re-applied several times per " +
                "second while it sounds. While ARMED this chosen volume is applied right away (even before the " +
                "alarm plays) and the media volume is kept above zero so the Volume Down trigger always " +
                "works. Your original volumes are restored when the app closes. Use 100% for maximum.",
            12f, false, GREY
        ))
        soundCard.addView(Switch(this).apply {
            text = "Force phone speaker (ignore headphones / Bluetooth)"
            isChecked = prefs.forceSpeaker
            setOnCheckedChangeListener { _, c -> prefs.forceSpeaker = c }
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
            addView(alarmOption)
            addView(recordOption)
            check(if (prefs.volumeDownAction == Prefs.ACTION_RECORD) 2 else 1)
            setOnCheckedChangeListener { _, checkedId ->
                prefs.volumeDownAction = if (checkedId == 2) Prefs.ACTION_RECORD else Prefs.ACTION_ALARM
                if (checkedId == 2) requestRecordingPermissions()
                refresh()
            }
        }
        actionCard.addView(actionGroup)
        recordingView = tv("", 14f)
        gap(recordingView)
        actionCard.addView(recordingView)
        actionCard.addView(button("Choose recording folder", BLUE) { pickRecordingFolder() }.also { gap(it) })
        actionCard.addView(button("STOP RECORDING", GREY) { AlarmService.instance?.stopRecording() }.also { gap(it) })
        actionCard.addView(tv(
            "Recordings are saved continuously as M4A audio. The default is the phone's Music/Security Services folder. " +
                "Android requires a small foreground-service status notification while the microphone is active; it is hidden on the lock screen.",
            12f, false, GREY
        ))
        root.addView(actionCard)

        // ---- arm / play
        val armCard = card()
        armCard.addView(tv("3. Arm and play", 16f, true))
        armBtn = button("ARM ALARM", GREEN) { toggleArm() }
        gap(armBtn)
        armCard.addView(armBtn)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = button("▶  PLAY", RED) { playNow() }
        stopBtn = button("■  STOP", GREY) { AlarmService.instance?.stopSound() }
        row.addView(playBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        row.addView(stopBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        gap(row)
        armCard.addView(row)
        armCard.addView(tv(
            "ARMED stays on when you press Home / Back or lock the phone. It switches OFF when you press DISARM, " +
                "swipe the app away in Recents, or use \"Close all\". It never starts by itself when the phone boots. " +
                "Stopping a sounding alarm needs the phone to be unlocked.",
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
        if (prefs.volumeDownAction == Prefs.ACTION_RECORD) requestRecordingPermissions()
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

    private fun toggleArm() {
        val svc = AlarmService.instance
        if (svc != null) {
            svc.disarm()
        } else {
            startForegroundService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_ARM))
        }
        ui.postDelayed({ refresh() }, 300)
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

    private fun requestRecordingPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        // Without this, starting the foreground service with the microphone type fails on
        // Android 11+ (and is mandatory for targetSdk 34): "Starting FGS with type microphone
        // ... requires permissions". Declaring it in the manifest is not enough on modern
        // Android; the user must also grant the permission at runtime.
        if (checkSelfPermission(PERMISSION_FOREGROUND_MICROPHONE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PERMISSION_FOREGROUND_MICROPHONE)
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_MICROPHONE)
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
        }
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
            else -> { statusView.text = "○  Not armed"; statusView.setTextColor(GREY) }
        }
        soundView.text = "Sound: " + prefs.soundName.ifEmpty { "(none chosen - the phone's default alarm tone will play)" }
        recordingView.text = "Recording folder: " + if (prefs.recordingTreeUri == null) {
            "Music/Security Services (default)"
        } else {
            "Custom folder selected"
        }
        updateVolLabel()

        armBtn.text = if (armed) "DISARM" else "ARM ALARM"
        setBg(armBtn, if (armed) GREY else GREEN)
        stopBtn.isEnabled = playing
        stopBtn.alpha = if (playing) 1f else 0.4f

        a11yView.text = if (isAccessibilityOn()) "Trigger service: ON ✓" else "Trigger service: OFF - enable it below"
        a11yView.setTextColor(if (isAccessibilityOn()) GREEN else RED)

        val nm = getSystemService(NotificationManager::class.java)
        dndView.text = if (nm.isNotificationPolicyAccessGranted) "Override allowed ✓" else "Not allowed (alarm still sounds in normal Do Not Disturb)"

        logView.text = EventLog.dump()
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
}
