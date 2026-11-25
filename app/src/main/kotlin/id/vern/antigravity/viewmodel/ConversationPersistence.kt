package id.vern.antigravity.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import id.vern.antigravity.data.AgentExecutionStep
import id.vern.antigravity.data.AgentStepKind
import id.vern.antigravity.data.AttachedFile
import id.vern.antigravity.data.ChangedFileDetail
import id.vern.antigravity.data.ChatMessage
import id.vern.antigravity.data.MessageRole
import id.vern.antigravity.data.MessageStatus
import id.vern.antigravity.data.TurnFileChanges
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ConversationPersistence {
    private const val TAG = "ConversationPersistence"
    private const val PREFS_NAME = "antigravity_prefs"
    private const val KEY_CONVERSATION_HISTORY = "ag_conversation_history"
    private const val KEY_LAST_ACTIVE_CONV_ID = "ag_last_active_conv_id"
    private const val KEY_CLI_SESSION_MAP = "ag_cli_session_map"

    /** Pure helper: serializes the conversation-id -> CLI/ACP session-id mapping. */
    fun serializeCliSessionMap(map: Map<String, String>): String {
        val obj = JSONObject()
        for ((conversationId, sessionId) in map) {
            if (conversationId.isBlank() || sessionId.isBlank()) continue
            obj.put(conversationId, sessionId)
        }
        return obj.toString()
    }

    /** Pure helper: skips malformed entries instead of dropping the whole mapping. */
    fun deserializeCliSessionMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "").trim()
                if (key.isBlank() || value.isEmpty()) {
                    Log.w(TAG, "Skipping invalid CLI session mapping entry: $key")
                    continue
                }
                result[key] = value
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing CLI session map: ${e.message}")
            emptyMap()
        }
    }

    fun saveCliSessionMapping(context: Context, conversationId: String, sessionId: String) {
        if (conversationId.isBlank() || sessionId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = deserializeCliSessionMap(prefs.getString(KEY_CLI_SESSION_MAP, null)).toMutableMap()
        if (current[conversationId] == sessionId) return
        current[conversationId] = sessionId
        prefs.edit().putString(KEY_CLI_SESSION_MAP, serializeCliSessionMap(current)).apply()
    }

    fun loadCliSessionMapping(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return deserializeCliSessionMap(prefs.getString(KEY_CLI_SESSION_MAP, null))
    }

    fun deleteCliSessionMapping(context: Context, conversationId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = deserializeCliSessionMap(prefs.getString(KEY_CLI_SESSION_MAP, null)).toMutableMap()
        if (current.remove(conversationId) != null) {
            prefs.edit().putString(KEY_CLI_SESSION_MAP, serializeCliSessionMap(current)).apply()
        }
    }

    /** Pure helper for serializing per-turn file changes attached to a chat message. */
    fun serializeFileChanges(changes: TurnFileChanges?): JSONObject? {
        if (changes == null) return null
        val obj = JSONObject().apply {
            put("totalFiles", changes.totalFiles)
            put("totalAdditions", changes.totalAdditions)
            put("totalDeletions", changes.totalDeletions)
        }
        val filesArr = JSONArray()
        for (file in changes.files) {
            filesArr.put(
                JSONObject().apply {
                    put("name", file.name)
                    put("relativePath", file.relativePath)
                    put("status", file.status)
                    put("additions", file.additions)
                    put("deletions", file.deletions)
                    put("diff", file.diff)
                }
            )
        }
        obj.put("files", filesArr)
        return obj
    }

    /** Pure helper for deserializing per-turn file changes; bad file entries are skipped. */
    fun deserializeFileChanges(raw: JSONObject?): TurnFileChanges? {
        if (raw == null) return null
        val files = mutableListOf<ChangedFileDetail>()
        val filesArr = try { raw.optJSONArray("files") } catch (e: Exception) { null }
        if (filesArr != null) {
            for (i in 0 until filesArr.length()) {
                try {
                    val fileObj = filesArr.getJSONObject(i)
                    val relativePath = fileObj.optString("relativePath", "")
                    if (relativePath.isBlank()) {
                        Log.w(TAG, "Skipping file change entry with no path at index $i")
                        continue
                    }
                    files.add(
                        ChangedFileDetail(
                            name = fileObj.optString("name", relativePath.substringAfterLast('/')),
                            relativePath = relativePath,
                            status = fileObj.optString("status", "M"),
                            additions = fileObj.optInt("additions", 0),
                            deletions = fileObj.optInt("deletions", 0),
                            diff = fileObj.optString("diff", "")
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed file change entry at index $i: ${e.message}")
                }
            }
        }
        return TurnFileChanges(
            totalFiles = if (raw.has("totalFiles")) raw.optInt("totalFiles", files.size) else files.size,
            totalAdditions = raw.optInt("totalAdditions", 0),
            totalDeletions = raw.optInt("totalDeletions", 0),
            files = files
        )
    }

    fun saveConversationHistory(context: Context, history: List<ConversationSummary>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (item in history) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("timestamp", item.timestamp)
                put("messageCount", item.messageCount)
                put("isPinned", item.isPinned)
                if (item.projectPath != null) put("projectPath", item.projectPath)
                val allowlist = JSONArray()
                item.allowedPaths.forEach { allowlist.put(it) }
                put("allowedPaths", allowlist)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_CONVERSATION_HISTORY, jsonArray.toString()).apply()
    }

    fun loadConversationHistory(context: Context): List<ConversationSummary> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONVERSATION_HISTORY, null)
        val list = mutableListOf<ConversationSummary>()
        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    try {
                        val obj = jsonArray.getJSONObject(i)
                        val allowedPaths = mutableListOf<String>()
                        val allowlistArr = try { obj.optJSONArray("allowedPaths") } catch (e: Exception) { null }
                        if (allowlistArr != null) {
                            for (j in 0 until allowlistArr.length()) {
                                val path = allowlistArr.optString(j, "").trim()
                                if (path.isNotEmpty()) allowedPaths.add(path)
                            }
                        }
                        list.add(
                            ConversationSummary(
                                id = obj.getString("id"),
                                title = obj.getString("title"),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                messageCount = obj.optInt("messageCount", 0),
                                isPinned = obj.optBoolean("isPinned", false),
                                projectPath = if (obj.has("projectPath")) obj.getString("projectPath") else null,
                                allowedPaths = allowedPaths
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed conversation summary at index $i: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing conversation history: ${e.message}")
            }
        }
        return list
    }

    fun saveLastActiveConversationId(context: Context, id: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (id == null) {
            prefs.edit().remove(KEY_LAST_ACTIVE_CONV_ID).apply()
        } else {
            prefs.edit().putString(KEY_LAST_ACTIVE_CONV_ID, id).apply()
        }
    }

    fun getLastActiveConversationId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_ACTIVE_CONV_ID, null)
    }

    fun saveMessagesForConversation(context: Context, conversationId: String, messages: List<ChatMessage>) {
        try {
            val convDir = File(context.filesDir, "conversations").apply { mkdirs() }
            val file = File(convDir, "$conversationId.json")
            val array = JSONArray()
            for (msg in messages) {
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role.name)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    put("status", msg.status.name)
                    if (msg.thinking != null) put("thinking", msg.thinking)
                    if (msg.commandRun != null) put("commandRun", msg.commandRun)
                    if (msg.commandOutput != null) put("commandOutput", msg.commandOutput)
                    if (msg.codeBlocks.isNotEmpty()) {
                        val blocksArr = JSONArray()
                        msg.codeBlocks.forEach { blocksArr.put(it) }
                        put("codeBlocks", blocksArr)
                    }
                    if (msg.attachments.isNotEmpty()) {
                        val attArr = JSONArray()
                        for (att in msg.attachments) {
                            val attObj = JSONObject().apply {
                                put("id", att.id)
                                put("uri", att.uri.toString())
                                put("name", att.name)
                                put("sizeBytes", att.sizeBytes)
                                put("mimeType", att.mimeType)
                                if (att.localPath != null) put("localPath", att.localPath)
                            }
                            attArr.put(attObj)
                        }
                        put("attachments", attArr)
                    }
                    if (msg.durationText != null) put("durationText", msg.durationText)
                    if (msg.actionSummary != null) put("actionSummary", msg.actionSummary)
                    if (msg.steps.isNotEmpty()) {
                        val stepsArr = JSONArray()
                        for (s in msg.steps) {
                            val sObj = JSONObject().apply {
                                put("id", s.id)
                                put("kind", s.kind.name)
                                put("title", s.title)
                                if (s.detail != null) put("detail", s.detail)
                                if (s.lines != null) put("lines", s.lines)
                                if (s.diff != null) put("diff", s.diff)
                                if (s.durationSeconds != null) put("durationSeconds", s.durationSeconds)
                                put("isCompleted", s.isCompleted)
                                if (s.filePath != null) put("filePath", s.filePath)
                            }
                            stepsArr.put(sObj)
                        }
                        put("steps", stepsArr)
                    }
                    serializeFileChanges(msg.fileChanges)?.let { put("fileChanges", it) }
                }
                array.put(obj)
            }
            writeAtomically(file, array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving messages for conversation $conversationId: ${e.message}")
        }
    }

    /** Writes to a temp file in the same directory then renames, so readers never see a partial file. */
    private fun writeAtomically(target: File, text: String) {
        val temp = File.createTempFile("conv_${target.nameWithoutExtension.take(40)}_", ".tmp", target.parentFile)
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                Log.w(TAG, "Atomic rename failed for ${target.name}; writing directly")
                target.writeText(text, Charsets.UTF_8)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun loadMessagesForConversation(context: Context, conversationId: String): List<ChatMessage> {
        val fallbackTranscript = {
            TranscriptTurnParser.parseTranscriptToMessages(context, conversationId)
        }
        val file = File(File(context.filesDir, "conversations"), "$conversationId.json")
        if (!file.exists()) return fallbackTranscript()

        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading messages for $conversationId: ${e.message}")
            return fallbackTranscript()
        }
        if (text.isBlank()) {
            Log.w(TAG, "Message file for $conversationId is blank; falling back to transcript")
            return fallbackTranscript()
        }

        return try {
            val array = JSONArray(text)
            val result = mutableListOf<ChatMessage>()
            var skipped = 0
            for (i in 0 until array.length()) {
                try {
                    result.add(parseChatMessage(array.getJSONObject(i)))
                } catch (e: Exception) {
                    skipped++
                    Log.w(TAG, "Skipping malformed message at index $i in $conversationId: ${e.message}")
                }
            }
            if (skipped > 0 && result.isEmpty()) {
                Log.w(TAG, "All messages in $conversationId were malformed; falling back to transcript")
                return fallbackTranscript()
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading messages for conversation $conversationId: ${e.message}")
            fallbackTranscript()
        }
    }

    private fun parseChatMessage(obj: JSONObject): ChatMessage {
        val roleStr = obj.optString("role", "USER")
        val role = try { MessageRole.valueOf(roleStr) } catch (e: Exception) { MessageRole.USER }
        val statusStr = obj.optString("status", "SENT")
        val status = try { MessageStatus.valueOf(statusStr) } catch (e: Exception) { MessageStatus.SENT }
        val codeBlocks = mutableListOf<String>()
        if (obj.has("codeBlocks")) {
            val cbArr = obj.getJSONArray("codeBlocks")
            for (j in 0 until cbArr.length()) {
                codeBlocks.add(cbArr.getString(j))
            }
        }
        val attachments = mutableListOf<AttachedFile>()
        if (obj.has("attachments")) {
            val attArr = obj.getJSONArray("attachments")
            for (j in 0 until attArr.length()) {
                val attObj = attArr.getJSONObject(j)
                attachments.add(
                    AttachedFile(
                        id = attObj.optString("id", UUID.randomUUID().toString()),
                        uri = Uri.parse(attObj.optString("uri", "")),
                        name = attObj.optString("name", "attachment"),
                        sizeBytes = attObj.optLong("sizeBytes", 0L),
                        mimeType = attObj.optString("mimeType", "application/octet-stream"),
                        localPath = attObj.optString("localPath", "").ifEmpty { null }
                    )
                )
            }
        }
        val steps = mutableListOf<AgentExecutionStep>()
        if (obj.has("steps")) {
            val stepsArr = obj.getJSONArray("steps")
            for (k in 0 until stepsArr.length()) {
                val sObj = stepsArr.getJSONObject(k)
                val kStr = sObj.optString("kind", "COMMAND")
                val kind = try { AgentStepKind.valueOf(kStr) } catch (e: Exception) { AgentStepKind.COMMAND }
                steps.add(
                    AgentExecutionStep(
                        id = sObj.optString("id", UUID.randomUUID().toString()),
                        kind = kind,
                        title = sObj.optString("title", ""),
                        detail = if (sObj.has("detail")) sObj.getString("detail") else null,
                        lines = if (sObj.has("lines")) sObj.getString("lines") else null,
                        diff = if (sObj.has("diff")) sObj.getString("diff") else null,
                        durationSeconds = if (sObj.has("durationSeconds")) sObj.getInt("durationSeconds") else null,
                        isCompleted = sObj.optBoolean("isCompleted", true),
                        filePath = if (sObj.has("filePath")) sObj.getString("filePath") else null
                    )
                )
            }
        }
        return ChatMessage(
            id = obj.optString("id", UUID.randomUUID().toString()),
            role = role,
            content = obj.optString("content", ""),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            status = status,
            attachments = attachments,
            thinking = if (obj.has("thinking")) obj.getString("thinking") else null,
            commandRun = if (obj.has("commandRun")) obj.getString("commandRun") else null,
            commandOutput = if (obj.has("commandOutput")) obj.getString("commandOutput") else null,
            codeBlocks = codeBlocks,
            durationText = if (obj.has("durationText")) obj.getString("durationText") else null,
            actionSummary = if (obj.has("actionSummary")) obj.getString("actionSummary") else null,
            steps = steps,
            fileChanges = deserializeFileChanges(obj.optJSONObject("fileChanges"))
        )
    }

    fun deleteConversationFile(context: Context, conversationId: String) {
        try {
            val file = File(File(context.filesDir, "conversations"), "$conversationId.json")
            if (file.exists()) file.delete()
            deleteCliSessionMapping(context, conversationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed deleting conversation file for $conversationId: ${e.message}")
        }
    }
}
