package com.screenflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.screenflow.databinding.ActivityMainBinding
import com.screenflow.service.ScreenCaptureService
import com.screenflow.utils.PermissionUtils
import com.screenflow.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import timber.log.Timber

/**
 * Main activity for ScreenFlow Android client
 * Handles service controls, pairing, and permissions
 */
class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    // Permission request codes
    companion object {
        private const val PERMISSION_REQUEST_OVERLAY = 1001
        private const val PERMISSION_REQUEST_CAMERA = 1002
        private const val PERMISSION_REQUEST_MEDIA_PROJECTION = 1003
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1004
    }

    // Activity result launchers
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            Timber.d("Overlay permission granted")
            checkAndStartService()
        } else {
            showPermissionDeniedDialog("overlay permission")
        }
    }

    private val qrCodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result: ScanIntentResult ->
        if (result.contents == null) {
            Timber.d("QR code scan cancelled")
            Toast.makeText(this, "QR code scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
            handleQRCodeResult(result.contents)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Timber.d("Notification permission granted")
            checkAndStartService()
        } else {
            showPermissionDeniedDialog("notification permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeViewModel()
        setupUI()
        observeViewModel()

        Timber.d("MainActivity created")
    }

    private fun initializeViewModel() {
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
    }

    private fun setupUI() {
        // Set up click listeners
        binding.buttonStartService.setOnClickListener {
            startScreenCaptureService()
        }

        binding.buttonStopService.setOnClickListener {
            stopScreenCaptureService()
        }

        binding.buttonPairWithPc.setOnClickListener {
            showPairingDialog()
        }

        binding.buttonScanQrCode.setOnClickListener {
            startQRCodeScanner()
        }

        binding.buttonSettings.setOnClickListener {
            openSettings()
        }

        // Initial UI state
        updateUIState()
    }

    private fun observeViewModel() {
        // Observe connection status
        viewModel.connectionStatus.observe(this) { status ->
            updateConnectionStatus(status)
        }

        // Observe service status
        viewModel.isServiceRunning.observe(this) { isRunning ->
            updateServiceStatus(isRunning)
        }

        // Observe pairing status
        viewModel.pairingStatus.observe(this) { status ->
            updatePairingStatus(status)
        }

        // Observe errors
        viewModel.error.observe(this) { error ->
            if (error != null) {
                showError(error)
                viewModel.clearError()
            }
        }

        // Observe statistics
        viewModel.connectionStats.observe(this) { stats ->
            updateStatistics(stats)
        }
    }

    private fun updateUIState() {
        val isServiceRunning = viewModel.isServiceRunning.value ?: false
        val isConnected = viewModel.connectionStatus.value == MainViewModel.ConnectionStatus.CONNECTED

        binding.buttonStartService.isVisible = !isServiceRunning
        binding.buttonStopService.isVisible = isServiceRunning
        binding.buttonPairWithPc.isVisible = !isConnected
        binding.buttonScanQrCode.isVisible = !isConnected

        updateConnectionStatus(viewModel.connectionStatus.value ?: MainViewModel.ConnectionStatus.DISCONNECTED)
    }

    private fun updateConnectionStatus(status: MainViewModel.ConnectionStatus) {
        when (status) {
            MainViewModel.ConnectionStatus.DISCONNECTED -> {
                binding.textConnectionStatus.text = getString(R.string.connection_status_disconnected)
                binding.indicatorConnectionStatus.setImageResource(R.drawable.ic_status_disconnected)
                binding.cardConnectionStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_disconnected)
                )
            }
            MainViewModel.ConnectionStatus.CONNECTING -> {
                binding.textConnectionStatus.text = getString(R.string.connection_status_connecting)
                binding.indicatorConnectionStatus.setImageResource(R.drawable.ic_status_connecting)
                binding.cardConnectionStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_connecting)
                )
            }
            MainViewModel.ConnectionStatus.CONNECTED -> {
                val serverInfo = viewModel.getServerInfo()
                binding.textConnectionStatus.text = getString(R.string.service_notification_connected, serverInfo)
                binding.indicatorConnectionStatus.setImageResource(R.drawable.ic_status_connected)
                binding.cardConnectionStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_connected)
                )
            }
        }
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        binding.layoutServiceStatus.isVisible = isRunning
        updateUIState()
    }

    private fun updatePairingStatus(status: MainViewModel.PairingStatus) {
        when (status) {
            MainViewModel.PairingStatus.NONE -> {
                // Clear any pairing status display
            }
            MainViewModel.PairingStatus.PAIRING -> {
                Toast.makeText(this, "Pairing in progress...", Toast.LENGTH_SHORT).show()
            }
            MainViewModel.PairingStatus.SUCCESS -> {
                Toast.makeText(this, getString(R.string.pairing_success), Toast.LENGTH_SHORT).show()
            }
            MainViewModel.PairingStatus.FAILED -> {
                Toast.makeText(this, getString(R.string.pairing_failed), Toast.LENGTH_SHORT).show()
            }
            MainViewModel.PairingStatus.EXPIRED -> {
                Toast.makeText(this, getString(R.string.pairing_expired), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatistics(stats: MainViewModel.ConnectionStats) {
        binding.layoutStats.isVisible = true

        binding.textResolution.text = getString(R.string.stats_resolution, stats.width, stats.height)
        binding.textFps.text = getString(R.string.stats_fps, stats.fps)
        binding.textBitrate.text = getString(R.string.stats_bitrate, stats.bitrate / 1000000.0)
        binding.textLatency.text = getString(R.string.stats_latency, stats.latency)

        val connectionTime = formatDuration(stats.connectionTime)
        binding.textConnectionTime.text = getString(R.string.stats_connection_time, connectionTime)
    }

    private fun startScreenCaptureService() {
        Timber.d("Starting screen capture service")

        // Check required permissions
        if (!checkRequiredPermissions()) {
            return
        }

        // Start the service
        val intent = Intent(this, ScreenCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        viewModel.setServiceRunning(true)
    }

    private fun stopScreenCaptureService() {
        Timber.d("Stopping screen capture service")

        val intent = Intent(this, ScreenCaptureService::class.java)
        stopService(intent)

        viewModel.setServiceRunning(false)
        viewModel.setConnectionStatus(MainViewModel.ConnectionStatus.DISCONNECTED)
    }

    private fun checkRequiredPermissions(): Boolean {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return false
        }

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission()
                return false
            }
        }

        return true
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_overlay_title))
            .setMessage(getString(R.string.permission_overlay_message))
            .setPositiveButton(getString(R.string.permission_overlay_settings)) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton(getString(R.string.action_cancel)) { _, _ ->
                Toast.makeText(this, "Overlay permission required for input injection", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showPairingDialog() {
        val options = arrayOf("Scan QR Code", "Enter Manual Code")

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pairing_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startQRCodeScanner()
                    1 -> showManualCodeDialog()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun startQRCodeScanner() {
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {

            EasyPermissions.requestPermissions(
                this,
                getString(R.string.qr_permission_required),
                PERMISSION_REQUEST_CAMERA,
                Manifest.permission.CAMERA
            )
            return
        }

        qrCodeLauncher.launch(ScanContract.createScanIntent())
    }

    private fun showManualCodeDialog() {
        val editText = android.widget.EditText(this)
        editText.hint = getString(R.string.manual_code_hint)
        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_UPPERCASE

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pairing_title))
            .setMessage(getString(R.string.pairing_instructions))
            .setView(editText)
            .setPositiveButton(getString(R.string.action_connect)) { _, _ ->
                val code = editText.text.toString().trim().uppercase()
                if (code.length == 6) {
                    handleManualCode(code)
                } else {
                    Toast.makeText(this, "Please enter a valid 6-character code", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun handleQRCodeResult(qrData: String) {
        Timber.d("QR code scanned: $qrData")

        lifecycleScope.launch {
            try {
                val success = viewModel.processQRCodeData(qrData)
                if (success) {
                    Toast.makeText(this@MainActivity, "QR code processed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.qr_invalid_code), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing QR code")
                Toast.makeText(this@MainActivity, "Error processing QR code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleManualCode(code: String) {
        Timber.d("Manual code entered: $code")

        lifecycleScope.launch {
            try {
                val success = viewModel.processManualCode(code)
                if (success) {
                    Toast.makeText(this@MainActivity, "Connecting to PC...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.pairing_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing manual code")
                Toast.makeText(this@MainActivity, "Error processing pairing code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSettings() {
        // TODO: Implement settings activity
        Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun showPermissionDeniedDialog(permission: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("$permission is required for ScreenFlow to function properly. Please enable it in settings.")
            .setPositiveButton("Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun showError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds % 60)
            else -> String.format("00:%02d", seconds)
        }
    }

    // Permission callbacks
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        Timber.d("Permissions granted for request: $requestCode")

        when (requestCode) {
            PERMISSION_REQUEST_CAMERA -> {
                startQRCodeScanner()
            }
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        Timber.d("Permissions denied for request: $requestCode")

        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            AppSettingsDialog.Builder(this).build().show()
        } else {
            Toast.makeText(this, "Camera permission required for QR code scanning", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Update UI based on current service status
        viewModel.updateServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MainActivity destroyed")
    }
}