package com.debarun.cretaremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.DayRoute

/**
 * The places the car stood during a day, numbered in order, joined by the roads between them. Bluelink only reports where the car
 * parks, so each leg is the road route between two stops rather than a recorded GPS track.
 */
@Composable
fun DayMap(day: String, trips: Int, vm: AppViewModel, dayKm: Double = 0.0) {
    val roads = false
    val route by produceState<DayRoute?>(null, day, roads) {
        value = vm.dayRoute(day, roads)
    }
    val r = route
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Where the car went")
        when {
            r == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Txt("Drawing the route…", size = 13.sp, color = C.Dim)
            }
            r.track.isNotEmpty() -> {
                val all = r.track.flatten()
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))) {
                    RouteMap(listOf(all.first(), all.last()), r.track.map { MapLeg(it) }, false)
                }
                Txt("${Fmt.n(r.trackKm, 1)} km along the recorded path", size = 12.sp, color = C.Dim)
                Txt(
                    "Recorded from this phone's GPS while the app was connected to the car" +
                        (if (r.trips > 0) " for ${r.tripsCovered} of ${r.trips} trip${if (r.trips == 1) "" else "s"}" else "") +
                        ". Bluelink itself sends no path, so the parts that were not recorded are missing.",
                    size = 11.sp, color = C.Faint,
                )
            }
            r.source == "bluelink" -> {
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))) {
                    RouteMap(r.stops.map { it.lat to it.lon }, r.legs.map { MapLeg(it.pts, it.straight) }, true)
                }
                val lineKm = r.legs.sumOf { it.km }
                Txt("${r.tripsCovered} of ${r.trips} trip${if (r.trips == 1) "" else "s"} · start and end points from Bluelink", size = 12.sp, color = C.Dim)
                Txt(
                    "Bluelink sends only where each trip began and ended, not the streets driven. " +
                        "The dashed lines only join those points, they are not the path. Settings → Record the route captures the real path from this phone on later drives." +
                        " Together they span about ${Fmt.n(lineKm, 1)} km" +
                        (if (dayKm > lineKm * 1.2 + 1.0) ", while the odometer counted ${Fmt.n(dayKm, 1)} km, so the car went further than these lines." else "."),
                    size = 11.sp, color = C.Faint,
                )
                if (roads && r.legs.any { it.straight }) Txt("Some legs stay dashed because no road route could be fetched (needs internet).", size = 11.sp, color = C.Faint)
            }
            r.stops.size < 2 -> Txt(
                "Bluelink's trip data for this day has no positions the app could read, no path was recorded from this phone, and no parking spots were seen around the trips. Use \"Copy what Bluelink sent\" under the trip list and send it over, or turn on Settings → Record the route.",
                size = 12.sp, color = C.Dim,
            )
            else -> {
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))) {
                    RouteMap(r.stops.map { it.lat to it.lon }, r.legs.map { MapLeg(it.pts, it.straight) }, true)
                }
                val km = r.legs.sumOf { it.km }
                Txt("${r.stops.size} stops · about ${Fmt.n(km, 1)} km ${if (roads) "by road (estimate)" else "in a straight line"}", size = 12.sp, color = C.Dim)
                if (r.trips > r.stops.size - 1) {
                    Txt(
                        "Bluelink reported ${r.stops.size - 1} parking spot${if (r.stops.size - 1 == 1) "" else "s"} for ${r.trips} trips, so some legs cover more than one trip.",
                        size = 11.sp, color = C.Faint,
                    )
                }
                if (r.legs.any { it.straight } && roads) Txt("Dashed lines are straight because no road route could be fetched (needs internet).", size = 11.sp, color = C.Faint)
                Txt("Stop 1 is where the car started. The lines only join the stops; they are not the streets driven. Turn on Settings → Record the route to capture the real path.", size = 11.sp, color = C.Faint)
            }
        }
    }
}

/** Mappls' map when it can start, else our own OpenStreetMap view. */
@Composable
private fun RouteMap(points: List<Pair<Double, Double>>, routes: List<MapLeg>, numbered: Boolean) {
    var mappls by remember { mutableStateOf(true) }
    if (mappls) {
        MapplsRouteMap(points, routes, numbered, Modifier.fillMaxSize(), onFail = { mappls = false })
    } else {
        CarMap(points = points, label = null, sub = null, modifier = Modifier.fillMaxSize(), showTrail = false, carHere = false, routes = routes, numbered = numbered)
    }
}
