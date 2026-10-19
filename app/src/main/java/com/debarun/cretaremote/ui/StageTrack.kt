package com.debarun.cretaremote.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.data.Phase

/**
 * Three-step track for a running command: Sending (phone to Hyundai), Car waking (Hyundai to the car's modem, which
 * sleeps to save the battery), Confirmed. The app cannot see inside the car, so the middle step stays active until
 * the car reports back.
 */
@Composable
fun StageTrack(phase: Phase, modifier: Modifier = Modifier) {
    val failed = phase == Phase.FAIL || phase == Phase.CANCELLED
    val active = when (phase) {
        Phase.SENDING -> 0
        Phase.WAITING -> 1
        else -> 2
    }
    val target = when (phase) {
        Phase.SENDING -> 0.12f
        Phase.WAITING -> 0.5f
        Phase.OK -> 1f
        else -> 0.5f
    }
    val progress by animateFloatAsState(target, tween(650, easing = FastOutSlowInEasing), label = "track")
    val inf = rememberInfiniteTransition(label = "stage")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val busy = phase == Phase.SENDING || phase == Phase.WAITING

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val r = 6.dp.toPx()
            val y = size.height / 2f
            val x2 = size.width - r
            val xs = listOf(r, (r + x2) / 2f, x2)
            val lineCol = C.Line
            val doneCol = if (failed) C.Red else C.Green
            drawLine(lineCol, Offset(r, y), Offset(x2, y), 2.dp.toPx(), StrokeCap.Round)
            val fx = r + (x2 - r) * progress
            drawLine(if (failed) C.Red else C.Cyan, Offset(r, y), Offset(fx, y), 2.dp.toPx(), StrokeCap.Round)
            if (busy) {
                // a travelling dash toward the next node, so it visibly keeps working while the car wakes
                val from = fx
                val to = xs[(active + 1).coerceAtMost(2)]
                val px = from + (to - from) * pulse
                drawCircle(C.Cyan.copy(alpha = 1f - pulse), 2.5.dp.toPx(), Offset(px, y))
            }
            xs.forEachIndexed { i, x ->
                when {
                    i < active || (phase == Phase.OK) -> {
                        drawCircle(doneCol, r * 0.8f, Offset(x, y))
                    }
                    i == active -> {
                        val col = if (failed) C.Red else C.Cyan
                        if (busy) drawCircle(col.copy(alpha = 0.35f * (1f - pulse)), r * (1f + pulse), Offset(x, y))
                        drawCircle(col, r * 0.8f, Offset(x, y))
                    }
                    else -> drawCircle(lineCol, r * 0.8f, Offset(x, y), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val labels = listOf("Sending", "Car waking", "Confirmed")
            labels.forEachIndexed { i, l ->
                Txt(
                    l, Modifier.weight(1f), size = 10.sp,
                    color = if (i <= active) C.Text else C.Faint,
                    align = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                    maxLines = 1,
                )
            }
        }
    }
}
