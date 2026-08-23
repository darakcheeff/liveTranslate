package com.livetranslate.phone

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.livetranslate.audio.service.LiveTranslationService
import com.livetranslate.core.model.ConnectionState
import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.phone.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: EncryptedPreferencesManager

    private var translationService: LiveTranslationService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "LiveTranslationService connected")
            val localBinder = binder as? LiveTranslationService.LocalBinder
            translationService = localBinder?.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "LiveTranslationService disconnected")
            translationService = null
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "Для работы перевода требуется доступ к микрофону", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferencesManager(this)
        checkPermissions()
        initUI()
    }

    override fun onResume() {
        super.onResume()
        updateHeaderInfo()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LiveTranslationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun initUI() {
        binding.btnSoloMode.setOnClickListener { startTranslation(TranslationMode.SOLO) }
        binding.btnDialogueMode.setOnClickListener { startTranslation(TranslationMode.DIALOGUE) }
        binding.btnStop.setOnClickListener { stopTranslation() }

        binding.btnSwapLanguages.setOnClickListener {
            swapLanguages()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun swapLanguages() {
        val config = prefs.loadConfig()
        val newConfig = config.copy(
            ourLanguage = config.opponentLanguage,
            opponentLanguage = config.ourLanguage
        )
        prefs.saveConfig(newConfig)
        updateHeaderInfo()
        translationService?.updateConfig(newConfig)

        val ourName = Language.findByCode(newConfig.ourLanguage).name
        val oppName = Language.findByCode(newConfig.opponentLanguage).name
        Toast.makeText(this, "$ourName ⇄ $oppName", Toast.LENGTH_SHORT).show()
    }

    private fun updateHeaderInfo() {
        val config = prefs.loadConfig()
        val ourLang = Language.findByCode(config.ourLanguage).name
        val opponentLang = Language.findByCode(config.opponentLanguage).name
        binding.tvOurLanguage.text = ourLang
        binding.tvOpponentLanguage.text = opponentLang
    }

    private fun startTranslation(mode: TranslationMode) {
        val config = prefs.loadConfig()
        if (config.apiKeys.isEmpty()) {
            Toast.makeText(this, "Пожалуйста, добавьте API ключ Gemini в Настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val intent = Intent(this, LiveTranslationService::class.java).apply {
            action = LiveTranslationService.ACTION_START
            putExtra(LiveTranslationService.EXTRA_MODE, mode.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        setSessionActiveUI(true)
    }

    private fun stopTranslation() {
        val intent = Intent(this, LiveTranslationService::class.java).apply {
            action = LiveTranslationService.ACTION_STOP
        }
        startService(intent)
        setSessionActiveUI(false)
        binding.tvStatus.setText(R.string.status_idle)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        binding.tvSubtitles.text = ""
    }

    private fun setSessionActiveUI(isActive: Boolean) {
        binding.panelIdleButtons.visibility = if (isActive) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (isActive) View.VISIBLE else View.GONE
    }

    private fun observeService() {
        val service = translationService ?: return

        lifecycleScope.launch {
            service.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Idle -> {
                        binding.tvStatus.setText(R.string.status_idle)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                        setSessionActiveUI(false)
                    }
                    is ConnectionState.Connecting -> {
                        binding.tvStatus.text = "Подключение... (${state.model})"
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_orange))
                        setSessionActiveUI(true)
                    }
                    is ConnectionState.Connected -> {
                        binding.tvStatus.setText(R.string.status_connected)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                        setSessionActiveUI(true)
                    }
                    is ConnectionState.RotatingKey -> {
                        binding.tvStatus.text = "Ротация ключа #${state.newKeyIndex + 1}"
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_orange))
                    }
                    is ConnectionState.FallbackModel -> {
                        binding.tvStatus.text = "Каскад на модель: ${state.toModel}"
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_orange))
                    }
                    is ConnectionState.Error -> {
                        binding.tvStatus.text = state.message
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                    }
                    is ConnectionState.Disconnected -> {
                        binding.tvStatus.setText(R.string.status_idle)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                        setSessionActiveUI(false)
                    }
                }
            }
        }

        lifecycleScope.launch {
            service.subtitleFlow.collect { subtitle ->
                binding.tvSubtitles.text = subtitle
                binding.scrollSubtitles.post {
                    binding.scrollSubtitles.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        lifecycleScope.launch {
            service.waveformRmsFlow.collect { rms ->
                binding.waveformVisualizer.addAmplitude(rms)
            }
        }
    }
}
