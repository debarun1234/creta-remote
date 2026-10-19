package com.debarun.cretaremote.ui

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    // looked up by name on purpose: the logo file is optional, so a direct R reference would break the build without it
    @SuppressLint("DiscouragedApi")
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
                        !ready -> "Getting your Creta ready…"
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
    var step by rememberSaveable { mutableIntStateOf(0) }
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
                            Txt("Your Creta,\nin your pocket.", Modifier.padding(top = 8.dp), size = 34.sp, weight = FontWeight.Light, spacing = (-0.8).sp, lineHeight = 40.sp)
                            Txt(
                                "Control and keep an eye on your Creta through Hyundai Bluelink, using your own account and no middle server.",
                                Modifier.padding(top = 10.dp), size = 14.sp, color = C.Dim,
                            )
                        }
                        Point(Icons.Rounded.Lock, "Remote control", "Lock, unlock, climate and engine start, lights, horn and boot. From the app, Quick Settings tiles or a home-screen widget.")
                        Point(Icons.Rounded.Speed, "Live car status", "Fuel, range, tyres, 12V, doors and windows on a top-down car. Resizable widgets redraw to fit, with a refresh button.")
                        Point(Icons.Rounded.LocalGasStation, "Fuel log and alarm", "Log your fill-ups for real km/L and cost per km, and get a phone alarm before you run low.")
                        Point(Icons.Rounded.Place, "Trips and parking", "Distance, drive time and every parking spot, on a map. Kept on this phone.")
                    }
                    1 -> {
                        Column {
                            Eyebrow("Before you start", color = C.RedHi)
                            Txt("Straight talk.", Modifier.padding(top = 8.dp), size = 34.sp, weight = FontWeight.Light, spacing = (-0.8).sp)
                        }
                        Point(Icons.Rounded.Cloud, "Cloud remote, not a key", "Commands go phone → Hyundai → the car's modem, so the car needs mobile coverage too. It does not replace the physical key.")
                        Point(Icons.Rounded.Speed, "The car's modem sleeps", "Refresh asks the car for fresh data and can take up to a minute. Waking it is not guaranteed, so the app tells you if the car is asleep.")
                        Point(Icons.Rounded.Place, "Parked location only", "Bluelink reports where the car is parked, not live GPS. Places shows parking spots, not a driving track.")
                        Point(Icons.Rounded.Cloud, "Unofficial and independent", "It uses Bluelink's undocumented API. Hyundai can change it at any time and some features may stop until the app is updated.")
                        Point(Icons.Rounded.Shield, "Your data stays yours", "Password and PIN are encrypted on this phone and sent only to Hyundai. No ads, no analytics, no account with us.")
                    }
                    else -> {
                        Column {
                            Eyebrow("Set up", color = C.RedHi)
                            Txt("Make it yours.", Modifier.padding(top = 8.dp), size = 34.sp, weight = FontWeight.Light, spacing = (-0.8).sp)
                            Txt("Three quick things. All of it stays on this phone and can be changed later in Settings.", Modifier.padding(top = 8.dp), size = 14.sp, color = C.Dim)
                        }
                        Panel(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Fingerprint, null, tint = C.RedHi, modifier = Modifier.size(28.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Txt("Confirm with fingerprint or PIN", size = 15.sp, weight = FontWeight.SemiBold)
                                    Txt("Asked before unlock, engine start and stop.", size = 12.sp, color = C.Dim)
                                }
                                Toggle(bio, { bio = it })
                            }
                        }
                        Field("Fuel tank (litres)", tank, { tank = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, keyboard = KeyboardType.Decimal, hint = "50")
                        Field("Fuel price per litre, ₹ (optional)", price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, keyboard = KeyboardType.Decimal, hint = "used for cost per km")
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
