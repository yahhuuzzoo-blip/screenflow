package com.screenflow.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.screenflow.R
import com.screenflow.capture.ScreenCaptureManager
import com.screenflow.network.NetworkManager
import com.screenflow.utils.ServiceUtils
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for screen capture and streaming
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.screenflow.START_CAPTURE"
        const val ACTION_STOP = "com.screenflow.STOP_CAPTURE"
        const val ACTION_DISCONNECT = "com.screenflow.DISCONNECT"
        const val ACTION_PAUSE = "com.screenflow.PAUSE_CAPTURE"
        const val ACTION_RESUME = "com.screenflow.RESUME_CAPTURE"
        const val ACTION_QUALITY_CHANGE = "com.screenflow.QUALITY_CHANGE"

        const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"
        const val EXTRA_MEDIA_PROJECTION_CODE = "media_projection_code"
        const val EXTRA_QUALITY = "quality"

        const val NOTIFICATION_ID = 1001
        const val SERVICE_ID = 1001

        // Service states
        const val STATE_STOPPED = 0
        const val STATE_STARTING = 1
        const val STATE_RUNNING = 2
        const val STATE_PAUSED = 3
        const val STATE_STOPPING = 4
    }

    private var serviceState = STATE_STOPPED
    private var isServiceStarted = AtomicBoolean(false)
    private var shouldStopService = AtomicBoolean(false)

    // Managers
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var networkManager: NetworkManager

    // Media projection
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Service components
    private lateinit var notificationManager: NotificationManager
    private lateinit var serviceScope: CoroutineScope

    // Display metrics
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Binder for service communication
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("ScreenCaptureService onCreate")

        initializeManagers()
        initializeService()
        getDisplayMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("ScreenCaptureService onStartCommand: ${intent?.action}")

        if (!isServiceStarted.getAndSet(true)) {
            startForegroundService()
        }

        when (intent?.action) {
            ACTION_START -> handleStartCapture(intent)
            ACTION_STOP -> handleStopCapture()
            ACTION_DISCONNECT -> handleDisconnect()
            ACTION_PAUSE -> handlePauseCapture()
            ACTION_RESUME -> handleResumeCapture()
            ACTION_QUALITY_CHANGE -> handleQualityChange(intent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Timber.d("ScreenCaptureService onDestroy")
        cleanup()
        super.onDestroy()
    }

    private fun initializeManagers() {
        screenCaptureManager = ScreenCaptureManager(this)
        networkManager = NetworkManager(this)

        // Configure network manager callbacks
        networkManager.onConnectionStateChanged = { isConnected ->
            updateNotification(isConnected)
            if (isConnected) {
                serviceState = STATE_RUNNING
            } else {
                serviceState = STATE_STARTING
            }
        }

        networkManager.onStatsUpdated = { stats ->
            // Update statistics in notification
            updateNotificationStats(stats)
        }

        networkManager.onError = { error ->
            handleError(error)
        }
    }

    private fun initializeService() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    private fun getDisplayMetrics() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(displayMetrics)
        } else {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
            display.getMetrics(displayMetrics)
        }

        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi

        Timber.d("Display metrics: ${screenWidth}x$screenHeight @ $screenDensity dpi")
    }

    private fun startForegroundService() {
        val notification = createNotification("Initializing...", false)
        startForeground(NOTIFICATION_ID, notification)
        Timber.d("Foreground service started")
    }

    private fun handleStartCapture(intent: Intent) {
        if (serviceState != STATE_STOPPED) {
            Timber.w("Service already started in state: $serviceState")
            return
        }

        serviceState = STATE_STARTING
        shouldStopService.set(false)

        val resultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_MEDIA_PROJECTION_DATA)

        if (resultCode == 0 || data == null) {
            handleError("Invalid media projection data")
            stopSelf()
            return
        }

        serviceScope.launch {
            try {
                initializeMediaProjection(resultCode, data)
                initializeScreenCapture()
                startNetworkConnection()
            } catch (e: Exception) {
                Timber.e(e, "Failed to start screen capture")
                handleError("Failed to start screen capture: ${e.message}")
                stopSelf()
            }
        }
    }

    private suspend fun initializeMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            throw IllegalStateException("Failed to create MediaProjection")
        }

        Timber.d("MediaProjection initialized")
    }

    private suspend fun initializeScreenCapture() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 3)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenFlow",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        if (virtualDisplay == null) {
            throw IllegalStateException("Failed to create virtual display")
        }

        // Initialize screen capture manager
        screenCaptureManager.initialize(imageReader!!, virtualDisplay!!)

        Timber.d("Screen capture initialized")
    }

    private suspend fun startNetworkConnection() {
        // Start network connection to PC server
        networkManager.connect()
        updateNotification("Connecting to PC...", false)
    }

    private fun handleStopCapture() {
        if (serviceState == STATE_STOPPING) {
            return
        }

        serviceState = STATE_STOPPING
        shouldStopService.set(true)

        serviceScope.launch {
            try {
                networkManager.disconnect()
                screenCaptureManager.stop()
                releaseMediaProjection()
                updateNotification("Stopping...", false)

                delay(1000) // Give time for cleanup
                stopSelf()
            } catch (e: Exception) {
                Timber.e(e, "Error during stop capture")
                stopSelf()
            }
        }
    }

    private fun handleDisconnect() {
        serviceScope.launch {
            networkManager.disconnect()
            updateNotification("Disconnected", false)
            serviceState = STATE_STARTING
        }
    }

    private fun handlePauseCapture() {
        if (serviceState == STATE_RUNNING) {
            serviceState = STATE_PAUSED
            screenCaptureManager.pause()
            updateNotification("Paused", true)
        }
    }

    private fun handleResumeCapture() {
        if (serviceState == STATE_PAUSED) {
            serviceState = STATE_RUNNING
            screenCaptureManager.resume()
            updateNotification("Connected", true)
        }
    }

    private fun handleQualityChange(intent: Intent) {
        val quality = intent.getStringExtra(EXTRA_QUALITY)
        if (quality != null) {
            screenCaptureManager.setQuality(quality)
            updateNotification("Quality: $quality", true)
        }
    }

    private fun releaseMediaProjection() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        Timber.d("MediaProjection released")
    }

    private fun createNotification(status: String, showActions: Boolean): Notification {
        val channelId = ScreenFlowApplication.NOTIFICATION_CHANNEL_SCREEN_CAPTURE

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_screen_share)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (showActions) {
            // Add action buttons
            val disconnectIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val disconnectPendingIntent = PendingIntent.getService(
                this, 0, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                R.drawable.ic_disconnect,
                getString(R.string.service_action_disconnect),
                disconnectPendingIntent
            )

            val pauseIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = if (serviceState == STATE_PAUSED) ACTION_RESUME else ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getService(
                this, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val pauseTitle = if (serviceState == STATE_PAUSED) "Resume" else "Pause"
            val pauseIcon = if (serviceState == STATE_PAUSED) R.drawable.ic_play_arrow else R.drawable.ic_pause
            builder.addAction(pauseIcon, pauseTitle, pausePendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(status: String, showActions: Boolean) {
        val notification = createNotification(status, showActions)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationStats(stats: NetworkManager.ConnectionStats) {
        val status = "Connected - ${stats.fps}fps, ${stats.bitrate / 1000000.0}Mbps"
        updateNotification(status, true)
    }

    private fun handleError(error: String) {
        Timber.e("Service error: $error")

        // Send error to MainActivity if it's visible
        val intent = Intent("com.screenflow.ERROR").apply {
            putExtra("error", error)
        }
        sendBroadcast(intent)

        updateNotification("Error: $error", false)
    }

    private fun cleanup() {
        serviceScope.cancel()

        if (isServiceStarted.get()) {
            try {
                networkManager.disconnect()
                screenCaptureManager.stop()
                releaseMediaProjection()
            } catch (e: Exception) {
                Timber.e(e, "Error during cleanup")
            }
        }

        isServiceStarted.set(false)
        serviceState = STATE_STOPPED
    }

    // Public API for bound components
    fun getServiceState(): Int = serviceState
    fun isRunning(): Boolean = serviceState == STATE_RUNNING
    fun getConnectionStats(): NetworkManager.ConnectionStats? = networkManager.getStats()
}