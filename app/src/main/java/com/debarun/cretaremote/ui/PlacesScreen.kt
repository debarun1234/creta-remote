package com.debarun.cretaremote.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

@Composable
fun PlacesScreen(d: AppData) {
    val car = d.car
    val ctx = LocalContext.current
    val lat = car?.lat
    val lon = car?.lon

    fun open(uri: String, chooser: Boolean = false) {
        try {
            val i = if (chooser) Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri), "Share car location")
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { /* nothing can handle it */ }
    }

    val trail = remember(d.places, lat, lon) {
        val pts = d.places.reversed().takeLast(40).map { it.lat to it.lon }.toMutableList()
        if (lat != null && lon != null && (pts.isEmpty() || Geo.distanceM(pts.last().first, pts.last().second, lat, lon) > 20.0)) pts += lat to lon
        pts.toList()
    }
    val latest = d.places.firstOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Eyebrow("Location")
            Txt("Places", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            RadarMap(trail, Modifier.fillMaxWidth().height(320.dp))
            if (trail.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    Txt("Waiting for the car's parked location…", size = 13.sp, color = C.Dim)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Parked")
                Txt(latest?.label ?: if (lat != null) "${Fmt.n(lat, 5)}, ${Fmt.n(lon ?: 0.0, 5)}" else "Unknown", size = 20.sp, weight = FontWeight.Medium)
                val since = car?.locTime
                if (since != null) Txt("Since ${dateTimeText(since)} · ${agoText(since)}", size = 13.sp, color = C.Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { GhostButton("Navigate", { if (lat != null) open("google.navigation:q=$lat,$lon&mode=d") }, color = C.Green) }
                    Box(Modifier.weight(1f)) { GhostButton("Open map", { if (lat != null) open("geo:$lat,$lon?q=$lat,$lon(My Creta)") }) }
                    Box(Modifier.weight(1f)) { GhostButton("Share", { if (lat != null) open("https://maps.google.com/?q=$lat,$lon", chooser = true) }) }
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Eyebrow("Parking history")
                if (d.places.isEmpty()) {
                    Txt("Nothing yet. Each time the car parks somewhere new it is added here.", Modifier.padding(top = 10.dp), size = 13.sp, color = C.Dim)
                }
                d.places.take(20).forEachIndexed { i, p ->
                    val older = d.places.getOrNull(i + 1)
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (i == 0) C.Red else C.Faint))
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Txt(p.label ?: "${Fmt.n(p.lat, 4)}, ${Fmt.n(p.lon, 4)}", size = 15.sp, weight = FontWeight.Medium)
                            Txt(dateTimeText(p.ts), size = 12.sp, color = C.Faint)
                        }
                        if (older != null) {
                            val km = Geo.distanceM(older.lat, older.lon, p.lat, p.lon) / 1000.0
                            Txt("${Fmt.n(km, if (km < 10) 1 else 0)} km", size = 13.sp, color = C.Dim)
                        }
                    }
                }
                Txt(
                    "Bluelink reports where the car is parked, not a live GPS track. The trail connects the places it has parked since this app started recording.",
                    Modifier.padding(top = 18.dp), size = 11.sp, color = C.Faint,
                )
            }
        }
    }
}

/** A tile-free radar map: grid rings with real-world scale, an animated trail between parking spots and a pulsing marker. */
@Composable
fun RadarMap(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "radar")
    val sweep by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val draw = remember { Animatable(0f) }
    LaunchedEffect(points) { draw.snapTo(0f); draw.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Offset(w / 2f, h / 2f)
        val radius = min(w, h) / 2f - 6.dp.toPx()
        val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - radius, c.y - radius, c.x + radius, c.y + radius)) }

        clipPath(disc) {
            drawRect(Brush.radialGradient(listOf(Color(0xFF14171F), Color(0xFF0A0C11)), c, radius), size = size)
            // grid
            val step = 28.dp.toPx()
            var x = c.x % step
            while (x < w) { drawLine(Color(0x0FFFFFFF), Offset(x, 0f), Offset(x, h), 1f); x += step }
            var y = c.y % step
            while (y < h) { drawLine(Color(0x0FFFFFFF), Offset(0f, y), Offset(w, y), 1f); y += step }
        }

        if (points.isEmpty()) { drawCircle(C.Line, radius, c, style = Stroke(1.5f)); return@Canvas }

        // projection
        val latMean = points.map { it.first }.average()
        val lonMean = points.map { it.second }.average()
        val kx = cos(Math.toRadians(latMean))
        val xs = points.map { (it.second - lonMean) * kx }
        val ys = points.map { -(it.first - latMean) }
        val extent = max(max(xs.max() - xs.min(), ys.max() - ys.min()), 0.0006)
        val cx = (xs.max() + xs.min()) / 2
        val cy = (ys.max() + ys.min()) / 2
        val scale = (radius * 1.25 / extent)
        fun px(i: Int) = Offset((c.x + (xs[i] - cx) * scale).toFloat(), (c.y + (ys[i] - cy) * scale).toFloat())

        // distance rings: pick the smallest "nice" spacing that is at least ~60dp apart
        val mPerPx = 111_320.0 / scale
        val nice = listOf(25.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0)
        val ringM = nice.firstOrNull { it / mPerPx >= 60.dp.toPx() } ?: nice.last()
        val ringPx = (ringM / mPerPx).toFloat()
        var k = 1
        while (ringPx * k < radius) {
            drawCircle(Color(0x22FFFFFF), ringPx * k, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))))
            k++
        }
        drawCircle(Color(0x33FFFFFF), radius, c, style = Stroke(1.5.dp.toPx()))

        clipPath(disc) {
            // radar sweep
            rotate(sweep, c) {
                drawCircle(
                    Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to Color(0x40E3192D), center = c),
                    radius, c,
                )
            }
            // trail
            if (points.size >= 2) {
                val path = Path()
                path.moveTo(px(0).x, px(0).y)
                for (i in 1 until points.size) path.lineTo(px(i).x, px(i).y)
                val pm = PathMeasure(); pm.setPath(path, false)
                val seg = Path(); pm.getSegment(0f, pm.length * draw.value, seg, true)
                drawPath(seg, C.Red.copy(alpha = 0.25f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(seg, C.RedHi, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            }
            for (i in 0 until points.size - 1) {
                drawCircle(C.Dim, 4.dp.toPx(), px(i))
                drawCircle(C.Bg, 2.dp.toPx(), px(i))
            }

            // car marker
            val p = px(points.size - 1)
            drawCircle(C.Red.copy(alpha = (1f - pulse) * 0.55f), 10.dp.toPx() + 30.dp.toPx() * pulse, p)
            drawCircle(C.Red.copy(alpha = 0.25f), 16.dp.toPx(), p)
            drawCircle(Color.White, 8.dp.toPx(), p)
            drawCircle(C.Red, 5.dp.toPx(), p)
        }
    }
}
