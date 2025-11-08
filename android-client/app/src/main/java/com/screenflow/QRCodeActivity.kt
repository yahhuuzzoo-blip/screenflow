package com.screenflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.screenflow.databinding.ActivityQrcodeBinding
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import timber.log.Timber

/**
 * QR code scanning activity for PC pairing
 */
class QRCodeActivity : AppCompatActivity(), BarcodeCallback {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
        private const val RESULT_QR_CODE = 1001
        private const val RESULT_MANUAL_CODE = 1002
    }

    private lateinit var binding: ActivityQrcodeBinding
    private var isScanning = false

    // Permission launcher for camera
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityQrcodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBarcodeScanner()
        setupUI()
        checkCameraPermission()
    }

    private fun setupBarcodeScanner() {
        binding.barcodeScanner.initializeFromIntent(intent)
        binding.barcodeScanner.decodeContinuous(this)

        // Configure barcode scanner
        binding.barcodeScanner.setStatusText(getString(R.string.qr_scan_instructions))
        binding.barcodeScanner.cameraSettings.isAutoTorchEnabled = false
        binding.barcodeScanner.cameraSettings.isAutoFocusEnabled = true

        // Set viewfinder settings
        val viewfinderView = binding.barcodeScanner.viewFinder
        viewfinderView.setLaserVisibility(true)
        viewfinderView.setLaserColor(ContextCompat.getColor(this, R.color.qr_scan_frame))
        viewfinderView.setBorderColor(ContextCompat.getColor(this, R.color.qr_scan_frame))
        viewfinderView.setBorderStrokeWidth(4)
        viewfinderView.setBorderCornerRadius(16)

        isScanning = true
    }

    private fun setupUI() {
        // Set up click listeners
        binding.buttonManualEntry.setOnClickListener {
            showManualCodeDialog()
        }

        binding.buttonToggleFlash.setOnClickListener {
            toggleFlash()
        }

        binding.buttonClose.setOnClickListener {
            finish()
        }

        // Set up result handling
        binding.barcodeScanner.decodeSingle { result ->
            if (result != null && result.text != null) {
                handleQRCodeResult(result.text.toString())
            }
        }

        // Adjust viewfinder size based on screen dimensions
        adjustViewfinderSize()
    }

    private fun adjustViewfinderSize() {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val viewfinderSize = minOf(screenWidth, screenHeight) * 0.7f

        val layoutParams = binding.barcodeScanner.viewFinder.layoutParams
        layoutParams.width = viewfinderSize.toInt()
        layoutParams.height = viewfinderSize.toInt()
        binding.barcodeScanner.viewFinder.layoutParams = layoutParams
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        EasyPermissions.requestPermissions(
            this,
            getString(R.string.qr_permission_required),
            CAMERA_PERMISSION_REQUEST,
            Manifest.permission.CAMERA
        )
    }

    @AfterPermissionGranted(CAMERA_PERMISSION_REQUEST)
    private fun onCameraPermissionGranted() {
        startCamera()
    }

    private fun startCamera() {
        try {
            binding.barcodeScanner.resume()
            isScanning = true
            binding.textViewInstructions.text = getString(R.string.qr_scan_instructions)
            binding.layoutPermissionRequest.visibility = View.GONE
            binding.barcodeScanner.visibility = View.VISIBLE
            binding.layoutControls.visibility = View.VISIBLE
        } catch (e: Exception) {
            Timber.e(e, "Failed to start camera")
            showError("Failed to start camera: ${e.message}")
        }
    }

    private fun stopCamera() {
        try {
            binding.barcodeScanner.pause()
            isScanning = false
        } catch (e: Exception) {
            Timber.e(e, "Error stopping camera")
        }
    }

    override fun onResume() {
        super.onResume()
        if (isScanning) {
            binding.barcodeScanner.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.barcodeScanner.pause()
    }

    // BarcodeCallback implementation
    override fun barcodeResult(result: BarcodeResult?) {
        if (result != null && result.text != null) {
            handleQRCodeResult(result.text.toString())
        }
    }

    override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {
        // Optional: Show visual feedback for detected points
    }

    private fun handleQRCodeResult(qrData: String) {
        if (!isScanning) {
            return
        }

        Timber.d("QR code scanned: $qrData")

        // Validate QR code
        if (validateQRCode(qrData)) {
            // Vibrate to indicate successful scan
            try {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(200)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error providing haptic feedback")
            }

            // Stop scanning and return result
            stopCamera()
            returnQRCodeResult(qrData)
        } else {
            showInvalidQRCodeDialog()
            // Resume scanning
            binding.barcodeScanner.resume()
        }
    }

    private fun validateQRCode(qrData: String): Boolean {
        return try {
            // Basic validation - check if it's valid ScreenFlow QR data
            qrData.contains("screenflow") ||
            qrData.contains("session_id") ||
            qrData.contains("server_ip") ||
            (qrData.length > 20 && qrData.contains("{")) // Likely JSON
        } catch (e: Exception) {
            Timber.e(e, "Error validating QR code")
            false
        }
    }

    private fun returnQRCodeResult(qrData: String) {
        val resultIntent = intent
        resultIntent.putExtra("qr_data", qrData)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showManualCodeDialog() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pairing_title))
            .setMessage(getString(R.string.pairing_instructions))

        val editText = android.widget.EditText(this)
        editText.hint = getString(R.string.manual_code_hint)
        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_UPPERCASE
        editText.maxEms = 6
        editText.filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(6))

        alertDialog.setView(editText)
        alertDialog.setPositiveButton(getString(R.string.action_connect)) { _, _ ->
            val code = editText.text.toString().trim()
            if (validateManualCode(code)) {
                returnManualCodeResult(code)
            } else {
                Toast.makeText(this, "Please enter a valid 6-character code", Toast.LENGTH_SHORT).show()
            }
        }
        alertDialog.setNegativeButton(getString(R.string.action_cancel), null)
        alertDialog.show()
    }

    private fun validateManualCode(code: String): Boolean {
        return code.length == 6 && code.all { it.isLetterOrDigit() }
    }

    private fun returnManualCodeResult(code: String) {
        val resultIntent = intent
        resultIntent.putExtra("manual_code", code)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun toggleFlash() {
        try {
            val isFlashOn = binding.barcodeScanner.cameraSettings.isTorchEnabled
            binding.barcodeScanner.setTorch(!isFlashOn)

            val flashIcon = if (!isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            binding.buttonToggleFlash.setImageResource(flashIcon)
        } catch (e: Exception) {
            Timber.e(e, "Error toggling flash")
        }
    }

    private fun showPermissionDeniedDialog() {
        binding.layoutPermissionRequest.visibility = View.VISIBLE
        binding.barcodeScanner.visibility = View.GONE
        binding.layoutControls.visibility = View.GONE

        binding.textViewInstructions.text = getString(R.string.qr_permission_required)
        binding.buttonRequestPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.buttonClose.setOnClickListener {
            finish()
        }
    }

    private fun showInvalidQRCodeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Invalid QR Code")
            .setMessage(getString(R.string.qr_invalid_code))
            .setPositiveButton("Scan Again") { _, _ ->
                binding.barcodeScanner.resume()
            }
            .setNegativeButton("Manual Entry") { _, _ ->
                showManualCodeDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // Handle hardware back button
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (binding.barcodeScanner.cameraSettings.isTorchEnabled) {
                toggleFlash()
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Permission handling
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    // EasyPermissions callbacks
    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        Timber.d("Permissions granted: $perms")
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        Timber.d("Permissions denied: $perms")

        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            showPermissionDeniedDialog()
        } else {
            Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}