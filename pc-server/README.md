# ScreenFlow PC Server

Desktop application for receiving and displaying Android screen mirroring streams.

## Dependencies

- **SDL2** - Window management and rendering
- **FFmpeg** - H.264 video decoding (libavcodec, libavutil, libswscale)
- **OpenSSL** - Encryption and security
- **CMake** - Build system
- **C++17** - Compiler support

## Building

### Ubuntu/Debian
```bash
sudo apt update
sudo apt install build-essential cmake libsdl2-dev libavcodec-dev libavutil-dev libswscale-dev libssl-dev
```

### macOS
```bash
brew install cmake sdl2 ffmpeg openssl
```

### Windows (with vcpkg)
```cmd
vcpkg install sdl2 ffmpeg openssl
```

### Build Steps
```bash
mkdir build && cd build
cmake ..
make
```

## Usage

1. Run the executable: `./ScreenFlow`
2. A QR code and pairing code will be displayed
3. Use the Android app to scan the QR code or enter the pairing code
4. The phone screen will appear in the window after successful connection

## Controls

- **Mouse**: Click and drag to control the phone
- **Keyboard**: Type directly into the phone
- **Right-click**: Long press gesture
- **Mouse wheel**: Scroll gesture
- **F11**: Toggle fullscreen
- **Esc**: Disconnect

## Configuration

The application creates a configuration file in:
- Windows: `%APPDATA%/ScreenFlow/config.json`
- macOS: `~/Library/Application Support/ScreenFlow/config.json`
- Linux: `~/.config/ScreenFlow/config.json`

### Configuration Options
```json
{
    "server": {
        "port": 8080,
        "auto_start": true
    },
    "video": {
        "default_quality": "medium",
        "hardware_acceleration": true
    },
    "security": {
        "require_pairing": true,
        "trusted_devices": []
    }
}
```