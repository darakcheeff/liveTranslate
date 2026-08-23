package com.livetranslate.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetranslate.core.model.HistoryItem
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.phone.databinding.ActivityHistoryBinding
import java.io.File

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var prefs: EncryptedPreferencesManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferencesManager(this)

        adapter = HistoryAdapter(
            items = emptyList(),
            onRename = { item -> showRenameDialog(item) },
            onDelete = { item -> showDeleteConfirmDialog(item) },
            onShare = { item -> shareHistoryItem(item) }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearAllHistory.setOnClickListener {
            showClearAllDialog()
        }

        loadHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.stopAudio()
    }

    private fun loadHistory() {
        val items = prefs.loadHistory()
        if (items.isEmpty()) {
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
        } else {
            binding.tvEmptyHistory.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            adapter.updateData(items)
        }
    }

    private fun showRenameDialog(item: HistoryItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Переименовать запись")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    prefs.updateHistoryItem(item.copy(title = newTitle))
                    loadHistory()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteConfirmDialog(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle("Удалить запись?")
            .setMessage("Аудиозапись и текст этого перевода будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                prefs.deleteHistoryItem(item.id)
                loadHistory()
                Toast.makeText(this, "Запись удалена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить всю историю?")
            .setMessage("Все сохраненные аудиозаписи и стенограммы будут удалены.")
            .setPositiveButton("Очистить") { _, _ ->
                prefs.clearHistory()
                loadHistory()
                Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun shareHistoryItem(item: HistoryItem) {
        val audioFile = item.audioFilePath?.let { File(it) }
        val shareIntent = Intent(Intent.ACTION_SEND)

        val textToShare = buildString {
            if (item.title.isNotBlank()) append(item.title).append("\n\n")
            if (item.translatedText.isNotBlank()) {
                append("Стенограмма перевода:\n")
                append(item.translatedText)
            }
        }

        if (audioFile != null && audioFile.exists()) {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                audioFile
            )
            shareIntent.type = "audio/wav"
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
            shareIntent.putExtra(Intent.EXTRA_TEXT, textToShare)
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_TEXT, textToShare)
        }

        startActivity(Intent.createChooser(shareIntent, "Поделиться переводом"))
    }
}
