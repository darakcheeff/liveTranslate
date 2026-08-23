package com.livetranslate.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.livetranslate.core.model.TranslationMode
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlaybackManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "AudioPlayback"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

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
        val bufferSize = maxOf(minBufferSize, 3200 * 8)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying.set(true)
            Log.i(TAG, "AudioTrack initialized and playing in mode: ${mode.name}")
            startPlaybackLoop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            return false
        }
    }

    fun enqueueAudioChunk(pcmChunk: ByteArray) {
        audioQueue.offer(pcmChunk)
        Log.d(TAG, "Enqueued ${pcmChunk.size} bytes for playback")
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
            var chunksPlayed = 0
            while (isPlaying.get() && isActive) {
                val chunk = audioQueue.poll()
                if (chunk != null) {
                    onPlaybackActiveChanged?.invoke(true)
                    val written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                    chunksPlayed++
                    if (chunksPlayed % 10 == 0) {
                        Log.d(TAG, "Played $chunksPlayed audio chunks ($written bytes written)")
                    }
                } else {
                    onPlaybackActiveChanged?.invoke(false)
                    delay(10)
                }
            }
        }
    }

    fun release() {
        isPlaying.set(false)
        audioQueue.clear()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
        onPlaybackActiveChanged?.invoke(false)
    }
}
