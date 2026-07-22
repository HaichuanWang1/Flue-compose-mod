package com.ailife.clox.cocos.bridge

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import org.json.JSONObject

object SystemHelper {

    fun getState(ctx: Context): JSONObject {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPercent = if (maxVol > 0) curVol * 100 / maxVol else 0

        val brightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Exception) { 128 }

        val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightMode == Configuration.UI_MODE_NIGHT_YES

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLowPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pm.isPowerSaveMode
        } else false

        return JSONObject().apply {
            put("volume",       volPercent)
            put("brightness",   brightness)
            put("darkMode",     isDarkMode)
            put("lowPowerMode", isLowPower)
            put("is24h",        DateFormat.is24HourFormat(ctx))
        }
    }
}
