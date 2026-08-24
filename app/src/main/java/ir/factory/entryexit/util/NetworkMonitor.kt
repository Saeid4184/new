package ir.factory.entryexit.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tells the rest of the app whether there is a usable internet connection right now, and lets
 * screens observe changes over time (to show/hide the "آفلاین" banner, and to know when it's a
 * good moment to confirm that offline-queued data has gone up to the server).
 *
 * This is deliberately just a connectivity signal, not a sync mechanism: the actual "keep
 * working offline, upload once back online" behavior is handled by Firestore's own on-device
 * persistent cache (see [ir.factory.entryexit.data.Repository] and [FactoryApp]) — Firestore
 * queues writes locally and delivers them automatically the moment a connection exists again.
 */
object NetworkMonitor {

    /** Best-effort synchronous check — used right before a write to decide whether to wait for
     *  a server round-trip or just let it queue offline (see Repository.awaitWrite). */
    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // can't tell — assume online rather than blocking legitimate writes
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Emits the current connectivity state immediately, then again every time it changes.
     *  Callers (Activities) turn this into a top banner and a "back online, syncing…" message. */
    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline(context))
            }

            override fun onLost(network: Network) {
                trySend(isOnline(context))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(isOnline(context))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        trySend(isOnline(context))

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
