package com.example.securityalarm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory troubleshooting log (never written to disk, cleared when the alarm is disarmed).
 * It only ever receives entries about volume-key presses and service start/stop.
 */
object EventLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addFirst(fmt.format(Date()) + "  " + message)
        while (lines.size > 40) lines.removeLast()
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "(nothing yet)" else lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
