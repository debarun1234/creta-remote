# Creta Remote

A fast, animated Android controller and telemetry app for a **Hyundai Creta (2022) with Bluelink India**.
Built with Kotlin and Jetpack Compose. Talks straight to Hyundai's Bluelink cloud with your own account, with no middle server.

## What it does

- **Garage**: your own mulberry Creta photos (home, welcome, vehicle report). The car drives in on launch, a light sheen sweeps the paint, lamps glow when the engine runs, and a pulse ring plus hazard blinks play on every lock or unlock. The top-down vehicle report highlights open doors, windows, hood, rear gate and low-pressure tyres live.
- **Remote control**: slide-to-lock/unlock, climate and engine start (temperature and duration), horn and lights, navigate to the car. Each command shows live progress until the car confirms.
- **Telemetry**: liquid-fill fuel gauge, range, rolling odometer, 12V battery, doors, windows, hood, boot, tyre and fluid warnings on a live top-down car.
- **Insights**: km/L from your real fill-ups, efficiency trend, refuel-by date and empty date prediction, 14-day distance bars, running cost, fill-up log.
- **Trips**: month distance with a moving-vs-idle ring, weekday rhythm, heat calendar, and per-day 24-hour timeline with trip cards (distance, duration, speed meter) where Bluelink sends them.
- **Places**: a real dark street map (OpenStreetMap tiles recoloured dark, no API key; internet needed) with a fly-in to the car, your Creta photo as the marker, the parking trail, scale bar, pinch zoom and a tappable parking history.
- **Fast by design**: opens on the last stored reading instantly, reads the cloud copy (no car wake), parallel requests, token and device id persisted so no login round trip on launch.
- **Launcher shortcuts**: long-press the app icon for Lock or Unlock.

## Honest limits

- It is **cloud remote control, not a digital key**. The car must have mobile coverage, and commands go phone, Hyundai cloud, car. You still need the physical key to drive. Whether remote engine start works depends on your variant.
- Bluelink's API is **unofficial and undocumented**. This client follows the community-reverse-engineered [`hyundai_kia_connect_api`](https://github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api) India implementation. Hyundai can change it at any time.
- Bluelink reports where the car is **parked**, not a live GPS track. The Places trail is built from parking spots recorded while the app runs.
- Horn and lights (`ccs2/control/hornlight`) and some trip fields are only confirmed for newer modems. If your car rejects a command the app shows Bluelink's reason.
- Fuel economy and predictions are derived from your own history (the app records odometer and fuel about every 15 minutes in the background). Until enough history exists, the app says so instead of guessing.

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
