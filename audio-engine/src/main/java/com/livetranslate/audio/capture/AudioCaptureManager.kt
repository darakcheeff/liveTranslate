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
        const val PAUSE_SILENCE_CHUNKS = 3            // 300 ms of silence = natural sentence pause
        const val SOLO_MAX_SEGMENT_CHUNKS = 45        // 4.5s max phrase length for 100% verbatim translation
        const val WINDOW_SIZE = 30                    // 3.0s sliding window for ambient noise floor
    }

    private val _audioChunkFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    private val _waveformRmsFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val waveformRmsFlow: SharedFlow<Float> = _waveformRmsFlow.asSharedFlow()

    private val _soloActivityStartFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val soloActivityStartFlow: SharedFlow<Unit> = _soloActivityStartFlow.asSharedFlow()

    private val _soloActivityEndFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val soloActivityEndFlow: SharedFlow<Unit> = _soloActivityEndFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
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

                        // ONLY update ambient noise floor when NOT speaking!
                        // This prevents loud speech from contaminating the baseline noise floor.
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
                            Log.d(TAG, "VAD: RMS=%.1f, AmbientFloor=%.1f, SpeechThresh=%.1f, SilThresh=%.1f, Speaking=%b, SentChunks=%d".format(
                                rms, currentAmbientFloor, speechThreshold, silenceThreshold, isInConfirmedSpeech, totalSpeechChunksSent
                            ))
                        }

                        if (rms >= speechThreshold) {
                            consecutiveSpeechChunks++
                            consecutiveSilenceChunks = 0

                            if (!isInConfirmedSpeech) {
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)

                                if (consecutiveSpeechChunks >= MIN_CONSECUTIVE_SPEECH_CHUNKS) {
                                    isInConfirmedSpeech = true
                                    totalSpeechChunksSent = 0
                                    Log.i(TAG, "VAD: >>> PHRASE START <<< (RMS=%.1f, Floor=%.1f, Thresh=%.1f)".format(
                                        rms, currentAmbientFloor, speechThreshold
                                    ))
                                    if (currentMode == TranslationMode.SOLO) {
                                        _soloActivityStartFlow.emit(Unit)
                                    }
                                    while (preSpeechRingBuffer.isNotEmpty()) {
                                        _audioChunkFlow.emit(preSpeechRingBuffer.removeFirst())
                                        totalSpeechChunksSent++
                                    }
                                }
                            } else {
                                _audioChunkFlow.emit(currentChunk)
                                totalSpeechChunksSent++

                                // In SOLO mode: limit continuous phrases to ~4.5 seconds for 100% verbatim translation
                                if (currentMode == TranslationMode.SOLO && totalSpeechChunksSent >= SOLO_MAX_SEGMENT_CHUNKS) {
                                    Log.i(TAG, "VAD (SOLO): 4.5s phrase complete ($totalSpeechChunksSent chunks). Emitting activityEnd.")
                                    _soloActivityEndFlow.emit(Unit)
                                    totalSpeechChunksSent = 0
                                    // Remain in speech mode but start new segment turn seamlessly
                                    _soloActivityStartFlow.emit(Unit)
                                }
                            }
                        } else if (rms < silenceThreshold) {
                            consecutiveSpeechChunks = 0
                            if (isInConfirmedSpeech) {
                                consecutiveSilenceChunks++
                                if (consecutiveSilenceChunks <= PAUSE_SILENCE_CHUNKS) {
                                    _audioChunkFlow.emit(currentChunk)
                                    totalSpeechChunksSent++
                                } else {
                                    // 300ms pause confirmed -> sentence boundary detected
                                    isInConfirmedSpeech = false
                                    consecutiveSilenceChunks = 0
                                    totalSpeechChunksSent = 0
                                    preSpeechRingBuffer.clear()
                                    Log.i(TAG, "VAD: <<< PHRASE PAUSE (300ms) >>> (RMS=%.1f, Floor=%.1f)".format(
                                        rms, currentAmbientFloor
                                    ))
                                    if (currentMode == TranslationMode.SOLO) {
                                        _soloActivityEndFlow.emit(Unit)
                                    }
                                }
                            } else {
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)
                            }
                        } else {
                            // Hysteresis zone (between silence and speech threshold)
                            consecutiveSpeechChunks = 0
                            if (isInConfirmedSpeech) {
                                _audioChunkFlow.emit(currentChunk)
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
