package com.example.securityalarm

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AlarmAccessibilityService : AccessibilityService() {

    private var volumePressCount = 0
    private var firstVolumePressAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (AlarmService.isArmed) {
            EventLog.add("trigger service connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // This service does not process accessibility events.
    }

    override fun onInterrupt() {
        // No interruption-specific cleanup is required.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return false
        }

        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        if (AlarmService.instance == null) {
            resetVolumeSequence()
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val direction = if (key == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"

        // Any consecutive Volume Up/Down press counts (mixing the two buttons is fine),
        // as long as the whole sequence happens inside a 10-second window.
        if (volumePressCount == 0 || now - firstVolumePressAt > WINDOW_MS) {
            volumePressCount = 1
            firstVolumePressAt = now
        } else {
            volumePressCount++
        }

        EventLog.add("volume $direction press $volumePressCount/$REQUIRED_PRESSES")

        if (volumePressCount >= REQUIRED_PRESSES) {
            AlarmService.instance?.let { alarm ->
                fire(alarm, "Volume buttons pressed $REQUIRED_PRESSES times within 10s")
            }

            resetVolumeSequence()
        }

        return false
    }

    private fun resetVolumeSequence() {
        volumePressCount = 0
        firstVolumePressAt = 0L
    }

    private fun fire(alarm: AlarmService, why: String) {
        EventLog.add("TRIGGER: $why")

        if (!AlarmService.isPlaying) {
            alarm.startAlarm()
        }
    }

    private companion object {
        const val REQUIRED_PRESSES = 3
        const val WINDOW_MS = 10_000L
    }
}