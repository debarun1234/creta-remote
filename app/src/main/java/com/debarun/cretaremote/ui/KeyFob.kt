package com.debarun.cretaremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.CmdUi

/**
 * The home-screen controls, laid out like a key fob: one big ignition button, a row of round action buttons,
 * and two small utility chips underneath.
 */
@Composable
fun KeyFob(
    engineOn: Boolean,
    cmd: CmdUi,
    navEnabled: Boolean,
    syncing: Boolean,
    onAct: (Cmd) -> Unit,
    onClimate: () -> Unit,
    onNavigate: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun busy(vararg c: Cmd) = cmd.busy && cmd.cmd in c
    Panel(modifier.fillMaxWidth(), radius = 28.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Eyebrow("Key fob")
            IgnitionButton(engineOn, busy(Cmd.START, Cmd.STOP)) { onAct(if (engineOn) Cmd.STOP else Cmd.START) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FobButton(Icons.Rounded.Thermostat, "Climate", C.Cyan, busy = false, onClick = onClimate)
                FobButton(Icons.Rounded.Lightbulb, "Lights", C.Amber, busy(Cmd.LIGHTS)) { onAct(Cmd.LIGHTS) }
                FobButton(Icons.Rounded.Campaign, "Horn", C.Red, busy(Cmd.HORN)) { onAct(Cmd.HORN) }
                FobButton(CarBootIcon, "Boot", C.Green, busy(Cmd.BOOT)) { onAct(Cmd.BOOT) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UtilityChip(Icons.Rounded.Navigation, "Navigate to car", Modifier.weight(1f), enabled = navEnabled, onClick = onNavigate)
                UtilityChip(Icons.Rounded.Bolt, "Sync live", Modifier.weight(1f), busy = syncing, onClick = onSync)
            }
        }
    }
}

@Composable
private fun IgnitionButton(running: Boolean, busy: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, spring(0.6f, Spring.StiffnessMedium), label = "ign")
    val accent by animateColorAsState(if (running) C.Amber else C.Red, label = "ignc")
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(66.dp).graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f))))
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .clickable(interactionSource = src, indication = null, enabled = !busy) { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (busy) Spinner(accent, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().clip(CircleShape).background(accent))
            if (!busy) Icon(Icons.Rounded.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(if (running) "Stop engine" else "Start engine", size = 16.sp, weight = FontWeight.SemiBold)
            Txt(if (running) "Engine is running" else "Remote ignition with your climate settings", size = 11.sp, color = C.Dim, maxLines = 1)
        }
    }
}

@Composable
private fun FobButton(icon: ImageVector, label: String, accent: Color, busy: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.55f, Spring.StiffnessMedium), label = "fob")
    Column(
        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
            .clickable(interactionSource = src, indication = null, enabled = !busy) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(62.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)).border(1.dp, accent.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) Spinner(accent, Modifier.size(62.dp)) else Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
        }
        Txt(label, size = 12.sp, weight = FontWeight.Medium, align = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun UtilityChip(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.height(44.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(shape).background(C.Surface2).border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .clickable(enabled = enabled && !busy) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) Spinner(C.Amber, Modifier.size(18.dp), 2f) else Icon(icon, null, tint = C.Dim, modifier = Modifier.size(18.dp))
        Txt(label, Modifier.padding(start = 8.dp), size = 12.sp, weight = FontWeight.Medium, color = C.Text, maxLines = 1)
    }
}

@Composable
private fun Spinner(color: Color, modifier: Modifier = Modifier, width: Float = 3f) {
    val inf = rememberInfiniteTransition(label = "spin")
    val a by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(modifier) {
        rotate(a) { drawArc(color, 0f, 100f, false, style = Stroke(width.dp.toPx(), cap = StrokeCap.Round)) }
    }
}
