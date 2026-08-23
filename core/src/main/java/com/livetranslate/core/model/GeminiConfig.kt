package com.livetranslate.core.model

import kotlinx.serialization.Serializable

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val model: String, val keyIndex: Int) : ConnectionState
    data class Connected(val model: String, val keyIndex: Int) : ConnectionState
    data class RotatingKey(val reason: String, val newKeyIndex: Int) : ConnectionState
    data class FallbackModel(val fromModel: String, val toModel: String) : ConnectionState
    data class Error(val message: String, val isRecoverable: Boolean) : ConnectionState
    data object Disconnected : ConnectionState
}

@Serializable
data class GeminiConfig(
    val apiKeys: List<String> = emptyList(),
    val currentKeyIndex: Int = 0,
    val ourLanguage: String = "ru",
    val opponentLanguage: String = "en",
    val selectedVoice: VoiceName = VoiceName.PUCK,
    val showSubtitles: Boolean = true,
    val saveHistory: Boolean = true,
    val preferredModels: List<String> = DEFAULT_MODELS
) {
    companion object {
        val DEFAULT_MODELS = listOf(
            "models/gemini-2.5-flash-native-audio-latest",
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            "models/gemini-2.5-flash-native-audio-preview-09-2025",
            "models/gemini-2.0-flash-exp",
            "models/gemini-2.0-flash-realtime-exp"
        )
    }

    fun getActiveApiKey(): String? {
        if (apiKeys.isEmpty()) return null
        val safeIndex = currentKeyIndex.coerceIn(0, apiKeys.size - 1)
        return apiKeys[safeIndex]
    }
}

@Serializable
data class HistoryItem(
    val id: String,
    val title: String = "",
    val timestamp: Long,
    val mode: TranslationMode = TranslationMode.SOLO,
    val sourceLang: String = "en",
    val targetLang: String = "ru",
    val originalText: String = "",
    val translatedText: String = "",
    val audioFilePath: String? = null
)
