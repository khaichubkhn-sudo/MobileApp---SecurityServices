package com.example.securityalarm

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AlarmAccessibilityService : AccessibilityService() {

    private var lastVolumeKey = 0
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

        if (AlarmService.instance == null) {
            resetVolumeSequence()
            return false
        }

        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> event.keyCode

            else -> return false
        }

        val now = SystemClock.elapsedRealtime()

        if (
            key != lastVolumeKey ||
            firstVolumePressAt == 0L ||
            now - firstVolumePressAt > WINDOW_MS
        ) {
            lastVolumeKey = key
            volumePressCount = 1
            firstVolumePressAt = now
        } else {
            volumePressCount++
        }

        EventLog.add(
            "volume ${
                if (key == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
            } press $volumePressCount/3"
        )

        if (volumePressCount >= REQUIRED_PRESSES) {
            AlarmService.instance?.let { alarm ->
                fire(
                    alarm,
                    if (key == KeyEvent.KEYCODE_VOLUME_UP) {
                        "Volume Up pressed 3 times"
                    } else {
                        "Volume Down pressed 3 times"
                    }
                )
            }

            resetVolumeSequence()
        }

        return false
    }

    private fun resetVolumeSequence() {
        lastVolumeKey = 0
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