package com.debarun.cretaremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

// ------------------------------------------------------------------ welcome (every launch)

/**
 * Shown on every cold start (the caller keeps a rememberSaveable flag, so rotation does not replay it).
 * It never advances on its own: once the first local load finishes it waits for a tap.
 */
@Composable
fun WelcomeSplash(ready: Boolean, signedIn: Boolean, onDone: () -> Unit) {

    val ctx = LocalContext.current
    // drop the official logo in app/src/main/res/drawable-nodpi/hyundai_logo.png (transparent, light-coloured) and it shows here
    val logoRes = remember { ctx.resources.getIdentifier("hyundai_logo", "drawable", ctx.packageName) }
    val rise = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(1300, delayMillis = 450, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) { bar.animateTo(1f, tween(BAR_MS, easing = LinearEasing)) }

    val inf = rememberInfiniteTransition(label = "wel")
    val glow by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val hint by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hint")

    Box(
        Modifier.fillMaxSize().background(C.Bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = ready) { onDone() },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0x55E3192D).copy(alpha = 0.33f * glow), Color.Transparent),
                    Offset(size.width * 0.5f, size.height * 0.38f), size.width * 1.1f,
                ),
            )
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SideCar(Modifier.fillMaxWidth(), lightsOn = true)
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 24.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (logoRes != 0) {
                    Image(painterResource(logoRes), contentDescription = "Hyundai", modifier = Modifier.height(34.dp).padding(bottom = 14.dp), contentScale = ContentScale.Fit)
                }
                Eyebrow(greeting(), color = C.RedHi)
                Txt("Creta Remote", Modifier.padding(top = 8.dp), size = 40.sp, weight = FontWeight.Light, spacing = (-1).sp)
                Txt(
                    when {
                        !ready -> "Waking up your Creta…"
                        signedIn -> "Welcome back"
                        else -> "Let's get you connected"
                    },
                    Modifier.padding(top = 8.dp), size = 14.sp, color = C.Dim,
                )
            }
            Spacer(Modifier.height(36.dp))
            Box(Modifier.width(120.dp).height(3.dp).clip(CircleShape).background(C.Line)) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = bar.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }.background(Brush.horizontalGradient(listOf(C.RedDeep, C.RedHi))))
            }
            Txt(
                "Tap to continue",
                Modifier.padding(top = 18.dp).graphicsLayer { alpha = if (ready) hint else 0f },
                size = 13.sp, color = C.Dim, weight = FontWeight.Medium, spacing = 0.6.sp,
            )
        }
    }
}

private const val BAR_MS = 2600

// ------------------------------------------------------------------ first-run setup

private const val STEPS = 3

/** First install only: what the app is, what it can't do, then local preferences. Ends at the sign-in screen. */
@Composable
fun SetupFlow(d: AppData, vm: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(0) }
    var tank by rememberSaveable { mutableStateOf("50") }
    var price by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf(true) }

    BackHandler(step > 0) { step-- }

    fun finish() {
        val t = tank.toDoubleOrNull()?.takeIf { it in 20.0..120.0 } ?: 50.0
        val p = price.toDoubleOrNull()?.takeIf { it in 0.0..500.0 } ?: 0.0
        vm.saveSettings(d.settings.copy(tankLitres = t, fuelPricePerL = p, confirmBiometric = bio, onboarded = true))
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEPS) { i ->
                val on by animateFloatAsState(if (i <= step) 1f else 0f, tween(380), label = "dot")
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(C.Line)) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        scaleX = on
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }.background(C.Red))
                }
            }
        }

        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(160)) },
            label = "setup",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (s) {
                    0 -> {
                        SideCar(Modifier.fillMaxWidth())
                        Column {
                            Eyebrow("Welcome", color = C.RedHi)
                            Txt("Your Creta,\nwithout the wait.", Modifier.padding(top = 8.dp), size = 34.sp, weight = FontWeight.Light, spacing = (-0.8).sp, lineHeight = 40.sp)
                            Txt(
                                "A faster, cleaner way to lock, unlock, start and watch your car through Hyundai Bluelink.",
                                Modifier.padding(top = 10.dp), size = 14.sp, color = C.Dim,
                            )
                        }
                        Point(Icons.Rounded.Lock, "Instant controls", "Lock, unlock, climate start, horn and lights from one screen.")
                        Point(Icons.Rounded.LocalGasStation, "Fuel you can plan around", "Range, km/L, cost, and a fuel alert you set per trip with a phone alarm.")
                        Point(Icons.Rounded.Place, "Where it's been", "Every trip and parking spot, kept on this phone.")
                    }
                    1 -> {
                        Column {
                            Eyebrow("How it works", color = C.RedHi)
                            Txt("Straight talk.", Modifier.padding(top = 8.dp), size = 34.sp, weight = FontWeight.Light, spacing = (-0.8).sp)
                        }
                        Point(Icons.Rounded.Cloud, "Talks to Bluelink's cloud", "Commands go phone → Hyundai → your car's modem, so both need network. It uses Hyundai's unofficial API and can break if they change it.")
                        Point(Icons.Rounded.Lock, "Not a digital key", "It can't open the car without signal and doesn't replace the physical key.")
                        Point(Icons.Rounded.Place, "Parked location only", "Bluelink reports where the car is parked, not live GPS, so Places shows parking spots.")
                        Point(Icons.Rounded.Speed, "Reads don't wake the car", "Background snapshots use Bluelink's cached data. Only your own commands reach the car.")
                    }
                    else -> {
                        Column {
                            Eyebrow("Set up", color = C.RedHi)
                            Txt("Make it yours.", Modifier.padding(top = 8.dp), size = 34.sp, weight = FontWeight.Light, spacing = (-0.8).sp)
                            Txt("Stored on this phone. Change any of it later in Settings.", Modifier.padding(top = 8.dp), size = 14.sp, color = C.Dim)
                        }
                        Panel(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Fingerprint, null, tint = C.RedHi, modifier = Modifier.size(28.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Txt("Confirm with fingerprint or PIN", size = 15.sp, weight = FontWeight.SemiBold)
                                    Txt("Before unlock, engine start and stop.", size = 12.sp, color = C.Dim)
                                }
                                Toggle(bio, { bio = it })
                            }
                        }
                        Field("Fuel tank (litres)", tank, { tank = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, keyboard = KeyboardType.Decimal, hint = "50")
                        Field("Petrol price per litre (optional)", price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, keyboard = KeyboardType.Decimal, hint = "for running-cost insights")
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                when (step) { 0 -> "Get started"; 1 -> "Continue"; else -> "Continue to sign in" },
                { if (step < STEPS - 1) step++ else finish() },
            )
            if (step > 0) GhostButton("Back", { step-- })
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(C.Red.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = C.RedHi, modifier = Modifier.size(22.dp)) }
        Column(Modifier.padding(start = 14.dp)) {
            Txt(title, size = 15.sp, weight = FontWeight.SemiBold)
            Txt(body, Modifier.padding(top = 2.dp), size = 13.sp, color = C.Dim, lineHeight = 18.sp)
        }
    }
}
