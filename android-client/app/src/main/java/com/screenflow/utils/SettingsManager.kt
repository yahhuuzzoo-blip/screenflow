package com.screenflow.utils

import android.content.Context
import android.content.SharedPreferences
import com.screenflow.BuildConfig

/**
 * Centralized settings management using SharedPreferences
 * Provides access to all app settings with default values
 */
object SettingsManager {

    private const val PREFS_NAME = "screenflow_settings"

    // Keys
    private const val KEY_VIDEO_QUALITY = "video_quality"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_SHOW_NOTIFICATIONS = "show_notifications"
    private const val KEY_RELAY_SERVER_URL = "relay_server_url"

    // Default values
    private const val DEFAULT_VIDEO_QUALITY = "medium"
    private const val DEFAULT_AUTO_CONNECT = false
    private const val DEFAULT_KEEP_AWAKE = true
    private const val DEFAULT_SHOW_NOTIFICATIONS = true
    private const val DEFAULT_RELAY_SERVER_URL = "ws://relay.screenflow.app:8082"

    private lateinit var preferences: SharedPreferences

    /**
     * Initialize the SettingsManager with application context
     * Must be called before accessing any settings
     */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get current video quality setting
     * @return Quality setting: "auto", "low", "medium", or "high"
     */
    fun getVideoQuality(): String {
        return preferences.getString(KEY_VIDEO_QUALITY, DEFAULT_VIDEO_QUALITY) ?: DEFAULT_VIDEO_QUALITY
    }

    /**
     * Set video quality setting
     * @param quality Quality setting: "auto", "low", "medium", or "high"
     */
    fun setVideoQuality(quality: String) {
        preferences.edit().putString(KEY_VIDEO_QUALITY, quality).apply()
    }

    /**
     * Check if auto-connect is enabled
     * @return true if auto-connect should occur on app launch
     */
    fun isAutoConnectEnabled(): Boolean {
        return preferences.getBoolean(KEY_AUTO_CONNECT, DEFAULT_AUTO_CONNECT)
    }

    /**
     * Set auto-connect setting
     * @param enabled true to auto-connect on app launch
     */
    fun setAutoConnect(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    /**
     * Check if keep device awake is enabled
     * @return true if device should stay awake during sharing
     */
    fun isKeepAwakeEnabled(): Boolean {
        return preferences.getBoolean(KEY_KEEP_AWAKE, DEFAULT_KEEP_AWAKE)
    }

    /**
     * Set keep awake setting
     * @param enabled true to keep device awake during sharing
     */
    fun setKeepAwake(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_AWAKE, enabled).apply()
    }

    /**
     * Check if notifications should be shown
     * @return true if notifications are enabled
     */
    fun isShowNotificationsEnabled(): Boolean {
        return preferences.getBoolean(KEY_SHOW_NOTIFICATIONS, DEFAULT_SHOW_NOTIFICATIONS)
    }

    /**
     * Set notification display setting
     * @param enabled true to show notifications
     */
    fun setShowNotifications(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_NOTIFICATIONS, enabled).apply()
    }

    /**
     * Get relay server URL
     * @return WebSocket URL of the relay server
     */
    fun getRelayServerUrl(): String {
        return preferences.getString(KEY_RELAY_SERVER_URL, DEFAULT_RELAY_SERVER_URL) ?: DEFAULT_RELAY_SERVER_URL
    }

    /**
     * Set relay server URL
     * @param url WebSocket URL of the relay server
     */
    fun setRelayServerUrl(url: String) {
        preferences.edit().putString(KEY_RELAY_SERVER_URL, url).apply()
    }

    /**
     * Get app version from BuildConfig
     * @return Version name string
     */
    fun getAppVersion(): String {
        return BuildConfig.VERSION_NAME
    }
}
