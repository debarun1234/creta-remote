package com.debarun.cretaremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.Fill
import com.debarun.cretaremote.data.FuelLedger
import java.text.SimpleDateFormat
import kotlin.math.max
import java.util.Locale

private fun rs(v: Double?, dec: Int = 0): String = if (v == null) "—" else "₹" + Fmt.n(v, dec)
private fun kmplText(v: Double?): String = if (v == null) "—" else Fmt.n(v, 1)
private fun cleanNum(s: String): String = s.filter { it.isDigit() || it == '.' }.take(8)

/** Fill-up ledger, true full-tank economy, running cost, per-trip cost and tank check. Opened from Insights. */
@Composable
fun FuelLogScreen(d: AppData, vm: AppViewModel, onClose: () -> Unit) {
    val ins = d.insights
    val l = ins.ledger
    val car = d.car
    val tank = d.settings.tankLitres
    var form by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Fill?>(null) }

    Column(
        Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Fuel")
                Txt("Fuel log", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(C.Surface2).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Close", tint = C.Dim, modifier = Modifier.size(20.dp)) }
        }

        // ------------------------------------------------------------ log a fill-up
        if (form) {
            Panel(Modifier.fillMaxWidth()) {
                FillForm(
                    initial = editing,
                    odoDefault = car?.odometerKm,
                    priceDefault = l.priceLast,
                    tank = tank,
                    onSave = { f -> vm.saveFill(f, editing?.ts).also { if (it == null) { form = false; editing = null } } },
                    onDelete = editing?.let { e -> { vm.deleteFill(e.ts); form = false; editing = null } },
                    onCancel = { form = false; editing = null },
                )
            }
        } else {
            PrimaryButton("Log a fill-up", { editing = null; form = true })
        }

        // ------------------------------------------------------------ economy
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Real fuel economy")
                CountUp(l.kmplNow, decimals = 1, size = 54.sp, suffix = "km/L", color = if (l.usable) C.Text else C.Amber)
                if (l.usable) {
                    Txt(
                        "Last ${l.recentLegs} stretch${if (l.recentLegs == 1) "" else "es"}" + when {
                            l.recentExact == l.recentLegs -> " between full tanks"
                            l.recentExact == 0 -> ", measured with the fuel gauge between fill-ups"
                            else -> ", ${l.recentLegs - l.recentExact} measured with the fuel gauge"
                        } + (if (l.inProgressKm != null) ", including the one in progress" else "") + ", pooled by distance",
                        size = 12.sp, color = C.Dim,
                    )
                    if (l.recentExact < l.recentLegs && l.errPct != null) {
                        Txt("Gauge-based, so accurate to about ±${Fmt.n(max(1.0, l.errPct), 0)}%. A brim-full fill makes the next figure exact.", size = 11.sp, color = C.Faint)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Average", kmplText(l.kmplAll), Modifier.weight(1f))
                        Stat("Best", kmplText(l.kmplBest), Modifier.weight(1f), C.Green)
                        Stat("Worst", kmplText(l.kmplWorst), Modifier.weight(1f), C.Amber)
                    }
                    l.index?.let { idx ->
                        val c = when { idx >= 103 -> C.Green; idx <= 95 -> C.Amber; else -> C.Dim }
                        val word = when { idx >= 103 -> "${idx - 100}% better than your average"; idx <= 95 -> "${100 - idx}% below your average"; else -> "in line with your average" }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Txt("$idx", size = 26.sp, color = c, weight = FontWeight.Medium)
                            Column {
                                Txt("Efficiency index", size = 13.sp, weight = FontWeight.Medium)
                                Txt("Latest two stretches, $word", size = 11.sp, color = C.Faint)
                            }
                        }
                    }
                } else if (l.provisionalKmpl != null) {
                    Txt("Provisional. Rough, from the fuel gauge. A measured figure appears after about 250 km of driving.", size = 12.sp, color = C.Amber)
                    Txt(
                        "${Fmt.n(l.openKm ?: 0.0, 0)} km since your last brim-full fill, about ${Fmt.n(l.provisionalLitres ?: 0.0, 1)} L burnt (${Fmt.n(tank, 0)} L full, gauge now ${ins.litresLeft?.let { Fmt.n(it, 1) } ?: "?"} L).",
                        size = 12.sp, color = C.Dim,
                    )
                    Txt("The gauge moves in coarse steps, so treat this as a range of a km/L either way. If the odometer in the app looks behind the dashboard, correct the fill-up's odometer first.", size = 11.sp, color = C.Faint)
                } else {
                    Txt(l.hint ?: "", size = 13.sp, color = C.Dim)
                }
                ins.kmplCar?.let { c ->
                    val mine = l.kmplNow
                    Txt(
                        "The car's own estimate (range ÷ fuel left) says ${Fmt.n(c, 1)} km/L" +
                            (if (mine != null && mine > 0) ", ${Fmt.n(kotlin.math.abs((c / mine - 1) * 100), 0)}% ${if (c >= mine) "above" else "below"} the measured figure" else "") + ".",
                        size = 11.sp, color = C.Faint,
                    )
                }
                val series = l.legs.filter { it.valid }.map { it.kmpl }
                if (series.size >= 2) {
                    LineChart(series, Modifier.fillMaxWidth().height(130.dp), C.Cyan)
                    Txt("km/L per stretch, oldest to newest", size = 11.sp, color = C.Faint)
                } else if (l.usable && l.hint != null) {
                    Txt(l.hint, size = 12.sp, color = C.Amber)
                }
            }
        }

        // ------------------------------------------------------------ cost
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Running cost")
                if (l.costPerKm != null || l.costPerKmNow != null || l.priceLast != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Paid per km", rs(l.costPerKm, 2), Modifier.weight(1f))
                        Stat(if (l.usable) "Per km at today's price" else "Per km (provisional)", rs(l.costPerKmNow, 2), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Per day", rs(l.costPerDay), Modifier.weight(1f))
                        Stat("Per month", rs(l.costPerMonth), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Average price / L", rs(l.priceAvg, 2), Modifier.weight(1f))
                        Stat("Latest price / L", rs(l.priceLast, 2), Modifier.weight(1f))
                    }
                    Txt(
                        "Total logged: ${Fmt.n(l.litresTotal, 1)} L, ${rs(l.spendTotal)}" +
                            (l.kmPerDay?.let { " · you drive about ${Fmt.n(it, 0)} km a day" } ?: ""),
                        size = 12.sp, color = C.Dim,
                    )
                    if (l.priceIsFallback) Txt("No price logged on any fill-up, so the price from Settings is used.", size = 11.sp, color = C.Faint)
                } else {
                    Txt("Add the price per litre (or the amount paid) when you log a fill-up, and cost per km, day and month appear here.", size = 13.sp, color = C.Dim)
                }
            }
        }

        // ------------------------------------------------------------ trip cost
        TripCostPanel(d)

        // ------------------------------------------------------------ tank check
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("In the tank now")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Gauge says", ins.litresLeft?.let { "${Fmt.n(it, 1)} L" } ?: "—", Modifier.weight(1f))
                    Stat("Fill-up log says", l.estLitres?.let { "${Fmt.n(it, 1)} L" } ?: "—", Modifier.weight(1f), C.Cyan)
                }
                if (l.openKm != null) {
                    Txt(
                        "Driven ${Fmt.n(l.openKm, 0)} km since your last brim-full fill" +
                            (if (l.openLitresAdded > 0) ", plus ${Fmt.n(l.openLitresAdded, 1)} L topped up since" else ""),
                        size = 12.sp, color = C.Dim,
                    )
                }
                if (ins.predictedRangeKm != null) {
                    Txt(
                        "About ${Fmt.n(ins.predictedRangeKm, 0)} km to go" +
                            (ins.refuelBy?.let { " · refuel by ${dayText(it)}" } ?: ""),
                        size = 13.sp, color = C.Text,
                    )
                }
                Txt(
                    "The gauge is a coarse percentage of the ${Fmt.n(tank, 0)} L tank. The log estimate is tank size minus km driven at your real economy plus top-ups, so it sharpens after every brim-full fill.",
                    size = 11.sp, color = C.Faint,
                )
            }
        }

        // ------------------------------------------------------------ stretches
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Stretches")
                if (l.legs.isEmpty()) {
                    Txt("A stretch is a distance with the fuel burnt over it: from one brim-full fill to the next, or, using the fuel gauge, from one reading to another about 250 km later. Keep logging fill-ups, full or partial, and stretches appear here.", size = 13.sp, color = C.Dim)
                } else {
                    l.legs.asReversed().take(12).forEach { g ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Txt("${Fmt.grouped(g.fromOdo.toLong())} to ${Fmt.grouped(g.toOdo.toLong())} km", size = 14.sp, weight = FontWeight.Medium)
                                Txt(
                                    "${Fmt.n(g.km, 0)} km · ${Fmt.n(g.litres, 1)} L · ${rs(g.cost)}" + (if (g.fills > 1) " · ${g.fills} fills" else "") + (if (g.exact) "" else " · from gauge"),
                                    size = 12.sp, color = C.Faint,
                                )
                                if (!g.valid) Txt(g.note, size = 11.sp, color = C.Amber)
                            }
                            Txt(
                                if (g.litres > 0) Fmt.n(g.kmpl, 1) else "—",
                                size = 18.sp, weight = FontWeight.Medium, color = if (g.valid) C.Cyan else C.Faint,
                            )
                            Txt(" km/L", size = 11.sp, color = C.Faint)
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ ledger
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Fill-ups", Modifier.weight(1f))
                    Box(
                        Modifier.clip(CircleShape).background(C.Surface2).clickable { editing = null; form = true }.padding(8.dp),
                    ) { Icon(Icons.Rounded.Add, "Log a fill-up", tint = C.RedHi, modifier = Modifier.size(18.dp)) }
                }
                if (l.fills.isEmpty()) {
                    Txt("Nothing logged yet. Tap Log a fill-up after your next visit to the pump.", size = 13.sp, color = C.Dim)
                } else {
                    l.fills.asReversed().forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable { editing = f; form = true }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Txt(SimpleDateFormat("dd-MM-yyyy", Locale.US).format(java.util.Date(f.ts)), size = 14.sp, weight = FontWeight.Medium)
                                Txt(
                                    "${Fmt.grouped(f.odoKm.toLong())} km · " + (if (f.pricePerL > 0) "${rs(f.pricePerL, 2)}/L · ${rs(f.amount)}" else "no price") +
                                        " · " + (if (f.full) "brim-full" else "top-up"),
                                    size = 12.sp, color = C.Faint,
                                )
                            }
                            Txt("+${Fmt.n(f.litres, 1)} L", size = 16.sp, color = C.Green, weight = FontWeight.Medium)
                        }
                    }
                    Txt("Tap an entry to edit or delete it.", size = 11.sp, color = C.Faint)
                }
            }
        }

        // ------------------------------------------------------------ method
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("How this is worked out")
                Txt("Brim-full to brim-full is the exact measure: the distance driven divided by every litre added since is your real km/L.", size = 12.sp, color = C.Dim)
                Txt("Partial top-ups count too. Between fill-ups the app reads the fuel gauge from your car: litres burnt = litres in the tank at the start + litres you poured in - litres in the tank at the end. That needs about 250 km of driving, and the accuracy is shown beside the figure.", size = 12.sp, color = C.Dim)
                Txt("Cost uses the price you actually paid on each fill, not one fixed price. Stretches shorter than 40 km, or far off your usual, are left out of the averages and flagged.", size = 12.sp, color = C.Dim)
                Txt("Gauge readings taken beside a fill-up, or with the tank nearly full or empty, are ignored because the gauge is unreliable there. A gauge rise with no fill-up logged is treated as a missed refuel, and the stretch starts again after it.", size = 12.sp, color = C.Dim)
                Txt("The stretch you are driving now counts too: once at least one stretch is finished, the distance and fuel since the last reading are added to the recent figure, so it moves as you drive. Stretches over 15 km are used.", size = 12.sp, color = C.Dim)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = C.Text) {
    Column(modifier) {
        Txt(value, size = 20.sp, weight = FontWeight.Medium, color = color, maxLines = 1)
        Txt(label, size = 11.sp, color = C.Faint)
    }
}

@Composable
fun TripCostPanel(d: AppData, compact: Boolean = false) {
    val ins = d.insights
    val l = ins.ledger
    val e = l.kmplNow
    val limit = d.settings.fuelLimitL
    val litresNow = ins.litresLeft
    val odo = d.car?.odometerKm
    var km by remember { mutableStateOf("") }
    val tripKm = km.toDoubleOrNull()?.takeIf { it > 0 }
    val q = tripKm?.let { ins.tripCost(it) }
    val range = tripKm?.let { FuelLedger.quote(it, l) }

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Trip cost and next fill-up")
            if (e == null) {
                Txt("Available once you have a fill-up and have driven a bit since.", size = 13.sp, color = C.Dim)
            } else {
                // what the car reports right now (Home already shows the gauge and odometer, so skip it there)
                if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Fuel now (car)", litresNow?.let { "${Fmt.n(it, 1)} L" } ?: "—", Modifier.weight(1f), C.Cyan)
                    Stat("Odometer", odo?.let { "${Fmt.grouped(it.toLong())} km" } ?: "—", Modifier.weight(1f))
                }
                Txt(
                    (if (compact) "${litresNow?.let { "${Fmt.n(it, 1)} L" } ?: "—"} in the tank, ${agoText(d.fetchedAt)}. " else "From the car's last report, ${agoText(d.fetchedAt)}. ") +
                        "Economy used: ${Fmt.n(e, 1)} km/L" + if (l.usable) "." else " (rough, until a stretch is measured).",
                    size = 11.sp, color = C.Faint,
                )

                // next fill-up, by odometer
                val fill = ins.fillAtOdo
                if (fill != null && ins.kmToLimit != null) {
                    Column {
                        Eyebrow("Fill up at")
                        Txt("${Fmt.grouped(fill.toLong())} km", size = 26.sp, weight = FontWeight.Light, color = C.Green)
                        Txt(
                            if (ins.kmToLimit <= 0.5) "You are at or below your $limit L fuel limit. Fill up now."
                            else "In ${Fmt.n(ins.kmToLimit, 0)} km, when the tank reaches your $limit L limit" + (ins.refuelBy?.let { ", around ${dayText(it)} at your usual pace" } ?: "") + ".",
                            size = 12.sp, color = C.Dim,
                        )
                    }
                }

                Field("Plan a trip (km)", km, { km = cleanNum(it) }, keyboard = KeyboardType.Decimal, hint = "e.g. 120")
                if (q != null) {
                    val tk = tripKm ?: 0.0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Fuel for the trip", "${Fmt.n(q.litres, 1)} L", Modifier.weight(1f))
                        Stat("Cost", rs(q.cost), Modifier.weight(1f), C.Cyan)
                    }
                    if (range?.costLow != null && range.costHigh != null) {
                        Txt("Likely ${rs(range.costLow)} to ${rs(range.costHigh)}, from your best and worst recent stretch.", size = 12.sp, color = C.Dim)
                    }
                    if (litresNow != null) {
                        val after = litresNow - q.litres
                        val kmAfter = max(0.0, (after - limit) * e)
                        when {
                            after < 0 -> Txt("Not enough fuel for this trip: about ${Fmt.n(-after, 1)} L short. Fill up before you leave or on the way.", size = 13.sp, color = C.RedHi)
                            after < limit -> Txt(
                                "You would reach your $limit L limit " + (ins.kmToLimit?.let { "${Fmt.n(it, 0)} km into the trip" } ?: "during the trip") +
                                    (fill?.let { ", at odometer ${Fmt.grouped(it.toLong())} km" } ?: "") + ". About ${Fmt.n(max(0.0, after), 1)} L left when you arrive. Plan a fill-up on the way.",
                                size = 13.sp, color = C.Amber,
                            )
                            else -> Txt(
                                "About ${Fmt.n(after, 1)} L left when you arrive" + (odo?.let { ", at odometer ${Fmt.grouped((it + tk).toLong())} km" } ?: "") +
                                    ". Then about ${Fmt.n(kmAfter, 0)} km more before your $limit L limit.",
                                size = 13.sp, color = C.Green,
                            )
                        }
                    }
                    if (!l.usable) Txt("Provisional, from the rough economy.", size = 11.sp, color = C.Amber)
                } else {
                    Txt("Enter a distance for its fuel, cost, and what is left when you arrive.", size = 12.sp, color = C.Faint)
                }
            }
        }
    }
}

@Composable
private fun FillForm(
    initial: Fill?,
    odoDefault: Double?,
    priceDefault: Double?,
    tank: Double,
    onSave: (Fill) -> String?,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false } }
    val start = remember { dateFmt.format(java.util.Date(initial?.ts ?: System.currentTimeMillis())) } // yyyy-MM-dd
    var dd by remember { mutableStateOf(start.substring(8, 10)) }
    var mm by remember { mutableStateOf(start.substring(5, 7)) }
    var yyyy by remember { mutableStateOf(start.substring(0, 4)) }
    val date = "$yyyy-${mm.padStart(2, '0')}-${dd.padStart(2, '0')}"
    val dateComplete = dd.isNotEmpty() && mm.isNotEmpty() && yyyy.length == 4
    var odo by remember { mutableStateOf((initial?.odoKm ?: odoDefault)?.toLong()?.toString() ?: "") }
    var litres by remember { mutableStateOf(initial?.litres?.let { Fmt.n(it, 2).trimEnd('0').trimEnd('.') } ?: "") }
    var price by remember { mutableStateOf((initial?.pricePerL ?: priceDefault)?.takeIf { it > 0 }?.let { Fmt.n(it, 2) } ?: "") }
    var amount by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(initial?.full ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(if (initial == null) "New fill-up" else "Edit fill-up")
        DateBoxes("Date", dd, mm, yyyy) { d, m, y -> dd = d; mm = m; yyyy = y }
        Field("Odometer (km)", odo, { odo = cleanNum(it) }, keyboard = KeyboardType.Number)
        Field("Litres filled (max ${Fmt.n(tank, 0)})", litres, { litres = cleanNum(it) }, keyboard = KeyboardType.Decimal)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Price per litre ₹", price, { price = cleanNum(it) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            Field("or amount paid ₹", amount, { amount = cleanNum(it) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Filled to the brim", size = 15.sp, weight = FontWeight.Medium)
                Txt(
                    if (full) "Pump clicked off. The most exact starting point for economy."
                    else "Partial top-up. It counts too: the app uses the fuel gauge before and after to measure economy.",
                    size = 12.sp, color = C.Faint,
                )
            }
            Toggle(full, { full = it })
        }
        error?.let { Txt(it, size = 13.sp, color = C.RedHi) }
        PrimaryButton("Save fill-up", {
            val day = if (dateComplete) runCatching { dateFmt.parse(date) }.getOrNull() else null
            val l = litres.toDoubleOrNull()
            val o = odo.toDoubleOrNull()
            when {
                day == null -> error = "Enter a real date as DD-MM-YYYY."
                l == null || o == null -> error = "Enter litres and odometer."
                else -> {
                    val now = System.currentTimeMillis()
                    val sameDay = dateFmt.format(java.util.Date(now)) == date
                    val ts = when {
                        initial != null && dateFmt.format(java.util.Date(initial.ts)) == date -> initial.ts
                        sameDay -> now
                        else -> day.time + 12 * 3_600_000L
                    }
                    val p = price.toDoubleOrNull() ?: amount.toDoubleOrNull()?.takeIf { l > 0 }?.let { it / l } ?: 0.0
                    error = onSave(Fill(ts, o, l, p, full))
                }
            }
        })
        onDelete?.let { GhostButton("Delete this fill-up", it, color = C.RedHi) }
        GhostButton("Cancel", onCancel, color = C.Dim)
    }
}
