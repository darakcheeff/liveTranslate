package com.livetranslate.gemini.model

import kotlinx.serialization.Serializable

@Serializable
data class SetupMessage(
    val setup: SetupConfig
)

@Serializable
data class RealtimeInputMessage(
    val realtimeInput: RealtimeInput
)

@Serializable
data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig,
    val systemInstruction: Content? = null
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String>,
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
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String
)

@Serializable
data class Blob(
    val mimeType: String,
    val data: String
)

@Serializable
data class RealtimeInput(
    val mediaChunks: List<Blob>
)

@Serializable
data class BidiServerMessage(
    val serverContent: ServerContent? = null
)

@Serializable
data class ServerContent(
    val modelTurn: ServerContentModelTurn? = null,
    val turnComplete: Boolean = false,
    val interrupted: Boolean = false
)

@Serializable
data class ServerContentModelTurn(
    val parts: List<ServerContentPart> = emptyList()
)

@Serializable
data class ServerContentPart(
    val text: String? = null,
    val inlineData: Blob? = null
)

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
