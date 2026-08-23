package com.livetranslate.gemini.discovery

import android.util.Log
import com.livetranslate.gemini.model.ModelsListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class GeminiModelDiscovery(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "ModelsDiscovery"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchLiveCapableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val request = Request.Builder().url(url).get().build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "HTTP ${response.code}: ${response.message}"
                    Log.e(TAG, err)
                    return@withContext Result.failure(IOException(err))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(
                    IOException("Empty response body")
                )

                Log.i(TAG, "ListModels API response: $body")
                val parsed = json.decodeFromString<ModelsListResponse>(body)
                
                // Filter models supporting bidiGenerateContent
                val bidiModels = parsed.models
                    .filter { model ->
                        model.supportedGenerationMethods.any { it.contains("bidi", ignoreCase = true) }
                    }
                    .map { it.name }

                Log.i(TAG, "Discovered Bidi models: $bidiModels")

                val resultList = if (bidiModels.isNotEmpty()) {
                    bidiModels
                } else {
                    // Fallback to flash models
                    val flashModels = parsed.models
                        .filter { it.name.contains("flash", ignoreCase = true) && !it.name.contains("8b", ignoreCase = true) }
                        .map { it.name }
                    flashModels.ifEmpty { listOf("models/gemini-2.0-flash-exp") }
                }

                Result.success(resultList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query ListModels", e)
            Result.failure(e)
        }
    }
}
