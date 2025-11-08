#include "PairingManager.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <random>
#include <openssl/rand.h>
#include <ifaddrs.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

namespace ScreenFlow {

const std::string PairingManager::ALPHANUMERIC_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

const std::string PairingManager::HEX_CHARS =
    "0123456789ABCDEF";

PairingManager::PairingManager() :
    isPairingActive_(false),
    sessionStartTime_(0),
    serverPort_(DEFAULT_SERVER_PORT),
    rng_(std::chrono::steady_clock::now().time_since_epoch().count()),
    dist_(0, 255) {

    // Initialize OpenSSL random number generator
    RAND_poll();
}

PairingManager::~PairingManager() {
    cleanup();
}

bool PairingManager::initialize() {
    std::cout << "Initializing pairing manager..." << std::endl;

    // Get local IP address
    serverIP_ = getLocalIPAddress();
    if (serverIP_.empty()) {
        serverIP_ = "127.0.0.1"; // Fallback
        std::cout << "Using fallback IP address: " << serverIP_ << std::endl;
    } else {
        std::cout << "Detected local IP: " << serverIP_ << std::endl;
    }

    // Load trusted devices from persistent storage
    if (!loadTrustedDevices()) {
        std::cout << "No existing trusted devices found" << std::endl;
    } else {
        std::cout << "Loaded " << trustedDevices_.size() << " trusted devices" << std::endl;
    }

    std::cout << "Pairing manager initialized successfully" << std::endl;
    return true;
}

void PairingManager::cleanup() {
    stopPairing();
    saveTrustedDevices();
}

bool PairingManager::startPairing() {
    std::cout << "Starting pairing process..." << std::endl;

    // Generate new session
    currentSessionId_ = generateSessionId();
    currentEncryptionKey_ = generateEncryptionKey();
    sessionStartTime_ = getCurrentTimestamp();
    isPairingActive_ = true;

    // Clear any expired pending pairings
    cleanupExpiredPairings();

    std::cout << "Pairing started with session ID: " << currentSessionId_ << std::endl;
    std::cout << "Server address: " << serverIP_ << ":" << serverPort_ << std::endl;
    std::cout << "Manual pairing code will be generated on request" << std::endl;

    return true;
}

void PairingManager::stopPairing() {
    if (isPairingActive_) {
        isPairingActive_ = false;
        currentSessionId_.clear();
        currentEncryptionKey_.clear();

        // Clear pending pairings
        {
            std::lock_guard<std::mutex> lock(pendingPairingsMutex_);
            pendingPairings_.clear();
        }

        std::cout << "Pairing process stopped" << std::endl;
    }
}

bool PairingManager::isPairingActive() const {
    return isPairingActive_ && isTimestampValid(sessionStartTime_, SESSION_TIMEOUT_SECONDS);
}

PairingInfo PairingManager::generatePairingInfo() {
    PairingInfo info;

    if (!isPairingActive()) {
        startPairing();
    }

    info.sessionId = currentSessionId_;
    info.qrCodeData = generateQRCodeData();
    info.manualCode = generateManualCode();
    info.encryptionKey = currentEncryptionKey_;
    info.expiryTime = sessionStartTime_ + SESSION_TIMEOUT_SECONDS;

    std::cout << "Generated pairing information:" << std::endl;
    std::cout << "  Session ID: " << info.sessionId << std::endl;
    std::cout << "  Manual code: " << info.manualCode << std::endl;
    std::cout << "  Expires in: " << (info.expiryTime - getCurrentTimestamp()) << " seconds" << std::endl;

    return info;
}

std::string PairingManager::generateQRCodeData() {
    if (!isPairingActive()) {
        return "";
    }

    QRCodeData qrData;
    qrData.sessionId = currentSessionId_;
    qrData.serverIP = serverIP_;
    qrData.serverPort = serverPort_;
    qrData.encryptionKey = currentEncryptionKey_;
    qrData.timestamp = getCurrentTimestamp();
    qrData.version = QR_PROTOCOL_VERSION;

    // Encode QR data as JSON string
    std::ostringstream oss;
    oss << std::boolalpha;
    oss << "{"
        << "\"v\":" << qrData.version << ","
        << "\"sid\":\"" << qrData.sessionId << "\","
        << "\"ip\":\"" << qrData.serverIP << "\","
        << "\"p\":" << qrData.serverPort << ","
        << "\"key\":\"" << qrData.encryptionKey << "\","
        << "\"ts\":" << qrData.timestamp
        << "}";

    return oss.str();
}

std::string PairingManager::generateManualCode() {
    if (!isPairingActive()) {
        return "";
    }

    return generateAlphanumericCode(MANUAL_CODE_LENGTH);
}

std::string PairingManager::generateEmailLink() {
    if (!isPairingActive()) {
        return "";
    }

    std::string token = generateLinkToken();
    std::string secureLink = generateSecureLink(currentSessionId_, token);

    return "https://screenflow.pair/" + secureLink;
}

bool PairingManager::validatePairingRequest(const std::string& requestData, std::string& deviceId) {
    try {
        // Parse JSON request
        // In a real implementation, you would use a JSON library
        std::cout << "Validating pairing request..." << std::endl;

        // Check if pairing session is active
        if (!isPairingActive()) {
            std::cout << "Pairing session not active" << std::endl;
            return false;
        }

        // Extract device ID and other info from request
        // For now, generate a temporary device ID
        deviceId = generateDeviceId();

        // Add to pending pairings
        {
            std::lock_guard<std::mutex> lock(pendingPairingsMutex_);
            PendingPairing pairing;
            pairing.deviceId = deviceId;
            pairing.deviceName = "Unknown Device"; // Would be extracted from request
            pairing.requestTime = getCurrentTimestamp();
            pairing.isValid = true;
            pendingPairings_[deviceId] = pairing;
        }

        std::cout << "Pairing request validated for device: " << deviceId << std::endl;
        return true;
    } catch (const std::exception& e) {
        std::cerr << "Pairing validation error: " << e.what() << std::endl;
        return false;
    }
}

bool PairingManager::completePairing(const std::string& deviceId, const std::string& response) {
    try {
        std::cout << "Completing pairing for device: " << deviceId << std::endl;

        // Check if device is in pending pairings
        {
            std::lock_guard<std::mutex> lock(pendingPairingsMutex_);
            auto it = pendingPairings_.find(deviceId);
            if (it == pendingPairings_.end() || !it->second.isValid) {
                std::cout << "Device not found in pending pairings or invalid" << std::endl;
                return false;
            }

            // Check if pairing is still within time limit
            if (!isTimestampValid(it->second.requestTime, SESSION_TIMEOUT_SECONDS)) {
                std::cout << "Pairing request expired" << std::endl;
                pendingPairings_.erase(it);
                return false;
            }

            // Add to trusted devices
            addTrustedDevice(deviceId, it->second.deviceName);

            // Remove from pending pairings
            pendingPairings_.erase(it);
        }

        std::cout << "Pairing completed successfully for device: " << deviceId << std::endl;
        return true;
    } catch (const std::exception& e) {
        std::cerr << "Pairing completion error: " << e.what() << std::endl;
        return false;
    }
}

bool PairingManager::isDeviceTrusted(const std::string& deviceId) const {
    std::lock_guard<std::mutex> lock(trustedDevicesMutex_);
    return trustedDevices_.find(deviceId) != trustedDevices_.end();
}

void PairingManager::addTrustedDevice(const std::string& deviceId, const std::string& deviceName) {
    std::lock_guard<std::mutex> lock(trustedDevicesMutex_);
    trustedDevices_[deviceId] = deviceName;
    saveTrustedDevices();
    std::cout << "Added trusted device: " << deviceName << " (" << deviceId << ")" << std::endl;
}

void PairingManager::removeTrustedDevice(const std::string& deviceId) {
    std::lock_guard<std::mutex> lock(trustedDevicesMutex_);
    auto it = trustedDevices_.find(deviceId);
    if (it != trustedDevices_.end()) {
        std::cout << "Removed trusted device: " << it->second << " (" << deviceId << ")" << std::endl;
        trustedDevices_.erase(it);
        saveTrustedDevices();
    }
}

std::vector<std::pair<std::string, std::string>> PairingManager::getTrustedDevices() const {
    std::lock_guard<std::mutex> lock(trustedDevicesMutex_);
    std::vector<std::pair<std::string, std::string>> devices;
    for (const auto& pair : trustedDevices_) {
        devices.emplace_back(pair.first, pair.second);
    }
    return devices;
}

std::string PairingManager::getCurrentSessionId() const {
    return currentSessionId_;
}

bool PairingManager::isSessionValid(const std::string& sessionId) const {
    return isPairingActive() && sessionId == currentSessionId_;
}

void PairingManager::invalidateCurrentSession() {
    stopPairing();
    std::cout << "Current session invalidated" << std::endl;
}

std::vector<uint8_t> PairingManager::generateQRCodeImage(const std::string& data, int size) {
    // Simplified QR code generation - in a real implementation, you would use
    // a library like qrcodegen, libqrencode, or similar
    return generateSimpleQRCode(data, size);
}

bool PairingManager::saveQRCodeToFile(const std::string& data, const std::string& filePath, int size) {
    try {
        auto qrImageData = generateQRCodeImage(data, size);

        std::ofstream file(filePath, std::ios::binary);
        if (!file) {
            std::cerr << "Failed to open file for QR code: " << filePath << std::endl;
            return false;
        }

        file.write(reinterpret_cast<const char*>(qrImageData.data()), qrImageData.size());
        file.close();

        std::cout << "QR code saved to: " << filePath << std::endl;
        return true;
    } catch (const std::exception& e) {
        std::cerr << "Error saving QR code: " << e.what() << std::endl;
        return false;
    }
}

std::string PairingManager::generateEncryptionKey() {
    return generateRandomString(ENCRYPTION_KEY_LENGTH);
}

bool PairingManager::validateEncryptionKey(const std::string& key) const {
    // Basic validation - check length and character set
    if (key.length() != ENCRYPTION_KEY_LENGTH) {
        return false;
    }

    return std::all_of(key.begin(), key.end(), [](char c) {
        return std::isxdigit(c);
    });
}

std::string PairingManager::generateRandomString(int length) {
    std::string result;
    result.reserve(length);

    for (int i = 0; i < length; ++i) {
        result += HEX_CHARS[rng_() % HEX_CHARS.length()];
    }

    return result;
}

std::string PairingManager::generateSessionId() {
    return generateRandomString(SESSION_ID_LENGTH);
}

std::string PairingManager::generateDeviceId() {
    // Generate device ID with timestamp to ensure uniqueness
    int64_t timestamp = getCurrentTimestamp();
    std::string randomPart = generateRandomString(16);

    std::ostringstream oss;
    oss << std::hex << timestamp << randomPart;
    return oss.str();
}

std::string PairingManager::generateAlphanumericCode(int length) {
    std::string result;
    result.reserve(length);

    std::uniform_int_distribution<int> alphaDist(0, ALPHANUMERIC_CHARS.length() - 1);

    for (int i = 0; i < length; ++i) {
        result += ALPHANUMERIC_CHARS[alphaDist(rng_)];
    }

    return result;
}

bool PairingManager::validateManualCode(const std::string& code) const {
    if (code.length() != MANUAL_CODE_LENGTH) {
        return false;
    }

    return std::all_of(code.begin(), code.end(), [](char c) {
        return std::isalnum(c);
    });
}

std::string PairingManager::generateSecureLink(const std::string& sessionId, const std::string& token) {
    // Create a secure link with encrypted session information
    std::ostringstream oss;
    oss << sessionId << "-" << token;
    return oss.str();
}

std::string PairingManager::generateLinkToken() {
    return generateRandomString(LINK_TOKEN_LENGTH);
}

int64_t PairingManager::getCurrentTimestamp() const {
    return std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

bool PairingManager::isTimestampValid(int64_t timestamp, int validitySeconds) const {
    int64_t currentTime = getCurrentTimestamp();
    return (timestamp <= currentTime) && (currentTime - timestamp <= validitySeconds);
}

std::string PairingManager::getLocalIPAddress() {
    struct ifaddrs *ifap, *ifa;

    if (getifaddrs(&ifap) == 0) {
        for (ifa = ifap; ifa != nullptr; ifa = ifa->ifa_next) {
            if (ifa->ifa_addr && ifa->ifa_addr->sa_family == AF_INET) {
                struct sockaddr_in* sin = (struct sockaddr_in*)ifa->ifa_addr;
                std::string ip = inet_ntoa(sin->sin_addr);

                // Skip loopback and prefer WiFi/ethernet interfaces
                if (strcmp(ifa->ifa_name, "lo") != 0 &&
                    (strncmp(ifa->ifa_name, "en", 2) == 0 ||
                     strncmp(ifa->ifa_name, "eth", 3) == 0 ||
                     strncmp(ifa->ifa_name, "wlan", 4) == 0 ||
                     strncmp(ifa->ifa_name, "wl", 2) == 0)) {
                    freeifaddrs(ifap);
                    return ip;
                }
            }
        }
        freeifaddrs(ifap);
    }

    return "";
}

std::string PairingManager::getServerInfo() {
    std::ostringstream oss;
    oss << serverIP_ << ":" << serverPort_;
    return oss.str();
}

bool PairingManager::loadTrustedDevices() {
    try {
        std::string filePath = getTrustedDevicesFilePath();
        std::ifstream file(filePath);

        if (!file.is_open()) {
            return false; // File doesn't exist, which is fine
        }

        std::lock_guard<std::mutex> lock(trustedDevicesMutex_);
        trustedDevices_.clear();

        std::string line;
        while (std::getline(file, line)) {
            size_t separator = line.find('=');
            if (separator != std::string::npos) {
                std::string deviceId = line.substr(0, separator);
                std::string deviceName = line.substr(separator + 1);
                trustedDevices_[deviceId] = deviceName;
            }
        }

        file.close();
        return true;
    } catch (const std::exception& e) {
        std::cerr << "Error loading trusted devices: " << e.what() << std::endl;
        return false;
    }
}

bool PairingManager::saveTrustedDevices() {
    try {
        std::string filePath = getTrustedDevicesFilePath();
        std::ofstream file(filePath);

        if (!file.is_open()) {
            std::cerr << "Failed to open trusted devices file for writing: " << filePath << std::endl;
            return false;
        }

        std::lock_guard<std::mutex> lock(trustedDevicesMutex_);
        for (const auto& pair : trustedDevices_) {
            file << pair.first << "=" << pair.second << std::endl;
        }

        file.close();
        return true;
    } catch (const std::exception& e) {
        std::cerr << "Error saving trusted devices: " << e.what() << std::endl;
        return false;
    }
}

std::string PairingManager::getTrustedDevicesFilePath() const {
    // Get user's home directory and append .screenflow/trusted_devices.txt
    const char* home = std::getenv("HOME");
    if (!home) {
        home = ".";
    }

    return std::string(home) + "/.screenflow/trusted_devices.txt";
}

std::vector<uint8_t> PairingManager::generateSimpleQRCode(const std::string& data, int size) {
    // Placeholder QR code generation
    // In a real implementation, you would use a proper QR code library
    std::vector<uint8_t> imageData(size * size * 4); // RGBA format

    // Generate a simple pattern
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            int index = (y * size + x) * 4;

            // Create a simple checkerboard pattern
            bool isBlack = ((x / 10) + (y / 10)) % 2 == 0;

            if (isBlack) {
                imageData[index + 0] = 0;     // R
                imageData[index + 1] = 0;     // G
                imageData[index + 2] = 0;     // B
            } else {
                imageData[index + 0] = 255;   // R
                imageData[index + 1] = 255;   // G
                imageData[index + 2] = 255;   // B
            }
            imageData[index + 3] = 255;       // A
        }
    }

    return imageData;
}

void PairingManager::cleanupExpiredPairings() {
    std::lock_guard<std::mutex> lock(pendingPairingsMutex_);
    int64_t currentTime = getCurrentTimestamp();

    auto it = pendingPairings_.begin();
    while (it != pendingPairings_.end()) {
        if (!isTimestampValid(it->second.requestTime, SESSION_TIMEOUT_SECONDS)) {
            std::cout << "Removing expired pairing request for device: " << it->first << std::endl;
            it = pendingPairings_.erase(it);
        } else {
            ++it;
        }
    }
}

} // namespace ScreenFlow