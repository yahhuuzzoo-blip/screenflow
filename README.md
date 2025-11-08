# ScreenFlow - Remote Phone Screen Mirroring

A scrcpy-like screen mirroring application with remote internet connectivity.

## Overview

ScreenFlow allows you to mirror and control your Android phone from a PC anywhere in the world via internet connection. It consists of three main components:

- **PC Server**: Desktop application that displays phone screen and forwards input
- **Android Client**: Phone app that captures screen and streams to PC
- **Relay Server**: Optional WebSocket relay for remote connections

## Features

- Real-time screen mirroring with adaptive quality
- Remote control via mouse and keyboard
- Secure pairing via QR code, manual code, or email link
- Works over WiFi and remote internet connections
- Automatic reconnection on network interruptions
- H.264 hardware encoding for optimal performance

## Quick Start

### PC Server
```bash
cd pc-server
mkdir build && cd build
cmake ..
make
./ScreenFlow
```

### Android Client
1. Install the APK on your Android device
2. Grant necessary permissions
3. Scan QR code from PC server or enter manual code
4. Start mirroring

## Network Architecture

ScreenFlow uses a hybrid approach:
- **Primary**: Direct WebSocket/WebRTC connection for lowest latency
- **Fallback**: Relay server for connections behind NAT/firewalls

## Requirements

- PC: Windows 10+, macOS 10.14+, or Linux (Ubuntu 18.04+)
- Android: API level 21+ (API 34+ recommended for latest features)
- Network: WiFi or mobile internet connection

## Security

- AES-256 encryption for all data transmission
- SHA-256 based authentication
- ECDH key exchange for secure session establishment
- Device-specific session tokens

## License

MIT License
