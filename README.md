# Speedometer

Offline-first Android GPS speedometer for portrait phones, designed as a modern motorcycle-style instrument cluster.

## What it does
- GPS speed display capped at **110 km/h**
- Adaptive GNSS smoothing and bad-fix rejection
- Uses device-reported GNSS speed when available, with distance/time fallback
- GPS accuracy, speed-accuracy and a confidence indicator
- Mock-location rejection
- Trip distance, average speed, maximum speed and moving time
- Startup self-test animation: **0 → 110 → 0 km/h**
- Modern motorcycle TFT-inspired UI with native Canvas rendering
- Picture-in-Picture mode with a compact live speed display
- Precise GPS permission handling
- No INTERNET permission: core speedometer operation is offline and GPS-only
- Android 16 / API 36 target

## Accuracy philosophy
The app does not claim a fixed percentage of accuracy because GNSS accuracy depends on satellite visibility, device hardware, environment and road conditions. Instead, it exposes the GPS confidence/accuracy and rejects obvious bad fixes before they affect the displayed speed or trip distance.

## Build
GitHub Actions automatically builds a debug APK on every push to `main`.

Open **Actions → Build APK → Artifacts** and use the `speedometer-debug-apk` artifact.

Local build requirements:
- JDK 17
- Gradle 8.11.1
- Android SDK API 36

```bash
gradle clean assembleDebug
```
