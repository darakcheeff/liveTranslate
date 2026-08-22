package com.livetranslate.watch.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

class WatchAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isBluetoothHeadsetConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    fun getAudioRouteDescription(): String {
        return if (isBluetoothHeadsetConnected()) "🎧 Наушники" else "🔊 Динамик часов"
    }
}
