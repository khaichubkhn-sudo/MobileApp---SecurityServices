package com.example.securityalarm

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Volume Down trigger: while the alarm is ARMED, holding the Volume Down button for [HOLD_MS]
 * fires the alarm regardless of the screen state (locked, unlocked or off). Volume Up is ignored.
 */
class AlarmAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** True once a held Volume Down has reached the required time (used for accurate logging). */
    private var holdFired = false

    /** Fires the alarm once Volume Down has been held for the required time. */
    private val holdTrigger = object : Runnable {
        override fun run() {
            holdFired = true
            AlarmService.instance?.let { alarm ->
                fire(alarm, "Volume Down held ${HOLD_MS / 1000}s")
            }
        }
    }

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

    override fun onDestroy() {
        cancelHold()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only the Volume Down button can trigger the alarm.
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // The alarm must be armed for anything to happen.
        if (AlarmService.instance == null) {
            cancelHold()
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // First press of this hold. If the alarm is already sounding there is nothing
                    // to do; otherwise start the 2-second hold timer.
                    if (AlarmService.isPlaying) {
                        cancelHold()
                        holdFired = false
                        return false
                    }
                    holdFired = false
                    EventLog.add("volume down hold started")
                    handler.removeCallbacks(holdTrigger)
                    handler.postDelayed(holdTrigger, HOLD_MS)
                }
                // repeatCount > 0: the user is still holding; the timer keeps running.
            }

            KeyEvent.ACTION_UP -> {
                // Released: cancel the pending trigger and log whether the hold was long enough.
                cancelHold()
                if (holdFired) {
                    holdFired = false
                    EventLog.add("volume down released")
                } else {
                    EventLog.add("volume down released before ${HOLD_MS / 1000}s hold")
                }
            }
        }

        // Never consume the event, so the system still adjusts the volume normally.
        return false
    }

    private fun cancelHold() {
        handler.removeCallbacks(holdTrigger)
    }

    private fun fire(alarm: AlarmService, why: String) {
        EventLog.add("TRIGGER: $why")

        if (!AlarmService.isPlaying) {
            alarm.startAlarm()
        }
    }

    private companion object {
        const val HOLD_MS = 2_000L
    }
}
