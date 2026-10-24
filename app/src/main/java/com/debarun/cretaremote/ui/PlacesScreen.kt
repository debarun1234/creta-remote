package com.debarun.cretaremote.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import com.debarun.cretaremote.AppViewModel
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
fun PlacesScreen(d: AppData, vm: AppViewModel) {
    val car = d.car
    val ctx = LocalContext.current
    val lat = car?.lat
    val lon = car?.lon
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    var locTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locTick++ }
    val locAllowed = remember(locTick) { Perms.locationAllowed(ctx) }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locTick++ }
    val me by rememberPhoneLocation(locAllowed)
    val now = remember(d.fetchedAt) { System.currentTimeMillis() }

    fun open(uri: String, chooser: Boolean = false) {
        try {
            val i = if (chooser) Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri), "Share car location")
            else Intent(Intent.ACTION_VIEW, uri.toUri())
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { /* nothing can handle it */ }
    }

    val thisMonth = remember { java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date()) }
    var month by rememberSaveable { mutableStateOf(thisMonth) }
    val isCurrent = month == thisMonth
    val earliest by produceState<String?>(null, d.places) { value = vm.earliestPlaceMonth() }
    // a month's parking spots, newest first; re-read whenever a new place is recorded
    val monthPlaces by produceState(emptyList<com.debarun.cretaremote.data.Place>(), month, d.places) { value = vm.placesFor(month) }

    // only the spots themselves, no lines between them; in the current month the car sits at the end
    val trail = remember(monthPlaces, lat, lon, isCurrent) {
        val pts = monthPlaces.reversed().map { it.lat to it.lon }.toMutableList()
        if (isCurrent && lat != null && lon != null && (pts.isEmpty() || Geo.distanceM(pts.last().first, pts.last().second, lat, lon) > 20.0)) pts += lat to lon
        pts.toList()
    }
    val latest = d.places.firstOrNull()
    val since = car?.locTime ?: latest?.ts
    val placeName = latest?.label

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Location")
                Txt("Places", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
            }
            NavArrow(true, enabled = earliest != null && month > earliest!!) { month = shiftMonth(month, -1) }
            Txt(monthTitle(month), Modifier.padding(horizontal = 10.dp), size = 14.sp, weight = FontWeight.Medium)
            NavArrow(false, enabled = month < thisMonth) { month = shiftMonth(month, 1) }
        }

        Panel(Modifier.fillMaxWidth()) {
            if (trail.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Txt(
                        when {
                            !isCurrent -> "No parking spots recorded in ${monthTitle(month)}."
                            d.refreshing -> "Finding your Creta…"
                            else -> "Waiting for the car's parked location…"
                        },
                        size = 13.sp, color = C.Dim,
                    )
                }
            } else {
                CarMap(
                    points = trail,
                    label = if (isCurrent) placeName ?: "Your Creta" else null,
                    sub = if (isCurrent) since?.let { "Parked ${agoText(it, now)}" } else null,
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    focus = focus,
                    me = if (isCurrent) me else null,
                    showTrail = false,
                    carHere = isCurrent,
                )
            }
        }

        if (isCurrent) Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Parked")
                Txt(placeName ?: if (lat != null) "${Fmt.n(lat, 5)}, ${Fmt.n(lon ?: 0.0, 5)}" else "Unknown", size = 22.sp, weight = FontWeight.Medium)
                if (since != null) {
                    Txt("For ${durText(now - since)}  ·  since ${dateTimeText(since)}", size = 13.sp, color = C.Dim)
                }
                if (lat != null && lon != null) {
                    Txt("${Fmt.n(lat, 5)}, ${Fmt.n(lon, 5)}", size = 11.sp, color = C.Faint, mono = true)
                }
                val here = me
                if (here != null && lat != null && lon != null) {
                    val m = Geo.distanceM(here.first, here.second, lat, lon)
                    Txt(
                        "You're ${if (m < 1000) "${m.toInt()} m" else "${Fmt.n(m / 1000.0, 1)} km"} from your Creta  ·  about ${maxOf(1, (m / 80).toInt())} min on foot as the crow flies",
                        size = 12.sp, color = C.Cyan,
                    )
                } else if (!locAllowed) {
                    GhostButton("Show my location on the map", {
                        if (!Perms.asked(ctx, "loc")) {
                            Perms.markAsked(ctx, "loc")
                            locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else Perms.openAppSettings(ctx)
                    }, color = C.Cyan)
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
                    if (monthPlaces.isNotEmpty()) Txt("${monthPlaces.size} place${if (monthPlaces.size == 1) "" else "s"} in ${monthTitle(month)}", size = 11.sp, color = C.Faint)
                }
                if (monthPlaces.isEmpty()) {
                    Txt(
                        if (isCurrent) "Nothing yet. Each time the car parks somewhere new it is added here." else "Nothing was recorded in ${monthTitle(month)}.",
                        Modifier.padding(top = 10.dp), size = 13.sp, color = C.Dim,
                    )
                }
                val shown = monthPlaces.take(60)
                shown.forEachIndexed { i, p ->
                    val older = monthPlaces.getOrNull(i + 1)
                    val newer = monthPlaces.getOrNull(i - 1)
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
                            Txt(if (i == 0 && isCurrent) "Parked ${durText(stay)} so far" else if (newer == null) "Last spot of the month" else "Stayed ${durText(stay)}", size = 12.sp, color = C.Dim)
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
                    "Bluelink reports where the car is parked, not a live GPS track, so this shows the places it has parked since the app started recording, month by month. Tap a place to see it on the map. A day's route on the road is on the Trips screen.",
                    Modifier.padding(top = 14.dp), size = 11.sp, color = C.Faint,
                )
            }
        }
    }
}
