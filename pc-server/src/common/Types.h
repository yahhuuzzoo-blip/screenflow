#pragma once

#include <string>
#include <cstdint>
#include <vector>
#include <memory>

namespace ScreenFlow {

// Common types used across the application

struct VideoFrame {
    std::vector<uint8_t> data;
    int width;
    int height;
    int64_t timestamp;
    bool isKeyFrame;
};

struct InputEvent {
    enum Type {
        MOUSE_DOWN,
        MOUSE_UP,
        MOUSE_MOVE,
        MOUSE_WHEEL,
        KEY_DOWN,
        KEY_UP,
        TOUCH_DOWN,
        TOUCH_UP,
        TOUCH_MOVE
    };

    Type type;
    int x;
    int y;
    int button;        // For mouse: 1=left, 2=right, 3=middle; For keys: key code
    int modifiers;     // Shift, Ctrl, Alt flags
    int64_t timestamp;
};

struct ConnectionInfo {
    std::string deviceId;
    std::string deviceName;
    std::string ipAddress;
    bool isConnected;
    int64_t lastPing;
};

struct PairingInfo {
    std::string sessionId;
    std::string qrCodeData;
    std::string manualCode;
    std::string encryptionKey;
    int64_t expiryTime;
};

enum class VideoQuality {
    LOW,      // 480p 15fps
    MEDIUM,   // 720p 30fps
    HIGH,     // 1080p 60fps
    AUTO      // Adaptive based on network
};

enum class ConnectionType {
    WEBSOCKET_RELAY,
    WEBRTC_P2P,
    UNKNOWN
};

// Network message types
enum class MessageType {
    PAIRING_REQUEST,
    PAIRING_RESPONSE,
    VIDEO_FRAME,
    INPUT_EVENT,
    CONNECTION_CONTROL,
    QUALITY_CHANGE,
    PING,
    PONG
};

// Security types
enum class EncryptionType {
    AES_256_GCM,
    CHACHA20_POLY1305
};

} // namespace ScreenFlow