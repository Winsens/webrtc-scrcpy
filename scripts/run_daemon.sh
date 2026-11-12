#!/bin/bash
# ==============================================================================
# webrtc-scrcpy ADB / AOSP Cloud Phone Headless Runner Script (Shell UID 2000)
#
# Usage: ./scripts/run_daemon.sh [PORT] [SERIAL]
#   APK=<path>    APK to run (default: the debug build output)
#   TOKEN=<token> fixed access token, to keep the same URL across runs (default: random)
#   ICE_SERVER=<url>[|user|pass]  STUN/TURN server for both device and browser, e.g. when
#                 the browser cannot reach the device's IP directly (see README)
# ==============================================================================
set -e

PORT=${1:-8080}
SERIAL=${2:-""}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK=${APK:-"$SCRIPT_DIR/../android/app/build/outputs/apk/debug/app-debug.apk"}

ADB_CMD="adb"
if [ -n "$SERIAL" ]; then
    ADB_CMD="adb -s $SERIAL"
fi

DEVICE_JAR="/data/local/tmp/webrtc-scrcpy.jar"
DEVICE_LIB="/data/local/tmp/libjingle_peerconnection_so.so"

if [ ! -f "$APK" ]; then
    echo "[-] APK not found: $APK"
    echo "    Build it first: ./gradlew :android:app:assembleDebug (or set APK=<path>)"
    exit 1
fi

echo "[*] Preparing to launch webrtc-scrcpy daemon on target Android device..."

# The APK is used as the daemon's CLASSPATH: it holds the dex code and the web/ assets
echo "[*] Pushing $APK -> $DEVICE_JAR"
$ADB_CMD push "$APK" "$DEVICE_JAR" >/dev/null

# app_process does not extract native libraries, so push the WebRTC library for the device ABI
ABI=$($ADB_CMD shell getprop ro.product.cpu.abi | tr -d '\r')
TMP_LIB="$(mktemp -t libjingle.XXXXXX)"
trap 'rm -f "$TMP_LIB"' EXIT
if ! unzip -p "$APK" "lib/$ABI/libjingle_peerconnection_so.so" > "$TMP_LIB" || [ ! -s "$TMP_LIB" ]; then
    echo "[-] The APK has no libjingle_peerconnection_so.so for device ABI '$ABI'"
    exit 1
fi
echo "[*] Pushing WebRTC native library ($ABI) -> $DEVICE_LIB"
$ADB_CMD push "$TMP_LIB" "$DEVICE_LIB" >/dev/null

echo "[*] Forwarding TCP port $PORT..."
$ADB_CMD forward tcp:$PORT tcp:$PORT

EXTRA_ARGS=""
if [ -n "$TOKEN" ]; then
    EXTRA_ARGS="--token=$TOKEN"
fi
if [ -n "$ICE_SERVER" ]; then
    EXTRA_ARGS="$EXTRA_ARGS '--ice-server=$ICE_SERVER'"
fi

echo "[*] Starting on-device WebRTC service process (app_process)..."
echo "    Open the URL printed below; it includes the access token."
$ADB_CMD shell "CLASSPATH=$DEVICE_JAR app_process / com.webrtc.scrcpy.core.server.DaemonMain --port=$PORT $EXTRA_ARGS"
