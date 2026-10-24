package com.debarun.cretaremote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.debarun.cretaremote.BuildConfig
import com.debarun.cretaremote.data.Notifier
import com.debarun.cretaremote.AppViewModel
import com.debarun.cretaremote.data.AppData
import com.debarun.cretaremote.data.Powertrain
import com.debarun.cretaremote.data.Transmission
import com.debarun.cretaremote.data.Vin
import com.debarun.cretaremote.data.passwordDaysLeft

@Composable
fun SettingsScreen(d: AppData, vm: AppViewModel) {
    var about by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SettingsList(d, vm, onAbout = { about = true })
        if (about) AboutScreen(onClose = { about = false })
    }
}

@Composable
private fun SettingsList(d: AppData, vm: AppViewModel, onAbout: () -> Unit) {
    val s = d.settings
    var tank by remember(s.tankLitres) { mutableStateOf(Fmt.n(s.tankLitres, 0)) }
    var price by remember(s.fuelPricePerL) { mutableStateOf(if (s.fuelPricePerL > 0) Fmt.n(s.fuelPricePerL, 2) else "") }
    var pin by remember { mutableStateOf("") }
    var pwDays by remember(s.passwordDays) { mutableStateOf(s.passwordDays.toString()) }

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

        MyCarPanel(d, vm)

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
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Bluelink password")
                val left = s.passwordDaysLeft()
                if (left == null) {
                    Txt("Not tracked yet. Tap below once you have set or changed the password, and the app will warn you before it expires.", size = 13.sp, color = C.Dim)
                } else {
                    val c = when { left < 0 -> C.RedHi; left <= 7 -> C.Amber; else -> C.Green }
                    Txt(
                        when { left < 0 -> "Probably expired ${-left} day${if (-left == 1) "" else "s"} ago"; left == 0 -> "Expires today"; else -> "Expires in $left day${if (left == 1) "" else "s"}" },
                        size = 20.sp, weight = FontWeight.Medium, color = c,
                    )
                    Txt("Set on ${dayText(s.passwordSetAt)}. A warning is sent a week before, then daily, when the app is closed.", size = 12.sp, color = C.Faint)
                }
                Field("Password lasts (days)", pwDays, { v ->
                    pwDays = v.filter { c -> c.isDigit() }.take(3)
                    v.filter { c -> c.isDigit() }.toIntOrNull()?.takeIf { it in 1..365 }?.let { vm.saveSettings(s.copy(passwordDays = it)) }
                }, keyboard = KeyboardType.Number, hint = "90")
                Txt("Hyundai does not publish the rule, so set the number of days to match what you have seen. Passwords are changed in the official Bluelink app, not here.", size = 11.sp, color = C.Faint)
                GhostButton(if (left == null) "Start tracking from today" else "I just changed my password", { vm.passwordChanged() }, color = C.Cyan)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("Home-screen widgets")
                Txt("Add them from your home screen's widget picker. Each widget has its own settings: light, dark or auto theme, and a solid or transparent background with adjustable transparency. They open when you add a widget, and again from the widget's long-press menu.", size = 12.sp, color = C.Dim)
                Txt("Fuel meter, dash, next fill-up, tyres and status redraw themselves as you resize them and have a round refresh button for a live sync. The key and lock-and-ignition widgets stay as buttons.", size = 12.sp, color = C.Dim)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("Diagnostics")
                Txt("Copies exactly what Bluelink sent for your car (status and maintenance, no location) so you can see which fields it reports, such as tyre pressure numbers.", size = 12.sp, color = C.Dim)
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                var copied by remember { mutableStateOf<Boolean?>(null) }
                PrimaryButton(
                    when (copied) { true -> "Copied"; false -> "Nothing to copy yet - sync first"; null -> "Copy raw car data" },
                    { val t = vm.rawForSupport(); copied = t != null; if (t != null) clip.setText(androidx.compose.ui.text.AnnotatedString(t)) },
                )
            }
        }

        PermissionsPanel(d, vm)

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("How this app works")
                Txt(
                    "Talks directly to Hyundai Bluelink India with your own account. Your password and PIN are encrypted on this phone with the Android Keystore and are only sent to Hyundai.",
                    size = 12.sp, color = C.Dim,
                )
                Txt(
                    "Every ~15 minutes the app records odometer and fuel in the background. That history powers the economy numbers; it never wakes the car.",
                    size = 12.sp, color = C.Dim,
                )
                Txt(
                    "Unofficial: Bluelink's API is undocumented and Hyundai can change it at any time. This app is not affiliated with Hyundai.",
                    size = 12.sp, color = C.Faint,
                )
            }
        }

        Panel(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onAbout() }) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("About Creta Remote", size = 15.sp, weight = FontWeight.SemiBold)
                    Txt("Version ${BuildConfig.VERSION_NAME} · privacy · limits · credits", size = 12.sp, color = C.Dim)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = C.Dim)
            }
        }

        GhostButton("Sign out and erase local data", { vm.signOut() }, color = C.RedHi)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MyCarPanel(d: AppData, vm: AppViewModel) {
    val s = d.settings
    var vin by remember(s.vin) { mutableStateOf(s.vin) }
    var svcKm by remember(s.serviceKm) { mutableStateOf(s.serviceKm.toString()) }
    var svcMonths by remember(s.serviceMonths) { mutableStateOf(s.serviceMonths.toString()) }
    val info = Vin.decode(vin)
    val blueVin = d.vehicle?.vin.orEmpty()
    val pt = Powertrain.of(s.powertrain)

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Eyebrow("My car")
            Field(
                "VIN (17 characters)", vin,
                { v ->
                    val c = Vin.clean(v)
                    vin = c
                    if (c.isEmpty() || c.length == 17) vm.saveSettings(s.copy(vin = c))
                },
                hint = "printed on the RC book and windscreen corner",
            )
            when {
                info.problem != null -> Txt(info.problem, size = 12.sp, color = C.Faint)
                info.valid -> {
                    Txt(
                        "${info.maker ?: "Unrecognised maker code"}  -  model year ${info.modelYear ?: "unknown"}",
                        size = 12.sp, color = C.Green,
                    )
                    if (blueVin.isNotBlank()) {
                        val same = info.vin.equals(blueVin, ignoreCase = true)
                        Txt(
                            if (same) "Matches your Bluelink account" else "Differs from Bluelink's VIN ($blueVin)",
                            size = 12.sp, color = if (same) C.Green else C.Amber,
                        )
                    }
                }
            }

            Eyebrow("Engine")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Powertrain.entries.forEach { p ->
                    OptionChip(
                        p.label, p == pt,
                        {
                            val keep = Transmission.entries.firstOrNull { it.name == s.transmission }?.takeIf { it in p.transmissions }
                            vm.saveSettings(s.copy(powertrain = p.name, transmission = keep?.name ?: ""))
                        },
                        Modifier.weight(1f),
                    )
                }
            }
            Eyebrow("Transmission")
            if (pt == null) {
                Txt("Pick the engine first.", size = 12.sp, color = C.Faint)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pt.transmissions.forEach { t ->
                        OptionChip(t.label, t.name == s.transmission, { vm.saveSettings(s.copy(transmission = t.name)) }, Modifier.weight(1f))
                    }
                }
            }

            Eyebrow("Service interval")
            Field("Every (km)", svcKm, {
                svcKm = it.filter { c -> c.isDigit() }.take(6)
                svcKm.toIntOrNull()?.takeIf { v -> v in 1000..50_000 }?.let { v -> vm.saveSettings(s.copy(serviceKm = v)) }
            }, keyboard = KeyboardType.Number)
            Field("or every (months)", svcMonths, {
                svcMonths = it.filter { c -> c.isDigit() }.take(2)
                svcMonths.toIntOrNull()?.takeIf { v -> v in 1..36 }?.let { v -> vm.saveSettings(s.copy(serviceMonths = v)) }
            }, keyboard = KeyboardType.Number)
            Txt("Default is 10,000 km or 12 months. Check your service booklet and adjust.", size = 11.sp, color = C.Faint)
        }
    }
}

@Composable
private fun PermissionsPanel(d: AppData, vm: AppViewModel) {
    val s = d.settings
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifOn = remember(tick) { Notifier.enabled(ctx) }
    val locOn = remember(tick) { Perms.locationAllowed(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val btOn = remember(tick) { Perms.btAllowed(ctx) }
    val battOn = remember(tick) { Perms.batteryUnrestricted(ctx) }
    var pick by remember { mutableStateOf(false) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> tick++; if (granted) pick = true }

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Eyebrow("Permissions and alerts")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Txt("Stay connected while driving", size = 14.sp, weight = FontWeight.Medium)
                    Txt("While the engine is on, the app keeps reading the car in the background (every 30 seconds, with a notification) so trips, distance and fuel are captured as you drive. The engine being on already means the car is awake, so this wakes nothing. It stops a few minutes after the engine is off.", size = 12.sp, color = C.Dim)
                }
                Toggle(s.driveSession, { vm.saveSettings(s.copy(driveSession = it)) })
            }
            if (s.driveSession) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt("Car Bluetooth", size = 15.sp, weight = FontWeight.Medium)
                        Txt(
                            if (s.carBtAddress.isBlank()) "Pick the car's Bluetooth and the app connects the moment the car does. Without it, it connects when you open the app or at the next background check (about 15 minutes)."
                            else "Connects when ${s.carBtName.ifBlank { "the car" }} does.",
                            size = 12.sp, color = C.Dim,
                        )
                    }
                    Box(Modifier.width(108.dp)) {
                        GhostButton(if (s.carBtAddress.isBlank()) "Choose" else "Change", {
                            if (btOn) pick = true
                            else if (!Perms.asked(ctx, "bt")) {
                                Perms.markAsked(ctx, "bt")
                                btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else Perms.openAppSettings(ctx)
                        }, color = C.Cyan)
                    }
                }
                PermRow(
                    "Unrestricted battery", "Lets Android start the drive session by itself when the car connects. Set this app to Unrestricted, or the session may only start while the app is open.", battOn,
                    onAllow = { Perms.openBatterySettings(ctx) },
                    onManage = { Perms.openBatterySettings(ctx) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Txt("Wake the car for commands", size = 14.sp, weight = FontWeight.Medium)
                    Txt("The dashboard never needs this: it reads the car's last report straight from Bluelink. Switch this on only to make lock, unlock and start quicker: it sends a live request when you open the app, then every 3 minutes for up to 10 minutes. Uses a little car battery and mobile data.", size = 12.sp, color = C.Dim)
                }
                Toggle(s.keepAwake, { vm.saveSettings(s.copy(keepAwake = it)) })
            }
            if (pick) {
                BtPicker(
                    current = s.carBtAddress,
                    onPick = { name, address -> vm.saveSettings(s.copy(carBtName = name, carBtAddress = address)); pick = false },
                    onDismiss = { pick = false },
                )
            }

            PermRow(
                "Notifications", "How a command ended and car alerts, when the app is closed.", notifOn,
                onAllow = {
                    if (Build.VERSION.SDK_INT >= 33 && !Perms.asked(ctx, "notif")) {
                        Perms.markAsked(ctx, "notif")
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else Perms.openNotificationSettings(ctx)
                },
                onManage = { Perms.openNotificationSettings(ctx) },
            )
            if (notifOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt("Command results", size = 14.sp, weight = FontWeight.Medium)
                        Txt("Lock, unlock, engine and horn from a tile or shortcut", size = 12.sp, color = C.Dim)
                    }
                    Toggle(s.notifyCommands, { vm.saveSettings(s.copy(notifyCommands = it)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt("Car alerts", size = 14.sp, weight = FontWeight.Medium)
                        Txt("Low fuel, something left open, unlocked for 20 minutes. Checked about every 15 minutes.", size = 12.sp, color = C.Dim)
                    }
                    Toggle(s.notifyAlerts, { vm.saveSettings(s.copy(notifyAlerts = it)) })
                }
            }

            PermRow(
                "Location", "Shows where you are on the map and how far the car is. Used only while the Places screen is open, never stored or sent.", locOn,
                onAllow = {
                    if (!Perms.asked(ctx, "loc")) {
                        Perms.markAsked(ctx, "loc")
                        locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else Perms.openAppSettings(ctx)
                },
                onManage = { Perms.openAppSettings(ctx) },
            )
        }
    }
}

/** Lists the phone's paired Bluetooth devices so the car's can be picked. */
@SuppressLint("MissingPermission")
@Composable
private fun BtPicker(current: String, onPick: (name: String, address: String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val devices = remember {
        try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bondedDevices
                ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first.lowercase() } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface2,
        title = { Txt("Which one is the car?", size = 18.sp, weight = FontWeight.Medium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (devices.isEmpty()) {
                    Txt("No paired Bluetooth devices. Pair the phone with the car first (the car's Bluetooth must be switched on).", size = 13.sp, color = C.Dim)
                }
                devices.forEach { (name, address) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(name, address) }.padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Txt(name, Modifier.weight(1f), size = 15.sp, weight = if (address == current) FontWeight.Bold else FontWeight.Normal)
                        if (address == current) Txt("Selected", size = 12.sp, color = C.Green)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (current.isNotBlank()) ({ TextButton(onClick = { onPick("", "") }) { Text("Clear") } }) else null,
    )
}

@Composable
private fun PermRow(title: String, sub: String, on: Boolean, onAllow: () -> Unit, onManage: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, size = 15.sp, weight = FontWeight.Medium)
                StatusChip(if (on) "Allowed" else "Off", if (on) C.Green else C.Amber)
            }
            Txt(sub, size = 12.sp, color = C.Dim)
        }
        Box(Modifier.width(108.dp)) {
            if (on) GhostButton("Manage", onManage) else GhostButton("Allow", onAllow, color = C.Cyan)
        }
    }
}

/** A small pill-style choice row. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(C.Surface2).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) C.Red.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(label, size = 13.sp, weight = FontWeight.Medium, color = if (on) C.Text else C.Dim)
            }
        }
    }
}
