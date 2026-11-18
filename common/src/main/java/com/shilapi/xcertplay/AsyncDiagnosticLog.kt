package com.shilapi.xcertplay

import com.shilapi.xcertplay.compat.RuntimeDiagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String)
    private val writer = if (RuntimeDiagnostics.enabled()) {
        BoundedDiagnosticWriter<Entry> { it.target.append(it.line) }
    } else null

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (!RuntimeDiagnostics.enabled()) return

        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        runCatching { writer?.enqueue(Entry(target, "$timestamp  $safe")) }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer?.awaitIdle(timeoutMillis) ?: true

    /** Keep every window field within the existing 700-character file limit. Logcat retains
     * the original line; file parts repeat stream/window identity for reconnect correlation.
     * Only fixed metadata windows are split, never free-form errors or protocol data.
     */
    fun appendAac(target: SessionLogFile?, message: String) {
        if (!RuntimeDiagnostics.enabled()) return

        if (target == null) return
        val marker = " event=window "
        val at = message.indexOf(marker)
        if (!message.startsWith("AAC: stream=") || at < 0) {
            append(target, message)
            return
        }
        val fields = message.substring(at + marker.length).split(' ')
        val window = fields.firstOrNull { it.startsWith("window=") } ?: "window=unknown"
        val prefix = message.substring(0, at) + marker + window
        val parts = mutableListOf<String>()
        var part = StringBuilder()
        for (field in fields) {
            if (field == window) continue
            if (part.isNotEmpty() && part.length + field.length + 1 > 480) {
                parts.add(part.toString())
                part = StringBuilder()
            }
            if (part.isNotEmpty()) part.append(' ')
            part.append(field)
        }
        if (part.isNotEmpty()) parts.add(part.toString())
        parts.forEachIndexed { index, body ->
            append(target, "$prefix part=${index + 1}/${parts.size} $body")
        }
    }
}
