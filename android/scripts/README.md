# Dual Emulator Scripts

This folder contains helper scripts to run two Android emulators and deploy the app to both.

## Scripts

- `start_first_emulator.sh`
  - Starts host emulator A.
  - Defaults: `Pixel_Lite_API24` on port `5554`.
- `start_second_emulator.sh`
  - Starts client emulator B.
  - Defaults: `Pixel_Lite_API24_B` on port `5556`.
- `build_install_dual_emulators.sh`
  - Waits for both emulators to boot.
  - Sets forwarding on emulator A (`tcp:18473 -> 18473` by default).
  - Builds debug APK.
  - Installs and launches app on both emulators.
- `build_install_phone.sh`
  - Builds debug APK.
  - Cleans previous app data (default on).
  - Installs and launches app on a USB-connected physical phone.
  - Default serial is `96c0a906` (can be overridden).

## One-time setup: create the two AVDs

Run once from any terminal:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Ensure required image exists
sdkmanager "platform-tools" "emulator" "system-images;android-24;google_apis;x86_64"
yes | sdkmanager --licenses

# Recreate clean AVDs (optional but recommended if your old AVDs are unstable)
avdmanager delete avd -n Pixel_Lite_API24 || true
avdmanager delete avd -n Pixel_Lite_API24_B || true

echo no | avdmanager create avd -n Pixel_Lite_API24   -k "system-images;android-24;google_apis;x86_64" -d "pixel_4"
echo no | avdmanager create avd -n Pixel_Lite_API24_B -k "system-images;android-24;google_apis;x86_64" -d "pixel_4"
```

## Run flow (which terminal does what)

Use 3 terminals.

### Terminal 1: start emulator A (host side)

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android
./scripts/start_first_emulator.sh
```

### Terminal 2: start emulator B (client side)

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android
./scripts/start_second_emulator.sh
```

### Terminal 3: build + install + launch on both

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android
./scripts/build_install_dual_emulators.sh
```

## Physical phone install (USB)

Default phone serial (`96c0a906`):

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android
./scripts/build_install_phone.sh
```

Specific serial:

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android
./scripts/build_install_phone.sh 4B201JEBF17591
```

Keep existing app data (skip clear/uninstall):

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android
CLEAN_INSTALL=0 ./scripts/build_install_phone.sh 96c0a906
```

## What to expect after script 3

- Both emulators should have `com.carcassonne.lan/.MainActivity` open.
- Emulator B reaches emulator A through `10.0.2.2:18473`.
- In-app LAN port should stay `18473` unless you changed `LAN_PORT`.

## Useful overrides

All launcher scripts accept custom AVD name and port:

```bash
./scripts/start_first_emulator.sh MyHostAvd 5554
./scripts/start_second_emulator.sh MyClientAvd 5556
```

Deployment script overrides:

```bash
LAN_PORT=18473 ./scripts/build_install_dual_emulators.sh emulator-5554 emulator-5556
```

## Troubleshooting: `Too many open files` / QThreadPipe crash

If you see:

- `QThreadPipe: Unable to create pipe: Too many open files`
- `Fatal: QEventDispatcherUNIXPrivate(): Cannot continue without a thread pipe`

use this sequence:

```bash
cd /home/ber0061/Repositories/carcassonne_min_web_sim/android

# 1) kill stale emulator/adb processes
adb kill-server || true
pkill -f "qemu-system-x86_64.*-avd" || true
pkill -f "/Android/Sdk/emulator/emulator.*-avd" || true

# 2) set higher fd limit in the current shell
ulimit -n 65535
ulimit -n

# 3) start emulators again with scripts
./scripts/start_first_emulator.sh
./scripts/start_second_emulator.sh
```

Notes:

- The start scripts now auto-check FD limits and clean stale emulator instances bound to the same ports.
- If `ulimit -n 65535` fails, your user hard limit is low; set a higher `nofile` limit in system config and reopen terminal.
