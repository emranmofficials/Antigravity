package id.vern.antigravity.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class PermissionDefaultMode { ALLOW, ASK, DENY }

data class PermissionRule(
    val action: String,
    val pattern: String,
    val mode: String
)

data class PermissionGrantsState(
    val rules: List<PermissionRule> = emptyList(),
    val autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_ASK",
    val enableTerminalSandbox: Boolean = false,
    val nonWorkspaceFileAccessPolicy: String = "AGENT_SETTING_POLICY_DENY",
    val planReviewPolicy: String = "Always Ask",
    val securityPreset: String = "Default"
) {
    fun getRulesForAction(action: String): List<PermissionRule> =
        rules.filter { it.action.equals(action, ignoreCase = true) }

    fun getCountForAction(action: String): Int =
        rules.count { it.action.equals(action, ignoreCase = true) }

    val toolPermissionsCount: Int
        get() = rules.count { it.action in listOf("read_file", "write_file", "command", "mcp") }

    val networkPermissionsCount: Int
        get() = rules.count { it.action == "read_url" }

    val commandsOutsideSandboxCount: Int
        get() = rules.count { it.action == "unsandboxed" }

    val browserActuationCount: Int
        get() = rules.count { it.action == "execute_url" }
}

object PermissionConfigService {
    private const val TAG = "PermissionConfig"
    private val GRANT_REGEX = Regex("^([^(]+)\\((.*)\\)$")

    /** Grants that older builds put in "allow" unconditionally. */
    private val LEGACY_PERMISSIVE_GRANTS = listOf(
        "command(*)",
        "unsandboxed(*)",
        "read_file(*)",
        "write_file(*)",
        "read_url(*)",
        "execute_url(*)",
        "mcp(*)"
    )
    private val DEFAULT_ASK_GRANTS = listOf(
        "command(*)",
        "read_file(*)",
        "write_file(*)",
        "read_url(*)",
        "mcp(*)"
    )
    private val DANGEROUS_ACTIONS = setOf("unsandboxed", "execute_url")

    @Volatile
    private var defaultMode: PermissionDefaultMode = PermissionDefaultMode.ASK

    /** Settings can read this to show the active default. Defaults to ASK. */
    fun getDefaultPermissionMode(): PermissionDefaultMode = defaultMode

    /** Settings can toggle the default applied to grants with no explicit rule. */
    @Synchronized
    fun setDefaultPermissionMode(mode: PermissionDefaultMode) {
        defaultMode = mode
    }

    /** ASK for normal tools, DENY for dangerous actions; ALLOW only when explicitly selected. */
    fun defaultModeForAction(action: String): String = when (defaultMode) {
        PermissionDefaultMode.ALLOW -> "allow"
        PermissionDefaultMode.ASK -> if (action.lowercase() in DANGEROUS_ACTIONS) "deny" else "ask"
        PermissionDefaultMode.DENY -> "deny"
    }

    private fun putGrant(
        mode: String,
        grant: String,
        allowSet: LinkedHashSet<String>,
        askSet: LinkedHashSet<String>,
        denySet: LinkedHashSet<String>
    ) {
        allowSet.remove(grant)
        askSet.remove(grant)
        denySet.remove(grant)
        when (mode.lowercase()) {
            "allow" -> allowSet.add(grant)
            "ask" -> askSet.add(grant)
            "deny" -> denySet.add(grant)
            else -> when (defaultModeForAction(grant.substringBefore("("))) {
                "allow" -> allowSet.add(grant)
                "ask" -> askSet.add(grant)
                else -> denySet.add(grant)
            }
        }
    }

    private fun addDefaultGrants(
        allowSet: LinkedHashSet<String>,
        askSet: LinkedHashSet<String>,
        denySet: LinkedHashSet<String>
    ) {
        for (grant in LEGACY_PERMISSIVE_GRANTS) {
            val mode = if (grant in DEFAULT_ASK_GRANTS) {
                if (defaultMode == PermissionDefaultMode.ALLOW) "allow" else defaultModeForAction(grant.substringBefore("("))
            } else {
                if (defaultMode == PermissionDefaultMode.ALLOW) "allow" else "deny"
            }
            putGrant(mode, grant, allowSet, askSet, denySet)
        }
    }

    private fun getCandidateFiles(context: Context): List<File> {
        val list = mutableListOf<File>()
        list.add(File(context.filesDir, ".gemini/config/config.json"))
        list.add(File(context.filesDir, "home/.gemini/config/config.json"))
        return list
    }

    private fun getCandidateSettingsFiles(context: Context): List<File> {
        val list = mutableListOf<File>()
        list.add(File(context.filesDir, ".gemini/settings.json"))
        list.add(File(context.filesDir, "home/.gemini/settings.json"))
        list.add(File(context.filesDir, ".gemini/antigravity-cli/settings.json"))
        list.add(File(context.filesDir, "home/.gemini/antigravity-cli/settings.json"))
        return list
    }

    private fun findPrimaryConfigFile(context: Context): File {
        val candidates = getCandidateFiles(context)
        for (f in candidates) {
            if (f.exists() && f.canRead()) return f
        }
        val defaultFile = candidates.first()
        defaultFile.parentFile?.mkdirs()
        return defaultFile
    }

    fun syncSettingsJsonFiles(context: Context, state: PermissionGrantsState = PermissionGrantsState()) {
        val allowSet = LinkedHashSet<String>()
        val askSet = LinkedHashSet<String>()
        val denySet = LinkedHashSet<String>()
        addDefaultGrants(allowSet, askSet, denySet)

        for (rule in state.rules) {
            val grantStr = "${rule.action}(${rule.pattern})"
            putGrant(rule.mode, grantStr, allowSet, askSet, denySet)
        }

        for (settingsFile in getCandidateSettingsFiles(context)) {
            try {
                settingsFile.parentFile?.mkdirs()
                val root = if (settingsFile.exists() && settingsFile.canRead()) {
                    val text = settingsFile.readText(Charsets.UTF_8).trim()
                    if (text.startsWith("{")) JSONObject(text) else JSONObject()
                } else {
                    JSONObject()
                }

                val existingPerms = root.optJSONObject("permissions")
                if (existingPerms != null) {
                    val existingAllow = existingPerms.optJSONArray("allow")
                    if (existingAllow != null) {
                        for (i in 0 until existingAllow.length()) {
                            val item = existingAllow.optString(i, "").trim()
                            if (item.isBlank()) continue
                            // Migrate legacy wildcard allows into the safer default bucket.
                            if (item in LEGACY_PERMISSIVE_GRANTS) {
                                val mode = defaultModeForAction(item.substringBefore("("))
                                putGrant(mode, item, allowSet, askSet, denySet)
                            } else {
                                putGrant("allow", item, allowSet, askSet, denySet)
                            }
                        }
                    }
                }

                val allowArr = JSONArray()
                allowSet.forEach { allowArr.put(it) }
                val askArr = JSONArray()
                askSet.forEach { askArr.put(it) }
                val denyArr = JSONArray()
                denySet.forEach { denyArr.put(it) }

                val permsObj = JSONObject().apply {
                    put("allow", allowArr)
                    if (askArr.length() > 0) put("ask", askArr)
                    if (denyArr.length() > 0) put("deny", denyArr)
                }
                root.put("permissions", permsObj)

                val userSettings = root.optJSONObject("userSettings") ?: JSONObject().also { root.put("userSettings", it) }
                userSettings.put("autoExecutionPolicy", state.autoExecutionPolicy)
                userSettings.put("enableTerminalSandbox", state.enableTerminalSandbox)
                userSettings.put("nonWorkspaceFileAccessPolicy", state.nonWorkspaceFileAccessPolicy)
                userSettings.put("planReviewPolicy", state.planReviewPolicy)
                userSettings.put("securityPreset", state.securityPreset)

                settingsFile.writeText(root.toString(2), Charsets.UTF_8)
                settingsFile.setReadable(true, false)
                settingsFile.setWritable(true, false)
            } catch (e: Exception) {}
        }
    }

    suspend fun loadPermissions(context: Context): PermissionGrantsState = withContext(Dispatchers.IO) {
        val file = findPrimaryConfigFile(context)
        if (!file.exists() || !file.canRead()) {
            val defaultState = PermissionGrantsState()
            syncSettingsJsonFiles(context, defaultState)
            return@withContext defaultState
        }
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isBlank() || !content.startsWith("{")) {
                val defaultState = PermissionGrantsState()
                syncSettingsJsonFiles(context, defaultState)
                return@withContext defaultState
            }
            val root = JSONObject(content)
            val userSettings = root.optJSONObject("userSettings") ?: JSONObject()

            val autoExec = userSettings.optString("autoExecutionPolicy", "CASCADE_COMMANDS_AUTO_EXECUTION_ASK")
            val sandbox = userSettings.optBoolean("enableTerminalSandbox", false)
            val nonWorkspaceFile = userSettings.optString("nonWorkspaceFileAccessPolicy", "AGENT_SETTING_POLICY_DENY")
            val planReview = userSettings.optString("planReviewPolicy", "Always Ask")
            val secPreset = userSettings.optString("securityPreset", "Default")

            val rules = mutableListOf<PermissionRule>()
            val grantsObj = userSettings.optJSONObject("globalPermissionGrants")
            if (grantsObj != null) {
                fun parseList(key: String, mode: String) {
                    val arr = grantsObj.optJSONArray(key) ?: return
                    for (i in 0 until arr.length()) {
                        val grantStr = arr.optString(i, "").trim()
                        val match = GRANT_REGEX.find(grantStr)
                        if (match != null) {
                            val action = match.groupValues[1].trim()
                            val pattern = match.groupValues[2].trim()
                            if (action.isNotBlank() && pattern.isNotBlank()) {
                                rules.add(PermissionRule(action, pattern, mode))
                            }
                        }
                    }
                }
                parseList("allow", "allow")
                parseList("ask", "ask")
                parseList("deny", "deny")
            }

            val loaded = PermissionGrantsState(
                rules = rules,
                autoExecutionPolicy = autoExec,
                enableTerminalSandbox = sandbox,
                nonWorkspaceFileAccessPolicy = nonWorkspaceFile,
                planReviewPolicy = planReview,
                securityPreset = secPreset
            )
            syncSettingsJsonFiles(context, loaded)
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load permissions: ${e.message}", e)
            val defaultState = PermissionGrantsState()
            syncSettingsJsonFiles(context, defaultState)
            defaultState
        }
    }

    suspend fun savePermissions(
        context: Context,
        state: PermissionGrantsState
    ): Boolean = withContext(Dispatchers.IO) {
        val file = findPrimaryConfigFile(context)
        try {
            file.parentFile?.mkdirs()
            val root = if (file.exists() && file.canRead()) {
                val text = file.readText(Charsets.UTF_8).trim()
                if (text.startsWith("{")) JSONObject(text) else JSONObject()
            } else {
                JSONObject()
            }

            val userSettings = root.optJSONObject("userSettings") ?: JSONObject().also { root.put("userSettings", it) }
            userSettings.put("autoExecutionPolicy", state.autoExecutionPolicy)
            userSettings.put("enableTerminalSandbox", state.enableTerminalSandbox)
            userSettings.put("nonWorkspaceFileAccessPolicy", state.nonWorkspaceFileAccessPolicy)
            userSettings.put("planReviewPolicy", state.planReviewPolicy)
            userSettings.put("securityPreset", state.securityPreset)

            val allowSet = LinkedHashSet<String>()
            val askSet = LinkedHashSet<String>()
            val denySet = LinkedHashSet<String>()
            addDefaultGrants(allowSet, askSet, denySet)

            for (rule in state.rules) {
                val grantStr = "${rule.action}(${rule.pattern})"
                putGrant(rule.mode, grantStr, allowSet, askSet, denySet)
            }

            val allowArr = JSONArray()
            allowSet.forEach { allowArr.put(it) }
            val askArr = JSONArray()
            askSet.forEach { askArr.put(it) }
            val denyArr = JSONArray()
            denySet.forEach { denyArr.put(it) }

            val grantsObj = JSONObject()
            grantsObj.put("allow", allowArr)
            grantsObj.put("ask", askArr)
            grantsObj.put("deny", denyArr)
            userSettings.put("globalPermissionGrants", grantsObj)

            val formattedJson = root.toString(2)

            for (target in getCandidateFiles(context)) {
                try {
                    target.parentFile?.mkdirs()
                    target.writeText(formattedJson, Charsets.UTF_8)
                    target.setReadable(true, false)
                    target.setWritable(true, false)
                } catch (e: Exception) {}
            }
            syncSettingsJsonFiles(context, state)
            Log.i(TAG, "Permissions saved successfully (${state.rules.size} rules)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save permissions: ${e.message}", e)
            false
        }
    }
}
