#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

export JAVA_HOME="/home/ameya/tools/jdk-17"
export ANDROID_HOME="/home/ameya/tools/android-sdk"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"

AVD_NAME="ReadingAssistant_API34"
APK_PATH="$DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "=========================================================="
echo " Starting Android Emulator ($AVD_NAME)..."
echo " Configuration: Camera (Host Webcam0 + VirtualScene), Bluetooth, 3GB RAM"
echo "=========================================================="

# Check if already running
if adb devices | grep -q "emulator-"; then
    echo "An emulator instance is already running."
else
    # Launch emulator in background with host webcam and KVM acceleration
    nohup emulator -avd "$AVD_NAME" -gpu swiftshader_indirect -no-snapshot-load -no-boot-anim -camera-back webcam0 -camera-front emulated -memory 1536 -netdelay none -netspeed full > /tmp/emulator.log 2>&1 &
    echo "Emulator launched (PID: $!). Waiting for device to boot..."
fi

echo "Waiting for ADB connection..."
adb wait-for-device

echo "Waiting for boot animation to complete..."
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 2
done

echo "Emulator booted successfully!"
echo "Installing Reading Assistant APK..."
if [ -f "$APK_PATH" ]; then
    adb install -r "$APK_PATH"
    echo "Starting Reading Assistant..."
    adb shell am start -n com.readingassistant/.ui.MainActivity
    echo "Application running on emulator!"
else
    echo "Warning: APK not found at $APK_PATH. Run ./gradlew assembleDebug first."
fi
