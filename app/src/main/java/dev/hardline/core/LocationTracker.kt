package dev.hardline.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/** Keeps the most recent position while something (overlay, picture metadata) needs it. */
class LocationTracker(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    @Volatile var last: Location? = null
        private set
    private var listening = false
    private val listener = LocationListener { last = it }

    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun setActive(active: Boolean) {
        if (active == listening) return
        if (!active) {
            manager.removeUpdates(listener)
            listening = false
            return
        }
        if (!permitted) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    manager.getLastKnownLocation(provider)?.let { if (last == null || it.time > last!!.time) last = it }
                    listening = true
                }
            }
        }
    }
}
