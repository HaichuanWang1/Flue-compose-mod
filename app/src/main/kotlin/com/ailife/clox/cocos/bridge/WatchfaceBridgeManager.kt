package com.ailife.clox.cocos.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.Log
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.cocos.LuaBridge
import org.json.JSONObject

/**
 * Simplified WatchfaceBridgeManager for Flue integration.
 * Pushes essential data (battery, time, system) to Lua.
 * Full bridge (weather, health, sensors) can be added later.
 */
class WatchfaceBridgeManager(private val context: Context) {
    companion object {
        private const val TAG = "WatchfaceBridgeMgr"
    }

    private var batteryLevel = 100
    private var batteryReceiver: BroadcastReceiver? = null
    private var isLuaReady = false

    /** Called when Lua engine signals readiness (wf_lua_ready event). */
    var luaReadyCallback: (() -> Unit)? = null

    fun start() {
        registerBatteryReceiver()
        LuaBridge.luaReadyHandler = {
            Log.i(TAG, "Lua engine ready — watchfacePath will be pushed")
            isLuaReady = true
            luaReadyCallback?.invoke()
            // Also push battery and settings immediately
            pushAll()
        }
        LuaBridge.watchfaceActionHandler = { payload ->
            Log.i(TAG, "Watchface action: $payload")
        }
    }

    fun stop() {
        unregisterBatteryReceiver()
        LuaBridge.luaReadyHandler = null
        LuaBridge.watchfaceActionHandler = null
        isLuaReady = false
    }

    /** Called when host activity pauses (screen off, another app launched). */
    fun onHostPause() {
        Log.i(TAG, "onHostPause — host is no longer visible")
    }

    /** Called when host activity resumes. */
    fun onHostResume() {
        Log.i(TAG, "onHostResume — host is visible again")
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (rawLevel >= 0 && scale > 0) {
                    batteryLevel = (rawLevel * 100 / scale).coerceIn(0, 100)
                    pushBattery()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
    }

    private fun pushAll() {
        pushBattery()
        pushSystem()
        pushSettings(DateFormat.is24HourFormat(context))
    }

    fun pushBattery() {
        queueLua("WatchfaceBridgeDispatch", JSONObject().apply {
            put("category", "battery")
            put("data", JSONObject().apply {
                put("level", batteryLevel)
                put("status", 2)
                put("scale", 100)
                put("plugged", 0)
            })
        }.toString())
    }

    private fun pushSystem() {
        val dm = context.resources.displayMetrics
        queueLua("WatchfaceBridgeDispatch", JSONObject().apply {
            put("category", "system")
            put("data", JSONObject().apply {
                put("width", dm.widthPixels)
                put("height", dm.heightPixels)
                put("density", dm.density.toDouble())
                put("densityDpi", dm.densityDpi)
                put("brand", android.os.Build.BRAND)
                put("model", android.os.Build.MODEL)
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("release", android.os.Build.VERSION.RELEASE)
            })
        }.toString())
    }

    fun pushSettings(is24Hour: Boolean, watchfacePath: String? = null) {
        queueLua("WatchfaceBridgeDispatch", JSONObject().apply {
            put("category", "settings")
            put("data", JSONObject().apply {
                put("is24", is24Hour)
                if (watchfacePath != null) put("watchfacePath", watchfacePath)
            })
        }.toString())
    }

    fun setWatchfacePath(path: String) {
        Log.i(TAG, "setWatchfacePath: $path")
        val json = JSONObject().apply {
            put("category", "settings")
            put("data", JSONObject().apply {
                put("watchfacePath", path)
                put("is24", android.text.format.DateFormat.is24HourFormat(context))
            })
        }.toString()
        Log.i(TAG, "Pushing settings: $json")
        queueLua("WatchfaceBridgeDispatch", json)
    }

    private fun queueLua(funcName: String, json: String) {
        val glView = CocosManager.getGlView() as? android.opengl.GLSurfaceView ?: return
        glView.queueEvent {
            try {
                LuaBridge.callLuaFunction(funcName, json)
            } catch (e: Exception) {
                Log.w(TAG, "Lua call failed: ${e.message}")
            }
        }
        dev.axmol.lib.AxmolRenderer.kickFrame()
    }
}
