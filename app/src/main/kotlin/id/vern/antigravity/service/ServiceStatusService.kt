package id.vern.antigravity.service

import android.content.Context
import id.vern.antigravity.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ServiceStatus(
    val daemonRunning: Boolean = false,
    val gatewayRunning: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val health: String = "unknown",
    val updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("daemon", daemonRunning)
        .put("gateway", gatewayRunning)
        .put("wakeLock", wakeLockHeld)
        .put("health", health)
        .put("updatedAt", updatedAt)

    fun summaryText(): String =
        "daemon=${if (daemonRunning) "on" else "off"} · " +
            "gateway=${if (gatewayRunning) "on" else "off"} · " +
            "wakeLock=${if (wakeLockHeld) "on" else "off"} · health=$health"
}

object ServiceStatusService {
    private val mutableStatus = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = mutableStatus.asStateFlow()

    /** Pure helper, also used by unit tests. */
    fun deriveHealth(daemonRunning: Boolean, gatewayRunning: Boolean): String = when {
        daemonRunning && gatewayRunning -> "ok"
        daemonRunning || gatewayRunning -> "partial"
        else -> "stopped"
    }

    fun update(
        daemonRunning: Boolean? = null,
        gatewayRunning: Boolean? = null,
        wakeLockHeld: Boolean? = null,
        health: String? = null
    ) {
        val current = mutableStatus.value
        mutableStatus.value = ServiceStatus(
            daemonRunning = daemonRunning ?: current.daemonRunning,
            gatewayRunning = gatewayRunning ?: current.gatewayRunning,
            wakeLockHeld = wakeLockHeld ?: current.wakeLockHeld,
            health = health ?: current.health,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun currentJson(): JSONObject = mutableStatus.value.toJson()

    /** Foreground notification text built from existing localized strings + compact state. */
    fun statusText(context: Context): String {
        val state = mutableStatus.value
        val baseRes = if (state.health == "ok") R.string.notification_engine_running else R.string.notification_engine_active
        return context.getString(baseRes) + " · " + state.summaryText()
    }

    fun reset() {
        mutableStatus.value = ServiceStatus()
    }
}
