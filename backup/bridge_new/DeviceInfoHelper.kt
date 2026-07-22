package com.ailife.clox.cocos.bridge

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import org.json.JSONObject

/**
 * Static device metadata for Lua bridge.
 * Data contract: { apiLevel, language, deviceLabel, deviceModel, manufacturer,
 *   productName, osType, appVersion, isRound, lastReboot }
 */
object DeviceInfoHelper {

    fun getInfo(context: Context): JSONObject {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val isRound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.resources.configuration.isScreenRound
        } else false

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

        return JSONObject().apply {
            put("apiLevel", Build.VERSION.SDK_INT)
            put("language", context.resources.configuration.locales[0]?.language ?: "")
            put("deviceLabel", "${Build.BRAND} ${Build.MODEL}")
            put("deviceModel", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("productName", Build.PRODUCT)
            put("osType", "Android")
            put("appVersion", appVersion)
            put("isRound", isRound)
            put("lastReboot", android.os.SystemClock.elapsedRealtime())
        }
    }
}
