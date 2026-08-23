package com.livetranslate.audio.capture

import android.content.Context
import android.util.Log
import com.google.speech.micro.GoogleEndpointer
import com.google.speech.micro.GoogleEndpointerData

class GoogleSpeechDetector(private val context: Context) {
    companion object {
        private const val TAG = "GoogleSpeechDetector"
    }

    private var endpointerData: GoogleEndpointerData? = null
    private var endpointer: GoogleEndpointer? = null
    val isInitialized: Boolean get() = endpointer != null

    fun initialize(): Boolean {
        return try {
            val assetManager = context.assets
            val inputStream = assetManager.open("endpointer.data")
            val bytes = inputStream.readBytes()
            inputStream.close()

            val data = GoogleEndpointerData(bytes)
            val modelId = data.endpointerModelId
            val idealBuffer = data.idealBufferBytes
            Log.i(TAG, "GoogleEndpointer initialized: modelId=$modelId, idealBufferBytes=$idealBuffer")

            endpointerData = data
            endpointer = GoogleEndpointer(data)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "GoogleEndpointer native engine not available, falling back to adaptive acoustic VAD", t)
            close()
            false
        }
    }

    fun processAudio(buffer: ByteArray, offset: Int, length: Int): GoogleEndpointer.GoogleEndpointerResult? {
        val ep = endpointer ?: return null
        return try {
            ep.process(buffer, offset, length)
        } catch (t: Throwable) {
            Log.w(TAG, "Error in GoogleEndpointer process", t)
            null
        }
    }

    fun reset() {
        try {
            endpointer?.reset()
        } catch (e: Exception) {}
    }

    fun close() {
        try {
            endpointer?.close()
            endpointer = null
            endpointerData?.close()
            endpointerData = null
        } catch (e: Exception) {}
    }
}
