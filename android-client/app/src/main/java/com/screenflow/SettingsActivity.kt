package com.screenflow

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.screenflow.databinding.ActivitySettingsBinding
import com.screenflow.utils.SettingsManager
import timber.log.Timber

/**
 * Settings Activity for configuring ScreenFlow preferences
 * Allows users to configure video quality, connection, and behavior settings
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupClickListeners()

        Timber.d("SettingsActivity created")
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun loadSettings() {
        // Load video quality
        val quality = SettingsManager.getVideoQuality()
        updateVideoQualityDisplay(quality)

        // Load auto-connect
        binding.switchAutoConnect.isChecked = SettingsManager.isAutoConnectEnabled()

        // Load keep awake
        binding.switchKeepAwake.isChecked = SettingsManager.isKeepAwakeEnabled()

        // Load show notifications
        binding.switchShowNotifications.isChecked = SettingsManager.isShowNotificationsEnabled()

        // Load relay server URL
        binding.textRelayServerUrlValue.text = SettingsManager.getRelayServerUrl()

        // Load app version
        binding.textVersionValue.text = SettingsManager.getAppVersion()
    }

    private fun setupClickListeners() {
        // Video Quality
        binding.itemVideoQuality.setOnClickListener {
            showVideoQualityDialog()
        }

        // Auto-connect
        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setAutoConnect(isChecked)
            Timber.d("Auto-connect set to: $isChecked")
        }

        // Keep Awake
        binding.switchKeepAwake.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setKeepAwake(isChecked)
            Timber.d("Keep awake set to: $isChecked")
        }

        // Show Notifications
        binding.switchShowNotifications.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setShowNotifications(isChecked)
            Timber.d("Show notifications set to: $isChecked")
        }

        // Relay Server URL
        binding.itemRelayServerUrl.setOnClickListener {
            showRelayServerUrlDialog()
        }

        // About
        binding.itemAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun showVideoQualityDialog() {
        val currentQuality = SettingsManager.getVideoQuality()
        val qualities = arrayOf("auto", "low", "medium", "high")
        val qualityLabels = arrayOf(
            getString(R.string.quality_auto),
            getString(R.string.quality_low),
            getString(R.string.quality_medium),
            getString(R.string.quality_high)
        )

        val currentIndex = qualities.indexOf(currentQuality).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.quality_title))
            .setSingleChoiceItems(qualityLabels, currentIndex) { dialog, which ->
                val selectedQuality = qualities[which]
                SettingsManager.setVideoQuality(selectedQuality)
                updateVideoQualityDisplay(selectedQuality)
                Timber.d("Video quality set to: $selectedQuality")
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun updateVideoQualityDisplay(quality: String) {
        val displayText = when (quality) {
            "auto" -> getString(R.string.quality_auto)
            "low" -> getString(R.string.quality_low)
            "medium" -> getString(R.string.quality_medium)
            "high" -> getString(R.string.quality_high)
            else -> getString(R.string.quality_medium)
        }
        binding.textVideoQualityValue.text = displayText
    }

    private fun showRelayServerUrlDialog() {
        val editText = EditText(this).apply {
            setText(SettingsManager.getRelayServerUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "ws://server:port"
            setPadding(50, 20, 50, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Relay Server URL")
            .setMessage("Enter the WebSocket URL of the relay server")
            .setView(editText)
            .setPositiveButton(getString(R.string.action_ok)) { _, _ ->
                val url = editText.text.toString().trim()
                if (validateRelayServerUrl(url)) {
                    SettingsManager.setRelayServerUrl(url)
                    binding.textRelayServerUrlValue.text = url
                    Timber.d("Relay server URL set to: $url")
                } else {
                    Toast.makeText(this, "Invalid URL format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun validateRelayServerUrl(url: String): Boolean {
        if (url.isBlank()) {
            return false
        }

        // Check if URL starts with ws:// or wss://
        return url.startsWith("ws://") || url.startsWith("wss://")
    }

    private fun showAboutDialog() {
        val message = """
            ScreenFlow - Remote Screen Mirroring

            Version: ${SettingsManager.getAppVersion()}

            Share your Android screen wirelessly with your PC using secure WebSocket connections and WebRTC technology.

            Features:
            • Real-time screen mirroring
            • QR code pairing
            • Adjustable video quality
            • Low latency streaming
            • Secure encrypted connection

            © 2024 ScreenFlow Project
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_about))
            .setMessage(message)
            .setPositiveButton(getString(R.string.action_ok), null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("SettingsActivity destroyed")
    }
}
