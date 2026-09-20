package com.example.securityalarm

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Lock-screen trigger.
 *
 * Android gives normal apps no way to hook the lock screen or the emergency dialer, so the only
 * supported route is an Accessibility Service. This one is deliberately minimal:
 *  - It does NOTHING unless the alarm is armed (AlarmService is running). Disarm / close the app
 *    and it becomes completely inert.
 *  - It only looks at the label of tapped buttons. It never reads screen content, never stores or logs
 *    any digit other than "1", and never sends anything anywhere.
 *
 * Recognised sequences (the "1" must be the tap immediately before the second tap, within 6 s):
 *   A) lock-screen PIN pad:   tap "1"  ->  tap "Emergency call"
 *   B) emergency dialer:      tap "1"  ->  tap the green call/dial button
 */
class AlarmAccessibilityService : AccessibilityService() {

    private enum class Kind { ONE, EMERGENCY, CALL, OTHER }

    private val prefs by lazy { Prefs(this) }
    private var lastOne = 0L
    private var emergencyScreenOpen = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (AlarmService.isArmed) EventLog.add("trigger service connected")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val alarm = AlarmService.instance ?: return // not armed => completely inert
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindow(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onText(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event, alarm)
        }
    }

    // ------------------------------------------------------------------ window tracking

    private fun onWindow(e: AccessibilityEvent) {
        val blob = "${e.packageName} ${e.className} ${e.text.joinToString(" ")}"
        val isEmergency = blob.contains("emergency", ignoreCase = true)
        if (isEmergency != emergencyScreenOpen) {
            EventLog.add(
                if (isEmergency) "emergency screen opened (${e.className})" else "left emergency screen"
            )
        }
        emergencyScreenOpen = isEmergency
    }

    /** Emergency dialer: the number field changed. Only the exact text "1" is of interest. */
    private fun onText(e: AccessibilityEvent) {
        if (!emergencyScreenOpen) return
        val t = e.text.joinToString("").trim()
        if (t == "1") {
            lastOne = SystemClock.uptimeMillis()
            EventLog.add("dialer field shows \"1\"")
        } else {
            lastOne = 0
        }
    }

    // ------------------------------------------------------------------ clicks

    private fun onClick(e: AccessibilityEvent, alarm: AlarmService) {
        val labels = ArrayList<String>()
        e.text.forEach { it?.toString()?.trim()?.let { s -> if (s.isNotEmpty()) labels.add(s) } }
        e.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) labels.add(it) }
        if (labels.isEmpty()) {
            lastOne = 0
            return
        }

        val now = SystemClock.uptimeMillis()
        val recentOne = lastOne != 0L && now - lastOne <= 6000

        when (classify(labels)) {
            Kind.ONE -> {
                lastOne = now
                EventLog.add("tapped \"1\"")
            }
            Kind.EMERGENCY -> {
                if (recentOne) {
                    fire(alarm, "\"1\" then Emergency button")
                } else {
                    EventLog.add("Emergency button tapped (\"1\" was not tapped right before it)")
                }
                lastOne = 0
            }
            Kind.CALL -> {
                if (recentOne && prefs.dialerPath) {
                    fire(alarm, "\"1\" then call button in emergency dialer")
                } else {
                    EventLog.add("call button tapped (\"1\" was not tapped right before it)")
                }
                lastOne = 0
            }
            Kind.OTHER -> {
                lastOne = 0
                // Troubleshooting only: show non-digit button labels seen on the lock screen / emergency
                // dialer, so a differently-named Emergency button can be identified. Digits are never logged.
                val pkg = e.packageName?.toString() ?: ""
                if ((emergencyScreenOpen || pkg.contains("systemui")) && !isDigitKey(labels[0])) {
                    EventLog.add("tapped \"${labels[0].take(30)}\" [${pkg.substringAfterLast('.')}]")
                }
            }
        }
    }

    private fun classify(labels: List<String>): Kind {
        // "1" key: label is "1", or "1" followed by a non-digit (e.g. "1, voicemail"). Short labels only.
        if (labels.any { it.length <= 20 && ONE.containsMatchIn(it) }) return Kind.ONE

        val words = prefs.keywords.lowercase().split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (labels.any { l -> val low = l.lowercase(); words.any { low.contains(it) } }) return Kind.EMERGENCY

        if (emergencyScreenOpen &&
            labels.any { val low = it.lowercase(); low.contains("dial") || low.contains("call") }
        ) return Kind.CALL

        return Kind.OTHER
    }

    private fun isDigitKey(label: String): Boolean =
        label.isNotEmpty() && label.length <= 8 && (label[0].isDigit() || label[0] == '*' || label[0] == '#')

    private fun fire(alarm: AlarmService, why: String) {
        EventLog.add("TRIGGER: $why")
        lastOne = 0
        if (!AlarmService.isPlaying) alarm.startAlarm()
    }

    private companion object {
        val ONE = Regex("^1(?!\\d)")
    }
}
