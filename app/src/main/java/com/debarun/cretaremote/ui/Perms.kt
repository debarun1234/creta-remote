package com.debarun.cretaremote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Permission state and the shortcuts into system settings. */
object Perms {
    private const val PREFS = "perm_asked"

    fun locationAllowed(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Android only shows each permission dialog a couple of times, so remember that we already asked. */
    fun asked(ctx: Context, key: String): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    fun markAsked(ctx: Context, key: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()

    fun openAppSettings(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
    )

    fun openNotificationSettings(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
    )

    private fun start(ctx: Context, i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* no settings app */ }
    }
}

/**
 * The phone's own position while [enabled] and this composable is on screen. Uses the platform location service
 * directly (no Google services) and stops listening as soon as the screen is left. Nothing is stored or sent.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberPhoneLocation(enabled: Boolean): State<Pair<Double, Double>?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Pair<Double, Double>?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) {
            state.value = null
            return@DisposableEffect onDispose { }
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { state.value = location.latitude to location.longitude }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        if (lm != null) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (!lm.isProviderEnabled(p)) continue
                    lm.getLastKnownLocation(p)?.let { if (state.value == null) state.value = it.latitude to it.longitude }
                    lm.requestLocationUpdates(p, 4_000L, 3f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    // this provider needs a permission level we were not given
                }
            }
        }
        onDispose { try { lm?.removeUpdates(listener) } catch (e: Exception) { } }
    }
    return state
}
