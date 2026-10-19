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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.CretaSpecs
import com.debarun.cretaremote.data.DueState
import com.debarun.cretaremote.data.Powertrain
import com.debarun.cretaremote.data.Service
import com.debarun.cretaremote.data.ServiceLog
import com.debarun.cretaremote.data.Transmission
import com.debarun.cretaremote.data.Vin
import com.debarun.cretaremote.data.carTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Opens when the car on the home screen is tapped: specifications and service tracking. */
@Composable
fun CarSheet(d: AppData, vm: AppViewModel, onClose: () -> Unit, onSettings: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.9f).clip(shape).background(C.Surface).border(1.dp, C.Line, shape).navigationBarsPadding(),
    ) {
        Box(
            Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp)
                .clip(CircleShape).background(C.Line),
        )
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp).padding(top = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Hyundai")
                Txt(d.settings.carTitle(), Modifier.padding(top = 2.dp), size = 26.sp, weight = FontWeight.Light, spacing = (-0.4).sp)
            }
            Txt(
                "Close",
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }.padding(8.dp),
                size = 14.sp, color = C.Dim,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip("Specifications", tab == 0, { tab = 0 }, Modifier.weight(1f))
            OptionChip("Service", tab == 1, { tab = 1 }, Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tab == 0) SpecsTab(d, onSettings) else ServiceTab(d, vm, onSettings)
        }
    }
}

@Composable
fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).background(if (selected) C.Red.copy(alpha = 0.18f) else C.Surface2)
            .border(1.dp, if (selected) C.Red else C.Line, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, size = 12.sp, color = if (selected) C.Text else C.Dim, weight = FontWeight.Medium, maxLines = 1, align = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ specifications

@Composable
private fun SpecGroup(title: String, rows: List<Pair<String, String>>) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow(title)
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Txt(k, Modifier.weight(1f), size = 13.sp, color = C.Dim)
                    Txt(v, Modifier.weight(1.5f), size = 13.sp, weight = FontWeight.Medium, align = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun SpecsTab(d: AppData, onSettings: () -> Unit) {
    val s = d.settings
    val info = Vin.decode(s.vin)
    val blueVin = d.vehicle?.vin.orEmpty()
    val pt = Powertrain.of(s.powertrain)
    val tr = Transmission.entries.firstOrNull { it.name == s.transmission }
    val year = info.modelYear ?: s.modelYear

    SpecGroup(
        "Identity",
        buildList {
            add("Make" to "Hyundai")
            add("Model" to "Creta")
            add("Variant" to "SX")
            add("Model year" to year.toString())
            if (blueVin.isNotBlank()) add("VIN (Bluelink)" to blueVin)
            if (info.valid) add("VIN (entered)" to info.vin)
            info.maker?.let { add("Built by" to it) }
            d.vehicle?.regDate?.takeIf { it.isNotBlank() }?.let { add("Registered" to it) }
        },
    )
    if (info.valid && blueVin.isNotBlank()) {
        val same = info.vin.equals(blueVin, ignoreCase = true)
        Txt(
            if (same) "The VIN you entered matches your Bluelink account." else "The VIN you entered differs from the one on your Bluelink account.",
            Modifier.padding(horizontal = 4.dp), size = 12.sp, color = if (same) C.Green else C.Amber,
        )
    }

    SpecGroup(
        "Engine",
        if (pt != null) listOf(
            "Engine" to pt.engine,
            "Displacement" to pt.displacement,
            "Power" to pt.power,
            "Torque" to pt.torque,
            "Fuel" to pt.fuel,
        ) else listOf("Engine" to "Not set"),
    )
    SpecGroup(
        "Transmission",
        if (tr != null) listOf("Type" to tr.label, "Detail" to tr.detail) else listOf("Type" to "Not set"),
    )
    SpecGroup("Body and capacity", CretaSpecs.common)

    if (pt == null || tr == null) PrimaryButton("Choose engine and transmission", onSettings)
    Txt(
        "Engine, transmission and body figures are Hyundai's published numbers for the 2022 Creta. The car doesn't report its engine, so it comes from your choice in Settings. Check your RC book or invoice if in doubt.",
        Modifier.padding(horizontal = 4.dp), size = 11.sp, color = C.Faint, lineHeight = 16.sp,
    )
}

// ------------------------------------------------------------------ service

private fun parseDay(s: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(s.trim())?.time
}.getOrNull()

@Composable
private fun ServiceTab(d: AppData, vm: AppViewModel, onSettings: () -> Unit) {
    val s = d.settings
    val odo = d.car?.odometerKm
    val now = remember { System.currentTimeMillis() }
    val due = Service.due(d.services.firstOrNull(), odo, now, s.serviceKm, s.serviceMonths)
    val shown = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    val color = when (due.state) {
        DueState.DUE -> C.RedHi
        DueState.SOON -> C.Amber
        DueState.OK -> C.Green
        DueState.NONE -> C.Dim
    }
    val used = when {
        due.state == DueState.NONE -> 0f
        due.kmLeft != null -> (1f - (due.kmLeft / s.serviceKm.toFloat()).toFloat()).coerceIn(0f, 1f)
        else -> (1f - ((due.daysLeft ?: 0L) / (s.serviceMonths * 30f))).coerceIn(0f, 1f)
    }
    val bar by animateFloatAsState(used, tween(900, easing = FastOutSlowInEasing), label = "svc")

    Panel(Modifier.fillMaxWidth(), tint = if (due.state == DueState.NONE) null else color) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Next service")
            if (due.state == DueState.NONE) {
                Txt("Log your last service below and this counts down to the next one.", size = 14.sp, color = C.Dim)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Txt(
                        if (due.kmLeft != null) "${maxOf(0.0, due.kmLeft).roundToInt()} km" else "-",
                        size = 38.sp, weight = FontWeight.Light, spacing = (-0.8).sp,
                    )
                    Txt("${maxOf(0L, due.daysLeft ?: 0L)} days", Modifier.padding(bottom = 7.dp), size = 15.sp, color = C.Dim)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.Line)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).graphicsLayer {
                        scaleX = bar
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }.background(color))
                }
                StatusChip(
                    when (due.state) { DueState.DUE -> "Due now"; DueState.SOON -> "Due soon"; else -> "On track" },
                    color,
                )
                Txt(
                    "At ${due.nextKm?.roundToInt()} km or ${due.nextDateMs?.let { shown.format(Date(it)) }}, whichever comes first.",
                    size = 12.sp, color = C.Dim,
                )
            }
            Txt(
                "Odometer ${odo?.roundToInt()?.toString() ?: "not reported"} km. Interval every ${s.serviceKm} km or ${s.serviceMonths} months.",
                size = 12.sp, color = C.Faint,
            )
            Txt(
                "Change interval in Settings",
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSettings() },
                size = 12.sp, color = C.RedHi,
            )
        }
    }

    var adding by remember { mutableStateOf(false) }
    var km by remember(odo) { mutableStateOf(odo?.roundToInt()?.toString() ?: "") }
    var day by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var note by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }

    if (!adding) {
        PrimaryButton("Log a service", { adding = true })
    } else {
        val ts = parseDay(day)
        val kmV = km.toDoubleOrNull()
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("New service entry")
                Field("Odometer (km)", km, { km = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, keyboard = KeyboardType.Decimal)
                Field("Date (yyyy-mm-dd)", day, { day = it.take(10) }, keyboard = KeyboardType.Number)
                Field("What was done (optional)", note, { note = it.take(80) }, hint = "e.g. oil + filter, 3rd free service")
                Field("Cost in rupees (optional)", cost, { cost = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, keyboard = KeyboardType.Decimal)
                PrimaryButton(
                    "Save service",
                    {
                        if (ts != null && kmV != null) {
                            vm.addService(ServiceLog(ts, kmV, note.trim(), cost.toDoubleOrNull()))
                            adding = false
                            note = ""
                            cost = ""
                        }
                    },
                    enabled = ts != null && kmV != null,
                )
                GhostButton("Cancel", { adding = false })
            }
        }
    }

    if (d.services.isNotEmpty()) {
        SectionTitle("History", trailing = "${d.services.size} entries")
        d.services.forEach { e ->
            Panel(Modifier.fillMaxWidth(), radius = 20.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt("${shown.format(Date(e.ts))}  -  ${e.km.roundToInt()} km", size = 14.sp, weight = FontWeight.Medium)
                        if (e.note.isNotBlank()) Txt(e.note, size = 12.sp, color = C.Dim)
                        e.cost?.let { Txt("Rs ${it.roundToInt()}", size = 12.sp, color = C.Faint) }
                    }
                    Txt(
                        "Remove",
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.deleteService(e.ts) }.padding(8.dp),
                        size = 12.sp, color = C.Faint,
                    )
                }
            }
        }
    } else {
        Txt(
            "Hyundai doesn't publish service history through Bluelink, so entries here are the ones you log yourself.",
            Modifier.padding(horizontal = 4.dp), size = 11.sp, color = C.Faint, lineHeight = 16.sp,
        )
    }
}
