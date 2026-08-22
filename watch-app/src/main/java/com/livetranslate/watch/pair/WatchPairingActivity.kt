package com.livetranslate.watch.pair

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.livetranslate.core.model.GeminiConfig
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.watch.databinding.ActivityWatchPairBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.UUID

class WatchPairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchPairBinding
    private lateinit var prefs: EncryptedPreferencesManager
    private var serverSocket: BluetoothServerSocket? = null
    private var isListening = true

    companion object {
        val SYNC_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferencesManager(this)
        generateAndDisplayQr()
        startBluetoothServer()
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun generateAndDisplayQr() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val mac = adapter?.address ?: "02:00:00:00:00:00"
        val qrContent = "LIVE_WATCH:$mac"

        val bitmap = encodeQrBitmap(qrContent, 240, 240)
        binding.ivQrCode.setImageBitmap(bitmap)
    }

    private fun encodeQrBitmap(content: String, width: Int, height: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("GeminiLiveTranslate", SYNC_UUID)
                withContext(Dispatchers.Main) {
                    binding.tvPairStatus.text = "Ожидание телефона..."
                }

                while (isListening) {
                    val socket: BluetoothSocket = serverSocket?.accept() ?: break
                    handleIncomingSync(socket)
                    break
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvPairStatus.text = "Ошибка BT: ${e.message}"
                }
            }
        }
    }

    private suspend fun handleIncomingSync(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream = socket.inputStream
            val buffer = ByteArray(4096)
            val bytesRead = inputStream.read(buffer)
            if (bytesRead > 0) {
                val jsonStr = String(buffer, 0, bytesRead, Charsets.UTF_8)
                val config = Json.decodeFromString<GeminiConfig>(jsonStr)
                prefs.saveConfig(config)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WatchPairingActivity, "Настройки получены!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            socket.close()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@WatchPairingActivity, "Ошибка приема: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
