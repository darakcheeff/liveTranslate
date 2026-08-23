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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class PhraseTask(
    val index: Int,
    val audioPcm: ByteArray
)

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

    // Triple WebSocket clients for continuous streaming zero-gap conveyor pipeline
    private lateinit var clientA: GeminiLiveWebSocketClient
    private lateinit var clientB: GeminiLiveWebSocketClient
    private lateinit var clientC: GeminiLiveWebSocketClient
    private lateinit var modelDiscovery: GeminiModelDiscovery

    private var activeMode = TranslationMode.DIALOGUE
    private var currentSessionId: String? = null
    private var currentSessionTranscript = StringBuilder()

    private val _compositeSubtitleFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val subtitleFlow: SharedFlow<String> = _compositeSubtitleFlow.asSharedFlow()

    private val channelA = Channel<PhraseTask>(capacity = Channel.UNLIMITED)
    private val channelB = Channel<PhraseTask>(capacity = Channel.UNLIMITED)
    private val channelC = Channel<PhraseTask>(capacity = Channel.UNLIMITED)
    private var phraseCounter = 0

    inner class LocalBinder : Binder() {
        val service: LiveTranslationService get() = this@LiveTranslationService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate (Continuous Streaming Triple-Engine)")
        prefsManager = EncryptedPreferencesManager(this)
        audioCapture = AudioCaptureManager(this)
        audioPlayback = AudioPlaybackManager(this)
        audioFocus = AudioFocusManager(
            this,
            onFocusLost = { pauseTranslation() },
            onFocusGained = { resumeTranslation() }
        )
        val initialConfig = prefsManager.loadConfig()
        clientA = GeminiLiveWebSocketClient(initialConfig)
        clientB = GeminiLiveWebSocketClient(initialConfig)
        clientC = GeminiLiveWebSocketClient(initialConfig)
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

    val connectionState: StateFlow<ConnectionState> get() = clientA.connectionState
    val waveformRmsFlow: SharedFlow<Float> get() = audioCapture.waveformRmsFlow

    fun updateConfig(config: GeminiConfig) {
        clientA.updateConfig(config)
        clientB.updateConfig(config)
        clientC.updateConfig(config)
    }

    fun startTranslation(mode: TranslationMode) {
        activeMode = mode
        val config = prefsManager.loadConfig()
        Log.i(TAG, "startTranslation with ${config.apiKeys.size} API keys, mode=${mode.name} (Continuous Streaming)")
        clientA.updateConfig(config)
        clientB.updateConfig(config)
        clientC.updateConfig(config)

        startForegroundNotification(mode)
        audioFocus.requestAudioFocus()

        val sessionId = UUID.randomUUID().toString()
        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date())
        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val sFile = File(recordingsDir, "session_${System.currentTimeMillis()}.wav")

        currentSessionId = sessionId
        currentSessionTranscript.clear()
        phraseCounter = 0

        val historyItem = HistoryItem(
            id = sessionId,
            title = "Перевод $dateStr",
            timestamp = System.currentTimeMillis(),
            mode = mode,
            sourceLang = config.ourLanguage,
            targetLang = config.opponentLanguage,
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
                    clientA.updateConfig(updatedConfig)
                    clientB.updateConfig(updatedConfig)
                    clientC.updateConfig(updatedConfig)
                }
            }
        }

        clientA.startSession(mode)
        if (mode == TranslationMode.SOLO) {
            clientB.startSession(mode)
            clientC.startSession(mode)
        }
    }

    fun stopTranslation() {
        Log.i(TAG, "stopTranslation called")
        clientA.stopSession()
        clientB.stopSession()
        clientC.stopSession()
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

    private fun observeStreams() {
        // Direct DIALOGUE mode mic streaming
        serviceScope.launch {
            audioCapture.audioChunkFlow.collect { pcmChunk ->
                if (activeMode == TranslationMode.DIALOGUE) {
                    clientA.sendAudioChunk(pcmChunk)
                }
            }
        }

        // Live Audio streaming directly into AudioPlayback FIFO queue from all workers!
        serviceScope.launch {
            clientA.incomingAudioFlow.collect { pcmChunk ->
                audioPlayback.enqueueAudioChunk(pcmChunk)
            }
        }
        serviceScope.launch {
            clientB.incomingAudioFlow.collect { pcmChunk ->
                audioPlayback.enqueueAudioChunk(pcmChunk)
            }
        }
        serviceScope.launch {
            clientC.incomingAudioFlow.collect { pcmChunk ->
                audioPlayback.enqueueAudioChunk(pcmChunk)
            }
        }

        // Subtitles
        serviceScope.launch {
            clientA.subtitleFlow.collect { text -> handleSubtitleText(text) }
        }
        serviceScope.launch {
            clientB.subtitleFlow.collect { text -> handleSubtitleText(text) }
        }
        serviceScope.launch {
            clientC.subtitleFlow.collect { text -> handleSubtitleText(text) }
        }

        // Conveyor phrase dispatcher for SOLO mode (alternates between Channel A, B, C)
        serviceScope.launch {
            audioCapture.completedPhraseFlow.collect { phrasePcm ->
                if (activeMode == TranslationMode.SOLO) {
                    val index = phraseCounter++
                    val task = PhraseTask(index, phrasePcm)
                    when (index % 3) {
                        0 -> {
                            Log.i(TAG, "Dispatcher: Phrase #$index (${phrasePcm.size} bytes) -> Worker A")
                            channelA.trySend(task)
                        }
                        1 -> {
                            Log.i(TAG, "Dispatcher: Phrase #$index (${phrasePcm.size} bytes) -> Worker B")
                            channelB.trySend(task)
                        }
                        else -> {
                            Log.i(TAG, "Dispatcher: Phrase #$index (${phrasePcm.size} bytes) -> Worker C")
                            channelC.trySend(task)
                        }
                    }
                }
            }
        }

        // Worker A
        serviceScope.launch(Dispatchers.IO) {
            for (task in channelA) {
                if (activeMode != TranslationMode.SOLO) continue
                processPhraseWithClient(clientA, "Worker-A", task)
            }
        }

        // Worker B
        serviceScope.launch(Dispatchers.IO) {
            for (task in channelB) {
                if (activeMode != TranslationMode.SOLO) continue
                processPhraseWithClient(clientB, "Worker-B", task)
            }
        }

        // Worker C
        serviceScope.launch(Dispatchers.IO) {
            for (task in channelC) {
                if (activeMode != TranslationMode.SOLO) continue
                processPhraseWithClient(clientC, "Worker-C", task)
            }
        }

        // Interrupted signals
        serviceScope.launch {
            clientA.interruptedFlow.collect {
                if (activeMode == TranslationMode.DIALOGUE) audioPlayback.flushAndInterrupt()
            }
        }
    }

    private suspend fun processPhraseWithClient(
        client: GeminiLiveWebSocketClient,
        workerName: String,
        task: PhraseTask
    ) {
        val phraseIndex = task.index
        val phrasePcm = task.audioPcm
        Log.i(TAG, "$workerName: Processing Phrase #$phraseIndex (${phrasePcm.size} bytes) -> streaming to Gemini")

        client.sendActivityStart()

        val chunkSize = 3200
        var offset = 0
        while (offset < phrasePcm.size) {
            val len = minOf(chunkSize, phrasePcm.size - offset)
            val chunk = phrasePcm.copyOfRange(offset, offset + len)
            client.sendAudioChunk(chunk)
            offset += len
            delay(2)
        }

        client.sendActivityEnd()
        Log.d(TAG, "$workerName: Awaiting Phrase #$phraseIndex turn completion...")
        client.waitForTurnComplete(timeoutMs = 25000)
        Log.i(TAG, "$workerName: Phrase #$phraseIndex turn complete!")
    }

    private fun handleSubtitleText(text: String) {
        if (text.isNotBlank()) {
            _compositeSubtitleFlow.tryEmit(text)
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
                    clientA.sendAudioChunk(chunk)
                    delay(80)
                }
                val silence = ByteArray(3200)
                repeat(5) {
                    clientA.sendAudioChunk(silence)
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
