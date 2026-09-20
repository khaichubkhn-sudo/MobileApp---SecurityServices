package com.example.securityalarm

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Lock-screen trigger.
 *
 * Android gives normal apps no way to hook the lock screen or the emergency dialer, so the only
 * supported route is an Accessibility Service. This one is deliberately minimal:
 *  - It does NOTHING unless the alarm is armed (AlarmService is running). Disarm / close the app
 *    and it becomes completely inert.
 *  - It only looks at the label of tapped buttons. It never reads screen content, never stores or logs
 *    any other screen content, and never sends anything anywhere.
 *
 * The Emergency button itself triggers the alarm while the alarm is armed.
 */
class AlarmAccessibilityService : AccessibilityService() {

    private enum class Kind { EMERGENCY, OTHER }

    private val prefs by lazy { Prefs(this) }
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

    // ------------------------------------------------------------------ clicks

    private fun onClick(e: AccessibilityEvent, alarm: AlarmService) {
        val labels = ArrayList<String>()
        e.text.forEach { it?.toString()?.trim()?.let { s -> if (s.isNotEmpty()) labels.add(s) } }
        e.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) labels.add(it) }
        collectNodeLabels(e.source, labels)
        if (labels.isEmpty()) return

        when (classify(labels)) {
            Kind.EMERGENCY -> {
                fire(alarm, "Emergency button")
            }
            Kind.OTHER -> {
                // Troubleshooting only: show non-digit button labels seen on the lock screen, so a
                // differently-named Emergency button can be identified.
                val pkg = e.packageName?.toString() ?: ""
                if ((emergencyScreenOpen || pkg.contains("systemui")) && !isDigitKey(labels[0])) {
                    EventLog.add("tapped \"${labels[0].take(30)}\" [${pkg.substringAfterLast('.')}]")
                }
            }
        }
    }

    private fun classify(labels: List<String>): Kind {
        val words = prefs.keywords.lowercase().split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (labels.any { l -> val low = l.lowercase(); words.any { low.contains(it) } }) return Kind.EMERGENCY

        return Kind.OTHER
    }

    private fun collectNodeLabels(node: android.view.accessibility.AccessibilityNodeInfo?, labels: MutableList<String>) {
        var current = node
        repeat(3) {
            if (current == null) return
            current.text?.toString()?.trim()?.let { if (it.isNotEmpty()) labels.add(it) }
            current.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) labels.add(it) }
            current.viewIdResourceName?.trim()?.let { if (it.isNotEmpty()) labels.add(it) }
            current = current.parent
        }
    }

    private fun isDigitKey(label: String): Boolean =
        label.isNotEmpty() && label.length <= 8 && (label[0].isDigit() || label[0] == '*' || label[0] == '#')

    private fun fire(alarm: AlarmService, why: String) {
        EventLog.add("TRIGGER: $why")
        if (!AlarmService.isPlaying) alarm.startAlarm()
    }
}
