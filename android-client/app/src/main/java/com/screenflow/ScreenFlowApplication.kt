package com.screenflow

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.screenflow.utils.SettingsManager
import timber.log.Timber

/**
 * ScreenFlow application class for global initialization
 */
class ScreenFlowApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_SCREEN_CAPTURE = "screen_capture_channel"
        const val NOTIFICATION_CHANNEL_CONNECTION = "connection_channel"
        const val NOTIFICATION_CHANNEL_SERVICE = "service_channel"

        lateinit var instance: ScreenFlowApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize logging
        initializeLogging()

        // Initialize settings manager
        SettingsManager.initialize(this)

        // Create notification channels
        createNotificationChannels()

        Timber.d("ScreenFlow application initialized")
    }

    private fun initializeLogging() {
        if (BuildConfig.DEBUG) {
            // Debug logging with Timber
            Timber.plant(Timber.DebugTree())
        } else {
            // Release logging (could integrate with crash reporting)
            Timber.plant(object : Timber.Tree() {
                override fun isLoggable(tag: String?, priority: Int): Boolean {
                    return priority >= android.util.Log.INFO
                }

                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    // Could integrate with Firebase Crashlytics or other crash reporting
                    if (t != null) {
                        // Log exceptions to crash reporting service
                    }
                }
            })
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Screen capture notification channel
            val screenCaptureChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SCREEN_CAPTURE,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing screen capture session"
                enableVibration(false)
                setSound(null, null)
            }

            // Connection status notification channel
            val connectionChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_CONNECTION,
                "Connection Status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Connection status with PC server"
                enableVibration(true)
                setShowBadge(true)
            }

            // Service notification channel
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                "Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background network service"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(listOf(
                screenCaptureChannel,
                connectionChannel,
                serviceChannel
            ))

            Timber.d("Notification channels created")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Timber.d("ScreenFlow application terminated")
    }
}