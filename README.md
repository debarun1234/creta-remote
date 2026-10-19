# Creta Remote

A fast, animated Android controller and telemetry app for a **Hyundai Creta (2022) with Bluelink India**.
Built with Kotlin and Jetpack Compose. Talks straight to Hyundai's Bluelink cloud with your own account, with no middle server.

## What it does

- **Garage**: your own mulberry Creta photos (home, welcome, vehicle report). The car drives in on launch, a light sheen sweeps the paint, lamps glow when the engine runs, and a pulse ring plus hazard blinks play on every lock or unlock. The top-down vehicle report highlights open doors, windows, hood, rear gate and low-pressure tyres live.
- **Remote control**: slide-to-lock/unlock, climate and engine start (temperature and duration), lights only, horn + lights, boot release, navigate to the car. Quick Settings tiles, launcher shortcuts and a home-screen widget (Lock and Lights fire directly; Unlock and Boot go through the biometric check). Each command shows live progress until the car confirms.
- **Telemetry**: liquid-fill fuel gauge, range, rolling odometer, 12V battery, doors, windows, hood, boot, tyre and fluid warnings on a live top-down car.
- **Insights**: fuel gauge, range, fuel alert, tyres, 14-day distance bars, and a summary of your real economy with a link to the Fuel log.
- **Fuel log**: your own fill-up ledger (date, odometer, litres, price or amount, brim-full or top-up). Economy is measured tank to tank, so partial top-ups pool into the next brim-full fill. Gives real km/L, best/worst, efficiency index, price actually paid per km/day/month, a trip-cost calculator, and a tank estimate that cross-checks the gauge.
- **Trips**: month distance with a moving-vs-idle ring, weekday rhythm, heat calendar, and per-day 24-hour timeline with trip cards (distance, duration, speed meter) where Bluelink sends them.
- **Places**: a real dark street map (OpenStreetMap tiles recoloured dark, no API key; internet needed) with a fly-in to the car, your Creta photo as the marker, the parking trail, scale bar, pinch zoom and a tappable parking history. Allow location to see yourself on the map and how far the car is.
- **Fast by design**: opens on the last stored reading instantly, reads the cloud copy (no car wake), parallel requests, token and device id persisted so no login round trip on launch.
- **Launcher shortcuts**: long-press the app icon for Lock or Unlock.

## Honest limits

- It is **cloud remote control, not a digital key**. The car must have mobile coverage, and commands go phone, Hyundai cloud, car. You still need the physical key to drive. Whether remote engine start works depends on your variant.
- Bluelink's API is **unofficial and undocumented**. This client follows the community-reverse-engineered [`hyundai_kia_connect_api`](https://github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api) India implementation. Hyundai can change it at any time.
- The car's modem sleeps, which is most of the wait on any command. While the app is open it sends a live status request on open and every 3 minutes for up to 10 minutes (toggle in Settings) as a wake-up call, and shows "Waking car… / Car awake". Bluelink has no documented wake command, so this is an attempt, not a guarantee, and each command shows a Sending → Car waking → Confirmed track.
- **Widgets, drawn to fit**: Creta fuel meter, dash (range in a ring around the fuel level, plus a 12V ring only if the car reports it), next fill-up (odometer to fill up at, from the Fuel log), tyres (top view) and status (lock state, fuel, range, odometer, tyres and fill-up). Each redraws as you drag its corners: a lone number, a strip, a ring, a wide gauge-and-figures view, or a tall stacked dashboard, always laid out to the real size so nothing is cut off. The round refresh button in the corner (right edge on a strip) does a live sync from the car without opening the app: amber while syncing, red if it failed. Tapping anywhere else opens the app.
- **Per-widget settings**: adding a widget (or long-pressing it and choosing Settings) opens a screen with a live preview: light, dark or auto theme, and a solid or transparent background with a transparency slider. Light with a solid background is the default. Applies to every widget, including the key and lock-and-ignition buttons.
- **Password expiry reminder**: Settings tracks when you last set your Bluelink password and warns a week before it expires (and daily after). Hyundai does not publish the lifetime, so you set the number of days; the default is 90. If Bluelink answers sign-in with `step 5`, the app explains the password has expired and must be changed in the official app.
- **Fuel alert** replaces the refuel prediction: set 10-30 L per trip (default 10, back to 10 after a refuel). A heads-up fires 2 L above it and a ringing phone alarm at the limit, until you tap "I'll fill up". Fuel only updates when the car reports it, so while the engine is on or the tank is near the limit the app checks every ~5 minutes in the background; alerts can still lag by a few minutes.
- Bluelink reports where the car is **parked**, not a live GPS track. The Places trail is built from parking spots recorded while the app runs.
- Lights, horn+lights and boot release are undocumented endpoints; the app tries the routes that fit your modem in order, and the Activity log shows which one worked ("via ..."). Boot release may not be supported remotely on your car. Some trip fields are only confirmed for newer modems. If your car rejects a command the app shows Bluelink's reason.
- Fuel economy and cost come only from your fill-up log, never from fuel-gauge jumps. It needs two brim-full fills before the first figure appears; until then the app shows the car's own range estimate and says so. Predictions (range, refuel-by) use gauge litres times your logged economy and are estimates.

## Security

Password, PIN and tokens are encrypted with an Android Keystore key and only ever sent to Hyundai. Unlock, start and stop can be gated behind fingerprint, face or phone PIN (Settings).

## Car details, specs and service

- **Settings -> My car**: enter your VIN (model year and maker are decoded locally and cross-checked against the VIN on your Bluelink account), choose engine and transmission, and set the service interval.
- **Tap the car** on the home screen for specifications and a service log with a next-service countdown. Engine, transmission and body figures are Hyundai's published numbers for the 2022 Creta; service history is what you log yourself (Bluelink doesn't expose it).
- **Welcome-screen logo**: save the official Hyundai logo (transparent PNG, light colour) as `app/src/main/res/drawable-nodpi/hyundai_logo.png`. Nothing is shown until the file exists. The logo is Hyundai's trademark, so it isn't bundled in this repo.

## Speed

Commands travel phone -> Hyundai cloud -> the car's modem, so the last leg (waking the modem and the car acting) is Hyundai's and can't be skipped. The app removes everything else: session and control token are pre-fetched on launch, the command goes out on a warm connection, and the result is polled every second with a live timer. **Quick Settings tiles**: add "Lock Creta" / "Unlock Creta" from the notification shade's edit menu to control the car without opening the app (unlock asks for the phone's lock screen first).

## Install on your phone

1. Phone: **Settings → About phone**, tap *Build number* 7 times, then **Developer options → USB debugging** on.
2. Connect by USB and accept the "Allow USB debugging" prompt.
3. Android Studio: pick your phone in the device dropdown and press **Run**. Or from a terminal: `./gradlew :app:installDebug`.
4. Without a cable: download `app-debug.apk` from the latest GitHub Actions run, open it on the phone and allow "Install unknown apps" for your browser/Files app.

First launch shows a three-step setup (what it does, its limits, biometric/tank preferences), then sign-in. Every launch after that opens with the welcome screen (tap to skip).

## Build

Needs JDK 17 and the Android SDK (API 35). Open in Android Studio, or:

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) builds the debug APK and uploads it as an artifact on every push.

## Layout

```
app/src/main/java/com/debarun/cretaremote/
  bluelink/   API client, models, tolerant response parser
  data/       repository, SQLite history, fuel analytics, encrypted vault, settings
  ui/         theme, animated car photos + live status overlays, components, screens
  work/       15-minute background snapshots
```

Not affiliated with Hyundai. Use at your own risk, and keep your Bluelink credentials to yourself.
