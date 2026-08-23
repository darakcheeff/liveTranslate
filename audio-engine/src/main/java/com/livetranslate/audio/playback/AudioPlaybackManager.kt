package com.livetranslate.audio.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.livetranslate.audio.capture.WavFileWriter
import com.livetranslate.core.model.TranslationMode
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlaybackManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "AudioPlayback"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val DIGITAL_GAIN_FACTOR = 2.0f // 200% digital gain boost
        const val JITTER_BUFFER_MIN_CHUNKS = 3 // 120ms pre-buffering to prevent network packet underrun jitter
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var wavFileWriterRaf: RandomAccessFile? = null

    var onPlaybackActiveChanged: ((Boolean) -> Unit)? = null

    fun initialize(mode: TranslationMode): Boolean {
        release()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize * 2, 3200 * 16)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            isPlaying.set(true)

            try {
                context?.let { ctx ->
                    val wavFile = File(ctx.cacheDir, "output_translation.wav")
                    wavFileWriterRaf = WavFileWriter.createWavFile(wavFile, SAMPLE_RATE, 1)
                    Log.i(TAG, "Dumping output translation WAV to: ${wavFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not initialize output WAV writer", e)
            }

            Log.i(TAG, "AudioTrack initialized with JitterBuffer ($JITTER_BUFFER_MIN_CHUNKS chunks) in mode: ${mode.name}")
            startPlaybackLoop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            return false
        }
    }

    fun enqueueAudioChunk(pcmChunk: ByteArray) {
        val boostedChunk = applyDigitalGain(pcmChunk, DIGITAL_GAIN_FACTOR)
        audioQueue.offer(boostedChunk)

        wavFileWriterRaf?.let { raf ->
            WavFileWriter.appendPcmData(raf, boostedChunk)
        }
    }

    fun flushAndInterrupt() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack", e)
        }
        onPlaybackActiveChanged?.invoke(false)
    }

    private fun startPlaybackLoop() {
        scope.launch {
            var isBuffering = true
            var emptyCycles = 0

            while (isPlaying.get() && isActive) {
                if (isBuffering) {
                    if (audioQueue.size >= JITTER_BUFFER_MIN_CHUNKS) {
                        isBuffering = false
                        emptyCycles = 0
                        onPlaybackActiveChanged?.invoke(true)
                    } else if (audioQueue.isNotEmpty() && emptyCycles > 10) {
                        isBuffering = false
                        emptyCycles = 0
                        onPlaybackActiveChanged?.invoke(true)
                    } else {
                        emptyCycles++
                        delay(10)
                        continue
                    }
                }

                val chunk = audioQueue.poll()
                if (chunk != null) {
                    audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    emptyCycles = 0
                } else {
                    emptyCycles++
                    if (emptyCycles >= 15) {
                        isBuffering = true
                        onPlaybackActiveChanged?.invoke(false)
                    }
                    delay(10)
                }
            }
        }
    }

    fun release() {
        isPlaying.set(false)
        audioQueue.clear()

        try {
            wavFileWriterRaf?.let { raf ->
                WavFileWriter.finalizeWavFile(raf, SAMPLE_RATE, 1)
                wavFileWriterRaf = null
            }
        } catch (e: Exception) {}

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
        onPlaybackActiveChanged?.invoke(false)
    }

    private fun applyDigitalGain(pcm16Bytes: ByteArray, gainMultiplier: Float): ByteArray {
        val boosted = ByteArray(pcm16Bytes.size)
        for (i in 0 until pcm16Bytes.size step 2) {
            val sample = ((pcm16Bytes[i + 1].toInt() shl 8) or (pcm16Bytes[i].toInt() and 0xFF)).toShort()
            val scaled = (sample * gainMultiplier).toInt()
            val clamped = scaled.coerceIn(-32768, 32767).toShort()
            boosted[i] = (clamped.toInt() and 0xFF).toByte()
            boosted[i + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()
        }
        return boosted
    }
}
