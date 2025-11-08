package com.screenflow.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.webrtc.*
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore

/**
 * Manages network connections to PC server using WebSocket and WebRTC
 */
class NetworkManager(private val context: Context) {

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val PING_INTERVAL_MS = 5000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5

        // Message types
        private const val MSG_TYPE_PAIRING_REQUEST = "pairing_request"
        private const val MSG_TYPE_PAIRING_RESPONSE = "pairing_response"
        private const val MSG_TYPE_VIDEO_FRAME = "video_frame"
        private const val MSG_TYPE_INPUT_EVENT = "input_event"
        private const val MSG_TYPE_PING = "ping"
        private const val MSG_TYPE_PONG = "pong"
        private const val MSG_TYPE_DISCONNECT = "disconnect"
        private const val MSG_TYPE_QUALITY_CHANGE = "quality_change"
    }

    // Connection state
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    // Connection type
    enum class ConnectionType {
        WEBSOCKET_RELAY,
        WEBRTC_P2P,
        UNKNOWN
    }

    // Statistics
    data class ConnectionStats(
        var latency: Long = 0,
        var bitrate: Long = 0,
        var fps: Int = 0,
        var packetsReceived: Long = 0,
        var packetsSent: Long = 0,
        var connectionTime: Long = 0,
        var reconnectCount: Int = 0
    )

    // WebSocket components
    private lateinit var okHttpClient: OkHttpClient
    private var webSocket: WebSocket? = null
    private val connectionState = AtomicBoolean(false)
    private var currentConnectionType = ConnectionType.UNKNOWN

    // WebRTC components
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // Connection info
    private var serverUrl = ""
    private var sessionId = ""
    private var encryptionKey = ""
    private var connectionStartTime = 0L

    // Statistics
    private val stats = ConnectionStats()
    private var lastPingTime = 0L

    // Coroutines
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onStatsUpdated: ((ConnectionStats) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInputEventReceived: ((InputEventData) -> Unit)? = null

    // JSON handling
    private val gson = Gson()

    data class InputEventData(
        val type: String,
        val x: Int = 0,
        val y: Int = 0,
        val button: Int = 0,
        val modifiers: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class NetworkMessage(
        val type: String,
        val data: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis(),
        val sessionId: String = ""
    )

    init {
        initializeOkHttpClient()
        initializeWebRTC()
    }

    fun connect() {
        if (connectionState.get()) {
            Timber.w("Already connected")
            return
        }

        // Default connection for testing
        connectToWebSocket("ws://192.168.1.100:8080")
    }

    fun connectToWebSocket(url: String, sessionId: String = "", encryptionKey: String = "") {
        if (connectionState.get()) {
            disconnect()
        }

        this.serverUrl = url
        this.sessionId = sessionId
        this.encryptionKey = encryptionKey

        networkScope.launch {
            try {
                connectionState.set(true)
                currentConnectionType = ConnectionType.WEBSOCKET_RELAY
                connectionStartTime = System.currentTimeMillis()

                Timber.d("Connecting to WebSocket: $url")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "ScreenFlow-Android/1.0")
                    .addHeader("X-Session-ID", sessionId)
                    .build()

                webSocket = okHttpClient.newWebSocket(request, webSocketListener)

                onConnectionStateChanged?.invoke(false) // Connecting state
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to WebSocket")
                handleError("Connection failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        connectionState.set(false)

        networkScope.launch {
            try {
                webSocket?.close(1000, "Normal closure")
                peerConnection?.close()

                webSocket = null
                peerConnection = null
                dataChannel = null

                currentConnectionType = ConnectionType.UNKNOWN
                onConnectionStateChanged?.invoke(false)

                Timber.d("Disconnected from server")
            } catch (e: Exception) {
                Timber.e(e, "Error during disconnect")
            }
        }
    }

    fun sendVideoFrame(frameData: ByteArray, timestamp: Long) {
        if (!connectionState.get()) {
            return
        }

        networkScope.launch {
            try {
                when (currentConnectionType) {
                    ConnectionType.WEBSOCKET_RELAY -> {
                        sendWebSocketMessage(NetworkMessage(
                            type = MSG_TYPE_VIDEO_FRAME,
                            data = mapOf(
                                "size" to frameData.size,
                                "timestamp" to timestamp
                            )
                        ))
                        // In a real implementation, send actual frame data
                    }
                    ConnectionType.WEBRTC_P2P -> {
                        sendDataChannelMessage(NetworkMessage(
                            type = MSG_TYPE_VIDEO_FRAME,
                            data = mapOf(
                                "size" to frameData.size,
                                "timestamp" to timestamp
                            )
                        ))
                    }
                    else -> {
                        // No connection
                    }
                }

                stats.packetsSent++
            } catch (e: Exception) {
                Timber.e(e, "Error sending video frame")
            }
        }
    }

    fun sendPong(originalTimestamp: Long) {
        if (!connectionState.get()) {
            return
        }

        networkScope.launch {
            try {
                val message = NetworkMessage(
                    type = MSG_TYPE_PONG,
                    data = mapOf(
                        "original_timestamp" to originalTimestamp,
                        "timestamp" to System.currentTimeMillis()
                    )
                )

                when (currentConnectionType) {
                    ConnectionType.WEBSOCKET_RELAY -> sendWebSocketMessage(message)
                    ConnectionType.WEBRTC_P2P -> sendDataChannelMessage(message)
                    else -> {}
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending pong")
            }
        }
    }

    private fun initializeOkHttpClient() {
        try {
            // Create custom SSL context for secure connections
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, null)

            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for streaming
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManagers[0] as X509TrustManager)
                .build()

            Timber.d("OkHttpClient initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize OkHttp client")
            throw e
        }
    }

    private fun initializeWebRTC() {
        try {
            // Initialize PeerConnectionFactory
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

            Timber.d("WebRTC initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WebRTC")
        }
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.d("WebSocket connection opened")

            // Send pairing request if we have session info
            if (sessionId.isNotEmpty()) {
                sendPairingRequest()
            } else {
                onConnectionStateChanged?.invoke(true)
            }

            startPingInterval()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleWebSocketMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Handle binary messages if needed
            Timber.d("Received binary message: ${bytes.size()} bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("WebSocket closing: $code - $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("WebSocket closed: $code - $reason")
            handleDisconnection()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.e(t, "WebSocket failure")
            handleError("Connection failed: ${t.message}")
            handleDisconnection()
        }
    }

    private fun handleWebSocketMessage(message: String) {
        try {
            val networkMessage = gson.fromJson(message, NetworkMessage::class.java)
            stats.packetsReceived++

            when (networkMessage.type) {
                MSG_TYPE_PAIRING_RESPONSE -> handlePairingResponse(networkMessage)
                MSG_TYPE_INPUT_EVENT -> handleInputEvent(networkMessage)
                MSG_TYPE_PING -> handlePing(networkMessage)
                MSG_TYPE_PONG -> handlePong(networkMessage)
                MSG_TYPE_DISCONNECT -> handleDisconnect(networkMessage)
                MSG_TYPE_QUALITY_CHANGE -> handleQualityChange(networkMessage)
                else -> {
                    Timber.w("Unknown message type: ${networkMessage.type}")
                }
            }
        } catch (e: JsonSyntaxException) {
            Timber.e(e, "Failed to parse JSON message: $message")
        } catch (e: Exception) {
            Timber.e(e, "Error handling WebSocket message")
        }
    }

    private fun handlePairingResponse(message: NetworkMessage) {
        val status = message.data["status"] as? String ?: "unknown"
        if (status == "success") {
            onConnectionStateChanged?.invoke(true)
            currentConnectionType = ConnectionType.WEBSOCKET_RELAY
            Timber.d("Pairing successful")
        } else {
            val error = message.data["error"] as? String ?: "Pairing failed"
            handleError(error)
        }
    }

    private fun handleInputEvent(message: NetworkMessage) {
        try {
            val inputData = message.data
            val inputEvent = InputEventData(
                type = inputData["type"] as? String ?: "unknown",
                x = (inputData["x"] as? Double)?.toInt() ?: 0,
                y = (inputData["y"] as? Double)?.toInt() ?: 0,
                button = (inputData["button"] as? Double)?.toInt() ?: 0,
                modifiers = (inputData["modifiers"] as? Double)?.toInt() ?: 0,
                timestamp = inputData["timestamp"] as? Long ?: System.currentTimeMillis()
            )

            onInputEventReceived?.invoke(inputEvent)
        } catch (e: Exception) {
            Timber.e(e, "Error handling input event")
        }
    }

    private fun handlePing(message: NetworkMessage) {
        val timestamp = message.data["timestamp"] as? Long ?: System.currentTimeMillis()
        sendPong(timestamp)
    }

    private fun handlePong(message: NetworkMessage) {
        val originalTimestamp = message.data["original_timestamp"] as? Long ?: 0
        if (originalTimestamp > 0) {
            val currentTime = System.currentTimeMillis()
            stats.latency = currentTime - originalTimestamp
        }
    }

    private fun handleDisconnect(message: NetworkMessage) {
        val reason = message.data["reason"] as? String ?: "Unknown reason"
        Timber.d("Disconnect received: $reason")
        disconnect()
    }

    private fun handleQualityChange(message: NetworkMessage) {
        val quality = message.data["quality"] as? String ?: "medium"
        Timber.d("Quality change requested: $quality")
        // Forward to screen capture manager
    }

    private fun sendPairingRequest() {
        val message = NetworkMessage(
            type = MSG_TYPE_PAIRING_REQUEST,
            sessionId = sessionId,
            data = mapOf(
                "device_id" to android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ),
                "device_name" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "encryption_key" to encryptionKey
            )
        )
        sendWebSocketMessage(message)
    }

    private fun sendWebSocketMessage(message: NetworkMessage) {
        try {
            val json = gson.toJson(message)
            webSocket?.send(json)
        } catch (e: Exception) {
            Timber.e(e, "Error sending WebSocket message")
        }
    }

    private fun sendDataChannelMessage(message: NetworkMessage) {
        try {
            val json = gson.toJson(message)
            dataChannel?.send(DataChannel.Buffer(ByteString.of(*json.toByteArray()), true))
        } catch (e: Exception) {
            Timber.e(e, "Error sending data channel message")
        }
    }

    private fun startPingInterval() {
        networkScope.launch {
            while (connectionState.get()) {
                delay(PING_INTERVAL_MS.toLong())
                if (connectionState.get()) {
                    sendPing()
                }
            }
        }
    }

    private fun sendPing() {
        lastPingTime = System.currentTimeMillis()
        val message = NetworkMessage(
            type = MSG_TYPE_PING,
            data = mapOf("timestamp" to lastPingTime)
        )

        when (currentConnectionType) {
            ConnectionType.WEBSOCKET_RELAY -> sendWebSocketMessage(message)
            ConnectionType.WEBRTC_P2P -> sendDataChannelMessage(message)
            else -> {}
        }
    }

    private fun handleDisconnection() {
        connectionState.set(false)
        onConnectionStateChanged?.invoke(false)

        // Attempt reconnection if not intentionally disconnected
        if (serverUrl.isNotEmpty() && stats.reconnectCount < MAX_RECONNECT_ATTEMPTS) {
            stats.reconnectCount++
            networkScope.launch {
                delay(RECONNECT_DELAY_MS * stats.reconnectCount)
                if (!connectionState.get()) {
                    Timber.d("Attempting reconnection ${stats.reconnectCount}/$MAX_RECONNECT_ATTEMPTS")
                    connectToWebSocket(serverUrl, sessionId, encryptionKey)
                }
            }
        }
    }

    private fun handleError(error: String) {
        Timber.e("Network error: $error")
        onError?.invoke(error)
    }

    private fun updateStats() {
        stats.connectionTime = if (connectionStartTime > 0) {
            System.currentTimeMillis() - connectionStartTime
        } else 0

        onStatsUpdated?.invoke(stats)
    }

    fun getStats(): ConnectionStats {
        updateStats()
        return stats.copy()
    }

    fun cleanup() {
        networkScope.cancel()
        disconnect()
    }
}