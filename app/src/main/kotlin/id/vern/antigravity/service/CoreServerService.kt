package id.vern.antigravity.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import id.vern.antigravity.R
import id.vern.antigravity.gateway.SystemGatewayServer
import id.vern.antigravity.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CoreServerService : Service() {

    companion object {
        private const val TAG = "CoreServerService"
        const val ACTION_START = "id.vern.antigravity.action.START"
        const val ACTION_STOP = "id.vern.antigravity.action.STOP"
        const val ACTION_RESTART = "id.vern.antigravity.action.RESTART"

        /** Stable action for a future boot receiver; startService(context) also works. */
        const val ACTION_BOOT_RESTART = "id.vern.antigravity.action.BOOT_RESTART"

        fun startService(context: Context) {
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wakeLockHolder = WakeLockHolder()
    private var gatewayServer: SystemGatewayServer? = null
    @Volatile
    private var daemonRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): CoreServerService = this@CoreServerService
    }

    private fun publishStatus(
        daemon: Boolean? = null,
        gateway: Boolean? = null,
        wakeLock: Boolean? = null,
        health: String? = null
    ) {
        val current = ServiceStatusService.status.value
        val daemonValue = daemon ?: daemonRunning
        val gatewayValue = gateway ?: (gatewayServer?.isRunning() == true)
        val wakeLockValue = wakeLock ?: wakeLockHolder.isHeld
        val healthValue = health ?: ServiceStatusService.deriveHealth(daemonValue, gatewayValue)
        ServiceStatusService.update(daemonValue, gatewayValue, wakeLockValue, healthValue)
        ForegroundNotification.update(this, ServiceStatusService.statusText(this))
        if (current.health != healthValue) {
            Log.i(TAG, "Service status: ${ServiceStatusService.currentJson()}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating CoreServerService...")
        NetworkStateService.register(this)
        ForegroundNotification.start(this, getString(R.string.notification_engine_active))
        wakeLockHolder.acquire(this, 15_000L)

        gatewayServer = SystemGatewayServer(this, 45161, wakeLockHolder)
        gatewayServer?.start()
        publishStatus(gateway = gatewayServer?.isRunning() == true)

        startBackgroundDaemon()
        AppLogger.d("Engine", "CoreServerService running on ports 45158 & 45161")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action requested")
                daemonRunning = false
                publishStatus(daemon = false, gateway = false, wakeLock = false, health = "stopped")
                dismissNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART, ACTION_BOOT_RESTART -> {
                Log.i(TAG, "Restart action requested (${intent.action})")
                stopBackgroundDaemon()
                startBackgroundDaemon()
                publishStatus()
            }
            else -> {
                publishStatus()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "Destroying CoreServerService...")
        daemonRunning = false
        publishStatus(daemon = false, gateway = false, wakeLock = false, health = "stopped")
        dismissNotification()
        serviceScope.cancel()
        stopBackgroundDaemon()
        gatewayServer?.stop()
        wakeLockHolder.release()
        NetworkStateService.unregister()
        AppLogger.d("Engine", "CoreServerService stopped")
        super.onDestroy()
    }

    private fun dismissNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(ForegroundNotification.NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing notification: ${e.message}")
        }
    }

    private fun startBackgroundDaemon() {
        serviceScope.launch {
            try {
                delay(1500)
                val daemonBin = File(filesDir, "usr/bin/agy-daemon")
                if (daemonBin.canExecute()) {
                    Log.i(TAG, "Starting autonomous background daemon...")
                    val homeDir = File(filesDir, "home").apply { mkdirs() }
                    val pb = ProcessBuilder(daemonBin.absolutePath, "start")
                    pb.directory(homeDir)
                    pb.environment()["PATH"] = "${filesDir.absolutePath}/usr/bin:${filesDir.absolutePath}/engine/bin:/system/bin"
                    pb.environment()["HOME"] = homeDir.absolutePath
                    pb.environment()["PREFIX"] = "${filesDir.absolutePath}/usr"
                    pb.environment()["AGY_APPLICATION_ID"] = packageName
                    pb.environment()["APPLICATION_ID"] = packageName
                    pb.start()
                    daemonRunning = true
                    publishStatus(daemon = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch agy-daemon: ${e.message}")
                daemonRunning = false
                publishStatus(daemon = false, health = ServiceStatusService.deriveHealth(false, gatewayServer?.isRunning() == true))
            }
        }
    }

    private fun stopBackgroundDaemon() {
        try {
            val daemonBin = File(filesDir, "usr/bin/agy-daemon")
            if (daemonBin.canExecute()) {
                Log.i(TAG, "Stopping background daemon...")
                val homeDir = File(filesDir, "home").apply { mkdirs() }
                val pb = ProcessBuilder(daemonBin.absolutePath, "stop")
                pb.directory(homeDir)
                pb.environment()["PATH"] = "${filesDir.absolutePath}/usr/bin:${filesDir.absolutePath}/engine/bin:/system/bin"
                pb.environment()["HOME"] = homeDir.absolutePath
                val p = pb.start()
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
                daemonRunning = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping daemon: ${e.message}")
        }
    }
}

