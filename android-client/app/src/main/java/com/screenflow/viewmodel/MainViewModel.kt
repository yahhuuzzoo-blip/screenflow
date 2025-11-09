package com.screenflow.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.screenflow.ScreenFlowApplication
import com.screenflow.service.ScreenCaptureService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for MainActivity
 * Manages service status, connection state, and pairing logic
 */
class MainViewModel : ViewModel() {

    // Connection status
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    // Pairing status
    enum class PairingStatus {
        NONE,
        PAIRING,
        SUCCESS,
        FAILED,
        EXPIRED
    }

    // Connection statistics
    data class ConnectionStats(
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val bitrate: Long = 0,
        val latency: Int = 0,
        val connectionTime: Long = 0
    )

    // LiveData
    private val _connectionStatus = MutableLiveData<ConnectionStatus>()
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    private val _isServiceRunning = MutableLiveData<Boolean>()
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    private val _pairingStatus = MutableLiveData<PairingStatus>()
    val pairingStatus: LiveData<PairingStatus> = _pairingStatus

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _connectionStats = MutableLiveData<ConnectionStats>()
    val connectionStats: LiveData<ConnectionStats> = _connectionStats

    // Internal state
    private var currentSessionId: String = ""
    private var serverInfo: String = ""
    private var connectionStartTime: Long = 0
    private val gson = Gson()

    // SharedPreferences for persistent session storage
    private val preferences by lazy {
        ScreenFlowApplication.instance.getSharedPreferences("screenflow_session", Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREF_SESSION_ID = "session_id"
        private const val PREF_SERVER_INFO = "server_info"
        private const val PREF_PAIRING_TIME = "pairing_time"
        private const val SESSION_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    init {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isServiceRunning.value = false
        _pairingStatus.value = PairingStatus.NONE

        // Load saved session info
        loadSessionInfo()
    }

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
        if (!isRunning) {
            setConnectionStatus(ConnectionStatus.DISCONNECTED)
        }
    }

    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
        when (status) {
            ConnectionStatus.CONNECTED -> {
                connectionStartTime = System.currentTimeMillis()
                startStatsUpdates()
            }
            ConnectionStatus.DISCONNECTED -> {
                connectionStartTime = 0
                _connectionStats.value = ConnectionStats()
            }
            else -> {
                // Do nothing for connecting state
            }
        }
    }

    fun setPairingStatus(status: PairingStatus) {
        _pairingStatus.value = status
    }

    fun setError(message: String) {
        _error.value = message
        Timber.e("Error: $message")
    }

    fun clearError() {
        _error.value = null
    }

    fun updateServiceStatus() {
        // This would typically check if the service is actually running
        // For now, we'll rely on the LiveData state
    }

    /**
     * Process QR code data from PC pairing
     */
    fun processQRCodeData(qrData: String): Boolean {
        return try {
            Timber.d("Processing QR code data: $qrData")

            // Parse QR code JSON data
            val qrInfo = parseQRCodeData(qrData) ?: return false

            // Validate QR code data
            if (!validateQRCodeInfo(qrInfo)) {
                setPairingStatus(PairingStatus.FAILED)
                setError("Invalid QR code data")
                return false
            }

            // Store session information
            currentSessionId = qrInfo.sessionId
            serverInfo = "${qrInfo.serverIP}:${qrInfo.serverPort}"

            // Start pairing process
            startPairing(qrInfo)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error processing QR code data")
            setError("Failed to process QR code: ${e.message}")
            setPairingStatus(PairingStatus.FAILED)
            false
        }
    }

    /**
     * Process manual pairing code
     */
    fun processManualCode(code: String): Boolean {
        return try {
            Timber.d("Processing manual code: $code")

            // Validate manual code format
            if (code.length != 6 || !code.all { it.isLetterOrDigit() }) {
                setError("Invalid manual code format")
                return false
            }

            // For manual code pairing, we would typically:
            // 1. Connect to a discovery server or known PC address
            // 2. Exchange the manual code for session information
            // 3. Establish connection with proper authentication

            // For this implementation, we'll simulate the process
            setPairingStatus(PairingStatus.PAIRING)

            viewModelScope.launch {
                delay(2000) // Simulate network request

                // Mock successful pairing for demonstration
                currentSessionId = "mock_session_${System.currentTimeMillis()}"
                serverInfo = "192.168.1.100:8080" // Mock server address

                setPairingStatus(PairingStatus.SUCCESS)
                setConnectionStatus(ConnectionStatus.CONNECTING)

                // Simulate connection establishment
                delay(1500)
                setConnectionStatus(ConnectionStatus.CONNECTED)
            }

            true
        } catch (e: Exception) {
            Timber.e(e, "Error processing manual code")
            setError("Failed to process manual code: ${e.message}")
            setPairingStatus(PairingStatus.FAILED)
            false
        }
    }

    fun getServerInfo(): String {
        return serverInfo
    }

    fun getCurrentSessionId(): String {
        return currentSessionId
    }

    private fun parseQRCodeData(qrData: String): QRCodeInfo? {
        return try {
            gson.fromJson(qrData, QRCodeInfo::class.java)
        } catch (e: JsonSyntaxException) {
            Timber.e(e, "Failed to parse QR code JSON")
            null
        }
    }

    private fun validateQRCodeInfo(qrInfo: QRCodeInfo): Boolean {
        // Check required fields
        if (qrInfo.sessionId.isEmpty() || qrInfo.serverIP.isEmpty() || qrInfo.encryptionKey.isEmpty()) {
            return false
        }

        // Check protocol version
        if (qrInfo.version != 1) {
            setError("Unsupported protocol version: ${qrInfo.version}")
            return false
        }

        // Check timestamp (should not be too old or too far in future)
        val currentTime = System.currentTimeMillis() / 1000
        val timestampDiff = kotlin.math.abs(currentTime - qrInfo.timestamp)

        if (timestampDiff > 300) { // 5 minutes
            setError("QR code expired")
            return false
        }

        return true
    }

    private fun startPairing(qrInfo: QRCodeInfo) {
        setPairingStatus(PairingStatus.PAIRING)

        viewModelScope.launch {
            try {
                // Simulate pairing process
                delay(1000)

                // In a real implementation, this would:
                // 1. Connect to the PC server at qrInfo.serverIP:qrInfo.serverPort
                // 2. Send pairing request with qrInfo.sessionId
                // 3. Exchange encryption keys
                // 4. Establish secure connection

                // Mock successful pairing
                setPairingStatus(PairingStatus.SUCCESS)
                setConnectionStatus(ConnectionStatus.CONNECTING)

                // Simulate connection establishment
                delay(1500)
                setConnectionStatus(ConnectionStatus.CONNECTED)

                Timber.d("Pairing completed successfully with server: $serverInfo")

            } catch (e: Exception) {
                Timber.e(e, "Pairing failed")
                setError("Pairing failed: ${e.message}")
                setPairingStatus(PairingStatus.FAILED)
                setConnectionStatus(ConnectionStatus.DISCONNECTED)
            }
        }
    }

    private fun startStatsUpdates() {
        viewModelScope.launch {
            while (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                updateConnectionStats()
                delay(1000) // Update stats every second
            }
        }
    }

    private fun updateConnectionStats() {
        val currentTime = System.currentTimeMillis()
        val connectionTime = if (connectionStartTime > 0) {
            currentTime - connectionStartTime
        } else {
            0
        }

        // In a real implementation, these would come from the actual connection
        val stats = ConnectionStats(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 2500000, // 2.5 Mbps
            latency = 45, // 45ms
            connectionTime = connectionTime
        )

        _connectionStats.value = stats
    }

    /**
     * QR Code data structure
     */
    data class QRCodeInfo(
        val version: Int,
        val sessionId: String,
        val serverIP: String,
        val serverPort: Int,
        val encryptionKey: String,
        val timestamp: Long
    )

    override fun onCleared() {
        super.onCleared()
        Timber.d("MainViewModel cleared")
    }
}

/**
 * ViewModel factory for MainViewModel
 */
class MainViewModelFactory(
    private val application: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}