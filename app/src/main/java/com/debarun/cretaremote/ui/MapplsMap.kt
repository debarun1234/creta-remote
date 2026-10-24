package com.debarun.cretaremote.ui

import android.graphics.Color as AColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mappls.sdk.maps.MapView
import com.mappls.sdk.maps.MapplsMap
import com.mappls.sdk.maps.OnMapReadyCallback
import com.mappls.sdk.maps.annotations.MarkerOptions
import com.mappls.sdk.maps.annotations.PolylineOptions
import com.mappls.sdk.maps.camera.CameraUpdateFactory
import com.mappls.sdk.maps.geometry.LatLng
import com.mappls.sdk.maps.geometry.LatLngBounds

/**
 * The same picture as [CarMap]'s route view (numbered stops joined by lines) on Mappls' map, the one Bluelink's own app uses.
 * It is created once per set of points; [onFail] is called if the Mappls map cannot start (for example its config belongs to a
 * different signing key, as with debug builds), and the caller then shows the OpenStreetMap view instead.
 */
@Composable
fun MapplsRouteMap(
    points: List<Pair<Double, Double>>,
    legs: List<MapLeg>,
    numbered: Boolean,
    modifier: Modifier = Modifier,
    onFail: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(points, legs) { runCatching { MapView(ctx) }.getOrNull() }
    if (view == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onFail() }
        return
    }
    DisposableEffect(view, owner) {
        var destroyed = false
        view.onCreate(null)
        view.getMapAsync(object : OnMapReadyCallback {
            override fun onMapReady(map: MapplsMap) {
                try { draw(map, points, legs, numbered) } catch (e: Throwable) { onFail() }
            }

            override fun onMapError(code: Int, message: String?) { onFail() }
        })
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (!destroyed) {
                destroyed = true
                runCatching { view.onPause(); view.onStop(); view.onDestroy() }
            }
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private fun draw(map: MapplsMap, points: List<Pair<Double, Double>>, legs: List<MapLeg>, numbered: Boolean) {
    for (leg in legs) {
        if (leg.pts.size < 2) continue
        map.addPolyline(
            PolylineOptions()
                .addAll(leg.pts.map { LatLng(it.first, it.second) })
                .color(if (leg.straight) AColor.parseColor("#9AA0A6") else AColor.parseColor("#E5383B"))
                .width(if (leg.straight) 2f else 4f),
        )
    }
    points.forEachIndexed { i, p ->
        val m = MarkerOptions().position(LatLng(p.first, p.second))
        if (numbered) m.title("${i + 1}")
        map.addMarker(m)
    }
    val all = (points + legs.flatMap { it.pts })
    if (all.isEmpty()) return
    val lo = all.minOf { it.first } to all.minOf { it.second }
    val hi = all.maxOf { it.first } to all.maxOf { it.second }
    if (hi.first - lo.first < 0.0005 && hi.second - lo.second < 0.0005) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lo.first, lo.second), 15.0))
    } else {
        val b = LatLngBounds.Builder().include(LatLng(lo.first, lo.second)).include(LatLng(hi.first, hi.second)).build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 80))
    }
}
