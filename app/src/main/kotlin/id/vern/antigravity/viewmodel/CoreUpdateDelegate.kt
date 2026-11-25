package id.vern.antigravity.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import id.vern.antigravity.R
import id.vern.antigravity.install.CoreUpdateInfo
import id.vern.antigravity.install.CoreUpdateManager
import id.vern.antigravity.service.TerminalLine
import id.vern.antigravity.util.AppUpdateState
import id.vern.antigravity.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object CoreUpdateDelegate {

    /**
     * CoreEngineUpdateState is owned by AntigravityViewModel, so the manifest digest is
     * cached here between the check and apply steps instead of changing that model.
     */
    private val sha512ByRelease = ConcurrentHashMap<String, String>()

    private fun releaseKey(version: String, url: String): String = "$version\u0000$url"

    /** Returns the cached manifest SHA-512 for a release, or an empty string. */
    fun getCachedSha512(version: String, url: String): String =
        sha512ByRelease[releaseKey(version, url)] ?: ""

    suspend fun checkForOtaUpdates(context: Context, isManualCheck: Boolean): AppUpdateState? {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val currentVersion = pInfo.versionName ?: "1.0.0"
            val checker = UpdateChecker(context)
            val info = checker.checkForUpdates(currentVersion, isManualCheck = isManualCheck)
            if (info != null) {
                AppUpdateState.Available(info)
            } else {
                if (isManualCheck) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.ota_check_up_to_date), Toast.LENGTH_SHORT).show()
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w("CoreUpdateDelegate", "Failed checking OTA updates: ${e.message}")
            null
        }
    }

    suspend fun startOtaDownload(
        context: Context,
        info: UpdateChecker.UpdateInfo,
        onProgress: (AppUpdateState) -> Unit
    ) {
        val checker = UpdateChecker(context)
        val downloadId = checker.startDownload(info)
        checker.trackDownloadProgress(downloadId) { state ->
            onProgress(state)
        }
    }

    suspend fun checkForCoreEngineUpdate(
        context: Context,
        force: Boolean,
        currentState: CoreEngineUpdateState
    ): CoreEngineUpdateState? {
        val now = System.currentTimeMillis()
        val lastChecked = currentState.lastCheckedTime
        if (!force && lastChecked > 0 && now - lastChecked < 15 * 60 * 1000L) {
            return null
        }
        val current = CoreUpdateManager.getCurrentVersion(context)
        return try {
            val updateInfo = CoreUpdateManager.checkForUpdate(context)
            if (updateInfo.sha512.isNotBlank()) {
                sha512ByRelease[releaseKey(updateInfo.version, updateInfo.url)] = updateInfo.sha512
            }
            CoreEngineUpdateState(
                isChecking = false,
                currentVersion = current,
                latestVersion = updateInfo.version,
                isUpdateAvailable = updateInfo.isUpdateAvailable,
                downloadUrl = updateInfo.url,
                sha512 = updateInfo.sha512,
                lastCheckedTime = System.currentTimeMillis(),
                error = null
            )
        } catch (e: Exception) {
            Log.w("CoreUpdateDelegate", "Failed checking core engine update: ${e.message}")
            currentState.copy(
                isChecking = false,
                currentVersion = current,
                error = e.message ?: "Network error"
            )
        }
    }

    suspend fun applyCoreEngineUpdate(
        context: Context,
        state: CoreEngineUpdateState,
        onOutputLine: (TerminalLine) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val latestVer = state.latestVersion ?: return
        val url = state.downloadUrl ?: return
        val updateInfo = CoreUpdateInfo(
            version = latestVer,
            url = url,
            sha512 = state.sha512.ifBlank { getCachedSha512(latestVer, url) },
            currentVersion = state.currentVersion,
            isUpdateAvailable = true
        )

        val result = CoreUpdateManager.downloadAndApply(context, updateInfo) { progress, step ->
            onOutputLine(TerminalLine(text = step, isError = false))
        }

        if (result.isSuccess) {
            val newVer = result.getOrNull() ?: latestVer
            onOutputLine(TerminalLine(text = context.getString(R.string.core_update_success, newVer), isError = false))
            onSuccess(newVer)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            onOutputLine(TerminalLine(text = context.getString(R.string.core_update_failed, err), isError = true))
            onError(err)
        }
    }
}
