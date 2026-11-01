package com.wayfindr.hus.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import com.wayfindr.hus.log

/**
 * Joins the dash's hotspot as a per-app network (WifiNetworkSpecifier), as Wayfindr's
 * DashWifiManager does: the phone keeps mobile data for Maps, and only the dash sockets are bound
 * to this network. The first time (prefix match, e.g. any RE_*) Android shows a picker; after
 * that the exact SSID and the dash's BSSID are pinned, so Android joins without asking.
 * Retries for as long as it is wanted.
 */
class DashWifi(
    context: Context,
    private val prefix: String,
    private val password: String,
    private var ssid: String,
    private var bssid: String,
    /** Called with (ssid, bssid) once they are known, to be saved for next time. */
    private val onResolved: (String, String) -> Unit,
) {
    private companion object {
        const val ATTEMPT_MS = 10_000
        const val PREFIX_ATTEMPT_MS = 30_000
        const val RETRY_MS = 4_000L
        val HIDDEN_BSSIDS = setOf("02:00:00:00:00:00", "00:00:00:00:00:00")
        const val UNKNOWN_SSID = "<unknown ssid>"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var attempt = 0
    @Volatile private var wanted = false

    /** The dash network while joined. */
    @Volatile var network: Network? = null
        private set

    /** Exact SSID of the joined dash (needed for its auth handshake), "" until known. */
    @Volatile var currentSsid = ""
        private set

    fun start() = main.post { wanted = true; attempt = 0; request() }

    fun stop() = main.post {
        wanted = false
        release()
    }

    private fun request() {
        release()
        if (!wanted) return
        if (!wifi.isWifiEnabled) log("dash wifi: the phone's Wi-Fi is off; turn it on")
        attempt++
        // By prefix only until the dash's name is known: a prefix request opens Android's Wi-Fi
        // picker on the phone screen (seen 2026-11-01). Clearing the saved name in the app
        // searches by prefix again.
        val exact = ssid.isNotBlank()
        val pinned = if (exact && attempt % 2 == 1) bssid.takeIf { it.isNotBlank() && it !in HIDDEN_BSSIDS } else null
        val spec = WifiNetworkSpecifier.Builder().apply {
            if (exact) setSsid(ssid) else setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
            pinned?.let { setBssid(MacAddress.fromString(it)) }
            if (password.isNotBlank()) setWpa2Passphrase(password)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        log("dash wifi: looking for ${if (exact) "'$ssid'" else "'$prefix*'"}${pinned?.let { " at $it" } ?: ""} (try $attempt)")
        val cb = if (Build.VERSION.SDK_INT >= 31) Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) else Callback()
        callback = cb
        runCatching { cm.requestNetwork(req, cb, if (exact) ATTEMPT_MS else PREFIX_ATTEMPT_MS) }
            .onFailure { log("dash wifi: request failed: ${it.message}"); retry() }
    }

    private fun retry() {
        // Back off while the dash stays away (each try is a Wi-Fi scan): 4, 8, 12, then every 16 s.
        if (wanted) main.postDelayed({ if (wanted && network == null) request() }, RETRY_MS * attempt.coerceIn(1, 4))
    }

    private fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        currentSsid = ""
    }

    private inner class Callback : ConnectivityManager.NetworkCallback {
        constructor() : super()
        constructor(flags: Int) : super(flags)

        override fun onAvailable(n: Network) {
            main.post {
                network = n
                attempt = 0
                if (currentSsid.isBlank() && ssid.isNotBlank()) currentSsid = ssid
                log("dash wifi: joined")
            }
        }

        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiInfo ?: return
            val s = info.ssid.orEmpty().trim('"').takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
            val b = info.bssid?.lowercase()?.takeIf { it !in HIDDEN_BSSIDS }
            main.post {
                if (s != null && s != currentSsid) { currentSsid = s; log("dash wifi: SSID $s") }
                if ((s != null && s != ssid) || (b != null && b != bssid)) {
                    s?.let { ssid = it }; b?.let { bssid = it }
                    onResolved(ssid, bssid)
                }
            }
        }

        override fun onUnavailable() {
            main.post { log("dash wifi: dash not found"); network = null; retry() }
        }

        override fun onLost(n: Network) {
            main.post { log("dash wifi: lost the dash"); network = null; currentSsid = ""; retry() }
        }
    }
}
