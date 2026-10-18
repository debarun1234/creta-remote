package com.debarun.cretaremote.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.FuelInsights
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun InsightsScreen(d: AppData, onFuelLimit: (Int) -> Unit, onTyreLog: (List<Double?>) -> Unit) {
    val ins = d.insights
    val car = d.car
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Eyebrow("Telemetry")
            Txt("Insights", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
        }

        // ---------------------------------------------------------------- fuel
        Panel(Modifier.fillMaxWidth(), tint = fuelColor(car?.fuelPct)) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FuelGauge(car?.fuelPct, ins.litresLeft, car?.lowFuel == true, Modifier.size(190.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Eyebrow("In the tank")
                        CountUp(ins.litresLeft, decimals = 1, size = 34.sp, suffix = "L")
                        Txt("of ${Fmt.n(d.settings.tankLitres, 0)} L tank", size = 12.sp, color = C.Faint)
                    }
                    Column {
                        Eyebrow("Car says")
                        CountUp(car?.dteKm, size = 26.sp, suffix = "km")
                    }
                    Column {
                        Eyebrow("You will get")
                        CountUp(ins.predictedRangeKm, size = 26.sp, suffix = "km", color = C.Cyan)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- efficiency
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Fuel economy")
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(ins.kmplUsed, decimals = 1, size = 54.sp, suffix = "km/L")
                }
                Txt(
                    if (ins.kmplUsed != null) "Based on ${ins.usedSource}" else "Needs a little driving history. The app records fuel and odometer every ~15 minutes in the background.",
                    size = 12.sp, color = C.Dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ins.kmplRecent?.let { MiniStat("Recent", "${Fmt.n(it, 1)}") }
                    ins.kmplLifetime?.let { MiniStat("Lifetime", "${Fmt.n(it, 1)}") }
                    ins.kmplCar?.let { MiniStat("Car est.", "${Fmt.n(it, 1)}") }
                }
                if (ins.cycles.size >= 2) {
                    LineChart(ins.cycles.map { it.kmpl }, Modifier.fillMaxWidth().height(140.dp), C.Cyan)
                    val f = SimpleDateFormat("d MMM", Locale.getDefault())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Txt(f.format(ins.cycles.first().endTs), size = 11.sp, color = C.Faint)
                        Txt("km/L per tank", size = 11.sp, color = C.Faint)
                        Txt(f.format(ins.cycles.last().endTs), size = 11.sp, color = C.Faint)
                    }
                } else if (ins.kmplUsed != null) {
                    Txt("The trend chart appears after two completed tank cycles.", size = 12.sp, color = C.Faint)
                }
            }
        }

        // ---------------------------------------------------------------- fuel alert
        FuelAlertCard(d.settings.fuelLimitL, d.fuel.litres ?: ins.litresLeft, d.fuel.stage, onFuelLimit)

        // ---------------------------------------------------------------- tyres
        TyrePanel(car, d.tyreLog, onTyreLog)

        // ---------------------------------------------------------------- distance
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Last 14 days")
                        CountUp(ins.dailyKm.sumOf { it.second }, decimals = 0, size = 34.sp, suffix = "km")
                    }
                    ins.avgDailyKm?.let { Txt("${Fmt.n(it, 1)} km / day", size = 13.sp, color = C.Dim) }
                }
                BarChart(ins.dailyKm.map { (day, km) -> weekdayInitial(day) to km }, Modifier.fillMaxWidth())
            }
        }

        // ---------------------------------------------------------------- cost
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("Running cost")
                if (ins.costPerKm != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            CountUp(ins.costPerKm, decimals = 1, size = 32.sp, suffix = "₹/km")
                        }
                        Column {
                            CountUp(ins.costPerKm * 100, decimals = 0, size = 32.sp, suffix = "₹/100km")
                        }
                    }
                    ins.costPerDay?.let { Txt("About ₹${Fmt.n(it, 0)} a day at your recent pace", size = 12.sp, color = C.Dim) }
                } else {
                    Txt("Set today's fuel price in Settings to see cost per km and per day.", size = 13.sp, color = C.Dim)
                }
            }
        }

        // ---------------------------------------------------------------- fill-ups
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Fill-ups")
                if (ins.refuels.isEmpty()) {
                    Txt("No fill-ups detected yet. One is logged when the fuel level jumps by 8% or more between two readings.", size = 13.sp, color = C.Dim)
                } else {
                    ins.refuels.take(8).forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Txt(dayText(r.ts), size = 14.sp, weight = FontWeight.Medium)
                                Txt("${Fmt.n(r.fromPct)}% to ${Fmt.n(r.toPct)}%" + (r.odoKm?.let { " · ${Fmt.grouped(it.toLong())} km" } ?: ""), size = 12.sp, color = C.Faint)
                            }
                            Txt("+${Fmt.n(r.litres, 1)} L", size = 16.sp, color = C.Green, weight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

private fun weekdayInitial(yyyymmdd: String): String = try {
    SimpleDateFormat("EEEEE", Locale.getDefault()).format(SimpleDateFormat("yyyyMMdd", Locale.US).parse(yyyymmdd)!!)
} catch (e: Exception) {
    ""
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(
        Modifier.padding(end = 4.dp),
    ) {
        Txt(value, size = 18.sp, weight = FontWeight.Medium)
        Txt(label, size = 11.sp, color = C.Faint)
    }
}

