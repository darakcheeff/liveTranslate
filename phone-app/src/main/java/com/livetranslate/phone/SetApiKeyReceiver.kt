package com.livetranslate.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.livetranslate.core.security.EncryptedPreferencesManager

class SetApiKeyReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SetApiKeyReceiver"
        const val ACTION_SET_API_KEY = "com.livetranslate.action.SET_API_KEY"
        const val EXTRA_API_KEY = "api_key"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SET_API_KEY) {
            val key = intent.getStringExtra(EXTRA_API_KEY)
            if (!key.isNullOrBlank()) {
                val prefs = EncryptedPreferencesManager(context)
                val config = prefs.loadConfig()
                val keys = if (config.apiKeys.contains(key)) config.apiKeys else listOf(key) + config.apiKeys
                val updated = config.copy(apiKeys = keys, currentKeyIndex = 0)
                prefs.saveConfig(updated)
                Log.i(TAG, "API Key successfully updated via ADB broadcast")
                Toast.makeText(context, "API Key сохранен!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
