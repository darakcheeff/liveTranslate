package com.livetranslate.phone

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: EncryptedPreferencesManager

    private var translationService: LiveTranslationService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? LiveTranslationService.LocalBinder
            translationService = localBinder?.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
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

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun updateHeaderInfo() {
        val config = prefs.loadConfig()
        val our = Language.findByCode(config.ourLanguage).name
        val opp = Language.findByCode(config.opponentLanguage).name
        binding.tvLanguagePair.text = "$our ↔ $opp"
    }

    private fun startTranslation(mode: TranslationMode) {
        val config = prefs.loadConfig()
        if (config.apiKeys.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте API-ключи в Настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        binding.panelIdleButtons.visibility = View.GONE
        binding.btnStop.visibility = View.VISIBLE
        binding.tvSubtitles.text = ""

        val intent = Intent(this, LiveTranslationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        translationService?.startTranslation(mode)
    }

    private fun stopTranslation() {
        translationService?.stopTranslation()
        binding.panelIdleButtons.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.status_idle)
    }

    private fun observeService() {
        val service = translationService ?: return

        lifecycleScope.launch {
            service.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Idle -> binding.tvStatus.text = "Готов к переводу"
                    is ConnectionState.Connecting -> binding.tvStatus.text = "Подключение (${state.model})..."
                    is ConnectionState.Connected -> binding.tvStatus.text = "Перевод активен (${state.model})"
                    is ConnectionState.RotatingKey -> binding.tvStatus.text = "Ротация ключа #${state.newKeyIndex + 1}"
                    is ConnectionState.FallbackModel -> binding.tvStatus.text = "Каскад на модель ${state.toModel}"
                    is ConnectionState.Error -> binding.tvStatus.text = "Ошибка: ${state.message}"
                    is ConnectionState.Disconnected -> stopTranslation()
                }
            }
        }

        lifecycleScope.launch {
            service.subtitleFlow.collect { text ->
                val config = prefs.loadConfig()
                if (config.showSubtitles) {
                    binding.tvSubtitles.append("$text ")
                    binding.scrollSubtitles.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        lifecycleScope.launch {
            service.waveformRmsFlow.collect { rms ->
                binding.waveformVisualizer.updateRms(rms)
            }
        }
    }
}
