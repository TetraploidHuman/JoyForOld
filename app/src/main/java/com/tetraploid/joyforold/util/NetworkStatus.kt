package com.tetraploid.joyforold.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.VisibleForTesting

object NetworkStatus {
    @VisibleForTesting
    internal var forceOnline: Boolean? = null

    fun hasInternet(context: Context): Boolean {
        forceOnline?.let { return it }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun offlineHint(context: Context): String? {
        if (hasInternet(context)) return null
        return "手机当前无可用网络，请打开 WiFi 或移动数据后再试语音识别"
    }
}
