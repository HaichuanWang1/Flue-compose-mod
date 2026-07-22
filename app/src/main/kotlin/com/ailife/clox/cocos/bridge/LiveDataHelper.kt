package com.ailife.clox.cocos.bridge

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Static + live system data for the Lua bridge.
 * Simplified from clox — no QsStateManager dependency.
 */
object LiveDataHelper {

    fun batteryJson(context: Context): JSONObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) level * 100 / scale else 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temperature = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0

        return JSONObject().apply {
            put("level", pct)
            put("status", plugged)
            put("scale", 100)
            put("plugged", plugged)
            put("charging", plugged != 0)
            put("temperature", temperature)
            put("voltage", voltage)
        }
    }

    fun systemJson(context: Context): JSONObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPercent = if (maxVol > 0) curVol * 100 / maxVol else 0

        val brightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Exception) { 128 }

        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightMode == Configuration.UI_MODE_NIGHT_YES

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLowPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) pm.isPowerSaveMode else false

        val dm = context.resources.displayMetrics
        return JSONObject().apply {
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
            put("density", dm.density.toDouble())
            put("densityDpi", dm.densityDpi)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("volume", volPercent)
            put("brightness", brightness)
            put("darkMode", isDarkMode)
            put("lowPowerMode", isLowPower)
            put("is24h", android.text.format.DateFormat.is24HourFormat(context))
        }
    }

    fun storageJson(context: Context): JSONObject {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val used = total - stat.availableBytes

            val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actMgr.getMemoryInfo(memInfo)

            JSONObject().apply {
                put("totalStorage", total)
                put("usedStorage", used)
                put("totalRam", memInfo.totalMem)
                put("usedRam", memInfo.totalMem - memInfo.availMem)
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun alarmJson(context: Context): JSONObject {
        return try {
            val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) alarmMgr.nextAlarmClock else null
            val now = System.currentTimeMillis()
            val withinDay = next != null && (next.triggerTime - now) in 0..(24 * 3600 * 1000)
            val alarmTime = if (withinDay) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next!!.triggerTime))
            } else ""

            JSONObject().apply {
                put("hasAlarm", withinDay)
                put("alarmTime", alarmTime)
                put("alarmTimestamp", if (withinDay) next!!.triggerTime else 0L)
            }
        } catch (_: Exception) {
            JSONObject().apply { put("hasAlarm", false); put("alarmTime", "") }
        }
    }
}
