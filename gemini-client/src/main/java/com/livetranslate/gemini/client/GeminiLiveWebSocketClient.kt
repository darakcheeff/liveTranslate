package com.livetranslate.gemini.client

import android.util.Base64
import android.util.Log
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
    companion object {
        private const val TAG = "GeminiLiveClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val keyPoolManager = KeyPoolManager(config)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingAudioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incomingAudioFlow: SharedFlow<ByteArray> = _incomingAudioFlow.asSharedFlow()

    private val _subtitleFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val subtitleFlow: SharedFlow<String> = _subtitleFlow.asSharedFlow()

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
    private val isConnectingOrConnected = AtomicBoolean(false)

    fun updateConfig(newConfig: GeminiConfig) {
        this.config = newConfig
        keyPoolManager.updateConfig(newConfig)
    }

    @Synchronized
    fun startSession(mode: TranslationMode) {
        if (isConnectingOrConnected.get()) {
            Log.w(TAG, "Session already active/connecting, skipping duplicate start")
            return
        }
        currentMode = mode
        isManualStop.set(false)
        isConnectingOrConnected.set(true)
        keyPoolManager.resetModelIndex()
        Log.i(TAG, "Starting Live session in mode: ${mode.name}")
        connectInternal()
    }

    @Synchronized
    fun stopSession() {
        Log.i(TAG, "Stopping Live session manually")
        isManualStop.set(true)
        isConnectingOrConnected.set(false)
        activeWebSocket?.close(1000, "Session stopped by user")
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun connectInternal() {
        val apiKey = keyPoolManager.getActiveApiKey()
        if (apiKey.isNullOrBlank()) {
            val err = "Список API-ключей пуст. Добавьте ключ в настройках."
            Log.e(TAG, err)
            isConnectingOrConnected.set(false)
            _connectionState.value = ConnectionState.Error(err, isRecoverable = false)
            return
        }

        val activeModel = keyPoolManager.getActiveModel()
        val keyIdx = keyPoolManager.currentKeyIndex.value
        Log.i(TAG, "Connecting to WebSocket: model=$activeModel, keyIndex=$keyIdx")
        _connectionState.value = ConnectionState.Connecting(model = activeModel, keyIndex = keyIdx)

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        activeWebSocket?.close(1000, "Reconnecting")
        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully! HTTP ${response.code}")
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
                Log.w(TAG, "WebSocket server closing: $code ($reason)")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code ($reason)")
                if (!isManualStop.get()) {
                    handleFailover("WebSocket закрыт: $code ($reason)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val msg = response?.message ?: t.message ?: "Unknown error"
                Log.e(TAG, "WebSocket failure: HTTP $code, msg=$msg", t)
                if (!isManualStop.get()) {
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

        val setupMsg = SetupMessage(
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

        val jsonString = json.encodeToString(setupMsg)
        Log.d(TAG, "Sending setup message: $jsonString")
        webSocket.send(jsonString)
    }

    fun sendAudioChunk(pcmChunk: ByteArray) {
        val ws = activeWebSocket ?: return
        if (_connectionState.value !is ConnectionState.Connected) return

        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val realtimeMsg = RealtimeInputMessage(
                realtimeInput = RealtimeInput(
                    mediaChunks = listOf(
                        Blob(
                            mimeType = "audio/pcm;rate=16000",
                            data = base64Data
                        )
                    )
                )
            )
            val jsonPayload = json.encodeToString(realtimeMsg)
            ws.send(jsonPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
        }
    }

    private fun handleIncomingMessage(text: String) {
        scope.launch {
            try {
                val response = json.decodeFromString<BidiServerMessage>(text)
                val serverContent = response.serverContent ?: return@launch

                if (serverContent.interrupted) {
                    Log.d(TAG, "Received interrupted signal")
                    _interruptedFlow.emit(Unit)
                }

                serverContent.modelTurn?.parts?.forEach { part ->
                    part.inlineData?.let { blob ->
                        if (blob.data.isNotEmpty()) {
                            val audioBytes = Base64.decode(blob.data, Base64.DEFAULT)
                            Log.d(TAG, "Received audio chunk from Gemini: ${audioBytes.size} bytes")
                            _incomingAudioFlow.emit(audioBytes)
                        }
                    }

                    part.text?.let { txt ->
                        if (txt.isNotBlank()) {
                            Log.d(TAG, "Received text translation: $txt")
                            _subtitleFlow.emit(txt)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing server message: $text", e)
            }
        }
    }

    private fun handleFailover(reason: String) {
        scope.launch {
            delay(1000)
            val result = keyPoolManager.rotateOnFailure(reason)
            if (result != null) {
                when (result) {
                    is FailoverResult.KeyRotated -> {
                        Log.w(TAG, "Key rotated to #${result.keyIndex + 1}: ${result.reason}")
                        _connectionState.value = ConnectionState.RotatingKey(
                            reason = result.reason,
                            newKeyIndex = result.keyIndex
                        )
                    }
                    is FailoverResult.ModelCascaded -> {
                        Log.w(TAG, "Model cascaded to ${result.newModel}: ${result.reason}")
                        _connectionState.value = ConnectionState.FallbackModel(
                            fromModel = "Previous",
                            toModel = result.newModel
                        )
                    }
                    is FailoverResult.AllExhaustedRestart -> {
                        Log.e(TAG, "All keys exhausted: ${result.reason}")
                        _connectionState.value = ConnectionState.Error(
                            message = "Все ключи исчерпали квоту. Ошибка: ${result.reason}",
                            isRecoverable = true
                        )
                    }
                }
                connectInternal()
            } else {
                isConnectingOrConnected.set(false)
                _connectionState.value = ConnectionState.Error(
                    message = "Критическая ошибка подключения: $reason",
                    isRecoverable = false
                )
            }
        }
    }
}
