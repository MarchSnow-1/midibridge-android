<div align="center">

# MIDIBridge Android

Turn your Android phone into a portable MIDI WebSocket server — plug in a USB MIDI keyboard and stream to any DAW over the network in real time.

<!-- Badges -->

[![Platform](https://img.shields.io/badge/Platform-Android-blue?style=for-the-badge)](https://github.com/MarchSnow-1/midibridge-android)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple?style=for-the-badge)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-orange?style=for-the-badge)](LICENSE)
<br>
[![GitHub Release](https://img.shields.io/github/v/release/MarchSnow-1/midibridge-android?style=for-the-badge)](https://github.com/MarchSnow-1/midibridge-android/releases)
[![GitHub Repo stars](https://img.shields.io/github/stars/MarchSnow-1/midibridge-android?style=for-the-badge)](https://github.com/MarchSnow-1/midibridge-android)
[![GitHub Last Commit](https://img.shields.io/github/last-commit/MarchSnow-1/midibridge-android?style=for-the-badge)](https://github.com/MarchSnow-1/midibridge-android)
[![Total Download](https://img.shields.io/github/downloads/MarchSnow-1/midibridge-android/total?style=for-the-badge)](https://github.com/MarchSnow-1/midibridge-android/releases)

[**English**](README.md) | [**简体中文**](README_zh-CN.md)

</div>

## Quick Start

Download the APK from [Releases](https://github.com/MarchSnow-1/midibridge-android/releases) and install it on your Android device.

1. Open MIDIBridge on your phone
2. Plug in a USB MIDI keyboard via OTG adapter
3. Select your device from the **MIDI Device** dropdown
4. Tap **Start** — the WebSocket server is now running
5. Connect from a MIDIBridge Client using the IP shown on screen

On first launch, the default password is **`midiBridge123`**. Change it immediately in Settings.

## Change Password

Open the app, edit the **Password** field in Settings, then tap **Save & Restart**.

All connected clients are kicked after a password change and must reconnect with the new password.

## Configuration

All settings are managed through the in-app GUI — no config file editing needed. Changes take effect after tapping **Save & Restart**.

| Setting | Default | Description |
|---------|---------|-------------|
| WS Port | `9001` | WebSocket listen port (1024–65535) |
| Allowed IPs | *(empty)* | IP allowlist, comma-separated. Empty = allow all |
| Password | `midiBridge123` | Connection password (min 6 characters, bcrypt-hashed) |
| MIDI Verbose Log | `On` | Log every MIDI note/CC event in the log panel |

### IP Allowlist Formats

- `192.168.1.1` — single IP
- `192.168.1.1-192.168.1.100` — IP range
- `172.16.0.0/16` — CIDR

Multiple entries separated by commas. Leave empty to allow all.

### Video Keep-Alive

Enable the **Video Keep-Alive** toggle to simulate media playback via MediaSession. This raises the service's process priority, making Android much less likely to kill it in the background.

## WebSocket Protocol

Fully compatible with MIDIBridge-Server (Go) and MIDIBridge-Client (Go).

### Client → Server

```json
{"type":"auth","password":"midiBridge123"}
{"type":"ping"}
```

### Server → Client

```json
{"type":"auth_ok"}
{"type":"auth_fail","reason":"Incorrect password"}
{"type":"midi","data":{"t":0.05,"m":"kGRAAQ=="}}
{"type":"kicked","reason":"server_shutdown"}
{"type":"pong"}
```

- `t` — delta seconds from the previous MIDI message
- `m` — raw MIDI bytes as standard Base64 (no line wrapping)
- Kick reasons: `auth_timeout` / `server_shutdown` / `password_changed`
- Authentication timeout: 5 seconds

## Build from Source

### Requirements

| Dependency | Notes |
|------------|-------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 23–34 (bundled with Android Studio) |

> No Go, gomobile, NDK, or CGo required — this is a pure Kotlin project.

### Build

**Android Studio (recommended)**

1. Open `MIDIBridge-android/` in Android Studio
2. Wait for Gradle sync to complete
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. Find the APK at `app/build/outputs/apk/debug/app-debug.apk`

**Command line**

```bash
# Clone the repo
git clone https://github.com/MarchSnow-1/midibridge-android.git
cd midibridge-android

# Build Debug APK (Windows)
gradlew.bat assembleDebug

# Build Debug APK (Linux / macOS)
./gradlew assembleDebug
```

### Install to device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

[MIT](LICENSE) — Use, modify, and distribute freely.
