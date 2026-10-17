package com.debarun.cretaremote.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import com.debarun.cretaremote.data.Cycle
import com.debarun.cretaremote.data.Phase
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Fmt {
    fun n(v: Double, dec: Int = 0): String = String.format(Locale.US, "%.${dec}f", v)
    fun grouped(v: Long): String = NumberFormat.getIntegerInstance(Locale("en", "IN")).format(v)
}

/** Animates 0 -> [target] the first time it appears, then follows target changes. */
@Composable
fun animateIn(target: Float, durationMs: Int = 1100, delayMs: Int = 0): State<Float> {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(target) { t = target }
    return animateFloatAsState(t, tween(durationMs, delayMs, FastOutSlowInEasing), label = "in")
}

@Composable
fun CountUp(
    value: Double?,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    size: androidx.compose.ui.unit.TextUnit = 40.sp,
    color: Color = C.Text,
    weight: FontWeight = FontWeight.Light,
    suffix: String = "",
    suffixSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val v by animateIn((value ?: 0.0).toFloat())
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Txt(if (value == null) "—" else Fmt.n(v.toDouble(), decimals), size = size, color = color, weight = weight, spacing = (-1).sp)
        if (suffix.isNotEmpty()) {
            Txt(suffix, Modifier.padding(start = 4.dp, bottom = 5.dp), size = suffixSize, color = C.Dim)
        }
    }
}

// ------------------------------------------------------------------ odometer

@Composable
fun Odometer(km: Double?, modifier: Modifier = Modifier, digitHeight: Dp = 52.dp) {
    val digits = (km ?: 0.0).toLong().coerceAtLeast(0L).toString().padStart(6, '0')
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        digits.forEachIndexed { i, ch -> RollingDigit(ch - '0', digitHeight, i * 70) }
    }
}

@Composable
private fun RollingDigit(d: Int, h: Dp, delayMs: Int) {
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(d) { target = d }
    val pos by animateFloatAsState(target.toFloat(), tween(1400, delayMs, FastOutSlowInEasing), label = "digit")
    val hPx = with(LocalDensity.current) { h.toPx() }
    Box(
        Modifier.width(34.dp).height(h).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1C2029), Color(0xFF0B0D12), Color(0xFF1C2029))))
            .border(1.dp, C.Line, RoundedCornerShape(10.dp)),
    ) {
        Column(
            Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true).graphicsLayer { translationY = -pos * hPx },
        ) {
            for (i in 0..9) {
                Box(Modifier.height(h).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Txt(i.toString(), size = 30.sp, weight = FontWeight.Medium, mono = true)
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000), Color(0x00000000), Color(0x66000000))),
            ),
        )
    }
}

// ------------------------------------------------------------------ fuel gauge with liquid

fun fuelColor(pct: Double?): Color = when {
    pct == null -> C.Dim
    pct < 15 -> C.Red
    pct < 30 -> C.Amber
    else -> C.Green
}

@Composable
fun FuelGauge(pct: Double?, litres: Double?, low: Boolean, modifier: Modifier = Modifier, ringWidth: Dp = 12.dp) {
    val p by animateIn(((pct ?: 0.0) / 100.0).toFloat(), 1500)
    val inf = rememberInfiniteTransition(label = "fuel")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    val wave2 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5100, easing = LinearEasing), RepeatMode.Restart), label = "wave2")
    val blink by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink")
    val c = fuelColor(pct)
    val cHi = when { pct == null -> C.Dim; pct < 15 -> C.RedHi; pct < 30 -> Color(0xFFFFD36B); else -> C.Cyan }

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val rw = ringWidth.toPx()
            val r = size.minDimension / 2f - rw
            val center = Offset(size.width / 2f, size.height / 2f)
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)

            // ticks
            for (i in 0..36) {
                val a = (135f + 270f * i / 36f) * (PI.toFloat() / 180f)
                val on = i / 36f <= p
                val len = if (i % 9 == 0) 9.dp.toPx() else 5.dp.toPx()
                val r1 = r + rw * 0.5f + 7.dp.toPx()
                val r2 = r1 + len
                drawLine(
                    if (on) c.copy(alpha = 0.9f) else C.Line,
                    Offset(center.x + kotlin.math.cos(a) * r1, center.y + kotlin.math.sin(a) * r1),
                    Offset(center.x + kotlin.math.cos(a) * r2, center.y + kotlin.math.sin(a) * r2),
                    strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round,
                )
            }

            // track
            drawArc(C.Line, 135f, 270f, false, tl, sz, style = Stroke(rw, cap = StrokeCap.Round))
            // glow + progress
            rotate(135f, center) {
                val brush = Brush.sweepGradient(0f to c, 0.75f to cHi, 1f to cHi, center = center)
                drawArc(c.copy(alpha = 0.18f), 0f, 270f * p, false, tl, sz, style = Stroke(rw * 2.2f, cap = StrokeCap.Round))
                drawArc(brush, 0f, 270f * p, false, tl, sz, style = Stroke(rw, cap = StrokeCap.Round))
            }
            // head dot
            val ha = (135f + 270f * p) * (PI.toFloat() / 180f)
            val head = Offset(center.x + kotlin.math.cos(ha) * r, center.y + kotlin.math.sin(ha) * r)
            drawCircle(Color.White, rw * 0.28f, head)

            // liquid in the middle
            val lr = r - rw * 1.6f
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center.x - lr, center.y - lr, center.x + lr, center.y + lr)) }
            drawCircle(Color(0xFF0B0D12), lr, center)
            clipPath(circle) {
                val level = center.y + lr - (lr * 2f) * p
                fun waveFill(phase: Float, amp: Float, alpha: Float) {
                    val path = Path()
                    path.moveTo(center.x - lr, center.y + lr)
                    var x = center.x - lr
                    while (x <= center.x + lr + 4f) {
                        val y = level + sin(((x - center.x + lr) / (lr * 2f) * 2f * PI + phase * 2f * PI)).toFloat() * amp
                        path.lineTo(x, y)
                        x += 4f
                    }
                    path.lineTo(center.x + lr, center.y + lr)
                    path.close()
                    drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.25f)), level - amp, center.y + lr))
                }
                val amp = lr * 0.045f * (0.4f + 0.6f * min(1f, p * 4f))
                waveFill(wave2, amp * 1.2f, 0.45f)
                waveFill(-wave, amp, 0.75f)
            }
            drawCircle(Color.White, lr, center, alpha = 0.05f, style = Stroke(1.5f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(if (pct == null) "—" else Fmt.n((p * 100f).toDouble()), size = 38.sp, weight = FontWeight.Light, color = Color.White, spacing = (-1).sp)
            Txt(if (litres != null) "${Fmt.n(litres, 1)} L" else "FUEL", size = 12.sp, color = C.Text.copy(alpha = 0.75f), weight = FontWeight.Medium)
            if (low) Txt("LOW", Modifier.padding(top = 2.dp), size = 10.sp, color = C.Red.copy(alpha = blink), weight = FontWeight.Bold, spacing = 1.5.sp)
        }
    }
}

// ------------------------------------------------------------------ range road bar

@Composable
fun RangeRoad(km: Double?, maxKm: Double = 650.0, modifier: Modifier = Modifier) {
    val p by animateIn(((km ?: 0.0) / maxKm).coerceIn(0.0, 1.0).toFloat(), 1600)
    val inf = rememberInfiniteTransition(label = "road")
    val flow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "flow")
    Canvas(modifier.fillMaxWidth().height(26.dp)) {
        val h = size.height
        val trackY = h / 2f
        drawRoundRect(C.Line, Offset(0f, trackY - 5.dp.toPx()), Size(size.width, 10.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        val w = size.width * p
        drawRoundRect(
            Brush.horizontalGradient(listOf(C.Red, C.RedHi, C.Amber), 0f, max(1f, size.width)),
            Offset(0f, trackY - 5.dp.toPx()), Size(w, 10.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
        )
        // moving lane dashes inside the filled part
        clipRect(0f, 0f, w, h) {
            drawLine(
                Color.White.copy(alpha = 0.55f), Offset(0f, trackY), Offset(size.width, trackY), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 10.dp.toPx()), -flow * 20.dp.toPx()),
            )
        }
        drawCircle(Color.White, 7.dp.toPx(), Offset(w, trackY))
        drawCircle(C.Red.copy(alpha = 0.35f), 12.dp.toPx(), Offset(w, trackY))
    }
}

// ------------------------------------------------------------------ charts

@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = C.Cyan, unit: String = "") {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) { progress.snapTo(0f); progress.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val inf = rememberInfiniteTransition(label = "dot")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "pulse")
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val padT = 20.dp.toPx(); val padB = 8.dp.toPx(); val padX = 10.dp.toPx()
        val w = size.width - padX * 2; val h = size.height - padT - padB
        val lo = values.min(); val hi = values.max()
        val span = max(hi - lo, 0.5)
        val minV = lo - span * 0.25; val maxV = hi + span * 0.25
        fun px(i: Int) = padX + w * i / (values.size - 1)
        fun py(v: Double) = padT + h * (1f - ((v - minV) / (maxV - minV)).toFloat())

        for (g in 0..3) {
            val y = padT + h * g / 3f
            drawLine(C.Line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val line = Path()
        line.moveTo(px(0), py(values[0]))
        for (i in 1 until values.size) {
            val x0 = px(i - 1); val y0 = py(values[i - 1]); val x1 = px(i); val y1 = py(values[i])
            val mx = (x0 + x1) / 2f
            line.cubicTo(mx, y0, mx, y1, x1, y1)
        }
        val pm = PathMeasure()
        pm.setPath(line, false)
        val len = pm.length
        val partial = Path()
        pm.getSegment(0f, len * progress.value, partial, true)

        val visibleW = padX + w * progress.value
        clipRect(0f, 0f, visibleW + 2f, size.height) {
            val area = Path().apply {
                addPath(line)
                lineTo(px(values.size - 1), size.height); lineTo(px(0), size.height); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), padT, size.height))
        }
        drawPath(partial, color.copy(alpha = 0.25f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(partial, color, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))

        for (i in values.indices) {
            if (px(i) <= visibleW) drawCircle(C.Bg, 4.dp.toPx(), Offset(px(i), py(values[i])))
            if (px(i) <= visibleW) drawCircle(color, 4.dp.toPx(), Offset(px(i), py(values[i])), style = Stroke(2.dp.toPx()))
        }
        if (progress.value > 0.98f) {
            val last = Offset(px(values.size - 1), py(values.last()))
            drawCircle(color.copy(alpha = (1f - pulse) * 0.5f), 6.dp.toPx() + 14.dp.toPx() * pulse, last)
            drawCircle(color, 5.dp.toPx(), last)
        }
    }
}

@Composable
fun BarChart(values: List<Pair<String, Double>>, modifier: Modifier = Modifier, color: Color = C.Red, highlightLast: Boolean = true) {
    val maxV = max(1.0, values.maxOfOrNull { it.second } ?: 1.0)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, (_, v) ->
                val a by animateIn((v / maxV).toFloat(), 800, i * 45)
                val isLast = highlightLast && i == values.lastIndex
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(max(0.025f, a))
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(
                                if (v <= 0.0) Brush.verticalGradient(listOf(C.Line, C.Line))
                                else Brush.verticalGradient(if (isLast) listOf(color, C.RedDeep) else listOf(C.Cyan.copy(alpha = 0.9f), C.Cyan.copy(alpha = 0.25f))),
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            values.forEach { (l, _) -> Txt(l, Modifier.weight(1f), size = 10.sp, color = C.Faint, align = TextAlign.Center) }
        }
    }
}

// ------------------------------------------------------------------ interactive controls

@Composable
fun LockSlider(locked: Boolean?, busy: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val isLocked = locked != false
    val accent = if (isLocked) C.Red else C.Cyan
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val knob = with(LocalDensity.current) { 56.dp.toPx() }
    val pad = with(LocalDensity.current) { 5.dp.toPx() }
    val maxX = max(1f, widthPx - knob - pad * 2)
    val inf = rememberInfiniteTransition(label = "shimmer")
    val shimmer by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "s")

    Box(
        modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(33.dp))
            .background(C.Surface2).border(1.dp, C.Line, RoundedCornerShape(33.dp))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(busy, isLocked) {
                if (busy) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offset.value > maxX * 0.82f) onToggle()
                            offset.animateTo(0f, spring(0.7f, Spring.StiffnessMedium))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(0f, maxX)) }
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // progress fill
        Box(
            Modifier.fillMaxHeight().width(with(LocalDensity.current) { (offset.value + knob + pad * 2).toDp() })
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.05f), accent.copy(alpha = 0.35f)))),
        )
        val txt = when {
            busy -> "Working…"
            isLocked -> "Slide to unlock"
            else -> "Slide to lock"
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Txt(txt, size = 15.sp, color = C.Text.copy(alpha = if (busy) 0.5f + 0.5f * sin(shimmer * 2 * PI).toFloat().let { it * it } else 0.7f), weight = FontWeight.Medium, spacing = 0.5.sp)
        }
        Box(
            Modifier.offset { IntOffset((pad + offset.value).roundToInt(), 0) }.size(56.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = C.Red,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.93f else 1f, spring(0.55f, Spring.StiffnessMedium), label = "press")
    Panel(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (enabled) 1f else 0.45f }
            .clickable(interactionSource = src, indication = null, enabled = enabled && !busy) { onClick() },
        radius = 22.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
                if (busy) {
                    val inf = rememberInfiniteTransition(label = "spin")
                    val a by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
                    Canvas(Modifier.fillMaxSize()) {
                        rotate(a) { drawArc(accent, 0f, 100f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                    }
                } else {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(accent.copy(alpha = 0.14f)))
                }
                Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Txt(label, Modifier.padding(top = 10.dp), size = 12.sp, color = C.Text, weight = FontWeight.Medium, align = TextAlign.Center, maxLines = 1)
        }
    }
}

/** Animated glyph for a command's life-cycle: spinner -> tick / cross. */
@Composable
fun PhaseGlyph(phase: Phase, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val inf = rememberInfiniteTransition(label = "pg")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spin")
    val draw = remember { Animatable(0f) }
    LaunchedEffect(phase) { draw.snapTo(0f); if (phase == Phase.OK || phase == Phase.FAIL || phase == Phase.CANCELLED) draw.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        when (phase) {
            Phase.SENDING, Phase.WAITING -> rotate(spin) { drawArc(C.Cyan, 0f, 110f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
            Phase.OK -> {
                drawCircle(C.Green.copy(alpha = 0.2f), w / 2f)
                val p = Path().apply { moveTo(w * 0.28f, w * 0.52f); lineTo(w * 0.44f, w * 0.68f); lineTo(w * 0.74f, w * 0.34f) }
                val pm = PathMeasure(); pm.setPath(p, false)
                val seg = Path(); pm.getSegment(0f, pm.length * draw.value, seg, true)
                drawPath(seg, C.Green, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Phase.FAIL -> {
                drawCircle(C.Red.copy(alpha = 0.2f), w / 2f)
                val t = draw.value
                drawLine(C.RedHi, Offset(w * 0.32f, w * 0.32f), Offset(w * (0.32f + 0.36f * t), w * (0.32f + 0.36f * t)), 2.6.dp.toPx(), StrokeCap.Round)
                drawLine(C.RedHi, Offset(w * 0.68f, w * 0.32f), Offset(w * (0.68f - 0.36f * t), w * (0.32f + 0.36f * t)), 2.6.dp.toPx(), StrokeCap.Round)
            }
            Phase.CANCELLED -> {
                drawCircle(C.Dim.copy(alpha = 0.2f), w / 2f)
                val t = draw.value
                drawLine(C.Dim, Offset(w * 0.3f, w * 0.5f), Offset(w * (0.3f + 0.4f * t), w * 0.5f), 2.6.dp.toPx(), StrokeCap.Round)
            }
            Phase.IDLE -> Unit
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val inf = rememberInfiniteTransition(label = "chip")
    val a by inf.animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = if (pulse) a else 1f)))
        Txt(text, Modifier.padding(start = 7.dp), size = 12.sp, color = color, weight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text, Modifier.weight(1f))
        if (trailing != null) Txt(trailing, size = 12.sp, color = C.Faint)
    }
}

// ------------------------------------------------------------------ inputs

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    hint: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Eyebrow(label, Modifier.padding(start = 4.dp, bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = C.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(C.Red),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface2)
                        .border(1.dp, if (focused) C.Red else C.Line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                ) {
                    if (value.isEmpty() && hint.isNotEmpty()) Txt(hint, size = 16.sp, color = C.Faint)
                    inner()
                }
            },
        )
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(if (on) 1f else 0f, spring(0.7f, Spring.StiffnessMedium), label = "tg")
    Box(
        modifier.width(52.dp).height(30.dp).clip(CircleShape)
            .background(lerp(C.Line, C.Red, p))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!on) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = 3.dp + 22.dp * p).size(24.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, spring(0.6f, Spring.StiffnessMedium), label = "pb")
    Box(
        modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (enabled) 1f else 0.5f }
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(C.RedDeep, C.Red, C.RedHi)))
            .clickable(interactionSource = src, indication = null, enabled = enabled && !loading) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) PhaseGlyph(Phase.WAITING) else Txt(text, size = 16.sp, weight = FontWeight.SemiBold, color = Color.White, spacing = 0.4.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Text) {
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, C.Line, RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Txt(text, size = 15.sp, color = color, weight = FontWeight.Medium) }
}

@Suppress("unused")
fun efficiencySeries(cycles: List<Cycle>): List<Double> = cycles.map { it.kmpl }
