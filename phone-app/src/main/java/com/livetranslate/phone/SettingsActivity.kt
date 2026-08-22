package com.livetranslate.phone

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.livetranslate.core.model.GeminiConfig
import com.livetranslate.core.model.Language
import com.livetranslate.core.model.VoiceName
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.gemini.discovery.GeminiModelDiscovery
import com.livetranslate.phone.databinding.ActivitySettingsBinding
import com.livetranslate.phone.sync.QrScannerActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: EncryptedPreferencesManager
    private val modelDiscovery = GeminiModelDiscovery()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferencesManager(this)
        initViews()
        loadCurrentSettings()
    }

    private fun initViews() {
        val languages = Language.SUPPORTED_LANGUAGES.map { "${it.name} (${it.nativeName})" }
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        binding.spinnerOurLanguage.adapter = langAdapter
        binding.spinnerOpponentLanguage.adapter = langAdapter

        val voices = VoiceName.entries.map { "${it.apiName} - ${it.genderDescription}" }
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
        binding.spinnerVoice.adapter = voiceAdapter

        binding.btnDiscoverModels.setOnClickListener {
            checkKeysAndDiscoverModels()
        }

        binding.btnSyncWithWatch.setOnClickListener {
            startActivity(Intent(this, QrScannerActivity::class.java))
        }

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadCurrentSettings() {
        val config = prefs.loadConfig()
        binding.etApiKeys.setText(config.apiKeys.joinToString("
"))

        val ourIdx = Language.SUPPORTED_LANGUAGES.indexOfFirst { it.code == config.ourLanguage }.coerceAtLeast(0)
        binding.spinnerOurLanguage.setSelection(ourIdx)

        val oppIdx = Language.SUPPORTED_LANGUAGES.indexOfFirst { it.code == config.opponentLanguage }.coerceAtLeast(1)
        binding.spinnerOpponentLanguage.setSelection(oppIdx)

        val voiceIdx = VoiceName.entries.indexOf(config.selectedVoice).coerceAtLeast(0)
        binding.spinnerVoice.setSelection(voiceIdx)

        binding.switchSubtitles.isChecked = config.showSubtitles
        binding.switchHistory.isChecked = config.saveHistory
    }

    private fun checkKeysAndDiscoverModels() {
        val keys = binding.etApiKeys.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) {
            Toast.makeText(this, "Введите хотя бы один API-ключ", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "Проверка ключа...", Toast.LENGTH_SHORT).show()
            val result = modelDiscovery.fetchLiveCapableModels(keys.first())
            result.onSuccess { models ->
                Toast.makeText(this@SettingsActivity, "Доступные модели: ${models.joinToString(", ")}", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(this@SettingsActivity, "Ошибка проверки: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveSettings() {
        val keys = binding.etApiKeys.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ourLang = Language.SUPPORTED_LANGUAGES[binding.spinnerOurLanguage.selectedItemPosition].code
        val oppLang = Language.SUPPORTED_LANGUAGES[binding.spinnerOpponentLanguage.selectedItemPosition].code
        val voice = VoiceName.entries[binding.spinnerVoice.selectedItemPosition]

        val config = GeminiConfig(
            apiKeys = keys,
            ourLanguage = ourLang,
            opponentLanguage = oppLang,
            selectedVoice = voice,
            showSubtitles = binding.switchSubtitles.isChecked,
            saveHistory = binding.switchHistory.isChecked
        )

        prefs.saveConfig(config)
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        finish()
    }
}
