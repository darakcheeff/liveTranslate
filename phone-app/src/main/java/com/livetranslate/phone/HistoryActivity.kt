package com.livetranslate.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.phone.databinding.ActivityHistoryBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var prefs: EncryptedPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferencesManager(this)
        loadHistory()

        binding.btnClearHistory.setOnClickListener {
            prefs.clearHistory()
            loadHistory()
            Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
        }

        binding.btnExportHistory.setOnClickListener {
            exportHistoryToFile()
        }
    }

    private fun loadHistory() {
        val items = prefs.loadHistory()
        if (items.isEmpty()) {
            binding.tvHistoryContent.text = "История переводов пуста."
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val builder = StringBuilder()
        items.forEach { item ->
            val dateStr = sdf.format(Date(item.timestamp))
            builder.append("[").append(dateStr).append("] (")
                .append(item.sourceLang).append(" -> ").append(item.targetLang).append(")\n")
            builder.append("• ").append(item.translatedText).append("\n\n")
        }
        binding.tvHistoryContent.text = builder.toString()
    }

    private fun exportHistoryToFile() {
        val items = prefs.loadHistory()
        if (items.isEmpty()) {
            Toast.makeText(this, "Нечего экспортировать", Toast.LENGTH_SHORT).show()
            return
        }

        val text = binding.tvHistoryContent.text.toString()
        val file = File(cacheDir, "gemini_translations_export_" + System.currentTimeMillis() + ".txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Экспортировать историю"))
    }
}
