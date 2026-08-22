package com.livetranslate.gemini.model

import kotlinx.serialization.Serializable

@Serializable
data class BidiClientMessage(
    val setup: SetupConfig? = null,
    val realtimeInput: RealtimeInput? = null
)

@Serializable
data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig? = null
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null
)

@Serializable
data class PrebuiltVoiceConfig(
    val voiceName: String
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: Blob? = null
)

@Serializable
data class Blob(
    val mimeType: String,
    val data: String // Base64 encoded
)

@Serializable
data class RealtimeInput(
    val mediaChunks: List<Blob>
)

// Incoming server messages
@Serializable
data class BidiServerMessage(
    val serverContent: ServerContent? = null
)

@Serializable
data class ServerContent(
    val modelTurn: Content? = null,
    val turnComplete: Boolean = false,
    val interrupted: Boolean = false
)

// Models discovery response
@Serializable
data class ModelsListResponse(
    val models: List<GeminiModelItem> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
data class GeminiModelItem(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String> = emptyList()
)
