package com.livetranslate.audio.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
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
        const val CHUNK_SIZE_BYTES = 3200 // 100 ms of 16kHz 16-bit Mono
        const val VAD_SPEECH_RMS_THRESHOLD = 30.0
        const val MIN_CONSECUTIVE_SPEECH_CHUNKS = 2 // 200 ms to confirm speech
        const val TRAILING_SILENCE_CHUNKS = 5 // 500 ms of trailing silence to trigger Gemini VAD
    }

    private val _audioChunkFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    private val _waveformRmsFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val waveformRmsFlow: SharedFlow<Float> = _waveformRmsFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val isRecording = AtomicBoolean(false)
    private var isDucking = AtomicBoolean(false)
    private var pcmFileOutputStream: FileOutputStream? = null

    fun setDucking(enabled: Boolean) {
        isDucking.set(enabled)
    }

    @SuppressLint("MissingPermission")
    fun startCapture(): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            val record = audioRecord ?: return false
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord state not initialized")
                return false
            }

            val audioSessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "AcousticEchoCanceler enabled")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "NoiseSuppressor enabled")
            }

            try {
                context?.let { ctx ->
                    val pcmFile = File(ctx.cacheDir, "last_recorded_audio.pcm")
                    pcmFileOutputStream = FileOutputStream(pcmFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open PCM debug dump file: ${e.message}")
            }

            record.startRecording()
            isRecording.set(true)
            Log.i(TAG, "AudioRecord capture started successfully")

            scope.launch {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                val silenceBuffer = ByteArray(CHUNK_SIZE_BYTES)
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

                        try {
                            pcmFileOutputStream?.write(buffer, 0, readBytes)
                        } catch (e: Exception) {}

                        if (isDucking.get()) {
                            continue
                        }

                        val currentChunk = buffer.copyOf(readBytes)

                        if (rms >= VAD_SPEECH_RMS_THRESHOLD) {
                            consecutiveSpeechChunks++
                            consecutiveSilenceChunks = 0

                            if (!isInConfirmedSpeech) {
                                if (preSpeechRingBuffer.size >= 5) preSpeechRingBuffer.removeFirst()
                                preSpeechRingBuffer.addLast(currentChunk)

                                if (consecutiveSpeechChunks >= MIN_CONSECUTIVE_SPEECH_CHUNKS) {
                                    isInConfirmedSpeech = true
                                    totalSpeechChunksSent = 0
                                    Log.d(TAG, "VAD: Confirmed speech started (RMS: $rms)")
                                    // Flush 500ms pre-speech baseline frames to calibrate Gemini noise floor
                                    while (preSpeechRingBuffer.isNotEmpty()) {
                                        _audioChunkFlow.emit(preSpeechRingBuffer.removeFirst())
                                        totalSpeechChunksSent++
                                    }
                                }
                            } else {
                                _audioChunkFlow.emit(currentChunk)
                                totalSpeechChunksSent++
                            }
                        } else {
                            consecutiveSpeechChunks = 0
                            if (isInConfirmedSpeech) {
                                consecutiveSilenceChunks++
                                if (consecutiveSilenceChunks <= TRAILING_SILENCE_CHUNKS) {
                                    _audioChunkFlow.emit(currentChunk)
                                    totalSpeechChunksSent++
                                } else {
                                    // Emit 2 explicit zeroed silence buffers to cleanly trigger Gemini server VAD
                                    _audioChunkFlow.emit(silenceBuffer)
                                    _audioChunkFlow.emit(silenceBuffer)
                                    isInConfirmedSpeech = false
                                    Log.d(TAG, "VAD: Speech completed after $totalSpeechChunksSent chunks. Emitted trailing silence to trigger Gemini translation.")
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
        try {
            pcmFileOutputStream?.flush()
            pcmFileOutputStream?.close()
            pcmFileOutputStream = null
        } catch (e: Exception) {}

        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null

            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioRecord = null
            Log.i(TAG, "AudioRecord capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        for (i in 0 until length step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }
        return if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
    }
}
