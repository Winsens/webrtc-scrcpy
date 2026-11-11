# WebRTC-Scrcpy 🚀

> **Next-generation ultra-low-latency Android screen mirroring and remote control running completely on-device**  
> Powered by native WebRTC audio/video streams and a binary `RTCDataChannel` control protocol—zero PC middleware required, click-to-connect over LAN or public networks via H5.

---

## 📖 Architecture Overview

Conventional browser-based Android remote control approaches often rely on an external PC/ADB relay and WebSocket (TCP) streaming, which introduces inherent limitations:
1. **External Relay Dependency**: The phone must stay connected via USB/ADB to a host machine running a relay process to read and repackage streams.
2. **TCP Head-of-Line Blocking**: Streaming video/audio over TCP triggers head-of-line retransmissions during packet loss or Wi-Fi jitter, causing frame queueing and latency spikes.
3. **No Standalone On-Device Hosting**: The device cannot independently serve both the web client and low-latency media streams on its own.

**WebRTC-Scrcpy** eliminates these bottlenecks by embedding the WebRTC media engine and signaling server directly onto the Android device, allowing modern browsers to establish a direct peer-to-peer WebRTC session with the phone.

### Architectural Comparison

| Feature | Traditional WebSocket Relay | WebRTC-Scrcpy |
| :--- | :--- | :--- |
| **Topology** | Phone -> USB/ADB -> Host Relay -> WebSocket -> Browser | **Phone (Embedded Server) <-> Direct Browser H5** |
| **Transport** | WebSocket (TCP, prone to head-of-line blocking) | **WebRTC (UDP / SRTP / SCTP with adaptive congestion control)** |
| **Control Channel** | Text / JSON over WebSocket | **Compact binary protocol over `RTCDataChannel`** |
| **End-to-End Latency** | Typically 150ms - 300ms | **LAN 20ms - 50ms, WAN 50ms - 100ms** |
| **Audio Support** | Requires external audio routing | **Native `AudioPlaybackCapture` system audio (Android 10+)** |
| **Deployment** | Host relay + Web server | **Standalone Android APK / AOSP headless daemon** |

---

## 🛠️ System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Android Phone / Cloud Phone               │
│                                                             │
│  [ Screen Capture (SurfaceControl / MediaProjection) ]      │
│                       │                                     │
│                       ▼                                     │
│  [ Low-Latency Encoder (MediaCodec / VP8) ]                 │
│                       │                                     │
│                       ▼                                     │
│  [ WebRTC PeerConnection ] ◄── (RTCP PLI Keyframe Loop)     │
│       │               │                                     │
│  (A/V RTP)       (SCTP DataChannel)                         │
│       │               ▲                                     │
│       │               │ (Binary Touch / Key / Scroll)       │
│       │               ▼                                     │
│       │     [ Input Injector (InputManager / Accessibility) ]│
│       │                                                     │
│  [ Embedded NanoHTTPD Web Server + WS Signaling (8080) ]    │
└───────┼───────────────┼─────────────────────────────────────┘
        │ UDP (SRTP)    │ SCTP (DataChannel)
        ▼               ▼
┌─────────────────────────────────────────────────────────────┐
│                   Web Browser Client (H5)                   │
│                                                             │
│  • Hardware-accelerated <video> rendering (low jitter buf)  │
│  • Normalized touch & mouse mapping (letterbox-aware)       │
│  • Virtual Android nav bar (Back, Home, Recents, Vol, Power)│
│  • Real-time stats overlay (FPS, RTT, Packet Loss, Bitrate) │
└─────────────────────────────────────────────────────────────┘
```

---

## ✨ Key Features

- **All-in-One On-Device Hosting**: Includes a built-in lightweight HTTP/WebSocket server on the phone. Open `http://<PHONE_IP>:8080` from any PC, phone, or tablet browser on the same LAN to control immediately with zero external dependencies.
- **Ultra-Low End-to-End Latency**: Built on native WebRTC pipelines with measured LAN latency between 20ms and 50ms.
- **High-Speed `RTCDataChannel` Control**: Touch and keyboard events are packed into a compact custom binary protocol (only 10-13 bytes per packet) for responsive gesture control.
- **Dual-Driver Input Injection**:
  - **AOSP / Cloud Phone / Root / ADB Mode**: Injects via `InputManager.getInstance().injectInputEvent` reflection with system-level responsiveness for multi-touch gestures and key mapping.
  - **Rootless Standard App Mode**: Uses `AccessibilityService` real-time streaming gesture dispatch without root or ADB.
- **PLI Keyframe Recovery Loop**: Automatically responds to WebRTC PLI/FIR feedback when packet loss occurs to request an immediate IDR frame and prevent prolonged visual corruption.
- **Public WAN NAT Traversal Support**: Includes a lightweight Node.js signaling relay template and supports external STUN/TURN servers for cross-network access.

---

## 📦 Project Structure

```text
webrtc-scrcpy/
├── android/                          # Android project modules
│   ├── app/                          # Foreground app shell (UI, foreground service, accessibility)
│   │   └── src/main/java/com/webrtc/scrcpy/app/
│   │       ├── MainActivity.kt       # Control dashboard & local IP display
│   │       ├── RemoteControlService.kt # Persistent foreground capture & signaling service
│   │       └── ScrcpyAccessibilityService.kt # Rootless accessibility input injector
│   └── core/                         # Core remote control library (usable standalone)
│       └── src/main/java/com/webrtc/scrcpy/core/
│           ├── audio/                # Android 10+ internal audio capture (AudioPlaybackCapture)
│           ├── capture/              # Screen capture (MediaProjection & SurfaceControl)
│           ├── codec/                # MediaCodec async hardware encoding & bitrate control
│           ├── control/              # DataChannel message decoding & routing
│           ├── input/                # System-level Native & Accessibility input injectors
│           ├── protocol/             # Binary control protocol definitions
│           ├── server/               # Embedded Web/WS server & Daemon entry point
│           └── webrtc/               # WebRTC PeerConnection lifecycle management
├── web/                              # H5 frontend client (zero NPM dependencies, pure vanilla JS)
│   ├── css/style.css                 # Dark console UI styles
│   ├── js/
│   │   ├── protocol.js               # Binary control protocol encoder
│   │   ├── webrtc.js                 # WebRTC client connection & stats monitor
│   │   └── app.js                    # Touch/mouse coordinate mapping, keyboard & nav controls
│   └── index.html                    # Remote control web entry page
├── signaling-server/                 # (Optional) Public relay signaling server (Node.js)
├── scripts/
│   └── run_daemon.sh                 # ADB / Cloud Phone headless app_process runner
├── build.gradle                      # Root build script
├── settings.gradle                   # Gradle project settings
├── LICENSE                           # Apache 2.0 License
└── README.md
```

---

## 🚀 Quick Start

### 1. Build & Install the Android App

Open the root directory in Android Studio, or build from the command line with Gradle:

```bash
# Build Release APK (or assembleDebug for debug builds)
./gradlew :android:app:assembleRelease
```

Install the generated APK onto your phone:
```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

### 2. Direct LAN Connection

1. Open **WebRTC-Scrcpy** on your Android device.
2. Tap **Start Remote Service** and grant the system screen capture permission prompt.
3. The app will display your device's LAN URL, for example:
   ```text
   http://192.168.1.108:8080
   ```
4. Open that URL in a browser on any computer or tablet on the same Wi-Fi/LAN to view the screen and control it with mouse, touch, and keyboard!

---

## ☁️ AOSP Cloud Phone / ADB Shell Headless Mode (Daemon Mode)

On cloud phones (e.g., Redroid, Genymotion) or devices with ADB/Root access, you can run headless as a background daemon without installing the UI app:

```bash
# Grant execute permission and start daemon
./scripts/run_daemon.sh 8080
```

Running with Shell (UID 2000) privileges provides key benefits:
- Silent startup with zero permission popups;
- Mirrors the display via `SurfaceControl` without consuming `VirtualDisplay` resources;
- Injects input directly via `InputManager.injectInputEvent` without requiring Accessibility Service.

---

## 📡 Binary Control Protocol

To eliminate JSON serialization overhead and minimize packet size, `RTCDataChannel` uses a compact fixed binary layout:

### 1. Touch Event (`Type = 0x01`, 13 bytes)
- `Byte 0`: `0x01` (Type)
- `Byte 1`: Action (`0` DOWN, `1` UP, `2` MOVE)
- `Byte 2`: Pointer ID (`0 - 9`)
- `Byte 3 - 6`: Normalized X coordinate (fixed-point unsigned int `0 - 10000`)
- `Byte 7 - 10`: Normalized Y coordinate (fixed-point unsigned int `0 - 10000`)
- `Byte 11 - 12`: Pressure (`0 - 1000`)

### 2. Key Event (`Type = 0x02`, 10 bytes)
- `Byte 0`: `0x02` (Type)
- `Byte 1`: Action (`0` DOWN, `1` UP)
- `Byte 2 - 5`: Android KeyCode (32-bit int, e.g. `66` for Enter, `4` for Back)
- `Byte 6 - 9`: MetaState flags (Ctrl / Shift / Alt)

### 3. Quick Command (`Type = 0x05`, 2 bytes)
- `Byte 0`: `0x05` (Type)
- `Byte 1`: Command ID (`1`: Back, `2`: Home, `3`: Recents, `4`: Power, `5`: Volume+, `6`: Volume-, `7`: Rotate Screen)

---

## 🌐 Public Network Traversal (Optional)

When the phone and browser are not on the same LAN (e.g., the phone is on 4G/5G cellular data), deploy the signaling relay server:

```bash
cd signaling-server
npm install
node server.js
```

Pair it with a STUN/TURN server (such as Coturn) and configure the room ID and relay URL on both ends for cross-network connections.

---

## 📄 License

This project is open-sourced under the [Apache License 2.0](LICENSE).

### Third-Party Acknowledgments

- **Google WebRTC** (`webrtc.jar` & `libjingle_peerconnection_so.so`): Licensed under the BSD 3-Clause License.
- **NanoHTTPD** (`org.nanohttpd:nanohttpd`, `org.nanohttpd:nanohttpd-websocket`): Licensed under the BSD 3-Clause License.

