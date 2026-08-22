package com.livetranslate.gemini.discovery

import com.livetranslate.gemini.model.GeminiModelItem
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
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchLiveCapableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: ${response.message}")
                    )
                }

                val body = response.body?.string() ?: return@withContext Result.failure(
                    IOException("Empty response body")
                )

                val parsed = json.decodeFromString<ModelsListResponse>(body)
                // Filter models supporting bidiGenerateContent / realtime audio
                val liveModels = parsed.models
                    .filter { model ->
                        model.supportedGenerationMethods.any { method ->
                            method.contains("bidi", ignoreCase = true) ||
                            method.contains("live", ignoreCase = true)
                        } || model.name.contains("flash", ignoreCase = true)
                    }
                    .map { it.name }

                Result.success(liveModels.ifEmpty { listOf("models/gemini-2.0-flash-exp") })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
