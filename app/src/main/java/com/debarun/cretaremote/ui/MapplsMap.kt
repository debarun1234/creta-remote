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
    onFail: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // started here, on first use, so a problem with the Mappls SDK can only ever cost this map and never the app opening
    val view = remember(points, legs) {
        runCatching {
            com.mappls.sdk.maps.Mappls.getInstance(ctx.applicationContext)
            MapView(ctx)
        }.getOrNull()
    }
    if (view == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onFail() }
        return
    }
    DisposableEffect(view, owner) {
        var destroyed = false
        view.onCreate(null)
        view.getMapAsync(object : OnMapReadyCallback {
            override fun onMapReady(map: MapplsMap) {
                try { draw(map, points, legs, numbered, onFail) } catch (e: Throwable) { onFail() }
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

private fun draw(map: MapplsMap, points: List<Pair<Double, Double>>, legs: List<MapLeg>, numbered: Boolean, onFail: () -> Unit) {
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
            onFail()
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
