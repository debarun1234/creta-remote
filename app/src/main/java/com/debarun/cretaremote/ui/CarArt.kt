package com.debarun.cretaremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.debarun.cretaremote.R
import com.debarun.cretaremote.bluelink.CarState
import kotlin.math.PI
import kotlin.math.sin

/*
 * The car art is the owner's own three photographs (res/drawable-nodpi, cropped to the car):
 *   creta_main.png               3/4 front view   - home hero
 *   creta_side_welcome.png       side view        - welcome / setup / sign-in
 *   creta_top_vehicle_report.png top-down view    - vehicle report with live open/closed overlays
 * Everything that moves is drawn around or over the photo; the photo itself is never altered.
 */

private val Mulberry = Color(0xFFD42A55)
private val DriveInEase = CubicBezierEasing(0.12f, 0.8f, 0.2f, 1f)

/** Where the picture sits inside its stage: [widthFrac] of the width, centred in both axes. */
private fun DrawScope.imageRect(aspect: Float, widthFrac: Float): Rect {
    val w = size.width * widthFrac
    val h = w / aspect
    return Rect((size.width - w) / 2f, (size.height - h) / 2f, (size.width + w) / 2f, (size.height + h) / 2f)
}

/** A point in picture coordinates (0..1 across and down the picture). */
private fun Rect.at(fx: Float, fy: Float) = Offset(left + fx * width, top + fy * height)

// =====================================================================================
//  Side / 3/4 photo with the live effects
// =====================================================================================

/**
 * A photo of the car with: drive-in slide, rotating floor rings and shadow, a light sheen that sweeps over the
 * paint only, headlamp / tail-lamp glow while the engine or lights are on, engine idle shake, and on every
 * lock / unlock ([flash]) a pulse ring, a paint flash and hazard blinks at the lamps.
 */
@Composable
private fun PhotoCar(
    modifier: Modifier,
    res: Int,
    aspect: Float,
    description: String,
    engineOn: Boolean,
    lightsOn: Boolean,
    locked: Boolean?,
    flash: Int,
    headAt: Offset,
    tailAt: Offset?,
) {
    val widthFrac = 0.9f
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1500, easing = DriveInEase)) }

    val inf = rememberInfiniteTransition(label = "photoCar")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val ring by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val buzz by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(80, easing = LinearEasing), RepeatMode.Restart), label = "buzz")

    val flashAnim = remember { Animatable(1f) }
    LaunchedEffect(flash) {
        if (flash > 0) {
            flashAnim.snapTo(0f)
            flashAnim.animateTo(1f, tween(1600, easing = LinearEasing))
        }
    }
    val lamp by animateFloatAsState(if (engineOn || lightsOn) 1f else 0f, tween(600), label = "lamp")

    Box(modifier.fillMaxWidth().aspectRatio(aspect / 1.3f)) {
        // ---- floor: backdrop glow, shadow, rotating rings, lock / unlock pulse
        Canvas(Modifier.fillMaxSize()) {
            val r = imageRect(aspect, widthFrac)
            val cx = size.width / 2f
            val gy = r.bottom - r.height * 0.04f
            val rx = r.width * 0.56f
            val ry = r.width * 0.05f
            val glowC = Offset(cx, r.center.y)
            drawCircle(
                Brush.radialGradient(listOf(Mulberry.copy(alpha = 0.30f), Color.Transparent), center = glowC, radius = size.width * 0.55f),
                radius = size.width * 0.55f, center = glowC,
            )
            withTransform({ scale(1f, ry / rx, Offset(cx, gy)) }) {
                val c = Offset(cx, gy)
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent), center = c, radius = rx), rx, c)
                drawCircle(Mulberry.copy(alpha = 0.40f), rx, c, style = Stroke(10f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), ring * 32f)))
                drawCircle(Mulberry.copy(alpha = 0.20f), rx * 1.15f, c, style = Stroke(6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 22f), -ring * 56f)))
                val t = flashAnim.value
                if (t < 1f) {
                    val col = if (locked == false) Color(0xFF3DD6F5) else Color(0xFFE05A78)
                    drawCircle(col.copy(alpha = (1f - t) * 0.9f), rx * (0.45f + 0.95f * t), c, style = Stroke(14f * (1f - t) + 3f))
                }
            }
        }

        // ---- the car: photo + lamp glow move together
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = (1f - enter.value) * -size.width * 1.1f
                translationY = if (engineOn) sin(buzz * 2 * PI).toFloat() * 0.6f else 0f
                alpha = (enter.value * 2.2f).coerceAtMost(1f)
            },
        ) {
            Image(
                painterResource(res), description,
                Modifier.fillMaxWidth(widthFrac).aspectRatio(aspect).align(Alignment.Center)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // sheen across the paint only (SrcAtop keeps it inside the car's opaque pixels)
                        val x0 = -size.width * 0.4f + sweep * size.width * 1.8f
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.26f), Color.Transparent),
                                Offset(x0, 0f), Offset(x0 + size.width * 0.3f, size.height * 0.5f),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                        val t = flashAnim.value
                        if (t < 1f) drawRect(Color.White.copy(alpha = 0.18f * (1f - t)), blendMode = BlendMode.SrcAtop)
                    },
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
            )
            Canvas(Modifier.fillMaxSize()) {
                val r = imageRect(aspect, widthFrac)
                val blink = flashAnim.value < 0.85f && ((flashAnim.value * 5f).toInt() % 2 == 0)
                val a = maxOf(lamp, if (blink) 1f else 0f)
                if (a > 0.01f) {
                    val head = if (blink) Color(0xFFFFB020) else Color(0xFFEAF4FF)
                    val hc = r.at(headAt.x, headAt.y)
                    val hr = r.width * 0.10f
                    drawCircle(Brush.radialGradient(listOf(head.copy(alpha = 0.8f * a), Color.Transparent), center = hc, radius = hr), hr, hc)
                    if (tailAt != null) {
                        val tc = r.at(tailAt.x, tailAt.y)
                        val tr = r.width * 0.07f
                        val tail = if (blink) Color(0xFFFFB020) else Color(0xFFFF2A3A)
                        drawCircle(Brush.radialGradient(listOf(tail.copy(alpha = 0.75f * a), Color.Transparent), center = tc, radius = tr), tr, tc)
                    }
                }
            }
        }
    }
}

/** Welcome, setup and sign-in car (side photo). */
@Composable
fun SideCar(modifier: Modifier = Modifier, lightsOn: Boolean = false) {
    PhotoCar(
        modifier, R.drawable.creta_side_welcome, 621f / 264f, "Hyundai Creta, side view",
        engineOn = false, lightsOn = lightsOn, locked = true, flash = 0,
        headAt = Offset(0.958f, 0.52f), tailAt = Offset(0.03f, 0.39f),
    )
}

/** Home hero (3/4 front photo), reacting to the live car state. */
@Composable
fun CarHero(car: CarState?, flash: Int, modifier: Modifier = Modifier) {
    PhotoCar(
        modifier, R.drawable.creta_main, 298f / 158f, "Your Hyundai Creta",
        engineOn = car?.engineOn == true,
        lightsOn = car?.headlampsOn == true,
        locked = car?.locked,
        flash = flash,
        headAt = Offset(0.655f, 0.51f), tailAt = null,
    )
}

// =====================================================================================
//  Top-down photo: vehicle report with live overlays
// =====================================================================================

private const val TOP_ASPECT = 225f / 426f
private const val TOP_WIDTH = 0.76f

/**
 * The top-down photo with live status drawn on it, all in picture coordinates:
 *  - a door that is open swings out as an amber panel (the closed position stays outlined);
 *  - an open window lights its glass cyan;
 *  - an open hood or rear gate lifts out past the body in amber;
 *  - a tyre with low pressure pulses amber.
 * A slow scan band runs over the paint, and the lamps glow while the engine or headlamps are on.
 */
@Composable
fun TopCar(modifier: Modifier = Modifier, car: CarState?, engineOn: Boolean, lightsOn: Boolean) {
    val inf = rememberInfiniteTransition(label = "top")
    val pulse by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Restart), label = "scan")

    val d = car?.doorsOpen
    val w = car?.windowsOpen
    val sp = spring<Float>(0.55f, Spring.StiffnessLow)
    val fl by animateFloatAsState(if (d?.fl == true) 1f else 0f, sp, label = "fl")
    val fr by animateFloatAsState(if (d?.fr == true) 1f else 0f, sp, label = "fr")
    val rl by animateFloatAsState(if (d?.rl == true) 1f else 0f, sp, label = "rl")
    val rr by animateFloatAsState(if (d?.rr == true) 1f else 0f, sp, label = "rr")
    val hood by animateFloatAsState(if (car?.hoodOpen == true) 1f else 0f, sp, label = "hood")
    val gate by animateFloatAsState(if (car?.trunkOpen == true) 1f else 0f, sp, label = "gate")
    val lamp by animateFloatAsState(if (engineOn || lightsOn) 1f else 0f, tween(500), label = "lamp")

    val tw = car?.tyreWarn
    val warnAll = car?.tyreWarnAny == true
    val tyreFL = tw?.fl == true || warnAll
    val tyreFR = tw?.fr == true || warnAll
    val tyreRL = tw?.rl == true || warnAll
    val tyreRR = tw?.rr == true || warnAll

    Box(modifier.fillMaxWidth().aspectRatio(TOP_ASPECT / (TOP_WIDTH * 1.12f))) {
        Image(
            painterResource(R.drawable.creta_top_vehicle_report), "Your Creta from above",
            Modifier.fillMaxWidth(TOP_WIDTH).aspectRatio(TOP_ASPECT).align(Alignment.Center)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val y0 = -size.height * 0.25f + scan * size.height * 1.5f
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                            Offset(0f, y0), Offset(0f, y0 + size.height * 0.25f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = imageRect(TOP_ASPECT, TOP_WIDTH)
            val amber = Color(0xFFFFB020)
            val cyan = Color(0xFF3DD6F5)
            val sw = r.width * 0.012f + 1.5f

            fun poly(vararg p: Pair<Float, Float>): Path = Path().apply {
                p.forEachIndexed { i, (x, y) -> val o = r.at(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                close()
            }

            // ---- tyres (peek out beside the body; amber + glow when pressure is low)
            fun tyre(cx: Float, cy: Float, warn: Boolean) {
                val tl = r.at(cx - 0.04f, cy - 0.065f)
                val sz = Size(r.width * 0.08f, r.height * 0.13f)
                val cr = CornerRadius(sz.width * 0.4f)
                drawRoundRect(Color(0xFF14161C), tl, sz, cr)
                if (warn) {
                    drawRoundRect(amber.copy(alpha = 0.28f * pulse), tl - Offset(5f, 5f), Size(sz.width + 10f, sz.height + 10f), CornerRadius(sz.width * 0.5f))
                    drawRoundRect(amber.copy(alpha = pulse), tl, sz, cr, style = Stroke(sw * 1.6f))
                } else {
                    drawRoundRect(Color(0xFF3A3F4B), tl, sz, cr, style = Stroke(1.5f))
                }
            }
            tyre(0.055f, 0.21f, tyreFL)
            tyre(0.945f, 0.21f, tyreFR)
            tyre(0.055f, 0.79f, tyreRL)
            tyre(0.945f, 0.79f, tyreRR)

            // ---- windows (cyan on the side glass)
            fun window(left: Boolean, y0: Float, y1: Float, open: Boolean) {
                if (!open) return
                val x0 = if (left) 0.125f else 0.795f
                val tl = r.at(x0, y0)
                drawRoundRect(cyan.copy(alpha = 0.45f + 0.35f * pulse), tl, Size(r.width * 0.08f, r.height * (y1 - y0)), CornerRadius(6f))
            }
            window(true, 0.37f, 0.535f, w?.fl == true)
            window(true, 0.555f, 0.73f, w?.rl == true)
            window(false, 0.37f, 0.535f, w?.fr == true)
            window(false, 0.555f, 0.73f, w?.rr == true)

            // ---- doors: closed outline always faint when open, amber panel swinging out about the front hinge
            fun door(left: Boolean, y0: Float, y1: Float, open: Float) {
                if (open < 0.01f) return
                val xEdge = if (left) 0.09f else 0.91f
                val hinge = r.at(xEdge, y0)
                val len = r.height * (y1 - y0)
                val closedW = r.width * 0.13f
                val closedTl = if (left) hinge else Offset(hinge.x - closedW, hinge.y)
                drawRoundRect(amber.copy(alpha = 0.5f * open), closedTl, Size(closedW, len), CornerRadius(8f), style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                drawRoundRect(amber.copy(alpha = (0.10f + 0.12f * pulse) * open), closedTl, Size(closedW, len), CornerRadius(8f))
                val panelW = r.width * 0.045f
                rotate(if (left) 30f * open else -30f * open, hinge) {
                    val tl = if (left) hinge - Offset(panelW, 0f) else hinge
                    drawRoundRect(amber.copy(alpha = (0.45f + 0.4f * pulse) * open), tl, Size(panelW, len), CornerRadius(6f))
                    drawRoundRect(amber.copy(alpha = open), tl, Size(panelW, len), CornerRadius(6f), style = Stroke(sw))
                }
            }
            door(true, 0.36f, 0.545f, fl)
            door(true, 0.545f, 0.75f, rl)
            door(false, 0.36f, 0.545f, fr)
            door(false, 0.545f, 0.75f, rr)

            // ---- hood: lifts past the bumper
            if (hood > 0.01f) {
                drawPath(poly(0.28f to 0.03f, 0.72f to 0.03f, 0.86f to 0.20f, 0.14f to 0.20f), amber.copy(alpha = (0.14f + 0.16f * pulse) * hood))
                val lift = poly(0.28f to 0.03f, 0.72f to 0.03f, 0.68f to (0.03f - 0.055f * hood), 0.32f to (0.03f - 0.055f * hood))
                drawPath(lift, amber.copy(alpha = (0.45f + 0.35f * pulse) * hood))
                drawPath(lift, amber.copy(alpha = hood), style = Stroke(sw))
            }

            // ---- rear gate: lifts past the rear bumper
            if (gate > 0.01f) {
                drawPath(poly(0.22f to 0.815f, 0.78f to 0.815f, 0.74f to 0.965f, 0.26f to 0.965f), amber.copy(alpha = (0.18f + 0.22f * pulse) * gate))
                val lift = poly(0.25f to 0.97f, 0.75f to 0.97f, 0.70f to (0.97f + 0.055f * gate), 0.30f to (0.97f + 0.055f * gate))
                drawPath(lift, amber.copy(alpha = (0.45f + 0.35f * pulse) * gate))
                drawPath(lift, amber.copy(alpha = gate), style = Stroke(sw))
            }

            // ---- lamps
            if (lamp > 0.01f) {
                for (fx in listOf(0.22f, 0.78f)) {
                    val c = r.at(fx, 0.03f)
                    val rad = r.width * 0.12f
                    drawCircle(Brush.radialGradient(listOf(Color(0xCCEAF4FF).copy(alpha = 0.7f * lamp), Color.Transparent), center = c, radius = rad), rad, c)
                    val t = r.at(fx, 0.975f)
                    val tr = r.width * 0.08f
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF2A3A).copy(alpha = 0.7f * lamp), Color.Transparent), center = t, radius = tr), tr, t)
                }
            }
        }
    }
}
