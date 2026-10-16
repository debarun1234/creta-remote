# Creta Remote

A fast, animated Android controller and telemetry app for a **Hyundai Creta (2022) with Bluelink India**.
Built with Kotlin and Jetpack Compose. Talks straight to Hyundai's Bluelink cloud with your own account, with no middle server.

## What it does

- **Garage**: your red Creta in vector, driving in on launch, light sweeping over the paint, headlight bloom when the engine runs, pulse ring and hazard blinks on every lock or unlock.
- **Remote control**: slide-to-lock/unlock, climate and engine start (temperature and duration), horn and lights, navigate to the car. Each command shows live progress until the car confirms.
- **Telemetry**: liquid-fill fuel gauge, range, rolling odometer, 12V battery, doors, windows, hood, boot, tyre and fluid warnings on a live top-down car.
- **Insights**: km/L from your real fill-ups, efficiency trend, refuel-by date and empty date prediction, 14-day distance bars, running cost, fill-up log.
- **Trips**: month summary, heat calendar, per-trip distance, average and top speed.
- **Places**: radar map with distance rings and an animated trail between the places the car has parked.
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
  ui/         theme, animated car art, components, screens
  work/       15-minute background snapshots
tools/        regenerates ui/CarPaths.kt from the SVG art in tools/art
```

Not affiliated with Hyundai. Use at your own risk, and keep your Bluelink credentials to yourself.
