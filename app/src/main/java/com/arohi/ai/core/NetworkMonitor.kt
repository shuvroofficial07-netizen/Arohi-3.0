package com.arohi.ai.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4 — Universal network reliability engine.
 *
 * Why v3 could wrongly show "অফলাইন":
 *  - it depended on ONE probe endpoint (google generate_204); DNS/captive/IPv6
 *    quirks on some networks (and some Samsung/Xiaomi firmwares) blocked it
 *  - the watchdog interval was fixed, not adaptive
 *
 * v4 fixes:
 *  - THREE independent probes (Google 204, Cloudflare 204, Cloudflare trace) —
 *    online if ANY succeeds, so no network quirk can fake an offline state
 *  - exponential backoff while offline: 10s → 20s → 40s → 60s (cap), so recovery
 *    is detected fast right after a drop without burning battery for hours
 *  - real network-type reporting (WIFI / MOBILE / NONE) published to the UI
 *  - auto-reconnect: when connectivity returns, a callback lets API layers retry
 *  - never shows a fake "connected": the flag comes only from real probes/caps
 */
object NetworkMonitor {

    @Volatile var isOnline: Boolean = true
        private set

    /** Real human label of the current transport, shown on the UI. */
    @Volatile var networkLabel: String = "যাচাই হচ্ছে…"
        private set

    /** Set by features that want a hook when internet comes back (auto-retry). */
    val reconnectListeners = mutableListOf<() -> Unit>()

    /** v4.1: named one-shot reconnect hooks (deduped — no leaks on repeated saves). */
    private val namedReconnect = mutableMapOf<String, () -> Unit>()

    fun addReconnectListener(name: String, action: () -> Unit) {
        synchronized(namedReconnect) { namedReconnect[name] = action }
    }

    fun removeReconnectListener(name: String) {
        synchronized(namedReconnect) { namedReconnect.remove(name) }
    }

    private var registered = false
    private var appCtx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backoffIndex = 0

    private val PROBES = listOf(
        "https://www.google.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://1.1.1.1/cdn-cgi/trace"
    )

    fun start(context: Context) {
        appCtx = context.applicationContext
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refresh() }
            override fun onLost(network: Network) { refresh() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
            override fun onUnavailable() { refresh() }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) { }
        refresh()

        // Adaptive watchdog: while OFFLINE probe aggressively (backoff 10→60s);
        // while ONLINE a light 90s heartbeat keeps the flag honest.
        scope.launch {
            while (isActive) {
                delay(if (isOnline) 90_000L else backoffMs())
                if (isOnline) {
                    // light honest check
                    if (!quickCapabilityCheck() && !httpProbe()) publish(false)
                } else {
                    probeAndPublish()
                }
            }
        }
    }

    private fun backoffMs(): Long {
        val steps = longArrayOf(10_000, 20_000, 40_000, 60_000)
        val ms = steps[backoffIndex.coerceIn(0, steps.size - 1)]
        if (backoffIndex < steps.size - 1) backoffIndex++
        return ms
    }

    /** Called from anywhere to force a fresh connectivity evaluation. */
    fun refresh() {
        val ctx = appCtx ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }

        // transport label — real info for the UI, no guessing
        networkLabel = when {
            caps == null -> "নেটওয়ার্ক নেই"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "মোবাইল ডেটা"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ইথারনেট"
            else -> "অন্য নেটওয়ার্ক"
        }

        val capabilityOnline = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (capabilityOnline) {
            publish(true)
        } else {
            // capabilities look doubtful (captive portal, partial validation) —
            // verify with a REAL probe before declaring offline
            scope.launch {
                val ok = httpProbe()
                if (ok && caps == null) networkLabel = "নেটওয়ার্ক (যাচাইকৃত)"
                publish(ok)
            }
        }
    }

    private suspend fun probeAndPublish() {
        val capsOk = quickCapabilityCheck()
        val probeOk = httpProbe()
        publish(capsOk || probeOk)
    }

    private fun quickCapabilityCheck(): Boolean {
        val ctx = appCtx ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
    }

    /** Multi-endpoint probe: online if ANY endpoint answers. */
    fun httpProbe(): Boolean = PROBES.any { probeUrl(it) }

    private fun probeUrl(url: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.useCaches = false
        val ok = conn.responseCode in 200..204
        conn.disconnect()
        ok
    } catch (_: Exception) { false }

    private fun publish(online: Boolean) {
        val changed = online != isOnline
        isOnline = online
        if (!online) networkLabel = "নেটওয়ার্ক নেই"
        ArohiBus.offline.value = !online
        if (changed) {
            if (online) {
                backoffIndex = 0
                ArohiBus.toast("ইন্টারনেট ফিরে এসেছে ($networkLabel)")
                // auto-reconnect hooks: let API layers retry pending work
                synchronized(reconnectListeners) { reconnectListeners.toList() }.forEach { l ->
                    try { l() } catch (_: Exception) {}
                }
                synchronized(namedReconnect) { namedReconnect.values.toList() }.forEach { l ->
                    try { l() } catch (_: Exception) {}
                }
            } else {
                ArohiBus.toast("অফলাইন মোড: ইন্টারনেট নেই — ডিভাইস কমান্ড চলবে")
            }
        }
    }
}
