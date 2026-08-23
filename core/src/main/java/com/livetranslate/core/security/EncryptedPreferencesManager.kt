package com.livetranslate.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.livetranslate.core.model.GeminiConfig
import com.livetranslate.core.model.HistoryItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class EncryptedPreferencesManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS_FILE_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun saveConfig(config: GeminiConfig) {
        val serialized = json.encodeToString(config)
        sharedPreferences.edit().putString(KEY_CONFIG, serialized).apply()
    }

    fun loadConfig(): GeminiConfig {
        val raw = sharedPreferences.getString(KEY_CONFIG, null) ?: return GeminiConfig()
        return try {
            json.decodeFromString<GeminiConfig>(raw)
        } catch (e: Exception) {
            GeminiConfig()
        }
    }

    fun saveHistory(history: List<HistoryItem>) {
        val serialized = json.encodeToString(history)
        sharedPreferences.edit().putString(KEY_HISTORY, serialized).apply()
    }

    fun loadHistory(): List<HistoryItem> {
        val raw = sharedPreferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<HistoryItem>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun appendHistoryItem(item: HistoryItem) {
        val current = loadHistory().toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        if (current.size > 1000) {
            val removed = current.removeAt(current.size - 1)
            removed.audioFilePath?.let { path ->
                try { File(path).delete() } catch (e: Exception) {}
            }
        }
        saveHistory(current)
    }

    fun updateHistoryItem(item: HistoryItem) {
        val current = loadHistory().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
            saveHistory(current)
        }
    }

    fun deleteHistoryItem(id: String) {
        val current = loadHistory().toMutableList()
        val item = current.find { it.id == id }
        if (item != null) {
            item.audioFilePath?.let { path ->
                try { File(path).delete() } catch (e: Exception) {}
            }
            current.removeAll { it.id == id }
            saveHistory(current)
        }
    }

    fun clearHistory() {
        val current = loadHistory()
        current.forEach { item ->
            item.audioFilePath?.let { path ->
                try { File(path).delete() } catch (e: Exception) {}
            }
        }
        sharedPreferences.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "gemini_live_translate_secure_prefs"
        private const val KEY_CONFIG = "gemini_config_json"
        private const val KEY_HISTORY = "gemini_history_json"
    }
}
