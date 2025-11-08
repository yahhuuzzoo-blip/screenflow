#pragma once

#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <queue>
#include <memory>
#include <functional>
#include <unordered_map>

#include "../common/Types.h"

// Forward declarations for WebSocket++ and WebRTC
namespace websocketpp {
    template<typename config>
    class server;
    namespace config {
        struct asio;
    }
}

namespace rtc {
    class PeerConnection;
    class DataChannel;
}

namespace ScreenFlow {

class ConnectionManager {
public:
    using MessageHandler = std::function<void(const std::string& message)>;
    using ConnectionHandler = std::function<void(bool connected)>;

    ConnectionManager();
    ~ConnectionManager();

    bool initialize();
    void run();
    void stop();

    bool isConnected() const;
    ConnectionType getConnectionType() const;
    ConnectionInfo getConnectionInfo() const;

    bool sendInputEvent(const InputEvent& event);
    bool sendQualityChange(VideoQuality quality);
    bool sendPing();

    void setMessageHandler(MessageHandler handler);
    void setConnectionHandler(ConnectionHandler handler);

    // Pairing methods
    std::string generatePairingInfo();
    bool validatePairingRequest(const std::string& requestData);
    bool completePairing(const std::string& response);

private:
    // WebSocket server methods
    void startWebSocketServer(int port = 8080);
    void stopWebSocketServer();
    void handleWebSocketMessage(const std::string& message);
    void broadcastWebSocketMessage(const std::string& message);

    // WebRTC methods
    void initializeWebRTC();
    void createPeerConnection();
    void handleWebRTCMessage(const std::string& message);
    void sendWebRTCMessage(const std::string& message);

    // Connection management
    void attemptP2PConnection();
    void fallbackToRelay();
    void handleConnectionEstablished(ConnectionType type);
    void handleConnectionLost();
    void monitorConnectionHealth();

    // Message processing
    void processIncomingMessage(const std::string& message);
    void processVideoFrame(const std::string& frameData);
    void processInputResponse(const std::string& responseData);
    void processPong(const std::string& pongData);

    // Security
    bool initializeEncryption();
    std::string encryptMessage(const std::string& message);
    std::string decryptMessage(const std::string& encryptedMessage);

    // Threading
    void networkLoop();
    void heartbeatLoop();

    // Internal state
    std::atomic<bool> isRunning_;
    std::atomic<bool> isConnected_;
    ConnectionType currentConnectionType_;

    // WebSocket components
    std::unique_ptr<websocketpp::server<websocketpp::config::asio>> wsServer_;
    std::thread wsThread_;
    std::string wsConnectionId_;

    // WebRTC components
    std::shared_ptr<rtc::PeerConnection> peerConnection_;
    std::shared_ptr<rtc::DataChannel> dataChannel_;
    bool webRTCSupported_;

    // Message queues
    std::queue<std::string> incomingMessages_;
    std::queue<std::string> outgoingMessages_;
    std::mutex incomingMutex_;
    std::mutex outgoingMutex_;

    // Handlers
    MessageHandler messageHandler_;
    ConnectionHandler connectionHandler_;

    // Connection info
    ConnectionInfo connectionInfo_;
    std::string localIP_;
    int serverPort_;

    // Security
    std::string encryptionKey_;
    bool encryptionEnabled_;

    // Health monitoring
    std::thread heartbeatThread_;
    std::atomic<int64_t> lastPingTime_;
    std::atomic<int> pingCount_;
    std::atomic<int> missedPings_;
    static constexpr int MAX_MISSED_PINGS = 3;
    static constexpr int HEARTBEAT_INTERVAL_MS = 5000;

    // Pairing state
    std::string currentSessionId_;
    std::unordered_map<std::string, std::string> pendingPairings_;
    std::mutex pairingMutex_;
};

} // namespace ScreenFlow