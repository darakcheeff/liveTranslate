package com.livetranslate.watch

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.livetranslate.audio.service.LiveTranslationService
import com.livetranslate.core.model.ConnectionState
import com.livetranslate.core.model.TranslationMode
import com.livetranslate.core.security.EncryptedPreferencesManager
import com.livetranslate.watch.audio.WatchAudioRouter
import com.livetranslate.watch.databinding.ActivityWatchMainBinding
import com.livetranslate.watch.pair.WatchPairingActivity
import kotlinx.coroutines.launch

class WatchMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchMainBinding
    private lateinit var prefs: EncryptedPreferencesManager
    private lateinit var audioRouter: WatchAudioRouter

    private var translationService: LiveTranslationService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? LiveTranslationService.LocalBinder
            translationService = localBinder?.service
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            translationService = null
            isBound = false
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferencesManager(this)
        audioRouter = WatchAudioRouter(this)

        checkPermissions()
        initUI()
    }

    override fun onResume() {
        super.onResume()
        binding.tvAudioRoute.text = audioRouter.getAudioRouteDescription()
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
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permLauncher.launch(permissions.toTypedArray())
    }

    private fun initUI() {
        binding.btnWatchSolo.setOnClickListener { startTranslation(TranslationMode.SOLO) }
        binding.btnWatchDialogue.setOnClickListener { startTranslation(TranslationMode.DIALOGUE) }
        binding.btnWatchStop.setOnClickListener {
            triggerHapticFeedback()
            stopTranslation()
        }
        binding.btnWatchPair.setOnClickListener {
            startActivity(Intent(this, WatchPairingActivity::class.java))
        }
    }

    private fun startTranslation(mode: TranslationMode) {
        val config = prefs.loadConfig()
        if (config.apiKeys.isEmpty()) {
            Toast.makeText(this, "Свяжите часы с телефоном по QR", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, WatchPairingActivity::class.java))
            return
        }

        binding.panelWatchIdle.visibility = View.GONE
        binding.panelWatchActive.visibility = View.VISIBLE
        binding.tvWatchSubtitles.text = "Слушаю..."

        val intent = Intent(this, LiveTranslationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        translationService?.startTranslation(mode)
    }

    private fun stopTranslation() {
        translationService?.stopTranslation()
        binding.panelWatchIdle.visibility = View.VISIBLE
        binding.panelWatchActive.visibility = View.GONE
        binding.tvWatchStatus.text = "Online"
    }

    private fun triggerHapticFeedback() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(100)
        }
    }

    private fun observeService() {
        val service = translationService ?: return

        lifecycleScope.launch {
            service.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Idle -> binding.tvWatchStatus.text = "Online"
                    is ConnectionState.Connecting -> binding.tvWatchStatus.text = "Подключение..."
                    is ConnectionState.Connected -> binding.tvWatchStatus.text = "Live"
                    is ConnectionState.RotatingKey -> binding.tvWatchStatus.text = "Смена ключа..."
                    is ConnectionState.FallbackModel -> binding.tvWatchStatus.text = "Fallback..."
                    is ConnectionState.Error -> binding.tvWatchStatus.text = "Ошибка"
                    is ConnectionState.Disconnected -> stopTranslation()
                }
            }
        }

        lifecycleScope.launch {
            service.subtitleFlow.collect { text ->
                binding.tvWatchSubtitles.text = text
            }
        }
    }
}
