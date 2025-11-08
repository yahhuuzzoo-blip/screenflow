# ScreenFlow Android Client

Android application for capturing screen and streaming to PC server over network connection.

## Features

- Real-time screen capture using MediaProjection API
- H.264 hardware encoding for optimal performance
- WebSocket and WebRTC connectivity support
- QR code and manual code pairing
- Input event injection from PC
- Adaptive quality based on network conditions
- Secure encrypted communication

## Requirements

- Android 5.0+ (API level 21+)
- Android 14+ (API level 34+) recommended for latest features
- Camera permission for QR code scanning
- Overlay permission for input injection
- Screen capture permission

## Architecture

The app consists of several main components:

- **MainActivity**: Main UI with service controls and pairing options
- **ScreenCaptureService**: Foreground service handling screen capture
- **ScreenCaptureManager**: MediaProjection API management and H.264 encoding
- **NetworkManager**: WebSocket/WebRTC connectivity to PC server
- **InputInjector**: Receives and processes input events from PC
- **QRCodeActivity**: QR code scanning for PC pairing

## Build Instructions

1. Open the project in Android Studio
2. Ensure you have Android SDK API 34 installed
3. Build the APK using Build > Build Bundle(s) / APK(s) > Build APK(s)

## Permissions Required

- `INTERNET` - Network communication
- `FOREGROUND_SERVICE` - Background screen capture
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` - Android 14+ media projection service
- `SYSTEM_ALERT_WINDOW` - Input event injection
- `CAMERA` - QR code scanning

## Usage

1. Install the APK on your Android device
2. Grant required permissions when prompted
3. Start the ScreenFlow PC server
4. Use QR code scanning or manual code to pair with PC
5. Start screen sharing
6. Control your phone from the PC

## Troubleshooting

### Permission Issues
- Ensure overlay permission is granted for input injection
- Grant screen capture permission when prompted
- Allow camera permission for QR code scanning

### Connection Issues
- Ensure PC and Android device are on the same network for direct connection
- Check firewall settings on PC server
- Verify pairing code is entered correctly

### Performance Issues
- Use lower quality settings on slower networks
- Ensure device is not in power-saving mode
- Check available network bandwidth

## Security

- All communication encrypted with AES-256
- Session-based authentication
- Device-specific encryption keys
- No data stored on third-party servers

## Development

### Key Files
- `app/src/main/java/com/screenflow/MainActivity.kt` - Main UI
- `app/src/main/java/com/screenflow/service/ScreenCaptureService.kt` - Screen capture service
- `app/src/main/java/com/screenflow/capture/ScreenCaptureManager.kt` - Capture management
- `app/src/main/java/com/screenflow/network/NetworkManager.kt` - Network connectivity
- `app/src/main/java/com/screenflow/input/InputInjector.kt` - Input handling

### Dependencies
- OkHttp for WebSocket communication
- WebRTC for peer-to-peer connections
- ZXing for QR code scanning
- MediaProjection API for screen capture
- MediaCodec for H.264 encoding

## License

MIT License