package com.debarun.cretaremote.ui

import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.bluelink.CarState
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.bluelink.tyreAttention
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.Link
import com.debarun.cretaremote.data.Settings
import com.debarun.cretaremote.data.carTitle
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlin.math.PI

@Composable
fun HomeScreen(d: AppData, vm: AppViewModel, flash: Int, onAct: (Cmd) -> Unit, onClimate: () -> Unit, onCar: () -> Unit, onActivity: () -> Unit) {
    val car = d.car
    val ctx = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30.seconds); value = System.currentTimeMillis() } }
    val lockBusy = d.cmd.busy && (d.cmd.cmd == Cmd.LOCK || d.cmd.cmd == Cmd.UNLOCK)

    fun openMaps(navigate: Boolean) {
        val lat = car?.lat ?: return
        val lon = car.lon ?: return
        val uri = if (navigate) "google.navigation:q=$lat,$lon&mode=d".toUri() else "geo:$lat,$lon?q=$lat,$lon(My Creta)".toUri()
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* no maps app */ }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ------------------------------------------------------------- header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Hyundai")
                Txt(d.settings.carTitle(), size = 28.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
            }
            Box {
                RoundIcon(Icons.Rounded.Notifications) { onActivity() }
                if (d.activityUnread > 0) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(11.dp).clip(CircleShape).background(C.Red).border(1.5.dp, C.Bg, CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
            // one refresh button, and it syncs live: wakes the car and reads fresh data instead of the cloud's cached copy
            RoundIcon(Icons.Rounded.Refresh, spinning = d.refreshing, tint = C.Amber) { vm.refresh(true) }
        }

        // ------------------------------------------------------------- status chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (car?.locked) {
                true -> StatusChip("Locked", C.Green)
                false -> StatusChip("Unlocked", C.Amber)
                null -> StatusChip(if (d.refreshing) "Connecting…" else "Status unknown", C.Dim, pulse = d.refreshing)
            }
            if (car?.engineOn == true) StatusChip("Engine on", C.Amber, pulse = true)
            when {
                d.link == Link.WAKING -> StatusChip("Waking car…", C.Cyan, pulse = true)
                d.link == Link.AWAKE && now - (d.linkAt ?: 0L) < 5 * 60_000L -> StatusChip(if ((d.liveUntil ?: 0L) > now) "Car awake · live" else "Car awake", C.Green)
                d.link == Link.ASLEEP || d.link == Link.AWAKE -> StatusChip("Car asleep", C.Dim)
            }
            Txt(
                "Car reported ${agoText(car?.updatedAt ?: d.fetchedAt, now)}",
                Modifier.weight(1f), size = 12.sp, color = C.Faint, maxLines = 1,
            )
        }

        if (d.update.release != null && d.update.status in setOf(com.debarun.cretaremote.data.UpdateStatus.AVAILABLE, com.debarun.cretaremote.data.UpdateStatus.DOWNLOADING, com.debarun.cretaremote.data.UpdateStatus.READY, com.debarun.cretaremote.data.UpdateStatus.ERROR)) {
            UpdateBanner(d.update) { vm.installUpdate() }
        }

        FuelAlarmBanner(d.fuel.stage, d.fuel.litres, d.settings.fuelLimitL, onAck = vm::ackFuelAlarm)

        // ------------------------------------------------------------- hero
        CarHero(
            car, flash,
            Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCar() },
        )
        Txt("Tap the car for specifications and service", Modifier.fillMaxWidth(), size = 11.sp, color = C.Faint, align = TextAlign.Center)

        LockSlider(car?.locked, busy = lockBusy, onToggle = { onAct(if (car?.locked == false) Cmd.LOCK else Cmd.UNLOCK) })

        // ------------------------------------------------------------- controls
        KeyFob(
            engineOn = car?.engineOn == true,
            cmd = d.cmd,
            navEnabled = car?.lat != null,
            syncing = d.refreshing,
            onAct = onAct,
            onClimate = onClimate,
            onNavigate = { openMaps(true) },
            onSync = { vm.refresh(true) },
        )

        d.error?.let { ErrorCard(it) { vm.refresh(true) } }

        // ------------------------------------------------------------- fuel + range
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FuelGauge(car?.fuelPct, d.insights.litresLeft, car?.lowFuel == true, Modifier.size(168.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Eyebrow("Range")
                        CountUp(car?.dteKm, size = 38.sp, suffix = "km")
                        RangeRoad(car?.dteKm, modifier = Modifier.padding(top = 6.dp))
                    }
                    Column {
                        Eyebrow("Fuel alert")
                        FuelLimitStepper(d.settings.fuelLimitL, vm::setFuelLimit, Modifier.padding(top = 4.dp))
                        Txt("heads-up at ${d.settings.fuelLimitL + 2} L, alarm at ${d.settings.fuelLimitL} L", size = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ------------------------------------------------------------- trip cost + next fill-up, without leaving Home
        d.liveTrip?.let { LiveTripCard(it) }
        TripCostPanel(d, compact = true)

        // ------------------------------------------------------------- tyres
        TyrePanel(car, d.tyreLog, vm::saveTyreLog)

        // ------------------------------------------------------------- 12V + today
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val today = d.insights.dailyKm.lastOrNull()?.second
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Eyebrow("Today")
                    CountUp(today, decimals = 1, size = 30.sp, suffix = "km")
                    Txt("driven", size = 12.sp, color = C.Faint)
                }
            }
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Eyebrow("12V battery")
                    val b = car?.batteryPct
                    CountUp(b?.toDouble(), size = 30.sp, suffix = "%", color = if (b != null && b < 50) C.Amber else C.Text)
                    Txt(if (b == null) "not reported" else if (b < 50) "weak - drive soon" else "healthy", size = 12.sp, color = C.Faint)
                }
            }
        }

        // ------------------------------------------------------------- odometer
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Eyebrow("Odometer")
                if (car?.odometerKm != null) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Odometer(car.odometerKm)
                        Txt("km", Modifier.padding(start = 10.dp, bottom = 8.dp), size = 14.sp, color = C.Dim)
                    }
                } else {
                    Txt(if (d.refreshing) "Reading from the car…" else "Not reported by Bluelink yet", Modifier.padding(top = 10.dp), size = 14.sp, color = C.Dim)
                }
            }
        }

        // ------------------------------------------------------------- vehicle health
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                TopCar(Modifier.width(128.dp), car, car?.engineOn == true, car?.headlampsOn == true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow("Vehicle health")
                    healthRows(car).forEach { (label, value, level) -> HealthRow(label, value, level) }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = C.Text, spinning: Boolean = false, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "ri")
    val a by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(C.Surface2).border(1.dp, C.Line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp).rotate(if (spinning) a else 0f))
    }
}

private data class Health(val label: String, val value: String, val level: Int) // 0 ok, 1 warn, 2 info

private fun healthRows(car: CarState?): List<Health> {
    if (car == null) return listOf(Health("Waiting for data", "…", 2))
    fun names(q: com.debarun.cretaremote.bluelink.Quad): String =
        listOfNotNull("FL".takeIf { q.fl }, "FR".takeIf { q.fr }, "RL".takeIf { q.rl }, "RR".takeIf { q.rr }).joinToString(" ")
    val rows = mutableListOf<Health>()
    rows += if (car.doorsOpen.any) Health("Doors", "Open: ${names(car.doorsOpen)}", 1) else Health("Doors", "Closed", 0)
    rows += if (car.windowsOpen.any) Health("Windows", "Open: ${names(car.windowsOpen)}", 1) else Health("Windows", "Closed", 0)
    rows += when {
        car.hoodOpen && car.trunkOpen -> Health("Hood / boot", "Both open", 1)
        car.hoodOpen -> Health("Hood / boot", "Hood open", 1)
        car.trunkOpen -> Health("Hood / boot", "Boot open", 1)
        else -> Health("Hood / boot", "Closed", 0)
    }
    rows += when {
        !car.tyreReported -> Health("Tyres", "Not reported", 2)
        car.tyreAttention -> Health("Tyres", "Check pressure", 1)
        else -> Health("Tyres", "Pressure OK", 0)
    }
    rows += when {
        car.brakeFluidLow -> Health("Fluids", "Brake fluid low", 1)
        car.washerLow -> Health("Fluids", "Washer low", 1)
        else -> Health("Fluids", "OK", 0)
    }
    if (car.smartKeyLow) rows += Health("Smart key", "Battery low", 1)
    if (car.headlampsOn) rows += Health("Headlamps", "On", 2)
    return rows
}

@Composable
private fun HealthRow(label: String, value: String, level: Int) {
    val c = when (level) { 0 -> C.Green; 1 -> C.Amber; else -> C.Cyan }
    val a by animateFloatAsState(1f, tween(700), label = "hr")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.copy(alpha = a)))
        Txt(label, Modifier.padding(start = 10.dp).weight(1f), size = 13.sp, color = C.Dim)
        Txt(value, size = 13.sp, color = if (level == 1) C.Amber else C.Text, weight = FontWeight.Medium, align = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun ErrorCard(msg: String, onRetry: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), tint = C.Red) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(msg, Modifier.weight(1f), size = 13.sp, color = C.Text)
            Txt(
                "Retry", Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRetry() }.padding(start = 12.dp),
                size = 13.sp, color = C.RedHi, weight = FontWeight.SemiBold,
            )
        }
    }
}

// ================================================================== climate sheet

@Composable
fun ClimateSheet(s: Settings, running: Boolean, onStart: (Double, Int) -> Unit, onStop: () -> Unit) {
    var temp by remember { mutableDoubleStateOf(s.climateTempC.coerceIn(14.0, 29.5)) }
    var mins by remember { mutableIntStateOf(s.climateMinutes) }
    val p by animateFloatAsState(((temp - 14.0) / 15.5).toFloat(), tween(450, easing = FastOutSlowInEasing), label = "temp")
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val accent = lerp(C.Cyan, C.Red, p)

    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.Surface).border(1.dp, C.Line, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(44.dp).height(4.dp).clip(CircleShape).background(C.Line))
        Txt("Start & climate", Modifier.padding(top = 16.dp), size = 20.sp, weight = FontWeight.Medium)
        Txt("Engine runs for $mins min, then shuts off", size = 13.sp, color = C.Dim)

        Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StepButton(Icons.Rounded.Remove) { temp = (temp - 0.5).coerceAtLeast(14.0) }
            Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 14.dp.toPx()
                    val tl = Offset(w, w)
                    val sz = Size(size.width - 2 * w, size.height - 2 * w)
                    drawArc(C.Line, 135f, 270f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                    drawArc(accent.copy(alpha = 0.2f), 135f, 270f * p, false, tl, sz, style = Stroke(w * 2f, cap = StrokeCap.Round))
                    drawArc(accent, 135f, 270f * p, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                    val ang = (135f + 270f * p) * (PI.toFloat() / 180f)
                    val r = sz.width / 2f
                    drawCircle(Color.White, w * 0.3f, Offset(size.width / 2f + kotlin.math.cos(ang) * r, size.height / 2f + kotlin.math.sin(ang) * r))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("${Fmt.n(temp, 1)}°", size = 44.sp, weight = FontWeight.Light, spacing = (-1).sp)
                    Txt("CELSIUS", size = 10.sp, color = C.Dim, spacing = 1.5.sp)
                }
            }
            StepButton(Icons.Rounded.Add) { temp = (temp + 0.5).coerceAtMost(29.5) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(5, 10, 15).forEach { m ->
                val on = m == mins
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Red.copy(alpha = 0.2f) else C.Surface2)
                        .border(1.dp, if (on) C.Red else C.Line, RoundedCornerShape(50))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { mins = m }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) { Txt("$m min", size = 14.sp, color = if (on) C.Text else C.Dim, weight = FontWeight.Medium) }
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (running) "Restart with these settings" else "Start engine", onClick = { onStart(temp, mins) })
        if (running) {
            Spacer(Modifier.height(10.dp))
            GhostButton("Stop engine", onStop, color = C.Amber)
        }
        Txt("Needs a variant with Bluelink remote start and the car parked and locked.", Modifier.padding(top = 14.dp), size = 11.sp, color = C.Faint, align = TextAlign.Center)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(C.Surface2).border(1.dp, C.Line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp)) }
}
