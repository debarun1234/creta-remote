package com.debarun.cretaremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.bluelink.CarState
import com.debarun.cretaremote.bluelink.tyreAttention

/** Pressure for all four tyres, refreshed every time the app reads the car. */
@Composable
fun TyrePanel(car: CarState?, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Tyre pressure", Modifier.weight(1f))
                when {
                    car == null -> {}
                    !car.tyreReported -> Txt("Not reported by the car", size = 12.sp, color = C.Faint)
                    car.tyreAttention -> Txt("Check pressure", size = 12.sp, color = C.Amber, weight = FontWeight.Medium)
                    else -> Txt("All OK", size = 12.sp, color = C.Green, weight = FontWeight.Medium)
                }
            }
            val w = car?.tyreWarn
            val flags = listOf(w?.fl == true, w?.fr == true, w?.rl == true, w?.rr == true)
            val names = listOf("Front left", "Front right", "Rear left", "Rear right")
            val psi = car?.tyrePsi ?: List(4) { null }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (col in 0..1) {
                            val i = row * 2 + col
                            TyreTile(names[i], psi[i], flags[i], car?.tyreReported == true, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (car?.tyreReported == true && psi.all { it == null }) {
                Txt("This car reports a warning light per tyre, not the pressure numbers.", size = 11.sp, color = C.Faint)
            }
        }
    }
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
                    Txt("${psi.toInt()}", size = 22.sp, weight = FontWeight.Light)
                    Txt("psi", Modifier.padding(start = 4.dp, bottom = 3.dp), size = 11.sp, color = C.Faint)
                }
                !reported -> Txt("--", size = 18.sp, color = C.Faint)
                low -> Txt("Low", size = 18.sp, color = C.Amber, weight = FontWeight.Medium)
                else -> Txt("OK", size = 18.sp, color = C.Text, weight = FontWeight.Light, align = TextAlign.Start)
            }
        }
    }
}
