package com.livetranslate.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.livetranslate.audio.service.LiveTranslationService

class TestInjectionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TestInjectionReceiver"
        const val ACTION_TEST_PCM = "com.livetranslate.action.TEST_PCM"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_TEST_PCM) {
            Log.i(TAG, "Received TEST_PCM broadcast. Starting LiveTranslationService with injection test...")
            val serviceIntent = Intent(context, LiveTranslationService::class.java).apply {
                action = LiveTranslationService.ACTION_INJECT_TEST
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
