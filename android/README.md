# Carcassonne LAN Android (Standalone Kotlin Rewrite)

This folder is a fully standalone Android project (`com.carcassonne.lan`) that rewrites the original web template as an Android-first LAN app.

## What This Project Includes

- Fullscreen Android app (system bars hidden).
- No central server dependency.
- Built-in host server running inside the app on LAN (`0.0.0.0`).
- Default LAN port: `18473` (changeable in Settings).
- Automatic LAN scan of local `/24` segments for `Carcassonne LAN` hosts.
- Lobby view that auto-lists discovered running apps.
- Auto-generated player name with numeric suffix, editable in Settings.
- Reconnect identity based on player name.
- Host + client metadata snapshots persisted locally for reconnect fallback.
- Android touch controls:
  - tap cell: preview/place cursor + rotate preview on repeated tap
  - long-press preview: submit tile placement
  - swipe: pan board
  - pinch: zoom board

## Resource Migration

The app carries local copies under `app/src/main/assets/`:

- `data/carcassonne_base_A-X.json`
- `data/carcassonne_base_A-X_areas.json`
- `data/everrides.json`
- `images/tile_*.png`

This keeps the Android project independent from the web template runtime.

## Build Prerequisites

- Java 17
- Android SDK (command-line tools + platform tools + platform 34)

Set in shell:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## Build

From `android/`:

```bash
./gradlew --version
./gradlew :app:assembleDebug --no-daemon --stacktrace
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

Prepared share artifact:

- `dist/carcassonne-lan-debug.apk`
- `dist/carcassonne-lan-debug.apk.sha256`

## Install / Run for Testing

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.carcassonne.lan/.MainActivity
```

If no device is connected:

```bash
emulator -list-avds
emulator -avd <AVD_NAME>
```

## Unit Tests Included

- `NameGeneratorTest`:
  - verifies numeric-suffix behavior.
- `CarcassonneEngineTest`:
  - verifies placement legality and city scoring formula.
- `HostGameManagerTest`:
  - verifies reconnect-by-name token refresh and player-slot retention.
