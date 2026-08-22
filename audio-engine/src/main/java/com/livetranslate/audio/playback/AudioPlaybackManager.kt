package com.livetranslate.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.livetranslate.core.model.TranslationMode
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlaybackManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val SAMPLE_RATE = 24000 // Gemini Live API output sample rate
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val JITTER_BUFFER_MS = 180 // 180ms buffer before initial play
        const val BYTES_PER_MS = (SAMPLE_RATE * 2) / 1000 // 48 bytes/ms
        const val JITTER_THRESHOLD_BYTES = JITTER_BUFFER_MS * BYTES_PER_MS
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val isBuffering = AtomicBoolean(true)
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var queuedBytes = 0

    var onPlaybackActiveChanged: ((Boolean) -> Unit)? = null

    fun initialize(mode: TranslationMode): Boolean {
        release()

        val usage = when (mode) {
            TranslationMode.SOLO -> AudioAttributes.USAGE_MEDIA
            TranslationMode.DIALOGUE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        }

        val contentType = AudioAttributes.CONTENT_TYPE_SPEECH

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, JITTER_THRESHOLD_BYTES * 4)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying.set(true)
            isBuffering.set(true)
            startPlaybackLoop()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun enqueueAudioChunk(pcmChunk: ByteArray) {
        audioQueue.offer(pcmChunk)
        queuedBytes += pcmChunk.size
    }

    /**
     * Interruption handling from Gemini Live (interrupted: true)
     */
    fun flushAndInterrupt() {
        audioQueue.clear()
        queuedBytes = 0
        isBuffering.set(true)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            // Ignore state exceptions during flush
        }
        onPlaybackActiveChanged?.invoke(false)
    }

    private fun startPlaybackLoop() {
        scope.launch {
            while (isPlaying.get() && isActive) {
                if (isBuffering.get()) {
                    if (queuedBytes >= JITTER_THRESHOLD_BYTES) {
                        isBuffering.set(false)
                    } else {
                        delay(20)
                        continue
                    }
                }

                val chunk = audioQueue.poll()
                if (chunk != null) {
                    queuedBytes -= chunk.size
                    onPlaybackActiveChanged?.invoke(true)
                    audioTrack?.write(chunk, 0, chunk.size)
                } else {
                    onPlaybackActiveChanged?.invoke(false)
                    isBuffering.set(true)
                    delay(20)
                }
            }
        }
    }

    fun release() {
        isPlaying.set(false)
        audioQueue.clear()
        queuedBytes = 0
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            // Ignore
        }
        onPlaybackActiveChanged?.invoke(false)
    }
}
