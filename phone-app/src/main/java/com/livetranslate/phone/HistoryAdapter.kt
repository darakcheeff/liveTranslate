package com.livetranslate.phone

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
import com.livetranslate.core.model.HistoryItem
import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode
import com.livetranslate.phone.databinding.ItemHistoryBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onRename: (HistoryItem) -> Unit,
    private val onDelete: (HistoryItem) -> Unit,
    private val onShare: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    fun updateData(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun stopAudio() {
        handler.removeCallbacksAndMessages(null)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        currentlyPlayingId = null
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            val context = binding.root.context
            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            val dateStr = sdf.format(Date(item.timestamp))

            val title = if (item.title.isNotBlank()) item.title else "Перевод $dateStr"
            binding.tvHistoryTitle.text = title

            val modeStr = if (item.mode == TranslationMode.SOLO) "🎧 Шепот" else "🗣 Диалог"
            val srcLang = Language.findByCode(item.sourceLang).code.uppercase()
            val tgtLang = Language.findByCode(item.targetLang).code.uppercase()
            binding.tvHistoryMeta.text = "$dateStr • $modeStr • $srcLang → $tgtLang"

            // Transcript text
            if (item.translatedText.isNotBlank()) {
                binding.tvTranscriptText.visibility = View.VISIBLE
                binding.tvTranscriptText.text = item.translatedText
            } else {
                binding.tvTranscriptText.visibility = View.GONE
            }

            // Audio Player Setup
            val audioFile = item.audioFilePath?.let { File(it) }
            val hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 44

            if (hasAudio) {
                binding.layoutAudioPlayer.visibility = View.VISIBLE
                val isThisPlaying = currentlyPlayingId == item.id && mediaPlayer?.isPlaying == true

                binding.btnPlayAudio.setImageResource(
                    if (isThisPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )

                binding.btnPlayAudio.setOnClickListener {
                    togglePlay(item, audioFile!!)
                }

                binding.seekAudioProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && currentlyPlayingId == item.id) {
                            mediaPlayer?.let { mp ->
                                val seekTo = (mp.duration * (progress / 100.0)).toInt()
                                mp.seekTo(seekTo)
                            }
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            } else {
                binding.layoutAudioPlayer.visibility = View.GONE
            }

            binding.btnRenameHistory.setOnClickListener { onRename(item) }
            binding.btnDeleteHistory.setOnClickListener {
                if (currentlyPlayingId == item.id) stopAudio()
                onDelete(item)
            }
            binding.btnShareItem.setOnClickListener { onShare(item) }
        }

        private fun togglePlay(item: HistoryItem, file: File) {
            if (currentlyPlayingId == item.id && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                return
            }

            if (currentlyPlayingId == item.id && mediaPlayer != null) {
                mediaPlayer?.start()
                binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                startProgressTracker()
                return
            }

            stopAudio()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                        binding.seekAudioProgress.progress = 0
                        currentlyPlayingId = null
                    }
                }
                currentlyPlayingId = item.id
                binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                startProgressTracker()
                notifyDataSetChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun startProgressTracker() {
            progressRunnable?.let { handler.removeCallbacks(it) }
            progressRunnable = object : Runnable {
                override fun run() {
                    mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            val duration = mp.duration
                            val current = mp.currentPosition
                            if (duration > 0) {
                                val progress = (current * 100 / duration)
                                binding.seekAudioProgress.progress = progress
                                val sec = (current / 1000) % 60
                                val min = (current / 1000) / 60
                                binding.tvAudioDuration.text = String.format("%d:%02d", min, sec)
                            }
                            handler.postDelayed(this, 250)
                        }
                    }
                }
            }
            handler.post(progressRunnable!!)
        }
    }
}
