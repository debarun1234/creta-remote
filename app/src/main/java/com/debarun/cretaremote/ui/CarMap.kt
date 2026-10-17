package com.debarun.cretaremote.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/*
 * A small slippy map drawn straight onto a Compose canvas: OpenStreetMap tiles (recoloured dark, no API key) fetched
 * over OkHttp with a disk cache, pinch / drag / double-finger zoom, a fly-in when the screen opens, the
 * parking trail with a flowing dash, and the car's own top-down photo as the marker.
 * Map data (c) OpenStreetMap contributors - the attribution is drawn on the map.
 */

// ------------------------------------------------------------------------------------------ tiles

/** OpenStreetMap tiles are light; this inverts them to a dark, slightly cool grey so the map suits the app. */
private val DarkMap: ColorFilter = run {
    fun row(tint: Float) = floatArrayOf(-0.85f * 0.299f * tint, -0.85f * 0.587f * tint, -0.85f * 0.114f * tint, 0f, 0.8875f * 255f * tint)
    val r = row(0.95f)
    val g = row(1.0f)
    val b = row(1.12f)
    ColorFilter.colorMatrix(ColorMatrix(r + g + b + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}

object TileCache {
    private val mem = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val inflight = ConcurrentHashMap<String, Boolean>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    @Volatile private var http: OkHttpClient? = null

    private fun client(ctx: Context): OkHttpClient = http ?: synchronized(this) {
        http ?: OkHttpClient.Builder()
            .cache(Cache(File(ctx.cacheDir, "tiles"), 50L * 1024 * 1024))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build().also { http = it }
    }

    fun peek(z: Int, x: Int, y: Int): ImageBitmap? = mem.get("$z/$x/$y")

    /** Returns true when a new tile has landed in memory. */
    suspend fun fetch(ctx: Context, z: Int, x: Int, y: Int): Boolean {
        val k = "$z/$x/$y"
        if (mem.get(k) != null) return false
        val bad = failedAt[k]
        if (bad != null && System.currentTimeMillis() - bad < 15_000L) return false
        if (inflight.putIfAbsent(k, true) != null) return false
        return try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://tile.openstreetmap.org/$z/$x/$y.png")
                    .header("User-Agent", "CretaRemote/1.0 (personal Android app)")
                    .build()
                client(ctx).newCall(req).execute().use { r ->
                    val bmp = if (r.isSuccessful) r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) } else null
                    if (bmp == null) {
                        failedAt[k] = System.currentTimeMillis()
                        false
                    } else {
                        mem.put(k, bmp.asImageBitmap())
                        true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[k] = System.currentTimeMillis()
            false
        } finally {
            inflight.remove(k)
        }
    }
}

// ------------------------------------------------------------------------------------------ projection

/** World size in dp at zoom z (256 dp per tile, as in standard web mercator). */
private fun worldSize(z: Double) = 256.0 * 2.0.pow(z)
private fun wx(lon: Double, z: Double) = (lon + 180.0) / 360.0 * worldSize(z)
private fun wy(lat: Double, z: Double): Double {
    val s = sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
    return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(z)
}
private fun lonOf(x: Double, z: Double) = x / worldSize(z) * 360.0 - 180.0
private fun latOf(y: Double, z: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / worldSize(z)))))

private class MapView(val z: Int, val n: Int, val tilePx: Float, val tx: Double, val ty: Double, val x0: Int, val x1: Int, val y0: Int, val y1: Int)

private fun mapView(lat: Double, lon: Double, zoom: Float, w: Int, h: Int, dens: Float): MapView {
    val zi = floor(zoom).toInt().coerceIn(2, 18)
    val tilePx = (256.0 * 2.0.pow((zoom - zi).toDouble()) * dens).toFloat()
    val tx = wx(lon, zi.toDouble()) / 256.0
    val ty = wy(lat, zi.toDouble()) / 256.0
    val hx = w / 2f / tilePx
    val hy = h / 2f / tilePx
    return MapView(zi, 1 shl zi, tilePx, tx, ty, floor(tx - hx).toInt(), floor(tx + hx).toInt(), floor(ty - hy).toInt(), floor(ty + hy).toInt())
}

private fun tilesOf(v: MapView): List<Triple<Int, Int, Int>> {
    val out = ArrayList<Triple<Int, Int, Int>>()
    for (x in v.x0..v.x1) for (y in v.y0..v.y1) {
        if (y in 0 until v.n) out += Triple(v.z, Math.floorMod(x, v.n), y)
    }
    // centre first, and never more than a screenful
    return out.sortedBy { (_, x, y) -> (x - v.tx).let { it * it } + (y - v.ty).let { it * it } }.take(48)
}

/** Zoom that fits every point with [padDp] of room, and the centre of their box. */
private fun fitCamera(pts: List<Pair<Double, Double>>, wPx: Int, hPx: Int, dens: Float, padDp: Float = 64f): Triple<Double, Double, Float> {
    val xs = pts.map { (it.second + 180.0) / 360.0 }
    val ys = pts.map { wy(it.first, 0.0) / 256.0 }
    val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-7)
    val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-7)
    val availW = (wPx / dens - 2 * padDp).coerceAtLeast(60f).toDouble()
    val availH = (hPx / dens - 2 * padDp).coerceAtLeast(60f).toDouble()
    val z = min(ln(availW / (256.0 * spanX)), ln(availH / (256.0 * spanY))) / ln(2.0)
    val cx = (xs.max() + xs.min()) / 2
    val cy = (ys.max() + ys.min()) / 2
    return Triple(latOf(cy * 256.0, 0.0), cx * 360.0 - 180.0, z.coerceIn(3.0, 17.0).toFloat())
}

// ------------------------------------------------------------------------------------------ the map

/** A request to move the map somewhere; a new [n] re-triggers even for the same place. */
data class MapFocus(val lat: Double, val lon: Double, val n: Int)

@Composable
fun CarMap(
    points: List<Pair<Double, Double>>,
    label: String?,
    sub: String?,
    modifier: Modifier = Modifier,
    focus: MapFocus? = null,
    me: Pair<Double, Double>? = null,
) {
    val ctx = LocalContext.current
    val dens = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val carPhoto = ImageBitmap.imageResource(R.drawable.creta_top_vehicle_report)
    val car = points.lastOrNull()

    var cLat by remember { mutableDoubleStateOf(car?.first ?: 12.9716) }
    var cLon by remember { mutableDoubleStateOf(car?.second ?: 77.5946) }
    var zoom by remember { mutableFloatStateOf(4f) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    var flyJob by remember { mutableStateOf<Job?>(null) }

    fun flyTo(lat: Double, lon: Double, z: Float, ms: Int) {
        flyJob?.cancel()
        flyJob = scope.launch {
            val sLat = cLat
            val sLon = cLon
            val sZ = zoom
            animate(0f, 1f, animationSpec = tween(ms, easing = FastOutSlowInEasing)) { t, _ ->
                cLat = sLat + (lat - sLat) * t
                cLon = sLon + (lon - sLon) * t
                zoom = sZ + (z - sZ) * t
            }
        }
    }

    // fly-in: start a few zoom levels out and glide down onto the car
    LaunchedEffect(car, sizePx) {
        if (car == null || sizePx == IntSize.Zero) return@LaunchedEffect
        cLat = car.first
        cLon = car.second
        zoom = 12.5f
        flyTo(car.first, car.second, 16f, 1900)
    }
    LaunchedEffect(focus) {
        if (focus != null && sizePx != IntSize.Zero) flyTo(focus.lat, focus.lon, 16.5f, 1000)
    }

    // fetch whatever tiles the current view needs
    LaunchedEffect(sizePx) {
        if (sizePx == IntSize.Zero) return@LaunchedEffect
        snapshotFlow { tilesOf(mapView(cLat, cLon, zoom, sizePx.width, sizePx.height, dens)) }
            .distinctUntilChanged()
            .collectLatest { list ->
                coroutineScope {
                    list.forEach { (z, x, y) -> launch { if (TileCache.fetch(ctx, z, x, y)) tick++ } }
                }
            }
    }

    val inf = rememberInfiniteTransition(label = "map")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val flow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "flow")
    val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(points) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1800, 900, FastOutSlowInEasing)) }

    Box(modifier.onSizeChanged { sizePx = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    flyJob?.cancel()
                    val d = dens.toDouble()
                    val z0 = zoom.toDouble()
                    val hx = sizePx.width / 2.0
                    val hy = sizePx.height / 2.0
                    val nz = (z0 + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(3.0, 18.5)
                    // world point under the fingers after the drag, then keep it under the fingers while zooming
                    val px = wx(cLon, z0) - pan.x / d + (centroid.x - hx) / d
                    val py = wy(cLat, z0) - pan.y / d + (centroid.y - hy) / d
                    val k = 2.0.pow(nz - z0)
                    val nx = px * k - (centroid.x - hx) / d
                    val ny = py * k - (centroid.y - hy) / d
                    cLon = lonOf(nx, nz)
                    cLat = latOf(ny, nz).coerceIn(-84.0, 84.0)
                    zoom = nz.toFloat()
                }
            },
        ) {
            val subscribe = tick // redraw when a tile lands
            if (subscribe < 0) return@Canvas
            val w = size.width
            val h = size.height
            val half = Offset(w / 2f, h / 2f)
            val z = zoom.toDouble()
            drawRect(Color(0xFF0B0D12))

            // ---- tiles (with a blurry parent tile standing in while the sharp one loads)
            val v = mapView(cLat, cLon, zoom, w.toInt(), h.toInt(), dens)
            for (x in v.x0..v.x1) for (y in v.y0..v.y1) {
                if (y !in 0 until v.n) continue
                val xw = Math.floorMod(x, v.n)
                val left = half.x + ((x - v.tx) * v.tilePx).toFloat()
                val top = half.y + ((y - v.ty) * v.tilePx).toFloat()
                val l = left.roundToInt()
                val t = top.roundToInt()
                val dstSize = IntSize((left + v.tilePx).roundToInt() - l, (top + v.tilePx).roundToInt() - t)
                val img = TileCache.peek(v.z, xw, y)
                if (img != null) {
                    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(l, t), dstSize, filterQuality = FilterQuality.Medium, colorFilter = DarkMap)
                } else {
                    var k = 1
                    while (k <= 3 && v.z - k >= 0) {
                        val a = TileCache.peek(v.z - k, xw shr k, y shr k)
                        if (a != null) {
                            val sw = a.width shr k
                            val sh = a.height shr k
                            val sx = (xw - ((xw shr k) shl k)) * sw
                            val sy = (y - ((y shr k) shl k)) * sh
                            drawImage(a, IntOffset(sx, sy), IntSize(sw, sh), IntOffset(l, t), dstSize, filterQuality = FilterQuality.Medium, colorFilter = DarkMap)
                            break
                        }
                        k++
                    }
                }
            }
            // soft vignette so the map melts into the card
            drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, Color.Transparent, Color(0x80000000))))

            fun toScreen(p: Pair<Double, Double>) = Offset(
                (half.x + (wx(p.second, z) - wx(cLon, z)) * dens).toFloat(),
                (half.y + (wy(p.first, z) - wy(cLat, z)) * dens).toFloat(),
            )

            // ---- trail between parking spots
            val pts = points.map { toScreen(it) }
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                }
                val pm = PathMeasure().also { it.setPath(path, false) }
                val seg = Path()
                pm.getSegment(0f, pm.length * reveal.value, seg, true)
                drawPath(seg, C.Red.copy(alpha = 0.28f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(
                    seg, C.RedHi,
                    style = Stroke(
                        2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()), -flow * 24.dp.toPx()),
                    ),
                )
            }
            for (i in 0 until pts.size - 1) {
                drawCircle(Color(0xFF0B0D12), 6.dp.toPx(), pts[i])
                drawCircle(C.RedHi, 4.dp.toPx(), pts[i])
                drawCircle(Color(0xFF0B0D12), 1.8.dp.toPx(), pts[i])
            }

            // ---- you (the phone), with a line and the distance to the car
            if (me != null && pts.isNotEmpty()) {
                val m = toScreen(me)
                val c = pts.last()
                drawLine(
                    C.Cyan.copy(alpha = 0.85f), m, c, 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()), -flow * 12.dp.toPx()),
                )
                val pr = (pulse + 0.25f) % 1f
                drawCircle(C.Cyan.copy(alpha = (1f - pr) * 0.45f), 8.dp.toPx() + 22.dp.toPx() * pr, m, style = Stroke(1.5.dp.toPx()))
                drawCircle(Color.White, 8.dp.toPx(), m)
                drawCircle(C.Cyan, 5.dp.toPx(), m)
                val meters = com.debarun.cretaremote.data.Geo.distanceM(me.first, me.second, car!!.first, car.second)
                val txt = measurer.measure(if (meters < 1000) "${meters.roundToInt()} m" else "${Fmt.n(meters / 1000.0, 1)} km", TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                val mid = Offset((m.x + c.x) / 2f, (m.y + c.y) / 2f)
                val bw = txt.size.width + 16.dp.toPx()
                val bh = txt.size.height + 8.dp.toPx()
                drawRoundRect(Color(0xE6101218), Offset(mid.x - bw / 2f, mid.y - bh / 2f), Size(bw, bh), CornerRadius(bh / 2f))
                drawText(txt, topLeft = Offset(mid.x - txt.size.width / 2f, mid.y - txt.size.height / 2f))
            }

            // ---- the car
            if (pts.isNotEmpty()) {
                val p = pts.last()
                for (ph in listOf(0f, 0.5f)) {
                    val t = (pulse + ph) % 1f
                    drawCircle(C.Red.copy(alpha = (1f - t) * 0.5f), 16.dp.toPx() + 40.dp.toPx() * t, p, style = Stroke(2.dp.toPx()))
                }
                drawCircle(C.Red.copy(alpha = 0.22f), 28.dp.toPx(), p)
                drawCircle(Color(0xF2101218), 22.dp.toPx(), p)
                drawCircle(C.Red, 22.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                val ih = 36.dp.toPx()
                val iw = ih * carPhoto.width / carPhoto.height
                drawImage(
                    carPhoto, IntOffset.Zero, IntSize(carPhoto.width, carPhoto.height),
                    IntOffset((p.x - iw / 2f).roundToInt(), (p.y - ih / 2f).roundToInt()), IntSize(iw.roundToInt(), ih.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
                if (label != null) drawLabel(measurer, label, sub, p, w, h)
            }

            drawScaleBar(measurer, cLat, zoom, dens, h)
        }

        // ---- controls
        Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(Icons.Rounded.Add) { flyTo(cLat, cLon, (zoom + 1f).coerceAtMost(18.5f), 350) }
            MapButton(Icons.Rounded.Remove) { flyTo(cLat, cLon, (zoom - 1f).coerceAtLeast(3f), 350) }
            if (car != null) MapButton(Icons.Rounded.MyLocation) { flyTo(car.first, car.second, 16f, 1100) }
            if (points.size > 1 || me != null) MapButton(Icons.Rounded.Timeline) {
                val (la, lo, zz) = fitCamera(points + listOfNotNull(me), sizePx.width, sizePx.height, dens)
                flyTo(la, lo, zz, 1200)
            }
        }
        Txt(
            "© OpenStreetMap contributors",
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
            size = 9.sp, color = Color(0x99FFFFFF),
        )
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0xE6101218)).border(1.dp, C.Line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(20.dp))
    }
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    label: String,
    sub: String?,
    at: Offset,
    w: Float,
    h: Float,
) {
    val a = measurer.measure(label, TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    val b = sub?.let { measurer.measure(it, TextStyle(color = C.Dim, fontSize = 10.sp)) }
    val padH = 12.dp.toPx()
    val padV = 8.dp.toPx()
    val bw = max(a.size.width, b?.size?.width ?: 0) + 2 * padH
    val bh = a.size.height + (b?.size?.height ?: 0) + 2 * padV
    val x = (at.x - bw / 2f).coerceIn(8.dp.toPx(), max(8.dp.toPx(), w - bw - 8.dp.toPx()))
    var y = at.y + 32.dp.toPx()
    if (y + bh > h - 20.dp.toPx()) y = at.y - 32.dp.toPx() - bh
    drawRoundRect(Color(0xE6101218), Offset(x, y), Size(bw, bh), CornerRadius(14.dp.toPx()))
    drawRoundRect(C.Line, Offset(x, y), Size(bw, bh), CornerRadius(14.dp.toPx()), style = Stroke(1.dp.toPx()))
    drawText(a, topLeft = Offset(x + padH, y + padV))
    if (b != null) drawText(b, topLeft = Offset(x + padH, y + padV + a.size.height))
}

private fun DrawScope.drawScaleBar(measurer: androidx.compose.ui.text.TextMeasurer, lat: Double, zoom: Float, dens: Float, h: Float) {
    // ground resolution: 156543 m per 256-unit tile pixel at zoom 0, and a tile is 256 dp wide here
    val mPerDp = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom.toDouble())
    val nice = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0, 1_000_000.0)
    val m = nice.lastOrNull { it <= 90.0 * mPerDp } ?: nice.first()
    val len = (m / mPerDp * dens).toFloat()
    val x = 14.dp.toPx()
    val y = h - 18.dp.toPx()
    val c = Color(0xCCFFFFFF)
    drawLine(c, Offset(x, y), Offset(x + len, y), 1.5.dp.toPx())
    drawLine(c, Offset(x, y - 4.dp.toPx()), Offset(x, y), 1.5.dp.toPx())
    drawLine(c, Offset(x + len, y - 4.dp.toPx()), Offset(x + len, y), 1.5.dp.toPx())
    val text = if (m >= 1000) "${(m / 1000).toInt()} km" else "${m.toInt()} m"
    val t = measurer.measure(text, TextStyle(color = c, fontSize = 10.sp))
    drawText(t, topLeft = Offset(x + len + 6.dp.toPx(), y - t.size.height / 2f))
}
