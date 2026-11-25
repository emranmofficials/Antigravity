package id.vern.antigravity.viewmodel

import android.content.Context
import android.widget.Toast
import id.vern.antigravity.R
import id.vern.antigravity.data.OpenTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class EditorSessionDelegate {
    private val mutableOpenTabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val openTabs: StateFlow<List<OpenTab>> = mutableOpenTabs.asStateFlow()

    private val mutableActiveTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = mutableActiveTabId.asStateFlow()

    private var appContext: Context? = null
    private val draftsDir: File?
        get() = appContext?.let { File(it.filesDir, "editors/drafts") }

    private val draftScope = CoroutineScope(Dispatchers.IO + Job())
    private var draftSaveJob: Job? = null

    /**
     * Enables draft persistence and restores any unsaved buffers from a previous run.
     * Safe to call more than once; with no context every persistence call is a no-op.
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            restoreDrafts()
        }
    }

    fun initIfNeeded(context: Context) = init(context)

    /** Restores drafts for files that are not already open. Public for ViewModel/Settings wiring. */
    fun restoreDrafts() {
        val dir = draftsDir ?: return
        if (!dir.exists()) return
        val draftFiles = try {
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".draft") }
        } catch (_: Exception) {
            null
        } ?: return

        val restored = mutableListOf<OpenTab>()
        for (draftFile in draftFiles) {
            try {
                val json = JSONObject(draftFile.readText())
                val path = json.optString("path")
                val content = json.optString("content")
                if (path.isBlank()) continue
                if (mutableOpenTabs.value.any { it.file.absolutePath == path }) continue

                val file = File(path)
                val diskContent = readFileIfSmall(file)
                if (content == diskContent) {
                    // The draft was saved in the meantime; no unsaved work left.
                    draftFile.delete()
                    continue
                }
                restored += OpenTab(
                    id = path,
                    file = file,
                    title = file.name,
                    content = content,
                    originalContent = diskContent,
                    isDirty = true
                )
            } catch (_: Exception) {
                // Ignore a corrupt draft rather than breaking editor startup.
            }
        }
        if (restored.isNotEmpty()) {
            mutableOpenTabs.update { it + restored }
            if (mutableActiveTabId.value == null) {
                mutableActiveTabId.value = restored.first().id
            }
        }
    }

    /** Immediately flushes all currently dirty buffers. Public for ViewModel/Settings wiring. */
    fun saveDrafts() {
        val dir = draftsDir ?: return
        writeDrafts(dir, captureDirtyTabs())
    }

    private fun scheduleDraftSave() {
        val dir = draftsDir ?: return
        val entries = captureDirtyTabs()
        draftSaveJob?.cancel()
        draftSaveJob = draftScope.launch {
            delay(400L)
            writeDrafts(dir, entries)
        }
    }

    private fun captureDirtyTabs(): List<DraftEntry> =
        mutableOpenTabs.value
            .filter { it.content != it.originalContent }
            .map { DraftEntry(it.file.absolutePath, it.content) }

    private fun writeDrafts(dir: File, entries: List<DraftEntry>) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            for (entry in entries) {
                val target = File(dir, draftFileName(entry.path))
                val tmp = File.createTempFile("draft-", ".tmp", dir)
                try {
                    tmp.writeText(JSONObject().put("path", entry.path).put("content", entry.content).toString())
                    if (!tmp.renameTo(target)) {
                        // Fallback for filesystems that refuse an atomic replace.
                        target.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (_: Exception) {
                    tmp.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun deleteDraft(path: String) {
        val dir = draftsDir ?: return
        try {
            File(dir, draftFileName(path)).delete()
        } catch (_: Exception) {}
    }

    private fun readFileIfSmall(file: File): String = try {
        if (file.exists() && file.isFile && file.length() < 2 * 1024 * 1024) file.readText() else ""
    } catch (_: Exception) {
        ""
    }

    private fun draftFileName(path: String): String {
        val safeName = File(path).name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "$safeName-${path.hashCode()}.draft"
    }

    private data class DraftEntry(val path: String, val content: String)

    fun openFile(file: File) {
        val existing = mutableOpenTabs.value.find { it.file.absolutePath == file.absolutePath }
        if (existing != null) {
            mutableActiveTabId.value = existing.id
            return
        }

        val content = readFileIfSmall(file)

        val tab = OpenTab(
            id = file.absolutePath,
            file = file,
            title = file.name,
            content = content,
            originalContent = content
        )

        mutableOpenTabs.update { it + tab }
        mutableActiveTabId.value = tab.id
    }

    fun closeTab(tabId: String) {
        val current = mutableOpenTabs.value
        val tabToClose = current.find { it.id == tabId } ?: return
        val updated = current.filter { it.id != tabId }
        mutableOpenTabs.value = updated
        if (mutableActiveTabId.value == tabId) {
            mutableActiveTabId.value = updated.lastOrNull()?.id
        }
        // A closed but unsaved buffer keeps its draft so it can be restored later.
    }

    fun selectTab(tabId: String) {
        if (mutableOpenTabs.value.any { it.id == tabId }) {
            mutableActiveTabId.value = tabId
        }
    }

    fun updateContent(tabId: String, newContent: String) {
        mutableOpenTabs.update { tabs ->
            tabs.map { tab ->
                if (tab.id == tabId) {
                    tab.copy(content = newContent, isDirty = newContent != tab.originalContent)
                } else {
                    tab
                }
            }
        }
        scheduleDraftSave()
    }

    fun setDiffView(tabId: String, isDiff: Boolean) {
        mutableOpenTabs.update { tabs ->
            tabs.map { tab ->
                if (tab.id == tabId) {
                    tab.copy(isDiffView = isDiff)
                } else {
                    tab
                }
            }
        }
    }

    fun saveTab(context: Context, tabId: String): Boolean {
        val tab = mutableOpenTabs.value.find { it.id == tabId } ?: return false
        return try {
            tab.file.writeText(tab.content)
            mutableOpenTabs.update { tabs ->
                tabs.map { t ->
                    if (t.id == tabId) {
                        t.copy(originalContent = t.content, isDirty = false)
                    } else {
                        t
                    }
                }
            }
            deleteDraft(tab.id)
            Toast.makeText(context, context.getString(R.string.file_saved_success, tab.file.name), Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.file_saved_error, tab.file.name, e.message ?: ""), Toast.LENGTH_SHORT).show()
            false
        }
    }
}
