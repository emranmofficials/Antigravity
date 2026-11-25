package id.vern.antigravity.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import android.widget.Toast
import id.vern.antigravity.service.WakeLockHolder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class SystemGatewayServer(
    private val context: Context,
    private val port: Int = 45161,
    private val wakeLockHolder: WakeLockHolder? = null
) {
    companion object {
        private const val TAG = "SystemGateway"
        const val TOKEN_FILE_NAME = ".gateway_token"
        const val TOKEN_HEADER = "X-Agy-Gateway-Token"

        private val knownToken = AtomicReference<String?>(null)

        /** Stable per-install secret. Settings can display/copy the returned value. */
        @Synchronized
        fun getOrCreateToken(context: Context): String {
            val appContext = context.applicationContext
            knownToken.get()?.takeIf { it.length >= 32 }?.let { return it }

            val tokenFile = File(appContext.filesDir, TOKEN_FILE_NAME)
            val existing = if (tokenFile.isFile) {
                try { tokenFile.readText(Charsets.UTF_8).trim() } catch (e: Exception) { "" }
            } else ""
            if (existing.length >= 32) {
                knownToken.set(existing)
                return existing
            }

            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            var tempFile: File? = null
            try {
                tempFile = File.createTempFile(TOKEN_FILE_NAME, ".tmp", appContext.filesDir)
                tempFile.writeText(token, Charsets.UTF_8)
                makeOwnerOnly(tempFile)
                if (tokenFile.exists() && !tokenFile.delete()) {
                    Log.w(TAG, "Could not replace stale gateway token file")
                }
                if (!tempFile.renameTo(tokenFile)) {
                    tempFile.copyTo(tokenFile, overwrite = true)
                }
                makeOwnerOnly(tokenFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed persisting gateway token: ${e.message}")
            } finally {
                tempFile?.let { if (it.exists()) it.delete() }
            }
            knownToken.set(token)
            return token
        }

        /** Constant-time comparison; returns false until the token has been loaded. */
        fun isValidToken(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val expected = knownToken.get() ?: return false
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                token.trim().toByteArray(Charsets.UTF_8)
            )
        }

        private fun makeOwnerOnly(file: File) {
            try {
                file.setReadable(true, true)
                file.setWritable(true, true)
                file.setExecutable(false, false)
                Os.chmod(file.absolutePath, 384) // 0600
            } catch (e: Exception) {
                Log.w(TAG, "Could not set gateway token file permissions: ${e.message}")
            }
        }
    }

    init {
        // Ensures the token exists and is in memory before any socket is accepted.
        getOrCreateToken(context.applicationContext)
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    @Synchronized
    fun start() {
        if (running) return
        running = true

        executor.execute {
            try {
                val socket = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "Gateway server listening on 127.0.0.1:$port (token required)")

                while (running && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind gateway on $port: ${e.message}")
                running = false
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                if (!client.inetAddress.isLoopbackAddress) {
                    writeResponse(client, 403, JSONObject().put("ok", false).put("error", "loopback-only"))
                    return
                }

                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawPath = parts.getOrNull(1) ?: "/"

                val headers = HashMap<String, String>()
                var contentLength = 0
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrEmpty()) {
                    val idx = headerLine.indexOf(':')
                    if (idx > 0) {
                        val key = headerLine.substring(0, idx).trim().lowercase()
                        val value = headerLine.substring(idx + 1).trim()
                        if (!headers.containsKey(key)) headers[key] = value
                    }
                    if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    headerLine = reader.readLine()
                }

                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buffer, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    String(buffer, 0, read)
                } else ""

                val suppliedToken = headers[TOKEN_HEADER.lowercase()]
                    ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
                if (!isValidToken(suppliedToken)) {
                    Log.w(TAG, "Rejected unauthenticated gateway request: $method $rawPath")
                    writeResponse(client, 401, JSONObject().put("ok", false).put("error", "unauthorized"))
                    return
                }

                val responseJson = dispatch(rawPath, body)
                writeResponse(client, 200, responseJson)
            } catch (e: Exception) {
                Log.w(TAG, "Gateway client handle error: ${e.message}")
            }
        }
    }

    private fun writeResponse(socket: Socket, statusCode: Int, json: JSONObject) {
        val responseBytes = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (statusCode) {
            200 -> "OK"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            else -> "Error"
        }
        val header = "HTTP/1.1 $statusCode $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(responseBytes)
        out.flush()
    }

    private fun dispatch(path: String, body: String): JSONObject {
        val json = try {
            if (body.isNotBlank()) JSONObject(body) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }

        val endpoint = path.removePrefix("/").substringBefore("?").trim()
        val result = JSONObject().put("ok", true)

        when (endpoint) {
            "toast" -> {
                val msg = json.optString("message", json.optString("text", "Antigravity"))
                mainHandler.post {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                result.put("toast", "shown")
            }
            "open_url" -> {
                val url = json.optString("url")
                if (url.isNotBlank()) {
                    mainHandler.post {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                    result.put("opened", url)
                }
            }
            "clipboard" -> {
                if (json.has("text")) {
                    val text = json.getString("text")
                    mainHandler.post {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("agy", text))
                    }
                    result.put("set", true)
                } else {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    result.put("clipboard", text)
                }
            }
            "wakelock" -> {
                val action = json.optString("action", "status")
                when (action) {
                    "acquire" -> wakeLockHolder?.acquire(context)
                    "release" -> wakeLockHolder?.release()
                }
                result.put("held", wakeLockHolder?.isHeld == true)
            }
            "system_info" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                result.put("battery", batteryPct)
                result.put("android", Build.VERSION.RELEASE)
                result.put("sdk", Build.VERSION.SDK_INT)
                result.put("model", Build.MODEL)
            }
            else -> {
                result.put("status", "running")
                result.put("gateway", "Antigravity Jetpack Gateway")
            }
        }
        return result
    }
}
