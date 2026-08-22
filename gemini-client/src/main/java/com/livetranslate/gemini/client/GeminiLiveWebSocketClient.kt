package com.livetranslate.gemini.client

import android.util.Base64
import com.livetranslate.core.model.ConnectionState
import com.livetranslate.core.model.GeminiConfig
import com.livetranslate.core.model.TranslationMode
import com.livetranslate.gemini.failover.FailoverResult
import com.livetranslate.gemini.failover.KeyPoolManager
import com.livetranslate.gemini.model.*
import com.livetranslate.gemini.prompt.PromptBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveWebSocketClient(
    private var config: GeminiConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val keyPoolManager = KeyPoolManager(config)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Stream of received audio chunks (PCM 24kHz raw bytes)
    private val _incomingAudioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingAudioFlow: SharedFlow<ByteArray> = _incomingAudioFlow.asSharedFlow()

    // Stream of received subtitle texts
    private val _subtitleFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val subtitleFlow: SharedFlow<String> = _subtitleFlow.asSharedFlow()

    // Stream of interruption signals
    private val _interruptedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val interruptedFlow: SharedFlow<Unit> = _interruptedFlow.asSharedFlow()

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var currentMode: TranslationMode = TranslationMode.SOLO
    private val isManualStop = AtomicBoolean(false)

    fun updateConfig(newConfig: GeminiConfig) {
        this.config = newConfig
        keyPoolManager.updateConfig(newConfig)
    }

    fun startSession(mode: TranslationMode) {
        currentMode = mode
        isManualStop.set(false)
        connectInternal()
    }

    fun stopSession() {
        isManualStop.set(true)
        activeWebSocket?.close(1000, "Session stopped by user")
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun connectInternal() {
        val apiKey = keyPoolManager.getActiveApiKey()
        if (apiKey.isNullOrBlank()) {
            _connectionState.value = ConnectionState.Error("Список API-ключей пуст", isRecoverable = false)
            return
        }

        val activeModel = keyPoolManager.getActiveModel()
        _connectionState.value = ConnectionState.Connecting(
            model = activeModel,
            keyIndex = keyPoolManager.currentKeyIndex.value
        )

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected(
                    model = activeModel,
                    keyIndex = keyPoolManager.currentKeyIndex.value
                )
                sendInitialSetup(webSocket, activeModel)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isManualStop.get()) {
                    handleFailover("WebSocket закрыт: $code ($reason)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isManualStop.get()) {
                    val code = response?.code ?: 0
                    val msg = response?.message ?: t.message ?: "Unknown network failure"
                    handleFailover("Сбой WebSocket HTTP $code: $msg")
                }
            }
        })
    }

    private fun sendInitialSetup(webSocket: WebSocket, model: String) {
        val systemPrompt = PromptBuilder.buildSystemPrompt(
            mode = currentMode,
            ourLanguageCode = config.ourLanguage,
            opponentLanguageCode = config.opponentLanguage
        )

        val setupMessage = BidiClientMessage(
            setup = SetupConfig(
                model = model,
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig = PrebuiltVoiceConfig(
                                voiceName = config.selectedVoice.apiName
                            )
                        )
                    )
                ),
                systemInstruction = Content(
                    parts = listOf(Part(text = systemPrompt))
                )
            )
        )

        val jsonString = json.encodeToString(setupMessage)
        webSocket.send(jsonString)
    }

    /**
     * Sends raw PCM 16kHz audio chunk to Gemini Live API
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        val ws = activeWebSocket ?: return
        if (_connectionState.value !is ConnectionState.Connected) return

        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val realtimeMessage = BidiClientMessage(
                realtimeInput = RealtimeInput(
                    mediaChunks = listOf(
                        Blob(
                            mimeType = "audio/pcm;rate=16000",
                            data = base64Data
                        )
                    )
                )
            )
            val jsonPayload = json.encodeToString(realtimeMessage)
            ws.send(jsonPayload)
        } catch (e: Exception) {
            // Buffer overflow or serialization error handled gracefully
        }
    }

    private fun handleIncomingMessage(text: String) {
        scope.launch {
            try {
                val response = json.decodeFromString<BidiServerMessage>(text)
                val serverContent = response.serverContent ?: return@launch

                if (serverContent.interrupted) {
                    _interruptedFlow.emit(Unit)
                }

                serverContent.modelTurn?.parts?.forEach { part ->
                    part.inlineData?.let { blob ->
                        if (blob.data.isNotEmpty()) {
                            val audioBytes = Base64.decode(blob.data, Base64.DEFAULT)
                            _incomingAudioFlow.emit(audioBytes)
                        }
                    }

                    part.text?.let { txt ->
                        if (txt.isNotBlank()) {
                            _subtitleFlow.emit(txt)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore unexpected or non-critical JSON payloads
            }
        }
    }

    private fun handleFailover(reason: String) {
        scope.launch {
            delay(300) // Small delay to avoid rapid socket churn
            val result = keyPoolManager.rotateOnFailure(reason)
            if (result != null) {
                when (result) {
                    is FailoverResult.KeyRotated -> {
                        _connectionState.value = ConnectionState.RotatingKey(
                            reason = result.reason,
                            newKeyIndex = result.keyIndex
                        )
                    }
                    is FailoverResult.ModelCascaded -> {
                        _connectionState.value = ConnectionState.FallbackModel(
                            fromModel = "Previous",
                            toModel = result.newModel
                        )
                    }
                    is FailoverResult.AllExhaustedRestart -> {
                        _connectionState.value = ConnectionState.Error(
                            message = "Все ключи и модели исчерпаны. Повторный перезапуск...",
                            isRecoverable = true
                        )
                    }
                }
                connectInternal()
            } else {
                _connectionState.value = ConnectionState.Error(
                    message = "Критическая ошибка: невозможно переподключиться ($reason)",
                    isRecoverable = false
                )
            }
        }
    }
}
