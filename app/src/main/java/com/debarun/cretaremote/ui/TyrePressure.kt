package com.debarun.cretaremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.debarun.cretaremote.bluelink.CarState
import com.debarun.cretaremote.bluelink.tyreAttention
import com.debarun.cretaremote.data.TyreLog

private val WHEELS = listOf("Front left", "Front right", "Rear left", "Rear right")

/**
 * Pressure for all four tyres. Numbers come from the car when it sends them; otherwise from the driver's own last
 * reading (dashboard or pump), shown with its age. The car's low-pressure warning lights always show live.
 */
@Composable
fun TyrePanel(car: CarState?, log: TyreLog?, onLog: (List<Double?>) -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val fromCar = car?.tyrePsi?.any { it != null } == true
    val psi = if (fromCar) car!!.tyrePsi else log?.psi ?: List(4) { null }
    val w = car?.tyreWarn
    val flags = listOf(w?.fl == true, w?.fr == true, w?.rl == true, w?.rr == true)
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Tyre pressure", Modifier.weight(1f))
                when {
                    car == null -> {}
                    car.tyreAttention -> Txt("Check pressure", size = 12.sp, color = C.Amber, weight = FontWeight.Medium)
                    car.tyreReported -> Txt("Car says OK", size = 12.sp, color = C.Green, weight = FontWeight.Medium)
                    else -> Txt("Not reported by the car", size = 12.sp, color = C.Faint)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (col in 0..1) {
                            val i = row * 2 + col
                            TyreTile(WHEELS[i], psi[i], flags[i], car?.tyreReported == true, Modifier.weight(1f))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    when {
                        fromCar -> "Pressure from the car"
                        log != null -> "Logged by you ${agoText(log.ts)}"
                        car?.tyreReported == true -> "This car sends a warning light per tyre, not pressure numbers."
                        else -> "No pressure data yet."
                    },
                    Modifier.weight(1f), size = 11.sp, color = C.Faint,
                )
                if (!fromCar) {
                    Txt(
                        if (log == null) "Log pressures" else "Update",
                        Modifier.clip(RoundedCornerShape(12.dp)).background(C.Surface2).clickable { editing = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                        size = 12.sp, weight = FontWeight.Medium, color = C.Cyan,
                    )
                }
            }
        }
    }
    if (editing) TyreLogDialog(log?.psi ?: List(4) { null }, onDismiss = { editing = false }, onSave = { onLog(it); editing = false })
}

@Composable
private fun TyreTile(name: String, psi: Double?, low: Boolean, reported: Boolean, modifier: Modifier) {
    val col = when {
        low -> C.Amber
        reported -> C.Green
        else -> C.Faint
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.clip(shape).background(C.Surface2).border(1.dp, col.copy(alpha = if (low) 0.6f else 0.18f), shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(col))
        Column(Modifier.weight(1f)) {
            Txt(name, size = 11.sp, color = C.Dim, maxLines = 1)
            when {
                psi != null -> Row(verticalAlignment = Alignment.Bottom) {
                    Txt(if (psi % 1.0 == 0.0) "${psi.toInt()}" else "%.1f".format(psi), size = 22.sp, weight = FontWeight.Light, color = if (low) C.Amber else C.Text)
                    Txt("psi", Modifier.padding(start = 4.dp, bottom = 3.dp), size = 11.sp, color = C.Faint)
                }
                low -> Txt("Low", size = 18.sp, color = C.Amber, weight = FontWeight.Medium)
                reported -> Txt("OK", size = 18.sp, weight = FontWeight.Light)
                else -> Txt("--", size = 18.sp, color = C.Faint)
            }
        }
    }
}

@Composable
private fun TyreLogDialog(initial: List<Double?>, onDismiss: () -> Unit, onSave: (List<Double?>) -> Unit) {
    val text = remember { List(4) { i -> mutableStateOf(initial[i]?.let { v -> if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(v) } ?: "") } }
    Dialog(onDismissRequest = onDismiss) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("Log tyre pressure")
                Txt("Type the psi from the dashboard display or the pump. Leave a wheel empty to skip it.", size = 12.sp, color = C.Dim)
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (col in 0..1) {
                            val i = row * 2 + col
                            Field(WHEELS[i], text[i].value, { v -> text[i].value = v.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, hint = "psi")
                        }
                    }
                }
                val parsed = text.map { it.value.toDoubleOrNull()?.takeIf { v -> v in 10.0..80.0 } }
                PrimaryButton("Save", { onSave(parsed) }, enabled = parsed.any { it != null })
            }
        }
    }
}
