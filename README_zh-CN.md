<div align="center">

# MIDIBridge Android

在 Android 设备上运行 MIDIBridge 服务端, 通过网络把 MIDI 信号实时传给其他设备

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

## 快速开始

从 [Releases](https://github.com/MarchSnow-1/midibridge-android/releases) 下载 APK 并安装到 Android 设备

1. 打开 MIDIBridge App
2. 将 MIDI 设备通过 OTG 或其他方式接入你的 Android 设备
3. 在 **MIDI Device** 下拉菜单中选中你的设备
4. 点击 **Start** 启动服务
5. 在 MIDIBridge Client 端使用界面上显示的 IP 地址连接

首次启动默认密码为 **`midiBridge123`**, 请在 Settings 中尽快修改

## 修改密码

在 App 界面编辑 **Password** 字段, 然后点击 **Save & Restart** 即可

改密码后所有已连接的客户端会被踢出, 需用新密码重连

## 配置说明

所有配置通过 App 内 GUI 直接修改, 修改后点击 **Save & Restart** 生效

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| WS Port | `9001` | WebSocket 监听端口（1024–65535） |
| Allowed IPs | *(空)* | IP 白名单, 逗号分隔。留空 = 允许所有 |
| Password | `midiBridge123` | 连接密码（最少 6 字符, bcrypt 哈希存储） |
| MIDI Verbose Log | `开` | 在日志面板中显示每条 MIDI 按键/控制事件 |

### IP 白名单格式

- `192.168.1.1` — 单个 IP
- `192.168.1.1-192.168.1.100` — IP 范围
- `172.16.0.0/16` — CIDR 子网

逗号分隔多个条目, 留空为不限制

### 视频保活

开启 **Video Keep-Alive** 开关后, App 通过 MediaSession 模拟视频播放

可提升进程优先级, 大幅降低 Android 系统在后台查杀服务的概率

## 从源码构建

### 环境要求

| 依赖 | 说明 |
|------|------|
| JDK | 17 |
| Android SDK | API 23–34 |

### 构建

**Android Studio（推荐）**

1. 在 Android Studio 中打开 `MIDIBridge-android/` 文件夹
2. 等待 Gradle 同步完成
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

**命令行**

```bash
# 获取源代码
git clone https://github.com/MarchSnow-1/midibridge-android.git
cd midibridge-android

# 构建 Debug APK（Windows）
gradlew.bat assembleDebug

# 构建 Debug APK（Linux / macOS）
./gradlew assembleDebug
```

## 许可证

[MIT](LICENSE) — 自由使用、修改、分发