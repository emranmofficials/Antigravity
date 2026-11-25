package id.vern.antigravity.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.vern.antigravity.util.AppLogger

/**
 * Restarts the local engine after a device reboot when the user opted in.
 * The preference key lives in the app's main shared preferences so Settings and
 * the receiver always agree on the same value.
 */
class BootRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val prefs = context.getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ag_start_on_boot", false)) {
            return
        }

        runCatching {
            CoreServerService.startService(context.applicationContext)
            AppLogger.d("BootRestartReceiver", "Engine restart requested after boot")
        }.onFailure {
            AppLogger.e("BootRestartReceiver", "Failed to restart engine: ${it.message}")
        }
    }
}
