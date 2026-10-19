package com.debarun.cretaremote.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.BuildConfig

/** Settings → About. Opens over the settings list; the system Back button closes it. */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " debug" else ""
    val info = "Creta Remote $version\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}"
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GhostButton("‹  Settings", onClose, color = C.Dim)
        Column {
            Eyebrow("About")
            Txt("Creta Remote", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
            Txt("Version $version", Modifier.padding(top = 4.dp), size = 13.sp, color = C.Dim)
        }

        AboutPanel(
            "What it is",
            "An Android remote and dashboard for the 2022 Hyundai Creta with Bluelink India. It lock-unlocks, starts and stops, shows fuel, range, tyres and trips, keeps your fuel log and warns you before the tank runs low.",
            "It talks to Hyundai's Bluelink cloud with your own account. There is no server of ours in between.",
        )
        AboutPanel(
            "Your data",
            "Your Bluelink password and PIN are encrypted on this phone with the Android Keystore and are sent only to Hyundai. Trips, parking spots and the fuel log are stored on this phone and are erased when you sign out.",
            "There are no ads, analytics or crash reporters. The only other network use is the map, which loads OpenStreetMap tiles and shows the position you allow.",
        )
        AboutPanel(
            "Know the limits",
            "This is cloud remote control, not a digital key, and it needs mobile coverage at the car. Bluelink's API is unofficial and undocumented, so Hyundai can change it and break features without notice. Do not rely on it for anything safety-critical, and always keep the physical key.",
            "Not affiliated with, endorsed by or supported by Hyundai. Hyundai and Bluelink are trademarks of their owners. Use at your own risk.",
        )
        AboutPanel(
            "Credits",
            "Bluelink India behaviour follows the community project hyundai_kia_connect_api. Map data © OpenStreetMap contributors (ODbL). Built with Kotlin, Jetpack Compose, OkHttp, WorkManager and AndroidX, all under the Apache 2.0 licence.",
            "Made by Debarun in Bengaluru.",
        )
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("Reporting a problem")
                Txt("Copy this with a short description of what happened. Settings → Diagnostics can also copy the raw car data Bluelink sent.", size = 12.sp, color = C.Dim)
                PrimaryButton(if (copied) "Copied" else "Copy app info", { clip.setText(AnnotatedString(info)); copied = true })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AboutPanel(title: String, vararg paragraphs: String) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(title)
            paragraphs.forEachIndexed { i, p -> Txt(p, size = 13.sp, color = if (i == 0) C.Text else C.Dim, lineHeight = 19.sp) }
        }
    }
}
