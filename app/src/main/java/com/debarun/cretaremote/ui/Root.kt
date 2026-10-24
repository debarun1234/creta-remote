package com.debarun.cretaremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.Phase

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Garage", Icons.Rounded.Home),
    INSIGHTS("Insights", Icons.Rounded.Insights),
    TRIPS("Trips", Icons.Rounded.Route),
    PLACES("Places", Icons.Rounded.Place),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

@Composable
fun Root(
    launchAction: MutableState<String?>,
    confirm: (String, () -> Unit) -> Unit,
    vm: AppViewModel = viewModel(),
) {
    val data by vm.data.collectAsStateWithLifecycle()

    // welcome screen on every cold start; rememberSaveable so rotation doesn't replay it
    var welcomed by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop()
        when {
            data.booting -> Unit
            !data.signedIn && !data.settings.onboarded -> SetupFlow(data, vm)
            !data.signedIn -> LoginScreen(data, vm)
            else -> Shell(data, vm, launchAction, confirm)
        }
        AnimatedVisibility(!welcomed, exit = fadeOut(tween(450))) {
            WelcomeSplash(ready = !data.booting, signedIn = data.signedIn, onDone = { welcomed = true })
        }
    }
}

@Composable
private fun Shell(
    data: com.debarun.cretaremote.data.AppData,
    vm: AppViewModel,
    launchAction: MutableState<String?>,
    confirm: (String, () -> Unit) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var climate by remember { mutableStateOf(false) }
    var carSheet by remember { mutableStateOf(false) }
    var activity by remember { mutableStateOf(false) }
    var fuelLog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    BackHandler(carSheet) { carSheet = false }
    BackHandler(activity) { activity = false }
    BackHandler(fuelLog) { fuelLog = false }
    var lockFlash by remember { mutableIntStateOf(0) }

    fun act(cmd: Cmd) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val gated = data.settings.confirmBiometric && (cmd == Cmd.UNLOCK || cmd == Cmd.START || cmd == Cmd.STOP || cmd == Cmd.BOOT)
        val title = when (cmd) {
            Cmd.UNLOCK -> "Unlock your Creta"
            Cmd.START -> "Start your Creta"
            Cmd.STOP -> "Stop your Creta"
            Cmd.BOOT -> "Open your Creta's boot"
            else -> "Confirm"
        }
        if (gated) confirm(title) { vm.run(cmd) } else vm.run(cmd)
    }

    // launcher shortcuts: long-press the app icon -> Lock / Unlock
    val action = launchAction.value
    LaunchedEffect(action) {
        when (action) {
            "com.debarun.cretaremote.LOCK" -> act(Cmd.LOCK)
            "com.debarun.cretaremote.UNLOCK" -> act(Cmd.UNLOCK)
            "com.debarun.cretaremote.START" -> act(Cmd.START)
            "com.debarun.cretaremote.LIGHTS" -> act(Cmd.LIGHTS)
            "com.debarun.cretaremote.BOOT" -> act(Cmd.BOOT)
            com.debarun.cretaremote.data.Updater.ACTION -> { tab = Tab.SETTINGS.ordinal; vm.installUpdate() }
        }
        if (action != null) launchAction.value = null
    }
    // pulse + hazard blink in the hero whenever a lock/unlock command succeeds
    LaunchedEffect(data.cmd.phase, data.cmd.cmd) {
        if (data.cmd.phase == Phase.OK && (data.cmd.cmd == Cmd.LOCK || data.cmd.cmd == Cmd.UNLOCK)) lockFlash++
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { (fadeIn(tween(260)) togetherWith fadeOut(tween(160))) },
            label = "tabs",
        ) { t ->
            when (Tab.entries[t]) {
                Tab.HOME -> HomeScreen(data, vm, lockFlash, onAct = ::act, onClimate = { climate = true }, onCar = { carSheet = true }, onActivity = { activity = true })
                Tab.INSIGHTS -> InsightsScreen(data, onFuelLimit = vm::setFuelLimit, onTyreLog = vm::saveTyreLog, onFuelLog = { fuelLog = true })
                Tab.TRIPS -> TripsScreen(data, vm)
                Tab.PLACES -> PlacesScreen(data)
                Tab.SETTINGS -> SettingsScreen(data, vm)
            }
        }

        AnimatedVisibility(fuelLog, enter = fadeIn(tween(240)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 }, exit = fadeOut(tween(180))) {
            FuelLogScreen(data, vm, onClose = { fuelLog = false })
        }
        CommandBanner(data, vm::cancelCmd, Modifier.align(Alignment.TopCenter))
        NavBar(tab, { tab = it; fuelLog = false }, Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            climate,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xAA000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { climate = false },
            )
        }
        AnimatedVisibility(carSheet, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color(0xAA000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { carSheet = false },
            )
        }
        AnimatedVisibility(activity, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color(0xAA000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { activity = false },
            )
        }
        AnimatedVisibility(
            activity,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(),
        ) {
            ActivitySheet(data, vm, onClose = { activity = false })
        }
        AnimatedVisibility(
            carSheet,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(),
        ) {
            CarSheet(data, vm, onClose = { carSheet = false }, onSettings = { carSheet = false; tab = Tab.SETTINGS.ordinal })
        }
        AnimatedVisibility(
            climate,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(),
        ) {
            ClimateSheet(
                data.settings,
                running = data.car?.engineOn == true,
                onStart = { temp, mins ->
                    vm.saveSettings(data.settings.copy(climateTempC = temp, climateMinutes = mins))
                    climate = false
                    // settings are applied by the repository on the next command; run after the state update
                    act(Cmd.START)
                },
                onStop = { climate = false; act(Cmd.STOP) },
            )
        }
    }
}

// ----------------------------------------------------------------------------- chrome

@Composable
private fun Backdrop() {
    val inf = rememberInfiniteTransition(label = "bd")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        drawRect(
            Brush.radialGradient(listOf(Color(0x33E3192D), Color.Transparent), Offset(w * (0.25f + 0.5f * t), size.height * 0.12f), w * 0.9f),
        )
        drawRect(
            Brush.radialGradient(listOf(Color(0x1A3DD6F5), Color.Transparent), Offset(w * (0.9f - 0.5f * t), size.height * 0.85f), w * 0.8f),
        )
    }
}

@Composable
private fun NavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0xF2101218)).border(1.dp, C.Line, RoundedCornerShape(30.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEachIndexed { i, t ->
                val on = i == selected
                val p by animateFloatAsState(if (on) 1f else 0f, tween(320, easing = FastOutSlowInEasing), label = "nav")
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(C.Red.copy(alpha = 0.18f * p))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, t.label, tint = if (on) C.RedHi else C.Dim, modifier = Modifier.size(22.dp))
                    if (on) Txt(t.label, Modifier.padding(start = 8.dp), size = 13.sp, color = C.Text, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CommandBanner(data: com.debarun.cretaremote.data.AppData, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = data.cmd
    AnimatedVisibility(
        visible = c.phase != Phase.IDLE,
        modifier = modifier,
        enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(260)) { -it } + fadeOut(),
    ) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.padding(top = 52.dp, start = 16.dp, end = 16.dp).fillMaxWidth().clip(shape)
                .background(Color(0xF2171A22)).border(1.dp, C.Line, shape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseGlyph(c.phase)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Txt(c.cmd?.label ?: "", size = 14.sp, weight = FontWeight.SemiBold)
                    Txt(c.message, size = 12.sp, color = C.Dim)
                }
                if (c.busy) {
                    Txt(
                        "Cancel",
                        Modifier.padding(start = 14.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x33E3192D))
                            .border(1.dp, C.Red, RoundedCornerShape(16.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        size = 13.sp, weight = FontWeight.SemiBold, color = C.RedHi,
                    )
                }
            }
            StageTrack(c.phase)
            if (c.phase == Phase.WAITING) {
                Txt("The car sleeps to save its battery, so the first command after a quiet spell is the slowest.", size = 11.sp, color = C.Faint)
            }
        }
    }
}
