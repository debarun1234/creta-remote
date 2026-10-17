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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.debarun.cretaremote.bluelink.CarState
import kotlin.math.PI
import kotlin.math.sin

private fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

private object SideP {
    val body = parsePath(CarPaths.Side.BODY)
    val glass = parsePath(CarPaths.Side.GLASS)
    val pillarB = parsePath(CarPaths.Side.PILLARB)
    val pillarC = parsePath(CarPaths.Side.PILLARC)
    val doorLines = parsePath(CarPaths.Side.DOORLINES)
    val belt = parsePath(CarPaths.Side.BELT)
    val crease = parsePath(CarPaths.Side.CREASE)
    val rails = parsePath(CarPaths.Side.RAILS)
    val railPosts = parsePath(CarPaths.Side.RAILPOSTS)
    val handleR = parsePath(CarPaths.Side.HANDLER)
    val handleF = parsePath(CarPaths.Side.HANDLEF)
    val mirror = parsePath(CarPaths.Side.MIRROR)
    val drl = parsePath(CarPaths.Side.DRL)
    val lamp = parsePath(CarPaths.Side.LAMP)
    val tail = parsePath(CarPaths.Side.TAIL)
    val fog = parsePath(CarPaths.Side.FOG)
    val grille = parsePath(CarPaths.Side.GRILLE)
    val cladding = parsePath(CarPaths.Side.CLADDING)
    val skidF = parsePath(CarPaths.Side.SKIDF)
    val skidR = parsePath(CarPaths.Side.SKIDR)
    val spoiler = parsePath(CarPaths.Side.SPOILER)
    val archF = parsePath(CarPaths.Side.ARCHF)
    val archR = parsePath(CarPaths.Side.ARCHR)
    val spoke = parsePath(CarPaths.Side.SPOKES)
}

private object TopP {
    val body = parsePath(CarPaths.Top.BODY)
    val hood = parsePath(CarPaths.Top.HOOD)
    val windshield = parsePath(CarPaths.Top.WINDSHIELD)
    val roof = parsePath(CarPaths.Top.ROOF)
    val pano = parsePath(CarPaths.Top.PANO)
    val rearGlass = parsePath(CarPaths.Top.REARGLASS)
    val trunk = parsePath(CarPaths.Top.TRUNK)
    val door = parsePath(CarPaths.Top.DOORFL)
    val mirrorL = parsePath(CarPaths.Top.MIRRORL)
    val mirrorR = parsePath(CarPaths.Top.MIRRORR)
    val head = parsePath(CarPaths.Top.HEAD)
    val tail = parsePath(CarPaths.Top.TAIL)
    val tyre = parsePath(CarPaths.Top.TYRE)
}

/** Mulberry red metallic. */
private val Paint = listOf(Color(0xFFE05A78), Color(0xFFB02A4A), Color(0xFF761432), Color(0xFF3D0719))
private val Glow = Color(0xFFD42A55)
private val GlassBrush = Brush.linearGradient(listOf(Color(0xFF2C3A4A), Color(0xFF0D141C), Color(0xFF1C2733)), Offset(120f, 50f), Offset(520f, 120f))
private val RimBrush = Brush.linearGradient(listOf(Color(0xFFE6E9EE), Color(0xFF6B7380)), Offset(-35f, -35f), Offset(35f, 35f))
private val DriveInEase = CubicBezierEasing(0.12f, 0.8f, 0.2f, 1f)

// =====================================================================================
//  SIDE VIEW  (design space 800 x 320)
// =====================================================================================

private fun DrawScope.wheel(cx: Float, cy: Float, rot: Float) {
    withTransform({ translate(cx, cy) }) {
        drawCircle(Color(0xFF0C0C0F), 50f, Offset.Zero)
        drawCircle(Color(0xFF1D1D22), 47f, Offset.Zero, style = Stroke(2f))
        drawCircle(Color(0xFF15151A), 37f, Offset.Zero)
        rotate(rot, Offset.Zero) {
            for (i in 0 until 10) rotate(i * 36f, Offset.Zero) { drawPath(SideP.spoke, RimBrush) }
        }
        drawCircle(RimBrush, 34f, Offset.Zero, style = Stroke(3f))
        drawCircle(Color(0xFF2A2A31), 9f, Offset.Zero)
        drawCircle(Color(0xFFB02A4A), 5f, Offset.Zero)
    }
}

/**
 * The hero: a red Creta in side profile. Drives in on first composition, light sweeps across the paint,
 * headlights bloom when the engine runs, and a pulse ring + hazard blinks play on every lock/unlock ([flash]).
 */
@Composable
fun SideCar(
    modifier: Modifier = Modifier,
    engineOn: Boolean,
    lightsOn: Boolean,
    doorOpen: Boolean,
    locked: Boolean?,
    flash: Int,
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1700, easing = DriveInEase)) }

    val inf = rememberInfiniteTransition(label = "side")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val ring by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val buzz by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(80, easing = LinearEasing), RepeatMode.Restart), label = "buzz")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    val flashAnim = remember { Animatable(1f) }
    LaunchedEffect(flash) {
        if (flash > 0) {
            flashAnim.snapTo(0f)
            flashAnim.animateTo(1f, tween(1600, easing = LinearEasing))
        }
    }
    val beam by animateFloatAsState(if (engineOn || lightsOn) 1f else 0f, tween(600), label = "beam")

    Canvas(modifier.fillMaxWidth().aspectRatio(800f / 340f)) {
        val s = size.width / 800f
        val h = size.height

        // backdrop glow
        drawCircle(
            Brush.radialGradient(listOf(Color(0x59D42A55), Color(0x00D42A55)), center = Offset(size.width * 0.5f, h * 0.6f), radius = size.width * 0.55f),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.5f, h * 0.6f),
        )

        withTransform({ translate(0f, 10f * s); scale(s, s, Offset.Zero) }) {
            // floor rings (rotating dashes) + shadow
            val ringDash = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), ring * 32f)
            withTransform({ scale(1f, 0.085f, Offset(400f, 268f)) }) {
                drawCircle(Brush.radialGradient(listOf(Color(0xE6000000), Color(0x00000000)), Offset(390f, 268f), 345f), 345f, Offset(390f, 268f))
                drawCircle(Color(0x66D42A55), 360f, Offset(400f, 268f), style = Stroke(9f, pathEffect = ringDash))
                drawCircle(Color(0x33D42A55), 395f, Offset(400f, 268f), style = Stroke(6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 22f), -ring * 56f)))
            }
            // lock / unlock pulse ring
            if (flashAnim.value < 1f) {
                val t = flashAnim.value
                val c = if (locked == false) Color(0xFF3DD6F5) else Color(0xFFE05A78)
                withTransform({ scale(1f, 0.085f, Offset(400f, 268f)) }) {
                    drawCircle(c.copy(alpha = (1f - t) * 0.9f), 120f + 300f * t, Offset(400f, 268f), style = Stroke(12f * (1f - t) + 2f))
                }
            }

            // car body group: drive-in + engine idle buzz
            val dx = (1f - enter.value) * -820f
            val buzzY = if (engineOn) sin(buzz * 2 * PI).toFloat() * 0.55f else 0f
            val bob = -breathe * 0.8f
            withTransform({ translate(dx, buzzY + bob) }) {
                // headlight beam
                val blink = flashAnim.value < 0.85f && ((flashAnim.value * 5f).toInt() % 2 == 0)
                val beamA = maxOf(beam, if (blink) 1f else 0f)
                if (beamA > 0.01f) {
                    val beamPath = Path().apply {
                        moveTo(706f, 142f); lineTo(1500f, 40f); lineTo(1500f, 330f); lineTo(706f, 168f); close()
                    }
                    drawPath(beamPath, Brush.horizontalGradient(listOf(Color(0xB3EAF4FF), Color(0x00EAF4FF)), 706f, 1250f), alpha = 0.55f * beamA)
                }

                // paint
                drawPath(SideP.body, Brush.verticalGradient(Paint, 20f, 243f))
                clipPath(SideP.body) {
                    drawPath(SideP.cladding, Color(0xFF16161B), alpha = 0.92f)
                    drawPath(SideP.skidF, Color(0xFFAEB4BE), alpha = 0.85f)
                    drawPath(SideP.skidR, Color(0xFFAEB4BE), alpha = 0.85f)
                    drawPath(SideP.crease, Color.Black, alpha = 0.25f, style = Stroke(3f))
                    drawPath(SideP.belt, Color.White, alpha = 0.28f, style = Stroke(2f))
                    // light sweep
                    val x0 = -260f + sweep * 1200f
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color(0x00FFFFFF), Color(0x40FFFFFF), Color(0x00FFFFFF)),
                            Offset(x0, 40f), Offset(x0 + 150f, 120f),
                        ),
                        topLeft = Offset(0f, 0f), size = Size(800f, 280f),
                    )
                }
                drawPath(SideP.spoiler, Color(0xFF0C0C10))
                drawPath(SideP.rails, Color(0xFFAEB4BE), style = Stroke(3f, cap = StrokeCap.Round))
                drawPath(SideP.railPosts, Color(0xFFAEB4BE), style = Stroke(3f))
                drawPath(SideP.glass, GlassBrush)
                clipPath(SideP.glass) {
                    val gx = 100f + ((sweep + 0.25f) % 1f) * 520f
                    drawRect(Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x22FFFFFF), Color(0x00FFFFFF)), Offset(gx, 40f), Offset(gx + 60f, 120f)), Offset(0f, 40f), Size(800f, 90f))
                }
                drawPath(SideP.pillarB, Color(0xFF0A0D12))
                drawPath(SideP.pillarC, Color(0xFF0A0D12))
                drawPath(SideP.doorLines, if (doorOpen) Color(0xFFFFB020) else Color.Black, alpha = if (doorOpen) 0.95f else 0.55f, style = Stroke(if (doorOpen) 2.6f else 1.6f))
                drawPath(SideP.handleR, Color(0xFF0C0C0F), alpha = 0.7f)
                drawPath(SideP.handleF, Color(0xFF0C0C0F), alpha = 0.7f)
                drawPath(SideP.mirror, Color(0xFF8F1B35))
                drawPath(SideP.grille, Color(0xFF101014))
                drawPath(SideP.lamp, Color(0xFFF4F8FF), alpha = 0.55f + 0.45f * beamA)
                drawPath(SideP.drl, Color(0xFFE8F4FF), alpha = 0.65f + 0.35f * beamA, style = Stroke(5f, cap = StrokeCap.Round))
                drawPath(SideP.tail, Color(0xFFFF2A3A), alpha = if (engineOn || blink) 1f else 0.72f)
                drawPath(SideP.fog, Color(0xFFCFD8E6))
                drawPath(SideP.archF, Color(0xFF16161B), style = Stroke(14f))
                drawPath(SideP.archR, Color(0xFF16161B), style = Stroke(14f))
                drawPath(SideP.archF, Color(0xFF08080A), style = Stroke(5f))
                drawPath(SideP.archR, Color(0xFF08080A), style = Stroke(5f))
                // hazard blink glow
                if (blink) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFB020), Color(0x00FFB020)), Offset(706f, 150f), 60f), 60f, Offset(706f, 150f))
                    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFB020), Color(0x00FFB020)), Offset(82f, 116f), 60f), 60f, Offset(82f, 116f))
                }
                // wheels roll while driving in
                val rot = enter.value * 825f - 825f
                wheel(585f, 212f, rot)
                wheel(207f, 212f, rot)
            }
        }
    }
}

// =====================================================================================
//  TOP VIEW  (design space 320 x 700) - live door / hood / tyre / window status
// =====================================================================================

private data class DoorSpec(val x: Float, val y: Float, val flip: Boolean, val open: Boolean, val window: Boolean)

@Composable
fun TopCar(modifier: Modifier = Modifier, car: CarState?, engineOn: Boolean, lightsOn: Boolean) {
    val inf = rememberInfiniteTransition(label = "top")
    val pulse by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    val d = car?.doorsOpen
    val w = car?.windowsOpen
    val flA by animateFloatAsState(if (d?.fl == true) 1f else 0f, spring(0.55f, Spring.StiffnessLow), label = "fl")
    val frA by animateFloatAsState(if (d?.fr == true) 1f else 0f, spring(0.55f, Spring.StiffnessLow), label = "fr")
    val rlA by animateFloatAsState(if (d?.rl == true) 1f else 0f, spring(0.55f, Spring.StiffnessLow), label = "rl")
    val rrA by animateFloatAsState(if (d?.rr == true) 1f else 0f, spring(0.55f, Spring.StiffnessLow), label = "rr")
    val hoodA by animateFloatAsState(if (car?.hoodOpen == true) 1f else 0f, spring(0.6f, Spring.StiffnessLow), label = "hood")
    val trunkA by animateFloatAsState(if (car?.trunkOpen == true) 1f else 0f, spring(0.6f, Spring.StiffnessLow), label = "trunk")
    val lamp by animateFloatAsState(if (engineOn || lightsOn) 1f else 0f, tween(500), label = "lamp")

    Canvas(modifier.fillMaxWidth().aspectRatio(320f / 700f)) {
        val s = size.width / 320f
        withTransform({ scale(s, s, Offset.Zero) }) {
            // tyres
            val tw = car?.tyreWarn
            val warnAll = car?.tyreWarnAny == true
            fun tyre(x: Float, y: Float, flip: Boolean, warn: Boolean) {
                withTransform({ translate(x, y); if (flip) scale(-1f, 1f, Offset.Zero) }) {
                    drawPath(TopP.tyre, Color(0xFF1B1D24))
                    drawPath(TopP.tyre, if (warn) Color(0xFFFFB020).copy(alpha = pulse) else Color(0xFF2E323D), style = Stroke(if (warn) 3f else 1.5f))
                }
            }
            tyre(36f, 120f, false, tw?.fl == true || warnAll)
            tyre(36f, 468f, false, tw?.rl == true || warnAll)
            tyre(284f, 120f, true, tw?.fr == true || warnAll)
            tyre(284f, 468f, true, tw?.rr == true || warnAll)

            // body
            drawPath(TopP.body, Brush.horizontalGradient(listOf(Color(0xFF5E0F25), Color(0xFFC23A58), Color(0xFFA3213F), Color(0xFF8A1A35), Color(0xFF3F0818)), 58f, 262f))

            // hood (lifts with a subtle shadow + amber outline when open)
            withTransform({ scale(1f, 1f + 0.03f * hoodA, Offset(160f, 196f)) }) {
                drawPath(TopP.hood, Color.Black, alpha = 0.12f + 0.2f * hoodA)
                drawPath(TopP.hood, if (hoodA > 0.5f) Color(0xFFFFB020) else Color.White, alpha = if (hoodA > 0.5f) 0.95f else 0.18f, style = Stroke(if (hoodA > 0.5f) 3f else 1.5f))
            }
            drawPath(TopP.windshield, GlassBrush)
            drawPath(TopP.rearGlass, GlassBrush)
            drawPath(TopP.roof, Color(0xFF8A1A35))
            drawPath(TopP.pano, GlassBrush)
            withTransform({ scale(1f, 1f + 0.03f * trunkA, Offset(160f, 490f)) }) {
                drawPath(TopP.trunk, if (trunkA > 0.5f) Color(0xFFFFB020) else Color.Black, alpha = if (trunkA > 0.5f) 0.95f else 0.35f, style = Stroke(if (trunkA > 0.5f) 3f else 1.5f))
            }
            drawPath(TopP.mirrorL, Color(0xFF7E1730))
            drawPath(TopP.mirrorR, Color(0xFF7E1730))

            // doors swing open about their front hinge
            val doors = listOf(
                Triple(Offset(58f, 226f), false, flA to (w?.fl == true)),
                Triple(Offset(58f, 350f), false, rlA to (w?.rl == true)),
                Triple(Offset(262f, 226f), true, frA to (w?.fr == true)),
                Triple(Offset(262f, 350f), true, rrA to (w?.rr == true)),
            )
            for ((hinge, right, st) in doors) {
                val (open, winOpen) = st
                withTransform({ translate(hinge.x, hinge.y) }) {
                    val sign = if (right) -1f else 1f
                    rotate(sign * open * 28f, Offset.Zero) {
                        withTransform({ scale(if (right) 1f else -1f, 1f, Offset.Zero) }) {
                            // body-side door panel sits inside the body edge: x from 0 to -18 (mirrored for the left)
                            withTransform({ translate(-18f, 0f) }) {
                                drawPath(TopP.door, if (open > 0.5f) Color(0xFFFFB020) else Color(0xFF5B1026), alpha = if (open > 0.5f) 0.9f else 0.55f)
                                if (winOpen) drawRect(Color(0xFF3DD6F5), Offset(4f, 8f), Size(10f, 96f), alpha = 0.9f)
                            }
                        }
                    }
                }
            }

            // lamps
            val glow = 0.35f + 0.65f * lamp
            drawPath(TopP.head, Color(0xFFEAF3FF), alpha = glow)
            withTransform({ translate(320f, 0f); scale(-1f, 1f, Offset.Zero) }) { drawPath(TopP.head, Color(0xFFEAF3FF), alpha = glow) }
            drawPath(TopP.tail, Color(0xFFFF2A3A), alpha = 0.5f + 0.5f * lamp)
            withTransform({ translate(320f, 0f); scale(-1f, 1f, Offset.Zero) }) { drawPath(TopP.tail, Color(0xFFFF2A3A), alpha = 0.5f + 0.5f * lamp) }
            if (lamp > 0.01f) {
                // headlight bloom
                drawCircle(Brush.radialGradient(listOf(Color(0x66EAF4FF), Color(0x00EAF4FF)), Offset(90f, 70f), 80f), 80f, Offset(90f, 70f), alpha = lamp)
                drawCircle(Brush.radialGradient(listOf(Color(0x66EAF4FF), Color(0x00EAF4FF)), Offset(230f, 70f), 80f), 80f, Offset(230f, 70f), alpha = lamp)
            }
        }
    }
}

// =====================================================================================
//  HERO wrapper
// =====================================================================================

@Composable
fun CarHero(car: CarState?, flash: Int, modifier: Modifier = Modifier) {
    val anyOpen = car?.doorsOpen?.any == true || car?.trunkOpen == true || car?.hoodOpen == true
    SideCar(
        modifier,
        engineOn = car?.engineOn == true,
        lightsOn = car?.headlampsOn == true,
        doorOpen = anyOpen,
        locked = car?.locked,
        flash = flash,
    )
}
