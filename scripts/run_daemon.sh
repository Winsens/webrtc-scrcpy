#!/bin/bash
# ==============================================================================
# webrtc-scrcpy ADB / AOSP Cloud Phone Headless Runner Script (Shell UID 2000)
# ==============================================================================
set -e

PORT=${1:-8080}
SERIAL=${2:-""}

ADB_CMD="adb"
if [ -n "$SERIAL" ]; then
    ADB_CMD="adb -s $SERIAL"
fi

echo "[*] Preparing to launch webrtc-scrcpy daemon on target Android device..."
DEVICE_JAR="/data/local/tmp/webrtc-scrcpy.jar"

echo "[*] Forwarding TCP port $PORT..."
$ADB_CMD forward tcp:$PORT tcp:$PORT

echo "[*] Starting on-device WebRTC service process (app_process)..."
echo "    (Make sure $DEVICE_JAR or the installed APK classes are present on device)"
$ADB_CMD shell "CLASSPATH=$DEVICE_JAR app_process / com.webrtc.scrcpy.core.server.DaemonMain --port=$PORT"

