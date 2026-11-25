package id.vern.antigravity.util

import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private var logHandler: ((String, String, Boolean) -> Unit)? = null
    private var clearHandler: (() -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val TOKEN_ASSIGNMENT = Regex(
        """(?i)\b(authorization|bearer|csrf[_-]?token|x-codeium-csrf-token|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|secret|password|token)\b\s*[:=]\s*(?:"([^"]*)"|'([^']*)'|([^\s,;}\]]+))"""
    )
    private val BEARER_VALUE = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val OPAQUE_TOKEN = Regex("""\b[A-Za-z0-9_-]{32,}={0,2}\b""")

    /** Pure helper used for both on-disk and in-memory logs (and unit tested). */
    fun redact(message: String): String {
        var out = TOKEN_ASSIGNMENT.replace(message) { match -> "${match.groupValues[1]}=<redacted>" }
        out = BEARER_VALUE.replace(out) { "Bearer <redacted>" }
        out = OPAQUE_TOKEN.replace(out) { "<redacted>" }
        return out
    }

    fun setHandler(handler: (String, String, Boolean) -> Unit) {
        logHandler = handler
    }

    /** Optional hook so UI log lists can be cleared together with the file. */
    fun setClearHandler(handler: () -> Unit) {
        clearHandler = handler
    }

    fun d(tag: String, message: String) {
        log(tag, message, false)
    }

    fun e(tag: String, message: String) {
        log(tag, message, true)
    }

    fun logFile(): File = File(Environment.getExternalStorageDirectory(), "Antigravity/logs.txt")

    /** Truncates the persisted log file and notifies any registered UI clear handler. */
    @Synchronized
    fun clearLogs() {
        try {
            synchronized(lock) {
                val file = logFile()
                file.parentFile?.mkdirs()
                file.writeText("", Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            // Clearing is best-effort; a stale file must not crash the caller.
        }
        try {
            clearHandler?.invoke()
        } catch (e: Throwable) {
            // Ignore handler failures.
        }
    }

    fun log(tag: String, message: String, isError: Boolean = false) {
        val safeMessage = redact(message)

        try {
            logHandler?.invoke(tag, safeMessage, isError)
        } catch (e: Throwable) {}

        scope.launch {
            try {
                val logFile = logFile()
                logFile.parentFile?.mkdirs()
                val timeStr = timeFormat.format(Date())
                val line = "[$timeStr] [$tag] $safeMessage\n"
                synchronized(lock) {
                    logFile.appendText(line, Charsets.UTF_8)
                }
            } catch (e: Throwable) {
                // Logging must never break the caller.
            }
        }
    }
}
