# Speedometer

A simple Android GPS speedometer.

## Features
- Real-time GPS speed in km/h
- Maximum speed tracking
- Start/Stop tracking
- Dark, minimal interface
- Android 16 target (API 36)

## Build
GitHub Actions automatically builds a debug APK on every push to `main`.

Open **Actions → Build APK → Artifacts** and download `speedometer-debug-apk`.

For local builds, use Gradle 8.11.1 with JDK 17:

```bash
gradle assembleDebug
```
