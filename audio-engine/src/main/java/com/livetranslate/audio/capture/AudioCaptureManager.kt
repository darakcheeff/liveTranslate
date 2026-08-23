package com.livetranslate.audio.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.google.speech.micro.GoogleEndpointer
import com.livetranslate.core.model.TranslationMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
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
        const val MIN_CONSECUTIVE_SPEECH_CHUNKS = 2   // 200 ms to confirm speech start
        const val PAUSE_SILENCE_CHUNKS = 4            // 400 ms of silence = natural sentence pause
        const val SOLO_MAX_SEGMENT_CHUNKS = 75        // 7.5s safety limit for uninterrupted monologue
        const val WINDOW_SIZE = 30                    // 3.0s sliding window for ambient noise floor
    }

    // Direct streaming flow (used in DIALOGUE mode)
    private val _audioChunkFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    // Completed phrase queue flow (used for continuous SOLO conveyor pipeline)
    private val _completedPhraseFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    val completedPhraseFlow: SharedFlow<ByteArray> = _completedPhraseFlow.asSharedFlow()

    private val _waveformRmsFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val waveformRmsFlow: SharedFlow<Float> = _waveformRmsFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var googleSpeechDetector: GoogleSpeechDetector? = null
    private val isRecording = AtomicBoolean(false)
    private var isDucking = AtomicBoolean(false)
    private var currentMode = TranslationMode.DIALOGUE
    private var wavFileWriterRaf: RandomAccessFile? = null

    fun setDucking(enabled: Boolean) {
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

            context?.let { ctx ->
                val detector = GoogleSpeechDetector(ctx)
                if (detector.initialize()) {
                    googleSpeechDetector = detector
                    Log.i(TAG, "Google Endpointer engine loaded successfully")
                } else {
                    googleSpeechDetector = null
                }
            }

            try {
                context?.let { ctx ->
                    val wavFile = File(ctx.cacheDir, "input_mic.wav")
                    wavFileWriterRaf = WavFileWriter.createWavFile(wavFile, SAMPLE_RATE, 1)
                    Log.i(TAG, "Dumping input mic WAV to: ${wavFile.absolutePath}")
                }
            } catch (e: Exception) { Log.w(TAG, "Could not open WAV debug dump file", e) }

            record.startRecording()
            isRecording.set(true)
            Log.i(TAG, "AudioRecord capture started in mode: ${mode.name}")

            scope.launch {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var consecutiveSpeechChunks = 0
                var consecutiveSilenceChunks = 0
                var isInConfirmedSpeech = false
                var totalSpeechChunksSent = 0
                var tickCounter = 0
                val ambientNoiseWindow = ArrayDeque<Double>(WINDOW_SIZE + 2)
                val preSpeechRingBuffer = ArrayDeque<ByteArray>(6)
                val currentPhraseStream = ByteArrayOutputStream(CHUNK_SIZE_BYTES * 80)
                var currentAmbientFloor = 35.0

                while (isRecording.get() && isActive) {
                    val readBytes = record.read(buffer, 0, CHUNK_SIZE_BYTES)
                    if (readBytes > 0) {
                        val rms = calculateRms(buffer, readBytes)
                        _waveformRmsFlow.emit(rms.toFloat())

                        wavFileWriterRaf?.let { raf ->
                            WavFileWriter.appendPcmData(raf, buffer, 0, readBytes)
                        }

                        if (isDucking.get()) continue

                        val currentChunk = buffer.copyOf(readBytes)
                        tickCounter++

                        // Google Neural Endpointer processing if available
                        var neuralSpeechStart = false
                        var neuralSpeechEnd = false
                        googleSpeechDetector?.let { detector ->
                            val result = detector.processAudio(buffer, 0, readBytes)
                            if (result != null) {
                                if (result.isSpeechStart) neuralSpeechStart = true
                                if (result.isSpeechEnd) neuralSpeechEnd = true
                            }
                        }

                        // Baseline acoustic noise floor estimation (updated only in silence)
                        if (!isInConfirmedSpeech) {
                            if (ambientNoiseWindow.size >= WINDOW_SIZE) ambientNoiseWindow.removeFirst()
                            ambientNoiseWindow.addLast(rms)

                            val sortedWindow = ambientNoiseWindow.sorted()
                            val percentileIndex = (sortedWindow.size / 6).coerceIn(0, sortedWindow.size - 1)
                            currentAmbientFloor = sortedWindow[percentileIndex].coerceIn(20.0, 200.0)
                        }

                        val speechThreshold = maxOf(55.0, currentAmbientFloor * 1.5)
                        val silenceThreshold = maxOf(32.0, currentAmbientFloor * 1.2)

                        if (tickCounter % 30 == 0) {
                            Log.d(TAG, "VAD: RMS=%.1f, AmbientFloor=%.1f, SpeechThresh=%.1f, SilThresh=%.1f, Speaking=%b, SentChunks=%d, NeuralEngine=%b".format(
                                rms, currentAmbientFloor, speechThreshold, silenceThreshold, isInConfirmedSpeech, totalSpeechChunksSent, googleSpeechDetector != null
                            ))
                        }

                        val isSpeechDetected = neuralSpeechStart || (rms >= speechThreshold)
                        val isSilenceDetected = neuralSpeechEnd || (rms < silenceThreshold)

                        if (isSpeechDetected) {
                            consecutiveSpeechChunks++
                            consecutiveSilenceChunks = 0

                            if (!isInConfirmedSpeech) {
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)

                                if (consecutiveSpeechChunks >= MIN_CONSECUTIVE_SPEECH_CHUNKS || neuralSpeechStart) {
                                    isInConfirmedSpeech = true
                                    totalSpeechChunksSent = 0
                                    currentPhraseStream.reset()

                                    Log.i(TAG, "VAD: >>> PHRASE START <<< (RMS=%.1f, Floor=%.1f, Neural=%b)".format(
                                        rms, currentAmbientFloor, neuralSpeechStart
                                    ))

                                    while (preSpeechRingBuffer.isNotEmpty()) {
                                        val preChunk = preSpeechRingBuffer.removeFirst()
                                        currentPhraseStream.write(preChunk)
                                        if (currentMode == TranslationMode.DIALOGUE) {
                                            _audioChunkFlow.emit(preChunk)
                                        }
                                        totalSpeechChunksSent++
                                    }
                                }
                            } else {
                                currentPhraseStream.write(currentChunk)
                                if (currentMode == TranslationMode.DIALOGUE) {
                                    _audioChunkFlow.emit(currentChunk)
                                }
                                totalSpeechChunksSent++

                                // Safety cutoff for long uninterrupted monologues (7.5 seconds)
                                if (currentMode == TranslationMode.SOLO && totalSpeechChunksSent >= SOLO_MAX_SEGMENT_CHUNKS) {
                                    val completedPcm = currentPhraseStream.toByteArray()
                                    if (completedPcm.isNotEmpty()) {
                                        Log.i(TAG, "VAD (SOLO): 7.5s safety phrase chunk complete (${completedPcm.size} bytes). Enqueueing.")
                                        _completedPhraseFlow.emit(completedPcm)
                                        currentPhraseStream.reset()
                                        totalSpeechChunksSent = 0
                                    }
                                }
                            }
                        } else if (isSilenceDetected) {
                            consecutiveSpeechChunks = 0
                            if (isInConfirmedSpeech) {
                                consecutiveSilenceChunks++
                                if (consecutiveSilenceChunks <= PAUSE_SILENCE_CHUNKS && !neuralSpeechEnd) {
                                    currentPhraseStream.write(currentChunk)
                                    if (currentMode == TranslationMode.DIALOGUE) {
                                        _audioChunkFlow.emit(currentChunk)
                                    }
                                    totalSpeechChunksSent++
                                } else {
                                    // Complete sentence boundary detected at 400ms pause
                                    isInConfirmedSpeech = false
                                    consecutiveSilenceChunks = 0
                                    totalSpeechChunksSent = 0
                                    preSpeechRingBuffer.clear()

                                    val completedPcm = currentPhraseStream.toByteArray()
                                    currentPhraseStream.reset()

                                    Log.i(TAG, "VAD: <<< SENTENCE COMPLETE (${completedPcm.size} bytes) >>> (RMS=%.1f, Neural=%b)".format(
                                        rms, neuralSpeechEnd
                                    ))

                                    if (currentMode == TranslationMode.SOLO && completedPcm.size >= CHUNK_SIZE_BYTES * 10) {
                                        Log.i(TAG, "VAD (SOLO): Complete sentence enqueued (${completedPcm.size} bytes).")
                                        _completedPhraseFlow.emit(completedPcm)
                                    }
                                }
                            } else {
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)
                            }
                        } else {
                            // Hysteresis zone
                            consecutiveSpeechChunks = 0
                            if (isInConfirmedSpeech) {
                                currentPhraseStream.write(currentChunk)
                                if (currentMode == TranslationMode.DIALOGUE) {
                                    _audioChunkFlow.emit(currentChunk)
                                }
                                totalSpeechChunksSent++
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
        try {
            googleSpeechDetector?.close()
            googleSpeechDetector = null
        } catch (e: Exception) {}

        try {
            wavFileWriterRaf?.let { raf ->
                WavFileWriter.finalizeWavFile(raf, SAMPLE_RATE, 1)
                wavFileWriterRaf = null
            }
        } catch (e: Exception) {}

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
