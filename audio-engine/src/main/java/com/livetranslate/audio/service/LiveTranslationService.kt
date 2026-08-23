package com.livetranslate.audio.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranslate.audio.capture.AudioCaptureManager
import com.livetranslate.audio.focus.AudioFocusManager
import com.livetranslate.audio.playback.AudioPlaybackManager
import com.livetranslate.core.model.ConnectionState
import com.livetranslate.core.model.HistoryItem
import com.livetranslate.core.model.TranslationMode
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.gemini.client.GeminiLiveWebSocketClient
import com.livetranslate.gemini.discovery.GeminiModelDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class LiveTranslationService : Service() {

    companion object {
        private const val TAG = "LiveTranslationService"
        const val ACTION_START = "com.livetranslate.action.START"
        const val ACTION_STOP = "com.livetranslate.action.STOP"
        const val ACTION_INJECT_TEST = "com.livetranslate.action.INJECT_TEST"
        const val EXTRA_MODE = "extra_mode"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live_translate_service_channel"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var prefsManager: EncryptedPreferencesManager
    private lateinit var webSocketClient: GeminiLiveWebSocketClient
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var audioPlayback: AudioPlaybackManager
    private lateinit var audioFocus: AudioFocusManager
    private val modelDiscovery = GeminiModelDiscovery()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var activeMode: TranslationMode = TranslationMode.SOLO

    inner class LocalBinder : Binder() {
        fun getService(): LiveTranslationService = this@LiveTranslationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        prefsManager = EncryptedPreferencesManager(this)
        val config = prefsManager.loadConfig()

        webSocketClient = GeminiLiveWebSocketClient(config)
        audioCapture = AudioCaptureManager(this)
        audioPlayback = AudioPlaybackManager()

        audioFocus = AudioFocusManager(
            context = this,
            onFocusLost = { pauseTranslation() },
            onFocusGained = { resumeTranslation() }
        )

        audioPlayback.onPlaybackActiveChanged = { isPlaying ->
            audioCapture.setDucking(isPlaying)
        }



        observeStreams()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val modeStr = intent?.getStringExtra(EXTRA_MODE)
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

    fun startTranslation(mode: TranslationMode) {
        activeMode = mode
        val config = prefsManager.loadConfig()
        Log.i(TAG, "startTranslation with ${config.apiKeys.size} API keys, mode=${mode.name}")
        webSocketClient.updateConfig(config)

        startForegroundNotification(mode)
        audioFocus.requestAudioFocus()
        audioPlayback.initialize(mode)
        audioCapture.startCapture()

        // Discover supported models dynamically for the active API key
        serviceScope.launch {
            val activeKey = config.getActiveApiKey()
            if (!activeKey.isNullOrBlank()) {
                val discoveryResult = modelDiscovery.fetchLiveCapableModels(activeKey)
                discoveryResult.onSuccess { models ->
                    Log.i(TAG, "Updating config with discovered models: $models")
                    val updatedConfig = prefsManager.loadConfig().copy(preferredModels = models)
                    prefsManager.saveConfig(updatedConfig)
                    webSocketClient.updateConfig(updatedConfig)
                }
            }
            webSocketClient.startSession(mode)
        }
    }

    fun runPcmInjectionTest() {
        Log.i(TAG, "Starting Automated PCM Injection Test on Phone...")
        startTranslation(TranslationMode.DIALOGUE)

        serviceScope.launch(Dispatchers.IO) {
            delay(1500) // Wait for WebSocket handshake
            var pcmFile = File(cacheDir, "last_recorded_audio.pcm")
            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                pcmFile = File("/data/local/tmp/user_speech.pcm")
            }

            if (pcmFile.exists() && pcmFile.length() > 0L) {
                Log.i(TAG, "Injecting PCM file: ${pcmFile.absolutePath} (${pcmFile.length()} bytes)")
                val bytes = pcmFile.readBytes()
                val chunkSize = 3200
                for (i in 0 until bytes.size step chunkSize) {
                    val end = minOf(i + chunkSize, bytes.size)
                    val chunk = bytes.copyOfRange(i, end)
                    webSocketClient.sendAudioChunk(chunk)
                    delay(80)
                }
                
                Log.i(TAG, "Finished injecting PCM speech. Sent turnComplete. Waiting for Gemini audio translation...")
            } else {
                Log.e(TAG, "No PCM audio file found for injection test!")
            }
        }
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
        audioCapture.startCapture()
    }

    private fun observeStreams() {
        serviceScope.launch {
            audioCapture.audioChunkFlow.collect { pcmChunk ->
                webSocketClient.sendAudioChunk(pcmChunk)
            }
        }

        serviceScope.launch {
            webSocketClient.incomingAudioFlow.collect { pcmChunk ->
                audioPlayback.enqueueAudioChunk(pcmChunk)
            }
        }

        serviceScope.launch {
            webSocketClient.interruptedFlow.collect {
                audioPlayback.flushAndInterrupt()
            }
        }

        serviceScope.launch {
            webSocketClient.subtitleFlow.collect { text ->
                val config = prefsManager.loadConfig()
                if (config.saveHistory && text.isNotBlank()) {
                    val item = HistoryItem(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        mode = activeMode,
                        sourceLang = config.opponentLanguage,
                        targetLang = config.ourLanguage,
                        originalText = "[Оппонент]",
                        translatedText = text
                    )
                    prefsManager.appendHistoryItem(item)
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveTranslate:WakeLock").apply {
            acquire()
        }

        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LiveTranslate:WifiLock").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startForegroundNotification(mode: TranslationMode) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gemini Live Перевод",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Синхронный перевод активен")
            .setContentText("Режим: " + mode.displayName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        serviceScope.cancel()
    }
}
