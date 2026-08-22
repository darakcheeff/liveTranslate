package com.livetranslate.phone.sync

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.phone.databinding.ActivityQrScannerBinding
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isScanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { qrText ->
                        if (!isScanned) {
                            isScanned = true
                            handleQrScanned(qrText)
                        }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка запуска камеры: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleQrScanned(qrContent: String) {
        lifecycleScope.launch {
            // QR contains MAC address or format: "LIVE_WATCH:<MAC>"
            val macAddress = if (qrContent.startsWith("LIVE_WATCH:")) {
                qrContent.removePrefix("LIVE_WATCH:")
            } else {
                qrContent.trim()
            }

            Toast.makeText(this@QrScannerActivity, "Часы найдены ($macAddress). Передача настроек...", Toast.LENGTH_SHORT).show()

            val prefs = EncryptedPreferencesManager(this@QrScannerActivity)
            val config = prefs.loadConfig()

            val result = PhoneBluetoothSyncManager.syncConfigToWatch(macAddress, config)
            if (result.isSuccess) {
                Toast.makeText(this@QrScannerActivity, "Настройки успешно переданы на часы!", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this@QrScannerActivity, "Сбой передачи: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                isScanned = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private class QrCodeAnalyzer(private val onQrDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader()

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val buffer: ByteBuffer = imageProxy.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val width = imageProxy.width
            val height = imageProxy.height
            val source = PlanarYUVLuminanceSource(
                data, width, height, 0, 0, width, height, false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                val result = reader.decodeWithState(binaryBitmap)
                onQrDetected(result.text)
            } catch (e: NotFoundException) {
                // No QR in this frame
            } finally {
                imageProxy.close()
            }
        }
    }
}
