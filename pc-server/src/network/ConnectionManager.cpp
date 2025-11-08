#include "ConnectionManager.h"
#include <iostream>
#include <json/json.h>
#include <openssl/rand.h>
#include <openssl/evp.h>
#include <chrono>
#include <ifaddrs.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

// WebSocket++ includes
#include <websocketpp/config/asio_no_tls.hpp>
#include <websocketpp/server.hpp>

// WebRTC includes (simplified for this example)
// In a real implementation, you would use libwebrtc or similar

namespace ScreenFlow {

using websocketpp::lib::placeholders::_1;
using websocketpp::lib::placeholders::_2;
using websocketpp::lib::bind;

ConnectionManager::ConnectionManager() :
    isRunning_(false),
    isConnected_(false),
    currentConnectionType_(ConnectionType::UNKNOWN),
    wsConnectionId_(""),
    webRTCSupported_(false),
    messageHandler_(nullptr),
    connectionHandler_(nullptr),
    serverPort_(8080),
    encryptionEnabled_(true),
    lastPingTime_(0),
    pingCount_(0),
    missedPings_(0) {

    // Detect local IP address
    detectLocalIP();
}

ConnectionManager::~ConnectionManager() {
    stop();
}

bool ConnectionManager::initialize() {
    try {
        // Initialize WebSocket server
        wsServer_ = std::make_unique<websocketpp::server<websocketpp::config::asio>>();

        wsServer_->set_access_channels(websocketpp::log::alevel::all);
        wsServer_->clear_access_channels(websocketpp::log::alevel::frame_payload);
        wsServer_->set_access_channels(websocketpp::log::elevel::all);

        // Initialize WebSocket handlers
        wsServer_->set_open_handler([this](websocketpp::connection_hdl hdl) {
            std::cout << "WebSocket connection opened" << std::endl;
            wsConnectionId_ = hdl.lock().get();

            // Send pairing challenge
            Json::Value challenge;
            challenge["type"] = "pairing_challenge";
            challenge["session_id"] = currentSessionId_;
            challenge["timestamp"] = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();

            broadcastWebSocketMessage(challenge.toStyledString());
        });

        wsServer_->set_close_handler([this](websocketpp::connection_hdl hdl) {
            std::cout << "WebSocket connection closed" << std::endl;
            handleConnectionLost();
        });

        wsServer_->set_message_handler([this](websocketpp::connection_hdl hdl,
                                            websocketpp::server<websocketpp::config::asio>::message_ptr msg) {
            handleWebSocketMessage(msg->get_payload());
        });

        // Initialize WebRTC
        initializeWebRTC();

        // Initialize encryption
        if (!initializeEncryption()) {
            std::cerr << "Failed to initialize encryption" << std::endl;
            return false;
        }

        isRunning_ = true;
        return true;
    } catch (const std::exception& e) {
        std::cerr << "ConnectionManager initialization failed: " << e.what() << std::endl;
        return false;
    }
}

void ConnectionManager::run() {
    if (!isRunning_) {
        return;
    }

    // Start WebSocket server
    startWebSocketServer();

    // Start network monitoring thread
    std::thread networkThread(&ConnectionManager::networkLoop, this);
    networkThread.detach();

    // Start heartbeat thread
    heartbeatThread_ = std::thread(&ConnectionManager::heartbeatLoop, this);
}

void ConnectionManager::stop() {
    isRunning_ = false;
    isConnected_ = false;

    stopWebSocketServer();

    if (heartbeatThread_.joinable()) {
        heartbeatThread_.join();
    }

    if (wsThread_.joinable()) {
        wsThread_.join();
    }
}

bool ConnectionManager::isConnected() const {
    return isConnected_.load();
}

ConnectionType ConnectionManager::getConnectionType() const {
    return currentConnectionType_;
}

ConnectionInfo ConnectionManager::getConnectionInfo() const {
    return connectionInfo_;
}

bool ConnectionManager::sendInputEvent(const InputEvent& event) {
    if (!isConnected_) {
        return false;
    }

    Json::Value message;
    message["type"] = "input_event";
    message["timestamp"] = event.timestamp;
    message["data"]["type"] = static_cast<int>(event.type);
    message["data"]["x"] = event.x;
    message["data"]["y"] = event.y;
    message["data"]["button"] = event.button;
    message["data"]["modifiers"] = event.modifiers;

    std::string messageStr = message.toStyledString();

    if (encryptionEnabled_) {
        messageStr = encryptMessage(messageStr);
    }

    if (currentConnectionType_ == ConnectionType::WEBSOCKET_RELAY) {
        broadcastWebSocketMessage(messageStr);
    } else if (currentConnectionType_ == ConnectionType::WEBRTC_P2P) {
        sendWebRTCMessage(messageStr);
    }

    return true;
}

bool ConnectionManager::sendQualityChange(VideoQuality quality) {
    if (!isConnected_) {
        return false;
    }

    Json::Value message;
    message["type"] = "quality_change";
    message["quality"] = static_cast<int>(quality);
    message["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    std::string messageStr = message.toStyledString();

    if (encryptionEnabled_) {
        messageStr = encryptMessage(messageStr);
    }

    if (currentConnectionType_ == ConnectionType::WEBSOCKET_RELAY) {
        broadcastWebSocketMessage(messageStr);
    } else if (currentConnectionType_ == ConnectionType::WEBRTC_P2P) {
        sendWebRTCMessage(messageStr);
    }

    return true;
}

bool ConnectionManager::sendPing() {
    if (!isConnected_) {
        return false;
    }

    Json::Value ping;
    ping["type"] = "ping";
    ping["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    std::string pingStr = ping.toStyledString();

    if (encryptionEnabled_) {
        pingStr = encryptMessage(pingStr);
    }

    if (currentConnectionType_ == ConnectionType::WEBSOCKET_RELAY) {
        broadcastWebSocketMessage(pingStr);
    } else if (currentConnectionType_ == ConnectionType::WEBRTC_P2P) {
        sendWebRTCMessage(pingStr);
    }

    lastPingTime_.store(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());

    pingCount_++;
    return true;
}

void ConnectionManager::setMessageHandler(MessageHandler handler) {
    messageHandler_ = handler;
}

void ConnectionManager::setConnectionHandler(ConnectionHandler handler) {
    connectionHandler_ = handler;
}

std::string ConnectionManager::generatePairingInfo() {
    // Generate session ID
    unsigned char sessionIdBytes[16];
    RAND_bytes(sessionIdBytes, sizeof(sessionIdBytes));

    char sessionIdStr[33];
    for (int i = 0; i < 16; i++) {
        sprintf(sessionIdStr + (i * 2), "%02x", sessionIdBytes[i]);
    }
    sessionIdStr[32] = '\0';

    currentSessionId_ = std::string(sessionIdStr);

    // Generate pairing info
    Json::Value pairingInfo;
    pairingInfo["session_id"] = currentSessionId_;
    pairingInfo["server_ip"] = localIP_;
    pairingInfo["server_port"] = serverPort_;
    pairingInfo["encryption_key"] = encryptionKey_;
    pairingInfo["timestamp"] = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    pairingInfo["expiry"] = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count() + 300; // 5 minutes

    return pairingInfo.toStyledString();
}

bool ConnectionManager::validatePairingRequest(const std::string& requestData) {
    try {
        Json::Value request;
        Json::Reader reader;

        if (!reader.parse(requestData, request)) {
            return false;
        }

        if (!request.isMember("session_id") || !request.isMember("device_id")) {
            return false;
        }

        std::string sessionId = request["session_id"].asString();
        if (sessionId != currentSessionId_) {
            return false;
        }

        // Check if pairing is still valid (not expired)
        int64_t timestamp = request.get("timestamp", 0).asInt64();
        int64_t currentTime = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

        if (currentTime - timestamp > 300) { // 5 minutes expiry
            return false;
        }

        return true;
    } catch (const std::exception& e) {
        std::cerr << "Pairing validation error: " << e.what() << std::endl;
        return false;
    }
}

bool ConnectionManager::completePairing(const std::string& response) {
    try {
        Json::Value responseJson;
        Json::Reader reader;

        if (!reader.parse(response, responseJson)) {
            return false;
        }

        if (responseJson.get("status", "failed").asString() != "success") {
            return false;
        }

        connectionInfo_.deviceId = responseJson["device_id"].asString();
        connectionInfo_.deviceName = responseJson["device_name"].asString();
        connectionInfo_.ipAddress = responseJson["ip_address"].asString();
        connectionInfo_.lastPing = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

        // Attempt P2P connection first
        attemptP2PConnection();

        return true;
    } catch (const std::exception& e) {
        std::cerr << "Pairing completion error: " << e.what() << std::endl;
        return false;
    }
}

void ConnectionManager::startWebSocketServer(int port) {
    try {
        wsServer_->listen(port);
        wsServer_->start_accept();

        std::cout << "WebSocket server started on port " << port << std::endl;
        std::cout << "Local IP: " << localIP_ << std::endl;

        wsThread_ = std::thread([this]() {
            wsServer_->run();
        });

    } catch (const std::exception& e) {
        std::cerr << "Failed to start WebSocket server: " << e.what() << std::endl;
    }
}

void ConnectionManager::stopWebSocketServer() {
    if (wsServer_) {
        wsServer_->stop();
    }
}

void ConnectionManager::handleWebSocketMessage(const std::string& message) {
    std::string decryptedMessage = message;

    if (encryptionEnabled_) {
        decryptedMessage = decryptMessage(message);
    }

    processIncomingMessage(decryptedMessage);
}

void ConnectionManager::broadcastWebSocketMessage(const std::string& message) {
    if (wsServer_ && !wsConnectionId_.empty()) {
        try {
            wsServer_->get_alog().write(websocketpp::log::alevel::app,
                                       "Broadcasting message: " + message);
            wsServer_->send(wsConnectionId_, message, websocketpp::frame::opcode::text);
        } catch (const std::exception& e) {
            std::cerr << "WebSocket broadcast error: " << e.what() << std::endl;
        }
    }
}

void ConnectionManager::initializeWebRTC() {
    // In a real implementation, this would initialize WebRTC library
    // For now, we'll assume WebRTC is supported
    webRTCSupported_ = true;
}

void ConnectionManager::createPeerConnection() {
    // In a real implementation, this would create a WebRTC peer connection
    // with appropriate STUN/TURN servers and signaling
}

void ConnectionManager::handleWebRTCMessage(const std::string& message) {
    // WebRTC message handling implementation
}

void ConnectionManager::sendWebRTCMessage(const std::string& message) {
    // WebRTC message sending implementation
}

void ConnectionManager::attemptP2PConnection() {
    if (webRTCSupported_) {
        std::cout << "Attempting WebRTC P2P connection..." << std::endl;
        createPeerConnection();

        // Set a timeout for P2P connection attempt
        std::thread([this]() {
            std::this_thread::sleep_for(std::chrono::seconds(10));
            if (!isConnected_) {
                std::cout << "P2P connection failed, falling back to relay" << std::endl;
                fallbackToRelay();
            }
        }).detach();
    } else {
        fallbackToRelay();
    }
}

void ConnectionManager::fallbackToRelay() {
    std::cout << "Using WebSocket relay connection" << std::endl;
    handleConnectionEstablished(ConnectionType::WEBSOCKET_RELAY);
}

void ConnectionManager::handleConnectionEstablished(ConnectionType type) {
    currentConnectionType_ = type;
    isConnected_.store(true);
    missedPings_.store(0);

    if (connectionHandler_) {
        connectionHandler_(true);
    }

    std::cout << "Connection established via "
              << (type == ConnectionType::WEBSOCKET_RELAY ? "WebSocket relay" : "WebRTC P2P")
              << std::endl;
}

void ConnectionManager::handleConnectionLost() {
    isConnected_.store(false);
    currentConnectionType_ = ConnectionType::UNKNOWN;

    if (connectionHandler_) {
        connectionHandler_(false);
    }

    std::cout << "Connection lost" << std::endl;
}

void ConnectionManager::processIncomingMessage(const std::string& message) {
    try {
        Json::Value root;
        Json::Reader reader;

        if (!reader.parse(message, root)) {
            std::cerr << "Failed to parse incoming message" << std::endl;
            return;
        }

        std::string messageType = root.get("type", "").asString();

        if (messageType == "video_frame") {
            processVideoFrame(message);
        } else if (messageType == "input_response") {
            processInputResponse(message);
        } else if (messageType == "pong") {
            processPong(message);
        } else if (messageType == "pairing_response") {
            completePairing(message);
        } else {
            std::cerr << "Unknown message type: " << messageType << std::endl;
        }

        if (messageHandler_) {
            messageHandler_(message);
        }

    } catch (const std::exception& e) {
        std::cerr << "Error processing incoming message: " << e.what() << std::endl;
    }
}

void ConnectionManager::processVideoFrame(const std::string& frameData) {
    // Video frame processing - this would be handled by VideoDecoder
    std::lock_guard<std::mutex> lock(incomingMutex_);
    incomingMessages_.push(frameData);
}

void ConnectionManager::processInputResponse(const std::string& responseData) {
    // Handle input acknowledgment from device
    std::cout << "Received input response" << std::endl;
}

void ConnectionManager::processPong(const std::string& pongData) {
    missedPings_.store(0);

    try {
        Json::Value pong;
        Json::Reader reader;

        if (reader.parse(pongData, pong)) {
            int64_t originalTimestamp = pong.get("original_timestamp", 0).asInt64();
            int64_t currentTime = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();

            int64_t latency = currentTime - originalTimestamp;
            connectionInfo_.lastPing = currentTime;

            std::cout << "Ping latency: " << latency << "ms" << std::endl;
        }
    } catch (const std::exception& e) {
        std::cerr << "Error processing pong: " << e.what() << std::endl;
    }
}

bool ConnectionManager::initializeEncryption() {
    // Generate encryption key
    unsigned char keyBytes[32];
    if (RAND_bytes(keyBytes, sizeof(keyBytes)) != 1) {
        return false;
    }

    char keyStr[65];
    for (int i = 0; i < 32; i++) {
        sprintf(keyStr + (i * 2), "%02x", keyBytes[i]);
    }
    keyStr[64] = '\0';

    encryptionKey_ = std::string(keyStr);
    return true;
}

std::string ConnectionManager::encryptMessage(const std::string& message) {
    // Simplified encryption - in a real implementation, use proper AES encryption
    return message; // Placeholder
}

std::string ConnectionManager::decryptMessage(const std::string& encryptedMessage) {
    // Simplified decryption - in a real implementation, use proper AES decryption
    return encryptedMessage; // Placeholder
}

void ConnectionManager::detectLocalIP() {
    struct ifaddrs *ifap, *ifa;

    if (getifaddrs(&ifap) == 0) {
        for (ifa = ifap; ifa != nullptr; ifa = ifa->ifa_next) {
            if (ifa->ifa_addr && ifa->ifa_addr->sa_family == AF_INET) {
                struct sockaddr_in* sin = (struct sockaddr_in*)ifa->ifa_addr;
                if (strcmp(ifa->ifa_name, "lo") != 0) { // Skip loopback
                    localIP_ = inet_ntoa(sin->sin_addr);
                    break;
                }
            }
        }
        freeifaddrs(ifap);
    }

    if (localIP_.empty()) {
        localIP_ = "127.0.0.1"; // Fallback
    }
}

void ConnectionManager::networkLoop() {
    while (isRunning_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        // Process message queues
        {
            std::lock_guard<std::mutex> lock(incomingMutex_);
            while (!incomingMessages_.empty()) {
                // Messages are processed in processIncomingMessage
                incomingMessages_.pop();
            }
        }

        {
            std::lock_guard<std::mutex> lock(outgoingMutex_);
            while (!outgoingMessages_.empty()) {
                std::string message = outgoingMessages_.front();
                outgoingMessages_.pop();

                if (currentConnectionType_ == ConnectionType::WEBSOCKET_RELAY) {
                    broadcastWebSocketMessage(message);
                } else if (currentConnectionType_ == ConnectionType::WEBRTC_P2P) {
                    sendWebRTCMessage(message);
                }
            }
        }
    }
}

void ConnectionManager::heartbeatLoop() {
    while (isRunning_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(HEARTBEAT_INTERVAL_MS));

        if (isConnected_) {
            sendPing();

            // Check for missed pings
            if (missedPings_.load() > MAX_MISSED_PINGS) {
                std::cout << "Too many missed pings, connection lost" << std::endl;
                handleConnectionLost();
            }
        }
    }
}

void ConnectionManager::disconnect() {
    if (isConnected_) {
        Json::Value disconnect;
        disconnect["type"] = "disconnect";
        disconnect["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

        std::string disconnectStr = disconnect.toStyledString();

        if (currentConnectionType_ == ConnectionType::WEBSOCKET_RELAY) {
            broadcastWebSocketMessage(disconnectStr);
        } else if (currentConnectionType_ == ConnectionType::WEBRTC_P2P) {
            sendWebRTCMessage(disconnectStr);
        }

        handleConnectionLost();
    }
}

} // namespace ScreenFlow