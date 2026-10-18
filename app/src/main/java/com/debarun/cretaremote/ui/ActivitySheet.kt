package com.debarun.cretaremote.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.Activity
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.Outcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Cmd.title() = when (this) {
    Cmd.LOCK -> "Lock"
    Cmd.UNLOCK -> "Unlock"
    Cmd.START -> "Start engine & climate"
    Cmd.STOP -> "Stop engine"
    Cmd.HORN -> "Horn & lights"
    Cmd.LIGHTS -> "Lights only"
    Cmd.BOOT -> "Open boot"
}

private fun Outcome.look(): Triple<String, Color, ImageVector> = when (this) {
    Outcome.SUCCESS -> Triple("Done", C.Green, Icons.Rounded.Check)
    Outcome.FAILED -> Triple("Failed", C.RedHi, Icons.Rounded.Close)
    Outcome.CANCELLED -> Triple("Cancelled", C.Dim, Icons.Rounded.Remove)
    Outcome.UNCONFIRMED -> Triple("Unconfirmed", C.Amber, Icons.Rounded.Schedule)
}

/** Everything sent to the car and how it ended, newest first. Opening it marks the entries as seen. */
@Composable
fun ActivitySheet(d: AppData, vm: AppViewModel, onClose: () -> Unit) {
    LaunchedEffect(d.activity.size) { vm.markActivitySeen() }
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).clip(shape).background(C.Surface).border(1.dp, C.Line, shape).navigationBarsPadding()) {
        Box(Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(C.Line))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp).padding(top = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Commands")
                Txt("Activity", Modifier.padding(top = 2.dp), size = 26.sp, weight = FontWeight.Light, spacing = (-0.4).sp)
            }
            if (d.activity.isNotEmpty()) {
                Txt(
                    "Clear",
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.clearActivity() }.padding(8.dp),
                    size = 14.sp, color = C.Dim,
                )
            }
            Txt(
                "Close",
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }.padding(8.dp),
                size = 14.sp, color = C.Dim,
            )
        }

        if (d.activity.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Txt(
                    "Nothing sent yet. Every lock, unlock, engine or horn command shows up here with its result and how long it took.",
                    size = 13.sp, color = C.Dim, align = TextAlign.Center,
                )
            }
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val dayFmt = remember { SimpleDateFormat("yyyyMMdd", Locale.US) }
                var lastDay = ""
                d.activity.forEachIndexed { i, a ->
                    val day = dayFmt.format(Date(a.ts))
                    if (day != lastDay) {
                        lastDay = day
                        Eyebrow(dayText(a.ts), Modifier.padding(top = if (i == 0) 0.dp else 8.dp, start = 4.dp), color = C.Faint)
                    }
                    ActivityRow(a, i)
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(a: Activity, index: Int) {
    val (label, color, icon) = a.outcome.look()
    val show by animateFloatAsState(1f, tween(450, index.coerceAtMost(8) * 50), label = "act")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = show; translationY = (1f - show) * 12.dp.toPx() }
            .clip(RoundedCornerShape(18.dp)).background(C.Surface2).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)).border(1.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(a.cmd.title(), Modifier.weight(1f), size = 15.sp, weight = FontWeight.Medium)
                Txt(label, size = 11.sp, color = color, weight = FontWeight.Medium)
            }
            Txt(a.message, size = 12.sp, color = C.Dim)
            val bits = listOfNotNull(
                SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(a.ts)),
                "took ${Fmt.n(a.totalMs / 1000.0, 1)} s",
                a.sendMs?.let { "accepted in ${Fmt.n(it / 1000.0, 1)} s" },
                a.via?.let { "via $it" },
            )
            Txt(bits.joinToString("  ·  "), size = 11.sp, color = C.Faint)
        }
    }
}
