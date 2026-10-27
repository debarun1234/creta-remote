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
import com.mappls.sdk.maps.camera.CameraUpdateFactory
import com.mappls.sdk.maps.geometry.LatLng
import com.mappls.sdk.geojson.Feature
import com.mappls.sdk.geojson.FeatureCollection
import com.mappls.sdk.geojson.LineString
import com.mappls.sdk.geojson.Point
import com.mappls.sdk.maps.Style
import com.mappls.sdk.maps.geometry.LatLngBounds
import com.mappls.sdk.maps.style.expressions.Expression
import com.mappls.sdk.maps.style.layers.CircleLayer
import com.mappls.sdk.maps.style.layers.LineLayer
import com.mappls.sdk.maps.style.layers.PropertyFactory
import com.mappls.sdk.maps.style.layers.SymbolLayer
import com.mappls.sdk.maps.style.sources.GeoJsonSource

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
    onFail: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // if the app died the last time this map was opened, it stays off (until the next app version) rather than crashing again
    val blocked = remember { MapplsGuard.blockedReason(ctx) }
    if (blocked != null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onFail(blocked) }
        return
    }
    var startError: String? = null
    // started here, on first use, so a problem with the Mappls SDK can only ever cost this map and never the app opening
    val view = remember(points, legs) {
        try {
            MapplsGuard.begin(ctx)
            com.mappls.sdk.maps.Mappls.getInstance(ctx.applicationContext)
            MapView(ctx)
        } catch (e: Throwable) {
            MapplsGuard.ok(ctx)
            startError = "${e.javaClass.simpleName}: ${e.message?.take(80)}"
            null
        }
    }
    if (view == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onFail("the Mappls SDK could not start (${startError ?: "unknown"})") }
        return
    }
    DisposableEffect(view, owner) {
        var destroyed = false
        fun safe(step: () -> Unit) { try { step() } catch (e: Throwable) { onFail("Mappls error: ${e.javaClass.simpleName}") } }
        safe {
            view.onCreate(null)
            view.getMapAsync(object : OnMapReadyCallback {
                override fun onMapReady(map: MapplsMap) { safe { draw(map, points, legs, numbered, onFail) } }

                override fun onMapError(code: Int, message: String?) { onFail("Mappls map error $code") }
            })
        }
        val obs = LifecycleEventObserver { _, e ->
            safe {
                when (e) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> { view.onStop(); MapplsGuard.ok(ctx) }
                    else -> {}
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (!destroyed) {
                destroyed = true
                runCatching { view.onPause(); view.onStop(); view.onDestroy() }
            }
            MapplsGuard.ok(ctx)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

/** Clears the "Mappls closed the app last time" marker, for the Try again button. */
fun resetMapplsGuard(ctx: android.content.Context) {
    ctx.getSharedPreferences("mappls_guard", android.content.Context.MODE_PRIVATE).edit().remove("broken").putInt("pid", 0).apply()
}

/**
 * A crash inside the Mappls SDK's native code cannot be caught, so this remembers that a map was opened and not closed normally.
 * If the next launch finds that, Mappls stays off for this app version and the OpenStreetMap view is used instead.
 */
private object MapplsGuard {
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("mappls_guard", android.content.Context.MODE_PRIVATE)

    private fun version(ctx: android.content.Context): Long =
        runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) }.getOrDefault(0L)

    fun blockedReason(ctx: android.content.Context): String? {
        val p = prefs(ctx)
        val v = version(ctx)
        if (p.getLong("ver", -1L) != v) p.edit().clear().putLong("ver", v).commit()
        p.getString("broken", null)?.let { return it }
        val pid = p.getInt("pid", 0)
        if (pid != 0 && pid != android.os.Process.myPid()) {
            val why = "the Mappls map closed the app last time it was opened"
            p.edit().putString("broken", why).putInt("pid", 0).commit()
            return why
        }
        return null
    }

    fun begin(ctx: android.content.Context) { prefs(ctx).edit().putInt("pid", android.os.Process.myPid()).commit() }

    fun ok(ctx: android.content.Context) { prefs(ctx).edit().putInt("pid", 0).apply() }
}

private fun draw(map: MapplsMap, points: List<Pair<Double, Double>>, legs: List<MapLeg>, numbered: Boolean, onFail: (String) -> Unit) {
    // the camera first, so something sensible shows even before the style has finished loading
    val all = (points + legs.flatMap { it.pts })
    if (all.isNotEmpty()) {
        val lo = all.minOf { it.first } to all.minOf { it.second }
        val hi = all.maxOf { it.first } to all.maxOf { it.second }
        if (hi.first - lo.first < 0.0005 && hi.second - lo.second < 0.0005) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lo.first, lo.second), 15.0))
        } else {
            val b = LatLngBounds.Builder().include(LatLng(lo.first, lo.second)).include(LatLng(hi.first, hi.second)).build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 80))
        }
    }
    map.getStyle { style ->
        try {
            addLines(style, "real", legs.filter { !it.straight && it.pts.size >= 2 }, "#E5383B", 4f, dashed = false)
            addLines(style, "straight", legs.filter { it.straight && it.pts.size >= 2 }, "#9AA0A6", 2f, dashed = true)
            addStops(style, points, numbered)
        } catch (e: Throwable) {
            onFail("Mappls could not draw the route")
        }
    }
}

private fun addLines(style: Style, name: String, legs: List<MapLeg>, color: String, width: Float, dashed: Boolean) {
    if (legs.isEmpty()) return
    val features = legs.map { leg -> Feature.fromGeometry(LineString.fromLngLats(leg.pts.map { Point.fromLngLat(it.second, it.first) })) }
    style.addSource(GeoJsonSource("src-$name", FeatureCollection.fromFeatures(features)))
    val layer = LineLayer("lyr-$name", "src-$name").withProperties(
        PropertyFactory.lineColor(color),
        PropertyFactory.lineWidth(width),
        PropertyFactory.lineCap("round"),
        PropertyFactory.lineJoin("round"),
    )
    if (dashed) layer.setProperties(PropertyFactory.lineDasharray(arrayOf(2f, 2f)))
    style.addLayer(layer)
}

private fun addStops(style: Style, points: List<Pair<Double, Double>>, numbered: Boolean) {
    if (points.isEmpty()) return
    val features = points.mapIndexed { i, p ->
        Feature.fromGeometry(Point.fromLngLat(p.second, p.first)).also { it.addStringProperty("n", "${i + 1}") }
    }
    style.addSource(GeoJsonSource("src-stops", FeatureCollection.fromFeatures(features)))
    style.addLayer(
        CircleLayer("lyr-stops", "src-stops").withProperties(
            PropertyFactory.circleRadius(if (numbered) 10f else 6f),
            PropertyFactory.circleColor("#E5383B"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    if (numbered) {
        style.addLayer(
            SymbolLayer("lyr-stop-numbers", "src-stops").withProperties(
                PropertyFactory.textField(Expression.get("n")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ),
        )
    }
}
