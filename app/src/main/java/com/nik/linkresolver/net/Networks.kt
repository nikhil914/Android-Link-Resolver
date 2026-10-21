package com.nik.linkresolver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** OkHttp clients: redirects off (the engine follows manually), browser-ish timeouts. */
class OkHttpFactory {
    val base: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Client whose sockets and DNS go over [network] only (e.g. cellular while Wi-Fi is connected). */
    fun boundTo(network: Network): OkHttpClient = base.newBuilder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
        })
        .build()
}

/** Holds a cellular network request open while a resolve uses it. Close to release. */
class CellularLease(val network: Network, private val cm: ConnectivityManager, private val cb: ConnectivityManager.NetworkCallback) : Closeable {
    override fun close() {
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}

class NetworkHelper(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Asks Android for mobile data even if Wi-Fi is connected. Returns null if cellular isn't available
     * within [timeoutMs] (no SIM, mobile data off, airplane mode…) — caller then uses the default network.
     */
    suspend fun acquireCellular(timeoutMs: Long = 5_000): CellularLease? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        val lease = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(CellularLease(network, cm, this))
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: SecurityException) {
                    cont.resume(null)
                } catch (e: RuntimeException) {
                    cont.resume(null)
                }
            }
        }
        if (lease == null) callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        return lease
    }

    /** "Wi-Fi", "Mobile data", … for the given network (default: the active one). */
    fun describe(network: Network? = cm.activeNetwork): String {
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return "No network"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "$type (VPN)" else type
    }
}
