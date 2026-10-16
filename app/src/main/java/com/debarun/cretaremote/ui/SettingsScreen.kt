package com.debarun.cretaremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData

@Composable
fun SettingsScreen(d: AppData, vm: AppViewModel) {
    val s = d.settings
    var tank by remember(s.tankLitres) { mutableStateOf(Fmt.n(s.tankLitres, 0)) }
    var price by remember(s.fuelPricePerL) { mutableStateOf(if (s.fuelPricePerL > 0) Fmt.n(s.fuelPricePerL, 2) else "") }
    var pin by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Eyebrow("Preferences")
            Txt("Settings", size = 30.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Vehicle")
                Txt(d.vehicle?.model?.ifBlank { null } ?: "Hyundai Creta", size = 20.sp, weight = FontWeight.Medium)
                d.vehicle?.let {
                    Txt("VIN ${it.vin}", size = 12.sp, color = C.Dim, mono = true)
                    if (it.regDate.isNotBlank()) Txt("Registered ${it.regDate}", size = 12.sp, color = C.Dim)
                }
                Txt("Signed in as ${d.email}", size = 12.sp, color = C.Faint)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("Fuel maths")
                Field("Tank size (litres)", tank, {
                    tank = it
                    it.toDoubleOrNull()?.takeIf { v -> v in 20.0..120.0 }?.let { v -> vm.saveSettings(s.copy(tankLitres = v)) }
                }, keyboard = KeyboardType.Decimal)
                Field("Fuel price (₹ per litre)", price, {
                    price = it
                    val v = it.toDoubleOrNull()
                    if (it.isEmpty()) vm.saveSettings(s.copy(fuelPricePerL = 0.0)) else if (v != null && v in 0.0..500.0) vm.saveSettings(s.copy(fuelPricePerL = v))
                }, keyboard = KeyboardType.Decimal, hint = "optional, enables running cost")
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("Security")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt("Confirm before unlock / start", size = 15.sp, weight = FontWeight.Medium)
                        Txt("Asks for fingerprint, face or phone PIN", size = 12.sp, color = C.Dim)
                    }
                    Toggle(s.confirmBiometric, { vm.saveSettings(s.copy(confirmBiometric = it)) })
                }
                Field("Bluelink PIN", pin, { pin = it.filter { c -> c.isDigit() }.take(6) }, password = true, keyboard = KeyboardType.NumberPassword,
                    hint = if (d.hasPin) "saved - type to replace" else "needed for engine stop and horn")
                if (pin.length >= 4) PrimaryButton("Save PIN", { vm.savePin(pin); pin = "" })
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("How this app works")
                Txt(
                    "Talks directly to Hyundai Bluelink India with your own account. Your password and PIN are encrypted on this phone with the Android Keystore and are only sent to Hyundai.",
                    size = 12.sp, color = C.Dim,
                )
                Txt(
                    "Every ~15 minutes the app records odometer and fuel in the background. That history powers the economy and prediction numbers; it never wakes the car.",
                    size = 12.sp, color = C.Dim,
                )
                Txt(
                    "Unofficial: Bluelink's API is undocumented and Hyundai can change it at any time. This app is not affiliated with Hyundai.",
                    size = 12.sp, color = C.Faint,
                )
            }
        }

        GhostButton("Sign out and erase local data", { vm.signOut() }, color = C.RedHi)
        Spacer(Modifier.height(8.dp))
    }
}
