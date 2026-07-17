package com.tetraploid.joyforold.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/** 读取最近一次定位（不主动请求权限；无权限则返回 null）。 */
object DeviceLocation {
    fun lastKnown(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return providers
            .distinct()
            .mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }
}
