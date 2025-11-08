#pragma once

#include <string>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <random>
#include <chrono>

#include "../common/Types.h"

namespace ScreenFlow {

class PairingManager {
public:
    PairingManager();
    ~PairingManager();

    bool initialize();
    void cleanup();

    // Pairing process management
    bool startPairing();
    void stopPairing();
    bool isPairingActive() const;

    // Generate pairing information
    PairingInfo generatePairingInfo();
    std::string generateQRCodeData();
    std::string generateManualCode();
    std::string generateEmailLink();

    // Pairing validation
    bool validatePairingRequest(const std::string& requestData, std::string& deviceId);
    bool completePairing(const std::string& deviceId, const std::string& response);
    bool isDeviceTrusted(const std::string& deviceId) const;

    // Trusted device management
    void addTrustedDevice(const std::string& deviceId, const std::string& deviceName);
    void removeTrustedDevice(const std::string& deviceId);
    std::vector<std::pair<std::string, std::string>> getTrustedDevices() const;

    // Session management
    std::string getCurrentSessionId() const;
    bool isSessionValid(const std::string& sessionId) const;
    void invalidateCurrentSession();

    // QR code generation
    std::vector<uint8_t> generateQRCodeImage(const std::string& data, int size = 300);
    bool saveQRCodeToFile(const std::string& data, const std::string& filePath, int size = 300);

    // Security
    std::string generateEncryptionKey();
    bool validateEncryptionKey(const std::string& key) const;

private:
    // Random generation
    std::string generateRandomString(int length);
    std::string generateSessionId();
    std::string generateDeviceId();

    // QR code data encoding
    std::string encodePairingData(const std::string& sessionId, const std::string& serverInfo,
                                 const std::string& encryptionKey);
    bool decodePairingData(const std::string& encodedData, std::string& sessionId,
                          std::string& serverInfo, std::string& encryptionKey);

    // Manual code generation
    std::string generateAlphanumericCode(int length);
    bool validateManualCode(const std::string& code) const;

    // Email link generation
    std::string generateSecureLink(const std::string& sessionId, const std::string& token);
    std::string generateLinkToken();

    // Time utilities
    int64_t getCurrentTimestamp() const;
    bool isTimestampValid(int64_t timestamp, int validitySeconds = 300) const;

    // Configuration
    std::string getLocalIPAddress();
    std::string getServerInfo();

    // Persistence
    bool loadTrustedDevices();
    bool saveTrustedDevices();
    std::string getTrustedDevicesFilePath() const;

    // Simple QR code generation (placeholder - would use a real QR code library)
    std::vector<uint8_t> generateSimpleQRCode(const std::string& data, int size);

    // Random number generator
    std::mt19937 rng_;
    std::uniform_int_distribution<int> dist_;

    // Pairing state
    bool isPairingActive_;
    std::string currentSessionId_;
    std::string currentEncryptionKey_;
    int64_t sessionStartTime_;
    std::string serverIP_;
    int serverPort_;

    // Trusted devices storage
    std::unordered_map<std::string, std::string> trustedDevices_; // deviceId -> deviceName
    mutable std::mutex trustedDevicesMutex_;

    // Pending pairings
    struct PendingPairing {
        std::string deviceId;
        std::string deviceName;
        int64_t requestTime;
        bool isValid;
    };
    std::unordered_map<std::string, PendingPairing> pendingPairings_;
    mutable std::mutex pendingPairingsMutex_;

    // Configuration constants
    static constexpr int SESSION_TIMEOUT_SECONDS = 300;        // 5 minutes
    static constexpr int PAIRING_CODE_LENGTH = 8;              // 8 characters
    static constexpr int SESSION_ID_LENGTH = 32;               // 32 hex characters
    static constexpr int ENCRYPTION_KEY_LENGTH = 64;           // 64 hex characters
    static constexpr int MANUAL_CODE_LENGTH = 6;               // 6 alphanumeric characters
    static constexpr int LINK_TOKEN_LENGTH = 32;               // 32 hex characters
    static constexpr int DEFAULT_SERVER_PORT = 8080;
    static constexpr int QR_CODE_SIZE = 300;

    // QR code format
    struct QRCodeData {
        std::string sessionId;
        std::string serverIP;
        int serverPort;
        std::string encryptionKey;
        int64_t timestamp;
        int version; // Protocol version
    };

    static constexpr int QR_PROTOCOL_VERSION = 1;

    // Character sets for code generation
    static const std::string ALPHANUMERIC_CHARS;
    static const std::string HEX_CHARS;
};

} // namespace ScreenFlow