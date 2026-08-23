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
    val systemInstruction: Content? = null,
    // In SOLO mode: disable Gemini's built-in VAD so we can use manual activityStart/activityEnd
    val realtimeInputConfig: RealtimeInputConfig? = null
)

@Serializable
data class RealtimeInputConfig(
    val automaticActivityDetection: AutomaticActivityDetection? = null
)

@Serializable
data class AutomaticActivityDetection(
    // Set to true to disable Gemini's server-side VAD (needed for manual activity control)
    val disabled: Boolean = true
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String>,
    val speechConfig: SpeechConfig? = null,
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class ThinkingConfig(
    val thinkingBudget: Int = 0
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
    val mediaChunks: List<Blob>? = null,
    val activityStart: ActivityStart? = null,  // manual VAD: speech started
    val activityEnd: ActivityEnd? = null        // manual VAD: speech/phrase ended
)

@Serializable
class ActivityStart  // empty object {}

@Serializable
class ActivityEnd    // empty object {}

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
