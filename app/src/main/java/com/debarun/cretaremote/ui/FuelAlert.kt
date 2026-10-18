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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.FuelWatch

/** The limit stepper: how many litres to keep in the tank on this trip (10 to 30). */
@Composable
fun FuelLimitStepper(limit: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepButton(Icons.Rounded.Remove, enabled = limit > FuelWatch.MIN) { onChange(limit - 1) }
        Txt("$limit L", size = 26.sp, weight = FontWeight.Light)
        StepButton(Icons.Rounded.Add, enabled = limit < FuelWatch.MAX) { onChange(limit + 1) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(C.Surface2).border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) C.Text else C.Faint, modifier = Modifier.size(20.dp))
    }
}

/** Full card for the Insights screen: set the limit, see where the tank is, and what will ring when. */
@Composable
fun FuelAlertCard(limit: Int, litres: Double?, stage: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.LocalGasStation, null, tint = C.Amber, modifier = Modifier.size(18.dp))
                Eyebrow("Fuel alert for this trip")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Keep at least", size = 13.sp, color = C.Dim)
                    FuelLimitStepper(limit, onChange)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Txt("In the tank", size = 13.sp, color = C.Dim)
                    Txt(litres?.let { "${Fmt.n(it, 1)} L" } ?: "--", size = 26.sp, weight = FontWeight.Light, color = if (stage >= 2) C.RedHi else C.Text)
                }
            }
            Txt(
                "A short alert at ${limit + 2} L, then a phone alarm at $limit L that rings until you tap \"I'll fill up\". " +
                    "Set it from 10 to 30 L before each trip. It goes back to 10 L after you refuel.",
                size = 12.sp, color = C.Dim,
            )
            Txt(
                "The car only reports fuel now and then, so alerts can arrive a few minutes after the tank crosses the line.",
                size = 11.sp, color = C.Faint,
            )
        }
    }
}

/** Shown on Home once the heads-up or the alarm has fired. */
@Composable
fun FuelAlarmBanner(stage: Int, litres: Double?, limit: Int, onAck: () -> Unit, modifier: Modifier = Modifier) {
    if (stage != 1 && stage != 2) return
    val alarm = stage == 2
    val col = if (alarm) C.Red else C.Amber
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(col.copy(alpha = 0.14f)).border(1.dp, col.copy(alpha = 0.5f), shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.LocalGasStation, null, tint = col, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Txt(if (alarm) "Fill the car now" else "Fuel nearing your $limit L limit", size = 15.sp, weight = FontWeight.SemiBold)
            Txt(
                (litres?.let { "About ${Fmt.n(it, 1)} L left. " } ?: "") + if (alarm) "At your limit." else "Plan a fill-up.",
                size = 12.sp, color = C.Dim,
            )
        }
        if (alarm) {
            Txt(
                "I'll fill up",
                Modifier.clip(RoundedCornerShape(14.dp)).background(col).clickable { onAck() }.padding(horizontal = 14.dp, vertical = 9.dp),
                size = 13.sp, weight = FontWeight.SemiBold, color = Color.White,
            )
        }
    }
}
