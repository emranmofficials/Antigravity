package id.vern.antigravity.install

import android.content.Context
import android.system.Os
import android.util.Log
import id.vern.antigravity.util.NetworkDownloader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class CoreUpdateInfo(
    val version: String,
    val url: String,
    val sha512: String = "",
    val currentVersion: String = "",
    val isUpdateAvailable: Boolean = false
)

object CoreUpdateManager {
    private const val TAG = "CoreUpdateManager"
    const val MANIFEST_URL = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"

    /**
     * Last-resort display value before any engine or .engine_version file exists.
     * The persisted version file and `agy --version` output are authoritative;
     * this constant only keeps the UI from showing a blank version on a broken install.
     */
    const val FALLBACK_VERSION = "1.5.3"

    private const val VERSION_FILE = ".engine_version"
    private const val VERSION_BACKUP_FILE = ".engine_version.bak"
    private const val BINARY_BACKUP_NAME = "bin/agy.bak"

    private val mutableRollbackAvailable = MutableStateFlow(false)
    /** Observable flag Settings can collect to show a "Restore previous core" action. */
    val rollbackAvailable: StateFlow<Boolean> = mutableRollbackAvailable.asStateFlow()

    /** Refreshes and returns whether a usable backup binary exists. */
    fun hasRollbackBackup(context: Context): Boolean {
        val available = File(BootstrapInstaller.engineDir(context), BINARY_BACKUP_NAME).isFile
        mutableRollbackAvailable.value = available
        return available
    }

    fun refreshRollbackAvailability(context: Context): Boolean = hasRollbackBackup(context)

    /** Restores the pre-update binary and version marker from the retained backup. */
    fun rollbackToBackup(context: Context): Result<String> =
        rollbackToBackupInternal(context, publishState = true)

    private fun rollbackToBackupInternal(context: Context, publishState: Boolean): Result<String> {
        val engineDir = BootstrapInstaller.engineDir(context)
        val agyBin = File(engineDir, "bin/agy")
        val backupFile = File(engineDir, BINARY_BACKUP_NAME)
        val versionFile = File(engineDir, VERSION_FILE)
        val versionBackupFile = File(engineDir, VERSION_BACKUP_FILE)
        val tmpAgy = File(context.filesDir, "tmp/engine/bin/agy")

        if (!backupFile.isFile) {
            if (publishState) hasRollbackBackup(context)
            return Result.failure(Exception("No previous core engine backup is available"))
        }

        return try {
            backupFile.copyTo(agyBin, overwrite = true)
            makeExecutable(agyBin)

            if (versionBackupFile.isFile) {
                versionBackupFile.copyTo(versionFile, overwrite = true)
            } else if (versionFile.exists()) {
                versionFile.delete()
            }

            if (tmpAgy.parentFile?.exists() == true) {
                try {
                    agyBin.copyTo(tmpAgy, overwrite = true)
                    makeExecutable(tmpAgy)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not refresh tmp engine binary during rollback: ${e.message}")
                }
            }

            val restoredVersion = if (versionFile.isFile) versionFile.readText().trim() else "backup"
            if (publishState) hasRollbackBackup(context)
            Log.i(TAG, "Restored previous core engine (version=$restoredVersion)")
            Result.success(restoredVersion.ifBlank { "backup" })
        } catch (e: Exception) {
            if (publishState) hasRollbackBackup(context)
            Log.e(TAG, "Failed rolling back core engine: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getCurrentVersion(context: Context): String {
        // Keep the observable rollback affordance in sync with disk on normal UI reads.
        hasRollbackBackup(context)
        val engineDir = BootstrapInstaller.engineDir(context)
        val agyBin = File(engineDir, "bin/agy")
        val ldBin = File(engineDir, "glibc/lib/ld-linux-aarch64.so.1")
        val libDir = File(engineDir, "glibc/lib")
        val prefixLib = File(BootstrapInstaller.prefixDir(context), "lib")

        val targetBin = when {
            agyBin.isFile -> agyBin
            File(context.filesDir, "tmp/engine/bin/agy").isFile -> File(context.filesDir, "tmp/engine/bin/agy")
            else -> null
        }

        if (targetBin != null && ldBin.isFile) {
            try {
                val libPaths = listOf(libDir.absolutePath, prefixLib.absolutePath)
                    .filter { File(it).exists() }
                    .joinToString(":")
                val pb = ProcessBuilder(ldBin.absolutePath, "--library-path", libPaths, targetBin.absolutePath, "--version")
                pb.environment().remove("LD_PRELOAD")
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                if (proc.exitValue() == 0 && out.isNotEmpty()) {
                    return out
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed running agy --version: ${e.message}")
            }
        }

        val verFile = File(engineDir, VERSION_FILE)
        if (verFile.isFile) {
            val v = verFile.readText().trim()
            if (v.isNotEmpty()) return v
        }

        return FALLBACK_VERSION
    }

    fun checkForUpdate(context: Context): CoreUpdateInfo {
        val currentVer = getCurrentVersion(context)
        val (code, jsonStr) = NetworkDownloader.httpGetString(MANIFEST_URL, connectTimeout = 10000, readTimeout = 15000)
        if (code !in 200..299 || jsonStr.isNullOrBlank()) {
            return CoreUpdateInfo(currentVer, "", "", currentVer, false)
        }
        val json = JSONObject(jsonStr)
        val remoteVer = json.getString("version")
        val downloadUrl = json.getString("url")
        val sha512 = json.optString("sha512", "")

        val isAvailable = isVersionNewer(remoteVer, currentVer)
        return CoreUpdateInfo(
            version = remoteVer,
            url = downloadUrl,
            sha512 = sha512,
            currentVersion = currentVer,
            isUpdateAvailable = isAvailable
        )
    }

    fun isVersionNewer(remote: String, current: String): Boolean {
        if (remote == current) return false
        // `agy --version` may append platform text; compare only numeric segments.
        fun numericParts(value: String): List<Int> = value.trim()
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
        val rParts = numericParts(remote)
        val cParts = numericParts(current)
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /** Lower-level helper, kept pure so it can be unit tested without Android. */
    fun sha512Matches(expected: String, actualHex: String): Boolean {
        fun normalize(value: String): String = value.trim().lowercase()
            .removePrefix("sha512:")
            .filter { it in '0'..'9' || it in 'a'..'f' }
        val expectedHex = normalize(expected)
        val actual = normalize(actualHex)
        if (expectedHex.length != 128 || actual.length != 128) return false
        return MessageDigest.isEqual(expectedHex.toByteArray(), actual.toByteArray())
    }

    /** SHA-512 of a file, lowercase hex. Used for both archives and extracted binaries. */
    fun sha512Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun sha512Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            sb.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return sb.toString()
    }

    private fun makeExecutable(file: File) {
        file.setReadable(true, false)
        file.setExecutable(true, false)
        try { Os.chmod(file.absolutePath, 493) } catch (e: Exception) {}
    }

    private data class SmokeResult(val success: Boolean, val output: String)

    private fun runVersionSmokeTest(
        agyBin: File,
        ldBin: File,
        libDir: File,
        prefixLib: File,
        expectedVersion: String
    ): SmokeResult {
        if (!ldBin.isFile || !agyBin.isFile) {
            return SmokeResult(false, "loader or binary missing")
        }
        val libPaths = listOf(libDir.absolutePath, prefixLib.absolutePath)
            .filter { File(it).exists() }
            .joinToString(":")
        return try {
            val pb = ProcessBuilder(ldBin.absolutePath, "--library-path", libPaths, agyBin.absolutePath, "--version")
            pb.environment().remove("LD_PRELOAD")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return SmokeResult(false, "smoke test timed out")
            }
            val versionLike = Regex("\\d+\\.\\d+").containsMatchIn(output)
            val versionMatches = expectedVersion.isBlank() || output.contains(expectedVersion)
            SmokeResult(
                success = proc.exitValue() == 0 && output.isNotBlank() && versionLike && versionMatches,
                output = output.take(400)
            )
        } catch (e: Exception) {
            SmokeResult(false, e.message ?: "smoke test could not start")
        }
    }

    fun downloadAndApply(
        context: Context,
        updateInfo: CoreUpdateInfo,
        onProgress: (Float, String) -> Unit
    ): Result<String> {
        val engineDir = BootstrapInstaller.engineDir(context)
        val agyBin = File(engineDir, "bin/agy")
        val ldBin = File(engineDir, "glibc/lib/ld-linux-aarch64.so.1")
        val libDir = File(engineDir, "glibc/lib")
        val prefixLib = File(BootstrapInstaller.prefixDir(context), "lib")
        val cacheDir = context.cacheDir

        val tarGzFile = File(cacheDir, "core_update_arm64.tar.gz")
        val stagingFile = File(cacheDir, "agy.staging")
        val backupFile = File(engineDir, BINARY_BACKUP_NAME)
        val versionFile = File(engineDir, VERSION_FILE)
        val versionBackupFile = File(engineDir, VERSION_BACKUP_FILE)

        var installStarted = false
        var smokeTestPassed = false

        try {
            val maxAttempts = 5
            var attempt = 0
            var downloadSucceeded = false
            var lastDownloadError: Throwable? = null

            while (attempt < maxAttempts && !downloadSucceeded) {
                attempt++
                try {
                    onProgress(0.05f, "Connecting to update server (attempt $attempt/$maxAttempts)...")
                    onProgress(0.10f, "Downloading Core Engine (${updateInfo.version})...")
                    var lastReport = System.currentTimeMillis()
                    downloadSucceeded = NetworkDownloader.downloadWithResume(
                        url = updateInfo.url,
                        dest = tarGzFile,
                        maxAttempts = 1,
                        onProgress = { totalRead, totalBytes ->
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 250 && totalBytes > 0) {
                                lastReport = now
                                val progress = 0.10f + 0.55f * (totalRead.toFloat() / totalBytes)
                                val mbRead = totalRead / (1024 * 1024)
                                val mbTotal = totalBytes / (1024 * 1024)
                                onProgress(progress, "Downloading: $mbRead MB / $mbTotal MB")
                            }
                        }
                    )
                } catch (e: Exception) {
                    lastDownloadError = e
                    Log.w(TAG, "Core Engine download attempt $attempt/$maxAttempts failed: ${e.message}", e)
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(attempt * 2000L)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }

            if (!downloadSucceeded) {
                throw IllegalStateException("Failed downloading core engine after $maxAttempts attempts: ${lastDownloadError?.message}", lastDownloadError)
            }

            val expectedSha = updateInfo.sha512.trim()
            val archiveShaHex = if (expectedSha.isNotBlank()) {
                onProgress(0.66f, "Verifying downloaded archive (SHA-512)...")
                sha512Hex(tarGzFile)
            } else {
                Log.w(TAG, "Update manifest has no sha512; relying on ELF header and smoke test")
                ""
            }

            onProgress(0.70f, "Extracting binary package...")
            var extracted = extractTarGzMember(tarGzFile, "antigravity", stagingFile)
            if (!extracted) {
                val tarBin = File(BootstrapInstaller.prefixDir(context), "bin/tar")
                if (tarBin.isFile) {
                    val pb = ProcessBuilder(
                        tarBin.absolutePath,
                        "-xzf",
                        tarGzFile.absolutePath,
                        "-C",
                        cacheDir.absolutePath,
                        "antigravity"
                    )
                    pb.start().waitFor()
                    val fallbackFile = File(cacheDir, "antigravity")
                    if (fallbackFile.isFile) {
                        fallbackFile.copyTo(stagingFile, overwrite = true)
                        fallbackFile.delete()
                        extracted = true
                    }
                }
            }

            if (!extracted || !stagingFile.isFile || stagingFile.length() < 50_000_000L) {
                return Result.failure(Exception("Failed to extract valid core binary from archive"))
            }

            if (expectedSha.isNotBlank()) {
                val archiveMatches = sha512Matches(expectedSha, archiveShaHex)
                val binaryMatches = if (archiveMatches) false else sha512Matches(expectedSha, sha512Hex(stagingFile))
                if (!archiveMatches && !binaryMatches) {
                    Log.e(TAG, "SHA-512 mismatch for archive and extracted binary (expected ${expectedSha.take(16)}…)")
                    return Result.failure(Exception("SHA-512 integrity check failed for downloaded core engine"))
                }
                onProgress(0.84f, if (archiveMatches) "Archive SHA-512 verified" else "Binary SHA-512 verified")
            }

            onProgress(0.85f, "Verifying binary integrity...")
            val header = ByteArray(4)
            FileInputStream(stagingFile).use { it.read(header) }
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) {
                return Result.failure(Exception("Downloaded file is not a valid ELF binary"))
            }

            makeExecutable(stagingFile)

            onProgress(0.88f, "Applying Android platform syscall patches...")
            CoreBinaryPatcher.patchBinary(context, stagingFile)

            onProgress(0.90f, "Installing new Core Engine...")
            installStarted = true
            if (agyBin.exists()) {
                if (backupFile.exists()) backupFile.delete()
                agyBin.copyTo(backupFile, overwrite = true)
                agyBin.delete()
            } else {
                agyBin.parentFile?.mkdirs()
            }
            if (versionFile.isFile) {
                versionFile.copyTo(versionBackupFile, overwrite = true)
            } else if (versionBackupFile.exists()) {
                versionBackupFile.delete()
            }
            stagingFile.copyTo(agyBin, overwrite = true)
            makeExecutable(agyBin)

            val tmpAgy = File(context.filesDir, "tmp/engine/bin/agy")
            if (tmpAgy.parentFile?.exists() == true) {
                try {
                    agyBin.copyTo(tmpAgy, overwrite = true)
                    makeExecutable(tmpAgy)
                } catch (e: Exception) {}
            }

            BootstrapInstaller.patchGlibcResolvConf(context, engineDir)

            onProgress(0.95f, "Running verification smoke test...")
            val smoke = runVersionSmokeTest(agyBin, ldBin, libDir, prefixLib, updateInfo.version)
            if (!smoke.success) {
                Log.e(TAG, "Smoke test failed (${smoke.output}), rolling back...")
                rollbackToBackupInternal(context, publishState = true)
                return Result.failure(Exception("Smoke test failed: ${smoke.output.ifBlank { "unknown error" }}"))
            }
            smokeTestPassed = true

            // Keep the known-good backup after a successful update so Settings can offer
            // "Restore previous core". The next successful update replaces it.
            if (stagingFile.exists()) stagingFile.delete()
            if (tarGzFile.exists()) tarGzFile.delete()

            versionFile.writeText(updateInfo.version)
            hasRollbackBackup(context)

            onProgress(1.0f, "Core engine successfully updated to v${updateInfo.version}!")
            return Result.success(updateInfo.version)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating core engine: ${e.message}", e)
            if (installStarted && !smokeTestPassed) {
                rollbackToBackupInternal(context, publishState = true)
            } else if (backupFile.exists() && !agyBin.exists()) {
                rollbackToBackupInternal(context, publishState = true)
            }
            return Result.failure(e)
        }
    }

    private fun extractTarGzMember(tarGzFile: File, memberName: String, destFile: File): Boolean {
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile), 65536)).use { tarIn ->
                val header = ByteArray(512)
                while (true) {
                    var bytesRead = 0
                    while (bytesRead < 512) {
                        val r = tarIn.read(header, bytesRead, 512 - bytesRead)
                        if (r == -1) return false
                        bytesRead += r
                    }

                    if (header.all { it == 0.toByte() }) break

                    val nameBytes = header.copyOfRange(0, 100)
                    val zeroIdx = nameBytes.indexOf(0.toByte())
                    val entryName = String(nameBytes, 0, if (zeroIdx >= 0) zeroIdx else nameBytes.size).trim()

                    val sizeBytes = header.copyOfRange(124, 136)
                    val sizeZeroIdx = sizeBytes.indexOf(0.toByte())
                    val sizeStr = String(sizeBytes, 0, if (sizeZeroIdx >= 0) sizeZeroIdx else sizeBytes.size).trim()
                    val entrySize = sizeStr.toLongOrNull(8) ?: 0L

                    if (entryName == memberName || entryName.endsWith("/$memberName")) {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { out ->
                            var remaining = entrySize
                            val buf = ByteArray(65536)
                            while (remaining > 0) {
                                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                                val r = tarIn.read(buf, 0, toRead)
                                if (r == -1) break
                                out.write(buf, 0, r)
                                remaining -= r
                            }
                        }
                        return true
                    } else {
                        val pad = ((entrySize + 511) / 512) * 512
                        var remainingToSkip = pad
                        val skipBuf = ByteArray(65536)
                        while (remainingToSkip > 0) {
                            val toRead = minOf(remainingToSkip, skipBuf.size.toLong()).toInt()
                            val r = tarIn.read(skipBuf, 0, toRead)
                            if (r == -1) break
                            remainingToSkip -= r
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractTarGzMember failed: ${e.message}")
        }
        return false
    }
}
