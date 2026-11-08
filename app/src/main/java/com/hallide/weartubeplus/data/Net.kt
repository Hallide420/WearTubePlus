package com.hallide.weartubeplus.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True unless we positively know there is no usable network. */
    fun isOnline(): Boolean {
        val ctx = appContext ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * True when the active network is unmetered (Wi-Fi/ethernet).
     * Used by the Wi-Fi-only download guard; unknown state returns false so a
     * metered connection is never assumed safe.
     */
    fun isUnmetered(): Boolean {
        val ctx = appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** True while the battery is charging or full. */
    fun isCharging(): Boolean {
        val ctx = appContext ?: return false
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java) ?: return false
        return bm.isCharging
    }

    /**
     * System DNS (IPv4 + IPv6).
     *
     * An IPv4-only Dns was tried 2026-08-18 to dodge googlevideo's `ip=` URL binding
     * breaking when Android rotates RFC-4941 IPv6 privacy addresses. It DID change the
     * bound address (URLs came back with ip=<v4>), but it is not enabled because the
     * two egresses are not equivalent: this network's IPv4 NAT address is shared with
     * other machines and can carry their bot-flag reputation, while the watch's IPv6
     * egress is its own. Forcing v4 therefore traded a rare failure for a common one.
     * If mid-playback 403 storms are ever traced to a v6 address change, revisit —
     * but verify the v4 egress is clean first.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
