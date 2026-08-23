package com.livetranslate.audio.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.livetranslate.core.model.TranslationMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioCaptureManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 3200  // 100 ms per chunk
        const val VAD_SPEECH_RMS_THRESHOLD = 30.0
        const val MIN_CONSECUTIVE_SPEECH_CHUNKS = 2   // 200 ms to confirm speech start
        const val TRAILING_SILENCE_CHUNKS = 5          // 500 ms of silence = sentence boundary
        // Safety fallback: force a turn boundary after 25 seconds of uninterrupted speech
        const val SOLO_MAX_SEGMENT_CHUNKS = 250
    }

    private val _audioChunkFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    private val _waveformRmsFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val waveformRmsFlow: SharedFlow<Float> = _waveformRmsFlow.asSharedFlow()

    // Emits Unit when a natural pause (or safety fallback) signals a turn should be completed
    private val _soloSegmentCompleteFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val soloSegmentCompleteFlow: SharedFlow<Unit> = _soloSegmentCompleteFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val isRecording = AtomicBoolean(false)
    private var isDucking = AtomicBoolean(false)
    private var currentMode = TranslationMode.DIALOGUE
    private var pcmFileOutputStream: FileOutputStream? = null

    fun setDucking(enabled: Boolean) {
        // In SOLO mode never duck: we capture speaker audio through mic regardless of playback
        if (currentMode == TranslationMode.DIALOGUE) {
            isDucking.set(enabled)
        } else {
            isDucking.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(mode: TranslationMode = TranslationMode.DIALOGUE): Boolean {
        if (isRecording.get()) return true
        this.currentMode = mode

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )

            val record = audioRecord ?: return false
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord state not initialized")
                return false
            }

            val audioSessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
                Log.d(TAG, "AcousticEchoCanceler enabled")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                Log.d(TAG, "NoiseSuppressor enabled")
            }

            try {
                context?.let { ctx ->
                    val pcmFile = File(ctx.cacheDir, "last_recorded_audio.pcm")
                    pcmFileOutputStream = FileOutputStream(pcmFile)
                }
            } catch (e: Exception) { Log.w(TAG, "Could not open PCM debug dump file") }

            record.startRecording()
            isRecording.set(true)
            Log.i(TAG, "AudioRecord capture started in mode: ${mode.name}")

            scope.launch {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var consecutiveSpeechChunks = 0
                var consecutiveSilenceChunks = 0
                var isInConfirmedSpeech = false
                var totalSpeechChunksSent = 0
                val preSpeechRingBuffer = ArrayDeque<ByteArray>(6)

                while (isRecording.get() && isActive) {
                    val readBytes = record.read(buffer, 0, CHUNK_SIZE_BYTES)
                    if (readBytes > 0) {
                        val rms = calculateRms(buffer, readBytes)
                        _waveformRmsFlow.emit(rms.toFloat())

                        try { pcmFileOutputStream?.write(buffer, 0, readBytes) } catch (e: Exception) {}

                        if (isDucking.get()) continue

                        val currentChunk = buffer.copyOf(readBytes)

                        if (rms >= VAD_SPEECH_RMS_THRESHOLD) {
                            consecutiveSpeechChunks++
                            consecutiveSilenceChunks = 0

                            if (!isInConfirmedSpeech) {
                                // Buffer pre-speech audio for context (catches first syllable)
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)

                                if (consecutiveSpeechChunks >= MIN_CONSECUTIVE_SPEECH_CHUNKS) {
                                    isInConfirmedSpeech = true
                                    totalSpeechChunksSent = 0
                                    Log.d(TAG, "VAD: Confirmed speech started (RMS: $rms)")
                                    while (preSpeechRingBuffer.isNotEmpty()) {
                                        _audioChunkFlow.emit(preSpeechRingBuffer.removeFirst())
                                        totalSpeechChunksSent++
                                    }
                                }
                            } else {
                                _audioChunkFlow.emit(currentChunk)
                                totalSpeechChunksSent++

                                // SOLO safety fallback: if speaker talks for >25 sec without any pause,
                                // force a turn boundary so translation doesn't starve indefinitely
                                if (currentMode == TranslationMode.SOLO
                                    && totalSpeechChunksSent >= SOLO_MAX_SEGMENT_CHUNKS
                                ) {
                                    Log.d(TAG, "VAD (SOLO): Safety fallback after 25s. Sending turnComplete.")
                                    _soloSegmentCompleteFlow.emit(Unit)
                                    totalSpeechChunksSent = 0
                                    // Keep isInConfirmedSpeech = true — speech continues
                                }
                            }
                        } else {
                            // SILENCE detected
                            consecutiveSpeechChunks = 0
                            if (isInConfirmedSpeech) {
                                consecutiveSilenceChunks++
                                if (consecutiveSilenceChunks <= TRAILING_SILENCE_CHUNKS) {
                                    // Include trailing audio so sentence tail is captured
                                    _audioChunkFlow.emit(currentChunk)
                                    totalSpeechChunksSent++
                                } else {
                                    // Natural pause detected: this is a sentence/phrase boundary
                                    isInConfirmedSpeech = false
                                    consecutiveSilenceChunks = 0
                                    totalSpeechChunksSent = 0
                                    preSpeechRingBuffer.clear()
                                    Log.d(TAG, "VAD: Natural pause detected. Sending turnComplete.")

                                    if (currentMode == TranslationMode.SOLO) {
                                        // Signal Gemini to translate this phrase now
                                        _soloSegmentCompleteFlow.emit(Unit)
                                    }
                                    // DIALOGUE mode: Gemini's own server VAD handles turn detection
                                }
                            } else {
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord capture", e)
            stopCapture()
            return false
        }
    }

    fun stopCapture() {
        isRecording.set(false)
        try { pcmFileOutputStream?.flush(); pcmFileOutputStream?.close(); pcmFileOutputStream = null } catch (e: Exception) {}
        try {
            echoCanceler?.release(); echoCanceler = null
            noiseSuppressor?.release(); noiseSuppressor = null
            audioRecord?.apply { if (state == AudioRecord.STATE_INITIALIZED) stop(); release() }
            audioRecord = null
            Log.i(TAG, "AudioRecord capture stopped")
        } catch (e: Exception) { Log.e(TAG, "Error stopping AudioRecord", e) }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }
        val sampleCount = length / 2
        return if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
    }
}
