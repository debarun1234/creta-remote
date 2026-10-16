package com.debarun.cretaremote.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.DayRow
import com.debarun.cretaremote.data.entries
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun shiftMonth(yyyymm: String, delta: Int): String {
    val c = Calendar.getInstance()
    c.clear()
    c.set(yyyymm.take(4).toInt(), yyyymm.substring(4, 6).toInt() - 1, 1)
    c.add(Calendar.MONTH, delta)
    return SimpleDateFormat("yyyyMM", Locale.US).format(c.time)
}

private fun monthTitle(yyyymm: String): String {
    val c = Calendar.getInstance()
    c.clear()
    c.set(yyyymm.take(4).toInt(), yyyymm.substring(4, 6).toInt() - 1, 1)
    return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
}

@Composable
fun TripsScreen(d: AppData, vm: AppViewModel) {
    val thisMonth = remember { SimpleDateFormat("yyyyMM", Locale.US).format(java.util.Date()) }
    var month by rememberSaveable { mutableStateOf(thisMonth) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(month) { selected = null; vm.loadTrips(month) }

    val t = d.trips?.takeIf { it.yyyymm == month }
    val days = t?.days.orEmpty()
    val byDay = days.associateBy { it.day }
    val sel = selected?.let { byDay[it] } ?: days.lastOrNull { it.dist > 0 || it.count > 0 }

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

        // ------------------------------------------------------------ month summary
        val sum = t?.summary
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Distance")
                        CountUp(sum?.distKm ?: days.sumOf { it.dist }.takeIf { it > 0 }, decimals = 0, size = 46.sp, suffix = "km")
                    }
                    if (d.tripsLoading) Txt("Syncing…", size = 12.sp, color = C.Cyan)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Driving", sum?.let { minutesText(it.driveMin) } ?: "—", Modifier.weight(1f))
                    Stat("Idle", sum?.let { minutesText(it.idleMin) } ?: "—", Modifier.weight(1f))
                    Stat("Avg speed", sum?.let { "${Fmt.n(it.avgKmh, 0)} km/h" } ?: "—", Modifier.weight(1f))
                    Stat("Top speed", sum?.let { "${Fmt.n(it.maxKmh, 0)} km/h" } ?: "—", Modifier.weight(1f))
                }
            }
        }

        // ------------------------------------------------------------ heat calendar
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("Calendar")
                HeatCalendar(month, byDay, sel?.day) { selected = it }
                t?.note?.let { Txt(it, size = 12.sp, color = C.Dim) }
            }
        }

        // ------------------------------------------------------------ selected day
        if (sel != null) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow(yyyymmddText(sel.day))
                            CountUp(sel.dist, decimals = 1, size = 32.sp, suffix = "km")
                        }
                        Txt("${sel.count} trip${if (sel.count == 1) "" else "s"}", size = 13.sp, color = C.Dim)
                    }
                    val entries = sel.entries()
                    val maxD = entries.maxOfOrNull { it.distKm ?: 0.0 }?.coerceAtLeast(0.1) ?: 0.1
                    entries.forEachIndexed { i, e ->
                        val w by animateFloatAsState(((e.distKm ?: 0.0) / maxD).toFloat(), tween(800, i * 70, FastOutSlowInEasing), label = "tb")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt(timeLabel(e.startHhmmss), Modifier.weight(1f), size = 14.sp, weight = FontWeight.Medium)
                                Txt(e.distKm?.let { "${Fmt.n(it, 1)} km" } ?: "—", size = 14.sp)
                            }
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.Line)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(w.coerceIn(0.03f, 1f)).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.Red, C.RedHi))))
                            }
                            val bits = listOfNotNull(
                                e.driveMin?.let { minutesText(it) },
                                e.avgKmh?.takeIf { it > 0 }?.let { "avg ${Fmt.n(it, 0)} km/h" },
                                e.maxKmh?.takeIf { it > 0 }?.let { "max ${Fmt.n(it, 0)} km/h" },
                            )
                            if (bits.isNotEmpty()) Txt(bits.joinToString("  ·  "), size = 12.sp, color = C.Faint)
                        }
                    }
                    if (entries.isEmpty()) {
                        Txt("Bluelink gave a daily total but no per-trip breakdown for this day.", size = 12.sp, color = C.Dim)
                    }
                }
            }
        }
    }
}

private fun timeLabel(hhmmss: String): String =
    if (hhmmss.length >= 4 && hhmmss.all { it.isDigit() }) "${hhmmss.substring(0, 2)}:${hhmmss.substring(2, 4)}" else "Trip"

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(10.dp)) {
        Txt(value, size = 14.sp, weight = FontWeight.Medium, maxLines = 1)
        Txt(label, size = 11.sp, color = C.Faint, maxLines = 1)
    }
}

@Composable
private fun NavArrow(left: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun HeatCalendar(month: String, byDay: Map<String, DayRow>, selected: String?, onPick: (String) -> Unit) {
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(month.take(4).toInt(), month.substring(4, 6).toInt() - 1, 1)
    val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val maxKm = byDay.values.maxOfOrNull { it.dist }?.coerceAtLeast(1.0) ?: 1.0

    Row(Modifier.fillMaxWidth()) {
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { Txt(it, Modifier.weight(1f), size = 11.sp, color = C.Faint, align = TextAlign.Center) }
    }
    val cells = offset + daysInMonth
    val rows = (cells + 6) / 7
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in 0 until 7) {
                val dayNum = r * 7 + c - offset + 1
                if (dayNum < 1 || dayNum > daysInMonth) {
                    Box(Modifier.weight(1f).aspectRatio(1f))
                } else {
                    val key = month + "%02d".format(dayNum)
                    val row = byDay[key]
                    val k = row?.dist ?: 0.0
                    val inten = (k / maxKm).toFloat()
                    val a by animateFloatAsState(if (k > 0) 0.18f + 0.82f * inten else 0f, tween(700, (r * 7 + c) * 18), label = "cal")
                    val isSel = key == selected
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(C.Surface2)
                            .background(C.Red.copy(alpha = a))
                            .border(if (isSel) 1.5.dp else 0.dp, if (isSel) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
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
