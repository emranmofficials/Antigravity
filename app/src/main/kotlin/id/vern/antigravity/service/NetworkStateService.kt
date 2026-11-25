package id.vern.antigravity.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connectivity helper for offline UX; ACCESS_NETWORK_STATE is already declared. */
object NetworkStateService {
    private val mutableOnline = MutableStateFlow(true)
    /** Optimistic until [refresh] or [register] runs; collect for live changes. */
    val isOnlineFlow: StateFlow<Boolean> = mutableOnline.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mutableOnline.value = true
        }

        override fun onLost(network: Network) {
            appContext?.let { mutableOnline.value = isOnline(it) } ?: run { mutableOnline.value = false }
        }

        override fun onUnavailable() {
            mutableOnline.value = false
        }
    }

    fun isOnline(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /** One-shot check that also updates the observable flow. */
    fun refresh(context: Context): Boolean {
        val online = isOnline(context)
        mutableOnline.value = online
        return online
    }

    @Synchronized
    fun register(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (registered) {
            refresh(app)
            return
        }
        try {
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager != null) {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            }
        } catch (e: Exception) {
            registered = false
        }
        refresh(app)
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        try {
            val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Ignore: the callback may already have been released by the system.
        }
        registered = false
    }
}
