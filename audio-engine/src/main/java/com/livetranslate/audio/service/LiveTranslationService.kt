package com.livetranslate.audio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranslate.audio.capture.AudioCaptureManager
import com.livetranslate.audio.focus.AudioFocusManager
import com.livetranslate.audio.playback.AudioPlaybackManager
import com.livetranslate.core.model.ConnectionState
import com.livetranslate.core.model.GeminiConfig
import com.livetranslate.core.model.HistoryItem
import com.livetranslate.core.model.TranslationMode
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.gemini.client.GeminiLiveWebSocketClient
import com.livetranslate.gemini.discovery.GeminiModelDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LiveTranslationService : Service() {

    companion object {
        private const val TAG = "LiveTranslationService"
        private const val NOTIFICATION_CHANNEL_ID = "live_translation_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.livetranslate.action.START"
        const val ACTION_STOP = "com.livetranslate.action.STOP"
        const val ACTION_INJECT_TEST = "com.livetranslate.action.TEST_PCM"
        const val EXTRA_MODE = "mode"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var prefsManager: EncryptedPreferencesManager
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var audioPlayback: AudioPlaybackManager
    private lateinit var audioFocus: AudioFocusManager
    private lateinit var webSocketClient: GeminiLiveWebSocketClient
    private lateinit var modelDiscovery: GeminiModelDiscovery

    private var activeMode = TranslationMode.DIALOGUE
    private var currentSessionId: String? = null
    private var currentSessionTranscript = StringBuilder()

    inner class LocalBinder : Binder() {
        val service: LiveTranslationService get() = this@LiveTranslationService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        prefsManager = EncryptedPreferencesManager(this)
        audioCapture = AudioCaptureManager(this)
        audioPlayback = AudioPlaybackManager(this)
        audioFocus = AudioFocusManager(
            this,
            onFocusLost = { pauseTranslation() },
            onFocusGained = { resumeTranslation() }
        )
        val initialConfig = prefsManager.loadConfig()
        webSocketClient = GeminiLiveWebSocketClient(initialConfig)
        modelDiscovery = GeminiModelDiscovery()

        audioPlayback.onPlaybackActiveChanged = { isPlaying ->
            audioCapture.setDucking(isPlaying)
        }

        createNotificationChannel()
        observeStreams()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val modeStr = intent.getStringExtra(EXTRA_MODE)
        val mode = if (modeStr != null) {
            try { TranslationMode.valueOf(modeStr) } catch (e: Exception) { TranslationMode.SOLO }
        } else {
            TranslationMode.SOLO
        }

        Log.i(TAG, "onStartCommand: action=$action, mode=${mode.name}")
        when (action) {
            ACTION_START -> startTranslation(mode)
            ACTION_STOP -> stopTranslation()
            ACTION_INJECT_TEST -> runPcmInjectionTest()
        }
        return START_NOT_STICKY
    }

    val connectionState: StateFlow<ConnectionState> get() = webSocketClient.connectionState
    val subtitleFlow: SharedFlow<String> get() = webSocketClient.subtitleFlow
    val waveformRmsFlow: SharedFlow<Float> get() = audioCapture.waveformRmsFlow

    fun updateConfig(config: GeminiConfig) {
        webSocketClient.updateConfig(config)
    }

    fun startTranslation(mode: TranslationMode) {
        activeMode = mode
        val config = prefsManager.loadConfig()
        Log.i(TAG, "startTranslation with ${config.apiKeys.size} API keys, mode=${mode.name}")
        webSocketClient.updateConfig(config)

        startForegroundNotification(mode)
        audioFocus.requestAudioFocus()

        val sessionId = UUID.randomUUID().toString()
        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date())
        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val sFile = File(recordingsDir, "session_${System.currentTimeMillis()}.wav")

        currentSessionId = sessionId
        currentSessionTranscript.clear()

        val historyItem = HistoryItem(
            id = sessionId,
            title = "Перевод $dateStr",
            timestamp = System.currentTimeMillis(),
            mode = mode,
            sourceLang = config.opponentLanguage,
            targetLang = config.ourLanguage,
            originalText = "",
            translatedText = "",
            audioFilePath = sFile.absolutePath
        )
        prefsManager.appendHistoryItem(historyItem)

        audioPlayback.initialize(mode, sFile)
        audioCapture.startCapture(mode)

        serviceScope.launch {
            val activeKey = config.getActiveApiKey()
            if (!activeKey.isNullOrBlank()) {
                val discoveryResult = modelDiscovery.fetchLiveCapableModels(activeKey)
                discoveryResult.onSuccess { models ->
                    Log.i(TAG, "Updating config with discovered models: $models")
                    val updatedConfig = config.copy(preferredModels = models)
                    prefsManager.saveConfig(updatedConfig)
                    webSocketClient.updateConfig(updatedConfig)
                }
            }
        }

        webSocketClient.startSession(mode)
    }

    fun stopTranslation() {
        Log.i(TAG, "stopTranslation called")
        webSocketClient.stopSession()
        audioCapture.stopCapture()
        audioPlayback.release()
        audioFocus.abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseTranslation() {
        audioCapture.stopCapture()
        audioPlayback.flushAndInterrupt()
    }

    private fun resumeTranslation() {
        audioCapture.startCapture(activeMode)
    }

    private val soloPhraseChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    private fun observeStreams() {
        serviceScope.launch {
            audioCapture.audioChunkFlow.collect { pcmChunk ->
                if (activeMode == TranslationMode.DIALOGUE) {
                    webSocketClient.sendAudioChunk(pcmChunk)
                }
            }
        }

        serviceScope.launch {
            audioCapture.completedPhraseFlow.collect { phrasePcm ->
                if (activeMode == TranslationMode.SOLO) {
                    soloPhraseChannel.trySend(phrasePcm)
                }
            }
        }

        // Dedicated translation conveyor worker for SOLO mode
        serviceScope.launch(Dispatchers.IO) {
            for (phrasePcm in soloPhraseChannel) {
                if (activeMode != TranslationMode.SOLO) continue

                Log.i(TAG, "Conveyor: Processing phrase (${phrasePcm.size} bytes) -> sending to Gemini")
                webSocketClient.sendActivityStart()

                val chunkSize = 3200
                var offset = 0
                while (offset < phrasePcm.size) {
                    val len = minOf(chunkSize, phrasePcm.size - offset)
                    val chunk = phrasePcm.copyOfRange(offset, offset + len)
                    webSocketClient.sendAudioChunk(chunk)
                    offset += len
                    delay(2)
                }

                webSocketClient.sendActivityEnd()

                Log.d(TAG, "Conveyor: Awaiting Gemini turn completion...")
                webSocketClient.waitForTurnComplete(timeoutMs = 20000)
                Log.i(TAG, "Conveyor: Gemini turn complete! Proceeding to next phrase in queue.")
            }
        }

        serviceScope.launch {
            webSocketClient.incomingAudioFlow.collect { pcmChunk ->
                audioPlayback.enqueueAudioChunk(pcmChunk)
            }
        }

        serviceScope.launch {
            webSocketClient.interruptedFlow.collect {
                if (activeMode == TranslationMode.DIALOGUE) {
                    audioPlayback.flushAndInterrupt()
                } else {
                    Log.d(TAG, "SOLO mode: preserving audio playback during background speech recording")
                }
            }
        }

        serviceScope.launch {
            webSocketClient.subtitleFlow.collect { text ->
                if (text.isNotBlank()) {
                    currentSessionTranscript.append(text).append(" ")
                    currentSessionId?.let { sId ->
                        val historyList = prefsManager.loadHistory()
                        val item = historyList.find { it.id == sId }
                        if (item != null) {
                            prefsManager.updateHistoryItem(
                                item.copy(translatedText = currentSessionTranscript.toString().trim())
                            )
                        }
                    }
                }
            }
        }
    }

    private fun runPcmInjectionTest() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pcmFile = File(cacheDir, "last_recorded_audio.pcm")
                if (!pcmFile.exists()) {
                    Log.w(TAG, "No test PCM file found in cache")
                    return@launch
                }
                val bytes = pcmFile.readBytes()
                Log.i(TAG, "Injecting test PCM: ${bytes.size} bytes")
                val chunkSize = 3200
                for (i in 0 until bytes.size step chunkSize) {
                    val end = minOf(i + chunkSize, bytes.size)
                    val chunk = bytes.copyOfRange(i, end)
                    webSocketClient.sendAudioChunk(chunk)
                    delay(80)
                }
                val silence = ByteArray(3200)
                repeat(5) {
                    webSocketClient.sendAudioChunk(silence)
                    delay(80)
                }
                Log.i(TAG, "Test PCM injection complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting test PCM", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Служба живого перевода",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый синхронный перевод речи"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(mode: TranslationMode) {
        val stopIntent = Intent(this, LiveTranslationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Live Translate")
            .setContentText("Идет перевод (${if (mode == TranslationMode.SOLO) "Шепот" else "Диалог"})")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        stopTranslation()
        serviceScope.cancel()
    }
}
