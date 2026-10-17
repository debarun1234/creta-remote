package com.debarun.cretaremote.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.Geo
import kotlinx.coroutines.launch

/** "9 h 12 m", "2 d 3 h". */
private fun durText(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1 -> "under a minute"
        m < 60 -> "$m m"
        m < 48 * 60 -> "${m / 60} h ${m % 60} m"
        else -> "${m / 1440} d ${(m % 1440) / 60} h"
    }
}

@Composable
fun PlacesScreen(d: AppData) {
    val car = d.car
    val ctx = LocalContext.current
    val lat = car?.lat
    val lon = car?.lon
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    val now = remember(d.fetchedAt) { System.currentTimeMillis() }

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
    val since = car?.locTime ?: latest?.ts
    val placeName = latest?.label
    val trailKm = remember(d.places) {
        d.places.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.lat, a.lon, b.lat, b.lon) } / 1000.0
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Eyebrow("Location")
            Txt("Places", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            if (trail.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Txt(if (d.refreshing) "Finding your Creta…" else "Waiting for the car's parked location…", size = 13.sp, color = C.Dim)
                }
            } else {
                CarMap(
                    points = trail,
                    label = placeName ?: "Your Creta",
                    sub = since?.let { "Parked ${agoText(it, now)}" },
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    focus = focus,
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Parked")
                Txt(placeName ?: if (lat != null) "${Fmt.n(lat, 5)}, ${Fmt.n(lon ?: 0.0, 5)}" else "Unknown", size = 22.sp, weight = FontWeight.Medium)
                if (since != null) {
                    Txt("For ${durText(now - since)}  ·  since ${dateTimeText(since)}", size = 13.sp, color = C.Dim)
                }
                if (lat != null && lon != null) {
                    Txt("${Fmt.n(lat, 5)}, ${Fmt.n(lon, 5)}", size = 11.sp, color = C.Faint, mono = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { GhostButton("Navigate", { if (lat != null) open("google.navigation:q=$lat,$lon&mode=d") }, color = C.Green) }
                    Box(Modifier.weight(1f)) { GhostButton("Open map", { if (lat != null) open("geo:$lat,$lon?q=$lat,$lon(My Creta)") }) }
                    Box(Modifier.weight(1f)) { GhostButton("Share", { if (lat != null) open("https://maps.google.com/?q=$lat,$lon", chooser = true) }) }
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Parking history", Modifier.weight(1f))
                    if (d.places.size > 1) Txt("${d.places.size} places · ${Fmt.n(trailKm, if (trailKm < 10) 1 else 0)} km", size = 11.sp, color = C.Faint)
                }
                if (d.places.isEmpty()) {
                    Txt("Nothing yet. Each time the car parks somewhere new it is added here.", Modifier.padding(top = 10.dp), size = 13.sp, color = C.Dim)
                }
                val shown = d.places.take(20)
                shown.forEachIndexed { i, p ->
                    val older = d.places.getOrNull(i + 1)
                    val newer = d.places.getOrNull(i - 1)
                    val stay = (newer?.ts ?: now) - p.ts
                    val a by animateFloatAsState(1f, tween(500, i * 60), label = "ph")
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min).graphicsLayer { alpha = a }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                focus = MapFocus(p.lat, p.lon, (focus?.n ?: 0) + 1)
                                scope.launch { scroll.animateScrollTo(0) }
                            },
                    ) {
                        Column(Modifier.width(22.dp).fillMaxHeight().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (i == 0) C.Red else C.Faint))
                            if (i < shown.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(C.Line))
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp, top = 12.dp, bottom = 12.dp)) {
                            Txt(p.label ?: "${Fmt.n(p.lat, 4)}, ${Fmt.n(p.lon, 4)}", size = 15.sp, weight = FontWeight.Medium)
                            Txt(dateTimeText(p.ts), size = 12.sp, color = C.Faint)
                            Txt(if (i == 0) "Parked ${durText(stay)} so far" else "Stayed ${durText(stay)}", size = 12.sp, color = C.Dim)
                        }
                        if (older != null) {
                            val km = Geo.distanceM(older.lat, older.lon, p.lat, p.lon) / 1000.0
                            Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.End) {
                                Txt("${Fmt.n(km, if (km < 10) 1 else 0)} km", size = 13.sp, color = C.Dim)
                                Txt("from previous", size = 10.sp, color = C.Faint)
                            }
                        }
                    }
                }
                Txt(
                    "Bluelink reports where the car is parked, not a live GPS track. The trail connects the places it has parked since this app started recording. Tap a place to see it on the map.",
                    Modifier.padding(top = 14.dp), size = 11.sp, color = C.Faint,
                )
            }
        }
    }
}
