package id.vern.antigravity.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import id.vern.antigravity.R
import id.vern.antigravity.executor.EngineExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TerminalLine(
    val id: Long = System.nanoTime(),
    val text: String,
    val isError: Boolean = false,
    val isCommand: Boolean = false
)

object TerminalSessionManager {
    const val AGY_SESSION_NAME = "AGY Live"
    val lines = mutableStateListOf<TerminalLine>()
    val history = mutableStateListOf<String>()
    val historyIndex = mutableIntStateOf(-1)
    val commandInput = mutableStateOf("")
    val isRunning = mutableStateOf(false)
    val sessionNames = mutableStateListOf("Session 1 (Bionic Shell)")
    val currentSessionName = mutableStateOf("Session 1 (Bionic Shell)")
    var currentWorkingDir: File? = null
        set(value) {
            field = value
            if (!restoring) persistAsync()
        }
    val sessionBuffers = mutableMapOf<String, MutableList<TerminalLine>>()
    private var initialized = false
    private var restoreAttempted = false
    private var restoring = false
    private var appContext: Context? = null
    private var activeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val persistenceScope = CoroutineScope(Dispatchers.IO + Job())
    private var persistJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class SessionSnapshot(
        val names: List<String>,
        val currentName: String,
        val cwd: String?,
        val history: List<String>,
        val buffers: Map<String, List<TerminalLine>>,
        val visibleLines: List<TerminalLine>
    )

    /** Enables persistence and restores saved sessions once. Safe no-op before wiring. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!restoreAttempted) {
            restoreAttempted = true
            if (Looper.myLooper() == Looper.getMainLooper()) {
                restoreSessions()
            } else {
                mainHandler.post { restoreSessions() }
            }
        }
        initIfNeeded()
    }

    fun initIfNeeded() {
        if (!initialized) {
            initialized = true
            sessionBuffers.getOrPut(currentSessionName.value) { mutableListOf() }
        }
    }

    /** Flushes current terminal state to disk (async, without debounce). */
    fun saveSessions() = persistAsync(immediate = true)

    /** Restores session names, buffers, command history and cwd from disk. */
    fun restoreSessions() {
        val ctx = appContext ?: return
        val stateFile = File(File(ctx.filesDir, "terminal"), "sessions.json")
        if (!stateFile.exists()) return
        val root = try {
            JSONObject(stateFile.readText())
        } catch (_: Exception) {
            return
        }

        restoring = true
        try {
            val names = mutableListOf<String>()
            root.optJSONArray("sessionNames")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i)
                    if (name.isNotBlank()) names.add(name)
                }
            }
            if (names.isEmpty()) return

            sessionNames.clear()
            sessionNames.addAll(names)

            history.clear()
            root.optJSONArray("history")?.let { arr ->
                for (i in 0 until arr.length()) history.add(arr.optString(i))
            }

            synchronized(sessionBuffers) {
                sessionBuffers.clear()
                root.optJSONObject("sessions")?.let { sessions ->
                    names.forEach { name ->
                        val arr = sessions.optJSONArray(name) ?: return@forEach
                        val buffer = mutableListOf<TerminalLine>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            buffer.add(
                                TerminalLine(
                                    text = obj.optString("text"),
                                    isError = obj.optBoolean("isError"),
                                    isCommand = obj.optBoolean("isCommand")
                                )
                            )
                        }
                        sessionBuffers[name] = buffer
                    }
                }
            }

            val savedCurrent = root.optString("currentSessionName").ifBlank { names.first() }
            val current = if (names.contains(savedCurrent)) savedCurrent else names.first()
            val savedBuffer = synchronized(sessionBuffers) { sessionBuffers.getOrPut(current) { mutableListOf() } }
            if (savedBuffer.isEmpty()) {
                root.optJSONArray("visibleLines")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        savedBuffer.add(
                            TerminalLine(
                                text = obj.optString("text"),
                                isError = obj.optBoolean("isError"),
                                isCommand = obj.optBoolean("isCommand")
                            )
                        )
                    }
                }
            }
            val cwd = root.optString("currentWorkingDir")
            currentWorkingDir = if (cwd.isNotBlank() && File(cwd).exists()) File(cwd) else null
            currentSessionName.value = current
            lines.clear()
            lines.addAll(savedBuffer)
        } catch (_: Exception) {
            // Keep the live session untouched if the saved state is malformed.
        } finally {
            restoring = false
        }
    }

    private fun persistAsync(immediate: Boolean = false) {
        val ctx = appContext ?: return
        if (restoring) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleWrite(ctx, captureSnapshot(), immediate)
        } else {
            mainHandler.post { scheduleWrite(ctx, captureSnapshot(), immediate) }
        }
    }

    private fun captureSnapshot(): SessionSnapshot {
        val buffers = LinkedHashMap<String, List<TerminalLine>>()
        synchronized(sessionBuffers) {
            sessionBuffers.forEach { (name, buffer) -> buffers[name] = ArrayList(buffer) }
        }
        return SessionSnapshot(
            names = ArrayList(sessionNames),
            currentName = currentSessionName.value,
            cwd = currentWorkingDir?.absolutePath,
            history = ArrayList(history),
            buffers = buffers,
            visibleLines = ArrayList(lines)
        )
    }

    private fun scheduleWrite(ctx: Context, snapshot: SessionSnapshot, immediate: Boolean) {
        persistJob?.cancel()
        persistJob = persistenceScope.launch {
            if (!immediate) delay(500L)
            writeSessions(ctx, snapshot)
        }
    }

    private fun writeSessions(ctx: Context, snapshot: SessionSnapshot) {
        try {
            val dir = File(ctx.filesDir, "terminal")
            if (!dir.exists() && !dir.mkdirs()) return

            val sessionsObj = JSONObject()
            snapshot.buffers.forEach { (name, buffer) ->
                val arr = JSONArray()
                buffer.takeLast(3000).forEach { line ->
                    arr.put(
                        JSONObject()
                            .put("text", line.text)
                            .put("isError", line.isError)
                            .put("isCommand", line.isCommand)
                    )
                }
                sessionsObj.put(name, arr)
            }
            val visibleArr = JSONArray()
            snapshot.visibleLines.takeLast(3000).forEach { line ->
                visibleArr.put(
                    JSONObject()
                        .put("text", line.text)
                        .put("isError", line.isError)
                        .put("isCommand", line.isCommand)
                )
            }
            val root = JSONObject()
                .put("version", 1)
                .put("sessionNames", JSONArray(snapshot.names))
                .put("currentSessionName", snapshot.currentName)
                .put("currentWorkingDir", snapshot.cwd)
                .put("history", JSONArray(snapshot.history.takeLast(500)))
                .put("sessions", sessionsObj)
                .put("visibleLines", visibleArr)

            // Atomic replace: write to a temp file in the same directory, then rename.
            val target = File(dir, "sessions.json")
            val tmp = File.createTempFile("terminal-", ".tmp", dir)
            try {
                tmp.writeText(root.toString())
                if (!tmp.renameTo(target)) {
                    target.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (_: Exception) {
                tmp.delete()
            }
        } catch (_: Exception) {}
    }

    fun clear() {
        lines.clear()
        sessionBuffers[currentSessionName.value]?.clear()
        persistAsync()
    }

    fun newSession() {
        saveCurrentSessionBuffer()
        val nextNum = sessionNames.count { it.startsWith("Session ") } + 1
        val name = "Session $nextNum (Bionic Shell)"
        sessionNames.add(name)
        currentSessionName.value = name
        val newBuf = mutableListOf<TerminalLine>()
        val startLine = TerminalLine(text = "[Session $nextNum (Bionic Shell)]", isCommand = true)
        newBuf.add(startLine)
        sessionBuffers[name] = newBuf
        lines.clear()
        lines.add(startLine)
        persistAsync()
    }

    fun ensureAgyLiveSession(autoSwitch: Boolean = false) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!sessionNames.contains(AGY_SESSION_NAME)) {
                sessionNames.add(0, AGY_SESSION_NAME)
                sessionBuffers.getOrPut(AGY_SESSION_NAME) { mutableListOf() }
                persistAsync()
            }
            if (autoSwitch && currentSessionName.value != AGY_SESSION_NAME) {
                switchSession(AGY_SESSION_NAME)
            }
        } else {
            mainHandler.post {
                if (!sessionNames.contains(AGY_SESSION_NAME)) {
                    sessionNames.add(0, AGY_SESSION_NAME)
                    sessionBuffers.getOrPut(AGY_SESSION_NAME) { mutableListOf() }
                    persistAsync()
                }
                if (autoSwitch && currentSessionName.value != AGY_SESSION_NAME) {
                    switchSession(AGY_SESSION_NAME)
                }
            }
        }
    }

    fun ensureBionicSession(autoSwitch: Boolean = true) {
        val bionicName = sessionNames.firstOrNull { it != AGY_SESSION_NAME } ?: "Session 1 (Bionic Shell)"
        if (autoSwitch && currentSessionName.value != bionicName) {
            switchSession(bionicName)
        }
    }

    fun saveCurrentSessionBuffer() {
        val currentBuf = sessionBuffers.getOrPut(currentSessionName.value) { mutableListOf() }
        currentBuf.clear()
        currentBuf.addAll(lines)
    }

    fun switchSession(name: String) {
        if (sessionNames.contains(name) && currentSessionName.value != name) {
            saveCurrentSessionBuffer()
            currentSessionName.value = name
            lines.clear()
            val targetBuf = sessionBuffers.getOrPut(name) { mutableListOf() }
            lines.addAll(targetBuf)
            persistAsync()
        }
    }

    fun clearInput() {
        commandInput.value = ""
    }

    fun currentOutputText(): String = lines.joinToString("\n") { it.text }

    private val ANSI_REGEX = Regex("(?:\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|\r)")
    fun cleanAnsi(text: String): String = text.replace(ANSI_REGEX, "").trimEnd('\r')

    fun appendLine(text: String, isError: Boolean, isUpdate: Boolean) {
        val clean = cleanAnsi(text)
        if (clean.isEmpty() && isUpdate) return

        mainHandler.post {
            try {
                val currentName = currentSessionName.value
                val buf = sessionBuffers.getOrPut(currentName) { mutableListOf() }
                if (isUpdate) {
                    if (buf.isNotEmpty() && !buf.last().isCommand) {
                        buf[buf.size - 1] = buf.last().copy(text = clean, isError = isError)
                    } else {
                        buf.add(TerminalLine(text = clean, isError = isError))
                    }
                } else {
                    buf.add(TerminalLine(text = clean, isError = isError))
                }
                if (buf.size > 2000) {
                    buf.subList(0, buf.size - 2000).clear()
                }

                if (isUpdate) {
                    if (lines.isNotEmpty() && !lines.last().isCommand) {
                        lines[lines.size - 1] = lines.last().copy(text = clean, isError = isError)
                    } else {
                        lines.add(TerminalLine(text = clean, isError = isError))
                    }
                } else {
                    lines.add(TerminalLine(text = clean, isError = isError))
                }
                if (lines.size > 50000) {
                    lines.removeRange(0, lines.size - 50000)
                }
                persistAsync()
            } catch (_: Exception) {}
        }
    }

    @Volatile
    private var isAgyFlushPending = false
    private val pendingAgyLines = java.util.concurrent.ConcurrentLinkedQueue<TerminalLine>()

    fun appendAgyLine(text: String, isError: Boolean = false, isCommand: Boolean = false) {
        val clean = cleanAnsi(text)
        if (clean.isEmpty()) return
        val splitLines = clean.lines()

        for (lineStr in splitLines) {
            val item = TerminalLine(text = lineStr, isError = isError, isCommand = isCommand)
            synchronized(sessionBuffers) {
                val buf = sessionBuffers.getOrPut(AGY_SESSION_NAME) { mutableListOf() }
                buf.add(item)
                if (buf.size > 50000) {
                    buf.subList(0, buf.size - 50000).clear()
                }
            }
            if (currentSessionName.value == AGY_SESSION_NAME) {
                pendingAgyLines.add(item)
            }
        }
        if (currentSessionName.value != AGY_SESSION_NAME) persistAsync()

        if (currentSessionName.value == AGY_SESSION_NAME && !isAgyFlushPending) {
            isAgyFlushPending = true
            mainHandler.postDelayed({
                isAgyFlushPending = false
                try {
                    if (currentSessionName.value == AGY_SESSION_NAME) {
                        var count = 0
                        while (!pendingAgyLines.isEmpty() && count < 200) {
                            val l = pendingAgyLines.poll() ?: break
                            lines.add(l)
                            count++
                        }
                        if (lines.size > 50000) {
                            lines.removeRange(0, lines.size - 50000)
                        }
                        if (!pendingAgyLines.isEmpty()) {
                            isAgyFlushPending = true
                            mainHandler.postDelayed({ appendAgyLine("") }, 30L)
                        }
                    } else {
                        pendingAgyLines.clear()
                    }
                    persistAsync()
                } catch (_: Exception) {}
            }, 30L)
        }
    }

    fun runCommand(context: Context, cmd: String, workingDir: File? = null, onFinished: ((Int) -> Unit)? = null): Job {
        initIfNeeded()
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) {
            onFinished?.invoke(0)
            return Job()
        }

        if (currentSessionName.value == AGY_SESSION_NAME) {
            val isAgyRunning = EngineExecutor.isAgyRunning()
            if (isAgyRunning) {
                appendAgyLine("> $trimmed", isCommand = true)
                EngineExecutor.writeToAgyStdin(trimmed)
            } else {
                appendAgyLine("> $trimmed", isCommand = true)
                appendAgyLine(context.getString(R.string.terminal_agy_idle_hint), isError = true)
            }
            commandInput.value = ""
            onFinished?.invoke(0)
            return Job()
        }

        if (workingDir != null) currentWorkingDir = workingDir
        val effectiveCwd = workingDir ?: currentWorkingDir
        lines.add(TerminalLine(text = "$ $trimmed", isCommand = true))
        sessionBuffers[currentSessionName.value]?.add(TerminalLine(text = "$ $trimmed", isCommand = true))
        history.add(trimmed)
        historyIndex.intValue = history.size
        commandInput.value = ""
        isRunning.value = true
        persistAsync()

        activeJob?.cancel()
        val job = scope.launch {
            val exitCode = EngineExecutor.executeCommandWithUpdates(context, trimmed, effectiveCwd) { line, isErr, isUpdate ->
                appendLine(line, isErr, isUpdate)
            }
            isRunning.value = false
            onFinished?.invoke(exitCode)
            persistAsync(immediate = true)
        }
        activeJob = job
        return job
    }

    fun killCurrentProcess() {
        if (currentSessionName.value == AGY_SESSION_NAME && EngineExecutor.isAgyRunning()) {
            EngineExecutor.stopAgyProcess()
            appendAgyLine("^C [AGY process stopped by user]", isError = true)
            return
        }
        if (isRunning.value) {
            activeJob?.cancel()
            lines.add(TerminalLine(text = "^C [Process terminated by user]", isError = true))
            sessionBuffers[currentSessionName.value]?.add(TerminalLine(text = "^C [Process terminated by user]", isError = true))
            isRunning.value = false
            persistAsync()
        }
    }

    fun restartShell() {
        killCurrentProcess()
        clear()
    }
}
