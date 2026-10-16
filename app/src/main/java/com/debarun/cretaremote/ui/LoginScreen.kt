package com.debarun.cretaremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData

@Composable
fun LoginScreen(d: AppData, vm: AppViewModel) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding()
            .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SideCar(Modifier.fillMaxWidth(), engineOn = false, lightsOn = false, doorOpen = false, locked = true, flash = 0)
        Column {
            Eyebrow("Hyundai Creta · Bluelink India")
            Txt("Creta Remote", size = 36.sp, weight = FontWeight.Light, spacing = (-0.8).sp)
            Txt("Sign in with the account you use in the official Bluelink app.", Modifier.padding(top = 6.dp), size = 14.sp, color = C.Dim)
        }
        Field("Bluelink email", email, { email = it }, keyboard = KeyboardType.Email)
        Field("Password", pass, { pass = it }, password = true, keyboard = KeyboardType.Password)
        Field("Bluelink PIN", pin, { pin = it.filter { c -> c.isDigit() }.take(6) }, password = true, keyboard = KeyboardType.NumberPassword, hint = "used for engine stop and horn")
        d.signInError?.let { Txt(it, size = 13.sp, color = C.RedHi) }
        PrimaryButton("Connect car", { vm.signIn(email, pass, pin) }, enabled = email.contains("@") && pass.isNotEmpty(), loading = d.signingIn)
        Spacer(Modifier.height(4.dp))
        Txt(
            "Your credentials are encrypted on this phone and sent only to Hyundai Bluelink.",
            Modifier.fillMaxWidth(), size = 12.sp, color = C.Faint, align = TextAlign.Center,
        )
    }
}
