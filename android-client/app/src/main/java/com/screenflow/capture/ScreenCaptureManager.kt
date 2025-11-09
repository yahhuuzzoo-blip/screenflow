package com.screenflow.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import com.screenflow.utils.SettingsManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages screen capture using MediaProjection API and H.264 encoding
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        // Video encoding parameters
        const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val VIDEO_FRAME_RATE = 30
        const val VIDEO_I_FRAME_INTERVAL = 2 // seconds

        // Quality presets
        data class QualityProfile(
            val width: Int,
            val height: Int,
            val bitRate: Int,
            val frameRate: Int,
            val name: String
        )

        val QUALITY_LOW = QualityProfile(854, 480, 1000000, 15, "Low")
        val QUALITY_MEDIUM = QualityProfile(1280, 720, 2500000, 30, "Medium")
        val QUALITY_HIGH = QualityProfile(1920, 1080, 5000000, 60, "High")
        val QUALITY_AUTO = QUALITY_MEDIUM // Default auto quality

        private const val ENCODER_TIMEOUT_MS = 10000
        private const val FRAME_BUFFER_SIZE = 3
    }

    // Encoding state
    private var mediaCodec: MediaCodec? = null
    private var mediaFormat: MediaFormat? = null
    private var isEncoding = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)

    // Capture state
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentQuality = QUALITY_AUTO

    // Threading
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    // Frame processing
    private val frameQueue = mutableListOf<ByteArray>()
    private var frameStats = FrameStats()

    // Callbacks
    var onFrameEncoded: ((ByteArray, Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatsUpdated: ((FrameStats) -> Unit)? = null

    data class FrameStats(
        var framesEncoded: Long = 0,
        var framesDropped: Long = 0,
        var currentBitrate: Long = 0,
        var averageBitrate: Long = 0,
        var encodingTime: Long = 0,
        var lastFrameTime: Long = 0
    )

    // Image listener
    private val imageListener = OnImageAvailableListener { reader ->
        if (!isEncoding.get() || isPaused.get()) {
            return@OnImageAvailableListener
        }

        try {
            val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
            processImage(image)
            image.close()
        } catch (e: Exception) {
            Timber.e(e, "Error processing captured image")
        }
    }

    fun initialize(imageReader: ImageReader, virtualDisplay: VirtualDisplay) {
        Timber.d("Initializing ScreenCaptureManager")

        this.imageReader = imageReader
        this.virtualDisplay = virtualDisplay

        // Load quality setting from preferences
        loadQualityFromSettings()

        initializeThreading()
        initializeEncoder()

        // Set up image listener
        imageReader.setOnImageAvailableListener(imageListener, captureHandler)

        Timber.d("ScreenCaptureManager initialized with quality: ${currentQuality.name}")
    }

    fun start() {
        if (isEncoding.get()) {
            Timber.w("ScreenCaptureManager already started")
            return
        }

        try {
            startEncoding()
            isEncoding.set(true)
            isPaused.set(false)

            // Start stats monitoring
            startStatsMonitoring()

            Timber.d("ScreenCaptureManager started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start ScreenCaptureManager")
            onError?.invoke("Failed to start screen capture: ${e.message}")
        }
    }

    fun stop() {
        if (!isEncoding.get()) {
            return
        }

        Timber.d("Stopping ScreenCaptureManager")

        isEncoding.set(false)
        isPaused.set(false)

        try {
            stopEncoding()
            cleanupThreading()
            clearFrameQueue()

            Timber.d("ScreenCaptureManager stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ScreenCaptureManager")
        }
    }

    fun pause() {
        if (isEncoding.get()) {
            isPaused.set(true)
            Timber.d("ScreenCaptureManager paused")
        }
    }

    fun resume() {
        if (isEncoding.get()) {
            isPaused.set(false)
            Timber.d("ScreenCaptureManager resumed")
        }
    }

    fun setQuality(quality: String) {
        val newQuality = when (quality) {
            "low" -> QUALITY_LOW
            "medium" -> QUALITY_MEDIUM
            "high" -> QUALITY_HIGH
            else -> QUALITY_AUTO
        }

        if (newQuality != currentQuality) {
            currentQuality = newQuality

            // Restart encoder with new quality if currently encoding
            if (isEncoding.get()) {
                encoderHandler?.post {
                    restartEncoderWithNewQuality()
                }
            }

            Timber.d("Quality changed to: ${newQuality.name}")
        }
    }

    fun getFrameStats(): FrameStats = frameStats

    /**
     * Load video quality setting from SettingsManager
     */
    private fun loadQualityFromSettings() {
        val qualitySetting = SettingsManager.getVideoQuality()
        currentQuality = when (qualitySetting) {
            "low" -> QUALITY_LOW
            "medium" -> QUALITY_MEDIUM
            "high" -> QUALITY_HIGH
            "auto" -> QUALITY_AUTO
            else -> QUALITY_MEDIUM // Default fallback
        }
        Timber.d("Loaded quality setting: $qualitySetting -> ${currentQuality.name}")
    }

    /**
     * Update video quality from current settings
     * Can be called when settings change to apply new quality
     */
    fun updateQualityFromSettings() {
        val qualitySetting = SettingsManager.getVideoQuality()
        setQuality(qualitySetting)
    }

    private fun initializeThreading() {
        // Capture thread for image processing
        captureThread = HandlerThread("ScreenCapture-Capture", Thread.NORM_PRIORITY).apply {
            start()
            captureHandler = Handler(looper)
        }

        // Encoder thread for H.264 encoding
        encoderThread = HandlerThread("ScreenCapture-Encoder", Thread.NORM_PRIORITY).apply {
            start()
            encoderHandler = Handler(looper)
        }
    }

    private fun cleanupThreading() {
        captureThread?.quitSafely()
        encoderThread?.quitSafely()

        captureThread = null
        captureHandler = null
        encoderThread = null
        encoderHandler = null
    }

    @SuppressLint("NewApi")
    private fun initializeEncoder() {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.WINDOW_SERVICE).javaClass.getMethod("getDefaultDisplay").invoke(null) as Display
            }

            val metrics = android.util.DisplayMetrics()
            display.getMetrics(metrics)

            // Use current quality settings
            val quality = currentQuality

            mediaFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, quality.width, quality.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, quality.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)

                // Additional encoding parameters
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setInteger(MediaFormat.KEY_COMPLEXITY, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE).apply {
                setCallback(mediaCodecCallback, encoderHandler)
                configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            Timber.d("Encoder initialized: ${quality.width}x${quality.height} @ ${quality.frameRate}fps, ${quality.bitRate}bps")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize encoder")
            throw e
        }
    }

    private fun startEncoding() {
        encoderHandler?.post {
            try {
                mediaCodec?.start()
                Timber.d("Encoder started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start encoder")
                onError?.invoke("Failed to start encoder: ${e.message}")
            }
        }
    }

    private fun stopEncoding() {
        encoderHandler?.post {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
                mediaCodec = null
                Timber.d("Encoder stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping encoder")
            }
        }
    }

    private fun restartEncoderWithNewQuality() {
        try {
            // Stop current encoder
            mediaCodec?.stop()
            mediaCodec?.release()

            // Reinitialize with new quality
            initializeEncoder()
            mediaCodec?.start()

            Timber.d("Encoder restarted with new quality: ${currentQuality.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to restart encoder")
            onError?.invoke("Failed to update quality: ${e.message}")
        }
    }

    private fun processImage(image: Image) {
        if (isPaused.get()) {
            return
        }

        val startTime = System.currentTimeMillis()

        try {
            // Convert Image to ByteArray (simplified conversion)
            val imageData = imageToByteArray(image)

            // Queue frame for encoding
            synchronized(frameQueue) {
                frameQueue.add(imageData)

                // Limit queue size to prevent memory buildup
                while (frameQueue.size > FRAME_BUFFER_SIZE) {
                    frameQueue.removeAt(0)
                    frameStats.framesDropped++
                }
            }

            // Trigger encoding
            encoderHandler?.post {
                processNextFrame()
            }

        } catch (e: Exception) {
            Timber.e(e, "Error processing image")
        } finally {
            val processingTime = System.currentTimeMillis() - startTime
            frameStats.encodingTime = (frameStats.encodingTime * 9 + processingTime) / 10 // Moving average
        }
    }

    private fun imageToByteArray(image: Image): ByteArray {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // Create bitmap from image data
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop to actual dimensions and convert to JPEG
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        val stream = ByteArrayOutputStream()
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)

        return stream.toByteArray()
    }

    private fun processNextFrame() {
        if (!isEncoding.get() || isPaused.get()) {
            return
        }

        val frame = synchronized(frameQueue) {
            if (frameQueue.isNotEmpty()) {
                frameQueue.removeAt(0)
            } else null
        }

        frame?.let {
            encodeFrame(it)
        }
    }

    private fun encodeFrame(frameData: ByteArray) {
        try {
            // For H.264 encoding, we would typically use Surface encoding
            // This is a simplified implementation that processes raw frames
            // In a production app, you'd use Surface-based encoding with MediaCodec

            val timestamp = System.nanoTime() / 1000 // Convert to microseconds
            onFrameEncoded?.invoke(frameData, timestamp)

            frameStats.framesEncoded++
            frameStats.lastFrameTime = System.currentTimeMillis()

        } catch (e: Exception) {
            Timber.e(e, "Error encoding frame")
        }
    }

    private val mediaCodecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Handle input buffer for Surface encoding
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val outputBuffer = codec.getOutputBuffer(index)
                if (outputBuffer != null && info.size > 0) {
                    val encodedData = ByteArray(info.size)
                    outputBuffer.get(encodedData)

                    onFrameEncoded?.invoke(encodedData, info.presentationTimeUs)

                    frameStats.framesEncoded++
                    updateBitrateStats(info.size)
                }

                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Timber.e(e, "Error processing output buffer")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Timber.e(e, "Encoder error")
            onError?.invoke("Encoder error: ${e.message}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Timber.d("Output format changed: $format")
        }
    }

    private fun updateBitrateStats(frameSize: Int) {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - frameStats.lastFrameTime

        if (timeDiff > 0) {
            frameStats.currentBitrate = (frameSize * 8 * 1000) / timeDiff // bits per second
            frameStats.averageBitrate = (frameStats.averageBitrate * 9 + frameStats.currentBitrate) / 10
        }
    }

    private fun startStatsMonitoring() {
        // Start a coroutine to monitor and report stats
        GlobalScope.launch {
            while (isEncoding.get()) {
                delay(1000) // Update stats every second

                if (isEncoding.get()) {
                    onStatsUpdated?.invoke(frameStats)
                }
            }
        }
    }

    private fun clearFrameQueue() {
        synchronized(frameQueue) {
            frameQueue.clear()
        }
    }
}