package com.debarun.cretaremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.AppData
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun InsightsScreen(d: AppData, onFuelLimit: (Int) -> Unit, onTyreLog: (List<Double?>) -> Unit, onFuelLog: () -> Unit) {
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

        // ---------------------------------------------------------------- economy (from the fill-up log)
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Fuel economy")
                CountUp(ins.kmplUsed, decimals = 1, size = 54.sp, suffix = "km/L")
                Txt(
                    if (ins.kmplUsed != null) "Based on ${ins.usedSource}"
                    else "Log your fill-ups and the app measures real economy, tank to tank.",
                    size = 12.sp, color = C.Dim,
                )
                val l = ins.ledger
                if (l.usable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        ins.costPerKm?.let { MiniStat("₹ per km", Fmt.n(it, 2)) }
                        ins.costPerDay?.let { MiniStat("₹ per day", Fmt.n(it, 0)) }
                        l.index?.let { MiniStat("Efficiency index", "$it") }
                    }
                }
                GhostButton(if (l.fills.isEmpty()) "Start your fuel log" else "Open fuel log", onFuelLog, color = C.Cyan)
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

