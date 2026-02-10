#!/usr/bin/env bash
set -euo pipefail

AVD_NAME="${1:-Pixel_Lite_API24}"
PORT="${2:-5554}"
GPU_MODE="${GPU_MODE:-host}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-xcb}"

echo "Starting emulator '$AVD_NAME' on port $PORT (gpu=$GPU_MODE)..."
exec emulator -avd "$AVD_NAME" \
  -port "$PORT" \
  -gpu "$GPU_MODE" \
  -accel on \
  -no-boot-anim \
  -noaudio \
  -camera-back none \
  -camera-front none \
  -netdelay none \
  -netspeed full \
  -no-snapshot \
  -no-snapshot-save
