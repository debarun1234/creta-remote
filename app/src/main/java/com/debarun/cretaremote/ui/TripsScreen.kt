package com.debarun.cretaremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.bluelink.TripEntry
import com.debarun.cretaremote.bluelink.TripSummary
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.DayRow
import com.debarun.cretaremote.data.entries
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun shiftMonth(yyyymm: String, delta: Int): String {
    val c = Calendar.getInstance()
    c.clear()
    c.set(yyyymm.take(4).toInt(), yyyymm.substring(4, 6).toInt() - 1, 1)
    c.add(Calendar.MONTH, delta)
    return SimpleDateFormat("yyyyMM", Locale.US).format(c.time)
}

internal fun monthTitle(yyyymm: String): String {
    val c = Calendar.getInstance()
    c.clear()
    c.set(yyyymm.take(4).toInt(), yyyymm.substring(4, 6).toInt() - 1, 1)
    return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
}

// ------------------------------------------------------------------------------------------ helpers

/** "162400" -> minutes since midnight, or null. */
private fun minutesOfDay(hhmmss: String?): Int? =
    if (hhmmss != null && hhmmss.length >= 4 && hhmmss.all { it.isDigit() }) hhmmss.substring(0, 2).toInt() * 60 + hhmmss.substring(2, 4).toInt() else null

private fun clock(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)

private fun timeLabel(hhmmss: String): String = minutesOfDay(hhmmss)?.let { clock(it) } ?: "Trip"

/** Slow (cyan) to fast (red). */
private fun speedColor(kmh: Double?): Color = if (kmh == null || kmh <= 0) C.RedHi else lerp(C.Cyan, C.RedHi, (kmh / 80.0).toFloat().coerceIn(0f, 1f))

private fun tripKind(avg: Double?, dist: Double?): Pair<String, Color>? = when {
    avg != null && avg > 0 -> when {
        avg < 20 -> "Crawl" to C.Amber
        avg < 40 -> "City" to C.Cyan
        avg < 65 -> "Mixed" to C.Green
        else -> "Highway" to C.RedHi
    }
    dist != null && dist > 0 && dist < 3 -> "Short hop" to C.Dim
    else -> null
}

private fun dayNoon(yyyymmdd: String): Long? = try {
    SimpleDateFormat("yyyyMMdd", Locale.US).parse(yyyymmdd)!!.time + 12 * 3_600_000L
} catch (e: Exception) {
    null
}

private fun dayOfWeekMon0(yyyymmdd: String): Int? = try {
    val c = Calendar.getInstance()
    c.time = SimpleDateFormat("yyyyMMdd", Locale.US).parse(yyyymmdd)!!
    (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
} catch (e: Exception) {
    null
}

// ------------------------------------------------------------------------------------------ screen

@Composable
fun TripsScreen(d: AppData, vm: AppViewModel) {
    val thisMonth = remember { SimpleDateFormat("yyyyMM", Locale.US).format(Date()) }
    var month by rememberSaveable { mutableStateOf(thisMonth) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(month) { selected = null; vm.loadTrips(month) }

    val t = d.trips?.takeIf { it.yyyymm == month }
    val days = t?.days.orEmpty()
    val byDay = days.associateBy { it.day }
    val sel = selected?.let { byDay[it] } ?: days.lastOrNull { it.dist > 0 || it.count > 0 }
    val ins = d.insights

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Journeys")
                Txt("Trips", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
            }
            NavArrow(true) { month = shiftMonth(month, -1) }
            Txt(monthTitle(month), Modifier.padding(horizontal = 10.dp), size = 14.sp, weight = FontWeight.Medium)
            NavArrow(false, enabled = month < thisMonth) { month = shiftMonth(month, 1) }
        }

        d.liveTrip?.let { LiveTripCard(it) }

        MonthHero(t?.summary, days, d.tripsLoading, ins)

        if (days.any { it.dist > 0 }) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow("Where your kilometres go")
                    WeekdayRhythm(days)
                    Txt("Total distance by day of the week, this month.", size = 11.sp, color = C.Faint)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Calendar", Modifier.weight(1f))
                    Txt("Tap a day", size = 11.sp, color = C.Faint)
                }
                HeatCalendar(month, byDay, sel?.day) { selected = it }
                CalendarLegend()
                t?.note?.let { Txt(it, size = 12.sp, color = C.Dim) }
                Txt("Distances come from the car's odometer, which counts whole kilometres, so a day can read up to 1 km either side of the dash. Bluelink's own trip figures run several percent short.", size = 11.sp, color = C.Faint)
            }
        }

        if (sel != null) DayCard(sel, ins, vm)
    }
}

/** The drive in progress, so a trip's distance and economy follow the car while it is driven. */
@Composable
fun LiveTripCard(t: com.debarun.cretaremote.data.LiveTrip) {
    Panel(Modifier.fillMaxWidth(), tint = C.Red) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Driving now")
            CountUp(t.km, decimals = 1, size = 38.sp, suffix = "km")
            Txt("Odometer since the engine started" + (t.litresUsed?.let { ", about ${Fmt.n(it, 1)} L used" } ?: ""), size = 12.sp, color = C.Dim)
            t.kmpl?.let { Txt("${Fmt.n(it, 1)} km/L so far on this trip, rough (the gauge moves in steps)", size = 13.sp, color = C.Cyan) }
        }
    }
}

// ------------------------------------------------------------------------------------------ month

@Composable
private fun MonthHero(sum: TripSummary?, days: List<DayRow>, loading: Boolean, ins: com.debarun.cretaremote.data.FuelInsights) {
    val dist = maxOf(sum?.distKm ?: 0.0, days.sumOf { it.dist }).takeIf { it > 0 }
    val drive = sum?.driveMin ?: days.sumOf { it.drive }
    val idle = sum?.idleMin ?: days.sumOf { it.idle }
    val moving = if (drive + idle > 0) drive.toFloat() / (drive + idle) else null
    val activeDays = days.count { it.dist > 0 }
    val trips = days.sumOf { it.count }
    val best = days.maxByOrNull { it.dist }?.takeIf { it.dist > 0 }
    // each day is costed at the economy and price that applied on that day, so month = sum of days and matches the day cards
    val costs = days.filter { it.dist > 0 }.mapNotNull { ins.tripCost(it.dist, dayNoon(it.day)) }
    val litres = if (costs.isNotEmpty()) costs.sumOf { it.litres } else dist?.let { ins.tripCost(it)?.litres }
    val money = if (costs.isNotEmpty() && costs.all { it.cost != null }) costs.sumOf { it.cost!! } else if (costs.isEmpty()) dist?.let { ins.tripCost(it)?.cost } else null

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Distance this month")
                        if (loading) Txt("  ·  syncing…", size = 11.sp, color = C.Cyan)
                    }
                    CountUp(dist, decimals = 0, size = 50.sp, suffix = "km")
                    if (activeDays > 0) {
                        Txt("$trips trips over $activeDays day${if (activeDays == 1) "" else "s"}", size = 12.sp, color = C.Dim)
                    }
                }
                MovingRing(moving)
            }
            StatGrid(
                listOf(
                    Tile("Driving time", if (drive > 0) minutesText(drive) else "—", C.Red),
                    Tile("Idling", if (idle > 0) minutesText(idle) else "—", C.Amber),
                    Tile("Average speed", sum?.takeIf { it.avgKmh > 0 }?.let { "${Fmt.n(it.avgKmh, 0)} km/h" } ?: "—", C.Cyan),
                    Tile("Top speed", sum?.takeIf { it.maxKmh > 0 }?.let { "${Fmt.n(it.maxKmh, 0)} km/h" } ?: "—", C.RedHi),
                    Tile("Longest day", best?.let { "${Fmt.n(it.dist, 0)} km · ${yyyymmddText(it.day).substringAfter(' ')}" } ?: "—", C.Green),
                    Tile(
                        "Fuel used" + if (ins.ledger.usable) "" else " (rough)",
                        litres?.let { "${Fmt.n(it, 1)} L" + (money?.let { m -> " · ₹${Fmt.grouped(m.toLong())}" } ?: "") } ?: "—",
                        C.Cyan,
                    ),
                ),
            )
        }
    }
}

/** Share of the month's engine-on time that was spent actually moving. */
@Composable
private fun MovingRing(frac: Float?, size: Dp = 84.dp) {
    val a by animateIn(frac ?: 0f, 1400)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(this.size.width - w, this.size.height - w)
            drawArc(C.Line, -90f, 360f, false, tl, sz, style = Stroke(w))
            if (frac != null) {
                drawArc(
                    Brush.sweepGradient(listOf(C.RedDeep, C.Red, C.RedHi, C.RedDeep)),
                    -90f, 360f * a, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(if (frac == null) "—" else "${Fmt.n((a * 100).toDouble(), 0)}%", size = 18.sp, weight = FontWeight.Medium)
            Txt("moving", size = 10.sp, color = C.Faint)
        }
    }
}

private data class Tile(val label: String, val value: String, val accent: Color)

@Composable
private fun StatGrid(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, tile ->
                    val a by animateIn(1f, 600, (r * 2 + c) * 80)
                    Row(
                        Modifier.weight(1f).graphicsLayer { alpha = a; translationY = (1f - a) * 10.dp.toPx() }
                            .clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 3.dp, height = 28.dp).clip(CircleShape).background(tile.accent))
                        Column(Modifier.padding(start = 10.dp)) {
                            Txt(tile.value, size = 15.sp, weight = FontWeight.Medium, maxLines = 1)
                            Txt(tile.label, size = 11.sp, color = C.Faint, maxLines = 1)
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WeekdayRhythm(days: List<DayRow>) {
    val sums = DoubleArray(7)
    days.forEach { r -> dayOfWeekMon0(r.day)?.let { sums[it] += r.dist } }
    val maxV = sums.max().coerceAtLeast(1.0)
    val top = sums.indices.maxByOrNull { sums[it] } ?: 0
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 7) {
            val a by animateIn((sums[i] / maxV).toFloat(), 900, i * 70)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(if (sums[i] > 0) Fmt.n(sums[i], 0) else "", size = 10.sp, color = if (i == top) C.Text else C.Faint, maxLines = 1)
                Box(
                    Modifier.padding(top = 4.dp).fillMaxWidth().height((70f * a).dp.coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(
                            if (sums[i] <= 0) Brush.verticalGradient(listOf(C.Line, C.Line))
                            else if (i == top) Brush.verticalGradient(listOf(C.RedHi, C.RedDeep))
                            else Brush.verticalGradient(listOf(C.Cyan.copy(alpha = 0.85f), C.Cyan.copy(alpha = 0.2f))),
                        ),
                )
                Txt(labels[i], Modifier.padding(top = 6.dp), size = 11.sp, color = if (i == top) C.Text else C.Faint)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ calendar

@Composable
internal fun NavArrow(left: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(C.Surface2).border(1.dp, C.Line, CircleShape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (left) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = C.Text)
    }
}

@Composable
private fun CalendarLegend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt("Fewer km", size = 10.sp, color = C.Faint)
        listOf(0.18f, 0.4f, 0.65f, 1f).forEach { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(C.Red.copy(alpha = it))) }
        Txt("More km", size = 10.sp, color = C.Faint)
        Box(Modifier.weight(1f))
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, C.Cyan, RoundedCornerShape(4.dp)))
        Txt("Today", size = 10.sp, color = C.Faint)
    }
}

@Composable
private fun HeatCalendar(month: String, byDay: Map<String, DayRow>, selected: String?, onPick: (String) -> Unit) {
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(month.take(4).toInt(), month.substring(4, 6).toInt() - 1, 1)
    val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val maxKm = byDay.values.maxOfOrNull { it.dist }?.coerceAtLeast(1.0) ?: 1.0
    val todayKey = remember { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }

    Row(Modifier.fillMaxWidth()) {
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { Txt(it, Modifier.weight(1f), size = 11.sp, color = C.Faint, align = TextAlign.Center) }
    }
    val cells = offset + daysInMonth
    val rows = (cells + 6) / 7
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in 0 until 7) {
                val dayNum = r * 7 + c - offset + 1
                if (dayNum !in 1..daysInMonth) {
                    Box(Modifier.weight(1f).aspectRatio(1f))
                } else {
                    val key = month + "%02d".format(dayNum)
                    val row = byDay[key]
                    val k = row?.dist ?: 0.0
                    val intensity = (k / maxKm).toFloat()
                    val a by animateFloatAsState(if (k > 0) 0.18f + 0.82f * intensity else 0f, tween(700, (r * 7 + c) * 18), label = "cal")
                    val isSel = key == selected
                    val border = when {
                        isSel -> Color.White
                        key == todayKey -> C.Cyan
                        else -> Color.Transparent
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(C.Surface2)
                            .background(C.Red.copy(alpha = a))
                            .border(if (isSel || key == todayKey) 1.5.dp else 0.dp, border, RoundedCornerShape(10.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = row != null) { onPick(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(dayNum.toString(), size = 12.sp, color = if (k > 0) Color.White else C.Faint, weight = if (k > 0) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        if (r < rows - 1) Box(Modifier.height(6.dp))
    }
}

// ------------------------------------------------------------------------------------------ day

@Composable
private fun DayCard(sel: DayRow, ins: com.debarun.cretaremote.data.FuelInsights, vm: AppViewModel) {
    val entries = remember(sel.day, sel.trips) { sel.entries().sortedBy { minutesOfDay(it.startHhmmss) ?: 0 } }
    val hasMetrics = entries.any { (it.distKm ?: 0.0) > 0 || (it.driveMin ?: 0) > 0 || (it.avgKmh ?: 0.0) > 0 }
    val tc = if (sel.dist > 0) ins.tripCost(sel.dist, dayNoon(sel.day)) else null

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(yyyymmddText(sel.day))
                    CountUp(sel.dist, decimals = 1, size = 38.sp, suffix = "km")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Txt("${sel.count} trip${if (sel.count == 1) "" else "s"}", size = 14.sp, weight = FontWeight.Medium)
                    if (sel.drive > 0) Txt("${minutesText(sel.drive)} driving", size = 12.sp, color = C.Dim)
                }
            }

            if (entries.any { minutesOfDay(it.startHhmmss) != null }) {
                DayTimeline(sel.day, entries)
            }

            DayMap(sel.day, sel.count, vm)

            StatGrid(
                listOf(
                    Tile("Driving", if (sel.drive > 0) minutesText(sel.drive) else "—", C.Red),
                    Tile("Idling", if (sel.idle > 0) minutesText(sel.idle) else "—", C.Amber),
                    Tile("Average speed", if (sel.avg > 0) "${Fmt.n(sel.avg, 0)} km/h" else "—", C.Cyan),
                    Tile("Top speed", if (sel.max > 0) "${Fmt.n(sel.max, 0)} km/h" else "—", C.RedHi),
                    Tile("Per trip", if (sel.count > 0 && sel.dist > 0) "${Fmt.n(sel.dist / sel.count, 1)} km" else "—", C.Green),
                    Tile(
                        tc?.let { "Fuel at ${Fmt.n(it.kmpl, 1)} km/L" + if (it.measured) "" else " (rough)" } ?: "Fuel",
                        tc?.let { "${Fmt.n(it.litres, 1)} L" + (it.cost?.let { c -> " · ₹${Fmt.grouped(c.toLong())}" } ?: "") } ?: "—",
                        C.Cyan,
                    ),
                ),
            )

            if (entries.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eyebrow("Trips, newest first")
                    val n = entries.size
                    entries.withIndex().toList().asReversed().forEach { (i, e) ->
                        TripCard(i + 1, e, sel.dist, n - 1 - i)
                    }
                }
                if (!hasMetrics) NoPerTripNote(entries.first())
            } else {
                Txt("Bluelink gave a daily total but no per-trip breakdown for this day.", size = 12.sp, color = C.Dim)
            }

            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            var copied by remember(sel.day) { mutableStateOf(false) }
            Txt(
                if (copied) "Copied" else "Copy what Bluelink sent for this day (may include locations)",
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    clip.setText(androidx.compose.ui.text.AnnotatedString(entries.joinToString("\n") { it.raw ?: "" }.ifBlank { "(nothing stored for this day yet)" }))
                    copied = true
                },
                size = 11.sp, color = C.Cyan,
            )
        }
    }
}

/** 24 hours as a strip: each pill is one trip: its position is when it started and its length is how long it lasted. */
@Composable
private fun DayTimeline(day: String, entries: List<TripEntry>) {
    val reveal = remember(day) { Animatable(0f) }
    LaunchedEffect(day) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val todayKey = remember { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }
    val nowMin = remember { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
    val anyDuration = entries.any { (it.driveMin ?: 0) > 0 }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp))) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.horizontalGradient(
                    0f to Color(0xFF090B11), 0.25f to Color(0xFF161B28), 0.5f to Color(0xFF222A3C), 0.75f to Color(0xFF161B28), 1f to Color(0xFF090B11),
                ),
            )
            for (hr in listOf(6, 12, 18)) {
                val x = w * hr / 24f
                drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
            }
            val ph = 22.dp.toPx()
            val top = (h - ph) / 2f
            val n = entries.size
            entries.forEachIndexed { i, e ->
                val s = minutesOfDay(e.startHhmmss) ?: return@forEachIndexed
                val x0 = w * s / 1440f
                val len = ((e.driveMin ?: 0).coerceAtLeast(0) / 1440f * w).coerceAtLeast(10.dp.toPx())
                val p = (reveal.value * (n + 1) - i).coerceIn(0f, 1f)
                if (p <= 0f) return@forEachIndexed
                val col = speedColor(e.avgKmh)
                val ww = (len * p).coerceAtLeast(ph * 0.5f)
                val x = x0.coerceAtMost(w - ww - 2.dp.toPx())
                drawRoundRect(col.copy(alpha = 0.28f * p), Offset(x - 3.dp.toPx(), top - 3.dp.toPx()), Size(ww + 6.dp.toPx(), ph + 6.dp.toPx()), CornerRadius(ph))
                drawRoundRect(Brush.horizontalGradient(listOf(col, col.copy(alpha = 0.7f)), x, x + ww), Offset(x, top), Size(ww, ph), CornerRadius(ph / 2f))
                drawCircle(Color.White.copy(alpha = 0.9f * p), 3.dp.toPx(), Offset(x + ph / 2f, top + ph / 2f))
            }
            if (day == todayKey) {
                val x = w * nowMin / 1440f
                drawLine(C.Cyan, Offset(x, 0f), Offset(x, h), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("12 am", "6 am", "12 pm", "6 pm", "12 am").forEachIndexed { i, s ->
                Txt(
                    s, Modifier.weight(1f), size = 10.sp, color = C.Faint,
                    align = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                )
            }
        }
        Txt(
            if (anyDuration) "Each pill is one trip: its position is when it started, its length is how long you drove. Colour runs from slow (blue) to fast (red)."
            else "Each pill marks when a trip started.",
            size = 11.sp, color = C.Faint,
        )
    }
}

@Composable
private fun TripCard(number: Int, e: TripEntry, dayDist: Double, order: Int) {
    val start = minutesOfDay(e.startHhmmss)
    val end = minutesOfDay(e.endHhmmss) ?: if (start != null && (e.driveMin ?: 0) > 0) start + e.driveMin!! else null
    val dist = e.distKm?.takeIf { it > 0 }
    val kind = tripKind(e.avgKmh, dist)
    val col = speedColor(e.avgKmh)
    val a by animateIn(1f, 600, order * 90)
    val share = if (dist != null && dayDist > 0) (dist / dayDist).toFloat().coerceIn(0f, 1f) else null

    Column(
        Modifier.fillMaxWidth().graphicsLayer { alpha = a; translationY = (1f - a) * 14.dp.toPx() }
            .clip(RoundedCornerShape(18.dp)).background(C.Surface2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(col.copy(alpha = 0.2f)).border(1.dp, col, CircleShape), contentAlignment = Alignment.Center) {
                Txt(number.toString(), size = 12.sp, weight = FontWeight.SemiBold, color = col)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Txt(
                    if (start != null && end != null) "${clock(start)}  →  ${clock(end)}" else timeLabel(e.startHhmmss),
                    size = 15.sp, weight = FontWeight.Medium,
                )
                val sub = listOfNotNull(
                    e.driveMin?.takeIf { it > 0 }?.let { minutesText(it) },
                    e.idleMin?.takeIf { it > 0 }?.let { "idle ${minutesText(it)}" },
                ).joinToString("  ·  ")
                if (sub.isNotEmpty()) Txt(sub, size = 12.sp, color = C.Dim)
            }
            if (kind != null) {
                Txt(
                    kind.first, Modifier.clip(CircleShape).background(kind.second.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    size = 11.sp, color = kind.second, weight = FontWeight.Medium,
                )
            }
            if (dist != null) {
                Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                    Txt(Fmt.n(dist, 1), size = 20.sp, weight = FontWeight.Light)
                    Txt("km", size = 10.sp, color = C.Faint)
                }
            }
        }
        if (e.avgKmh != null && e.avgKmh > 0) SpeedMeter(e.avgKmh, e.maxKmh, order)
        if (share != null) {
            val s by animateIn(share, 800, order * 90 + 200)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(C.Line)) {
                    Box(Modifier.fillMaxWidth(s.coerceAtLeast(0.02f)).height(4.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(C.Red, C.RedHi))))
                }
                Txt("${Fmt.n(share * 100.0, 0)}% of the day's distance", Modifier.padding(start = 10.dp), size = 11.sp, color = C.Faint)
            }
        }
    }
}

/** A track from 0 to max km/h: the filled part is the average speed, the tick is the top speed. */
@Composable
private fun SpeedMeter(avg: Double, max: Double?, order: Int) {
    val scaleMax = maxOf(120.0, (max ?: 0.0) * 1.1)
    val a by animateIn((avg / scaleMax).toFloat().coerceIn(0f, 1f), 900, order * 90 + 100)
    val m by animateIn(((max ?: 0.0) / scaleMax).toFloat().coerceIn(0f, 1f), 1100, order * 90 + 100)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val h = size.height
            drawRoundRect(C.Line, Offset.Zero, Size(size.width, h), CornerRadius(h))
            if (a > 0f) drawRoundRect(Brush.horizontalGradient(listOf(C.Cyan, speedColor(avg)), 0f, size.width * a.coerceAtLeast(0.05f)), Offset.Zero, Size(size.width * a.coerceAtLeast(0.05f), h), CornerRadius(h))
            if (max != null && max > 0) {
                val x = size.width * m
                drawRoundRect(Color.White, Offset(x - 1.5.dp.toPx(), -2.dp.toPx()), Size(3.dp.toPx(), h + 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
        Row {
            Txt("avg ${Fmt.n(avg, 0)} km/h", Modifier.weight(1f), size = 11.sp, color = C.Dim)
            if (max != null && max > 0) Txt("top ${Fmt.n(max, 0)} km/h", size = 11.sp, color = C.Dim)
        }
    }
}

@Composable
private fun NoPerTripNote(first: TripEntry) {
    var showRaw by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(
            "Bluelink sent only the start time for each trip on this day, so distance and speed are shown for the whole day, not per trip.",
            size = 11.sp, color = C.Faint,
        )
        Txt(
            if (showRaw) "Hide what Bluelink sent" else "Show what Bluelink sent",
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showRaw = !showRaw },
            size = 11.sp, color = C.Cyan,
        )
        if (showRaw) Txt(first.raw ?: "(not stored for this day yet; pull to reopen Trips)", size = 10.sp, color = C.Dim, mono = true)
    }
}
