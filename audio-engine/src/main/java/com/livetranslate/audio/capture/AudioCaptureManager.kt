package com.livetranslate.audio.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioCaptureManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 3200 // 100 ms of 16kHz 16-bit Mono (1600 samples * 2 bytes)
        const val DEFAULT_SILENCE_RMS_THRESHOLD = 200.0
    }

    private val _audioChunkFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    private val _waveformRmsFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val waveformRmsFlow: SharedFlow<Float> = _waveformRmsFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val isRecording = AtomicBoolean(false)
    private var isDucking = AtomicBoolean(false)

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
                return false
            }

            // Enable hardware Acoustic Echo Cancellation and Noise Suppression
            val audioSessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }

            record.startRecording()
            isRecording.set(true)

            scope.launch {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isRecording.get() && isActive) {
                    val readBytes = record.read(buffer, 0, CHUNK_SIZE_BYTES)
                    if (readBytes > 0) {
                        val rms = calculateRms(buffer, readBytes)
                        _waveformRmsFlow.emit(rms.toFloat())

                        // Dynamic mic threshold when ducking (playback active through speaker)
                        val effectiveThreshold = if (isDucking.get()) {
                            DEFAULT_SILENCE_RMS_THRESHOLD * 2.5
                        } else {
                            DEFAULT_SILENCE_RMS_THRESHOLD
                        }

                        if (rms >= effectiveThreshold) {
                            val chunkCopy = buffer.copyOf(readBytes)
                            _audioChunkFlow.emit(chunkCopy)
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            stopCapture()
            return false
        }
    }

    fun stopCapture() {
        isRecording.set(false)
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
        } catch (e: Exception) {
            // Ignore release exceptions
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
