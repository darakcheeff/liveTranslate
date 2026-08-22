package com.livetranslate.phone.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.livetranslate.core.model.GeminiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.UUID

object PhoneBluetoothSyncManager {

    val SYNC_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SerialPort SPP

    @SuppressLint("MissingPermission")
    suspend fun syncConfigToWatch(
        macAddress: String,
        config: GeminiConfig
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.failure(IllegalStateException("Bluetooth не поддерживается"))

        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
            val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SYNC_UUID)
            socket.connect()

            val jsonPayload = Json.encodeToString(config)
            val outputStream: OutputStream = socket.outputStream
            outputStream.write(jsonPayload.toByteArray(Charsets.UTF_8))
            outputStream.flush()

            socket.close()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
