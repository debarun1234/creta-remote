package com.debarun.cretaremote.ui

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

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop()
        when {
            data.booting -> Unit
            !data.signedIn -> LoginScreen(data, vm)
            else -> Shell(data, vm, launchAction, confirm)
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
    var lockFlash by remember { mutableIntStateOf(0) }

    fun act(cmd: Cmd) {
        val gated = data.settings.confirmBiometric && (cmd == Cmd.UNLOCK || cmd == Cmd.START || cmd == Cmd.STOP)
        val title = when (cmd) {
            Cmd.UNLOCK -> "Unlock your Creta"
            Cmd.START -> "Start your Creta"
            Cmd.STOP -> "Stop your Creta"
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
                Tab.HOME -> HomeScreen(data, vm, lockFlash, onAct = ::act, onClimate = { climate = true })
                Tab.INSIGHTS -> InsightsScreen(data)
                Tab.TRIPS -> TripsScreen(data, vm)
                Tab.PLACES -> PlacesScreen(data)
                Tab.SETTINGS -> SettingsScreen(data, vm)
            }
        }

        CommandBanner(data, Modifier.align(Alignment.TopCenter))
        NavBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))

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
        AnimatedVisibility(
            climate,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(),
        ) {
            ClimateSheet(
                data.settings,
                running = data.car?.engineOn == true,
                onClose = { climate = false },
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
private fun CommandBanner(data: com.debarun.cretaremote.data.AppData, modifier: Modifier = Modifier) {
    val c = data.cmd
    AnimatedVisibility(
        visible = c.phase != Phase.IDLE,
        modifier = modifier,
        enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(260)) { -it } + fadeOut(),
    ) {
        Row(
            Modifier.padding(top = 52.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(26.dp))
                .background(Color(0xF2171A22)).border(1.dp, C.Line, RoundedCornerShape(26.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhaseGlyph(c.phase)
            Column(Modifier.padding(start = 12.dp)) {
                Txt(c.cmd?.label ?: "", size = 14.sp, weight = FontWeight.SemiBold)
                Txt(c.message, size = 12.sp, color = C.Dim)
            }
        }
    }
}
