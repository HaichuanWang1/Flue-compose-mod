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
 * Minimal bridge manager — only the data categories that drive rendering.
 *
 * Core (always pushed):
 *   settings  → triggers watchfacePath load in Lua
 *   battery   → common {bl} {bp} tags
 *   system    → is24h for time formatting
 *
 * Deferred (disabled until core is proven working):
 *   health_*, weather, sensor_*, calendar, storage, alarm,
 *   network, connectivity, notification, location, timezones
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

    /** Called when Lua finishes loading a watchface (wf_loaded event). */
    var wfLoadedCallback: (() -> Unit)? = null

    private val eventListener: (String, String) -> Unit = { event, _ ->
        if (event == "wf_loaded") {
            Log.i(TAG, "wf_loaded — watchface rendering started")
            wfLoadedCallback?.invoke()
        }
    }

    fun start() {
        registerBatteryReceiver()
        LuaBridge.luaReadyHandler = {
            Log.i(TAG, "Lua engine ready — pushing core data")
            isLuaReady = true
            luaReadyCallback?.invoke()
            pushCore()
        }
        LuaBridge.addEventListner(eventListener)
    }

    /**
     * Fast-path reattach: the Lua engine is already running (activity
     * recreation / Compose recomposition that reuses the live GL view).
     * Skip waiting for wf_lua_ready — it was already sent during the
     * scene's onEnter and won't fire again. Push the path and core data
     * immediately so the face reloads without a 2 s fallback delay.
     */
    fun reattach() {
        Log.i(TAG, "reattach — engine already live, pushing path immediately")
        isLuaReady = true
        luaReadyCallback?.invoke()
        pushCore()
    }

    fun stop() {
        unregisterBatteryReceiver()
        LuaBridge.luaReadyHandler = null
        LuaBridge.removeEventListener(eventListener)
        isLuaReady = false
    }

    fun onHostPause() { /* no-op for now */ }
    fun onHostResume() { /* no-op for now */ }

    // ── Core data push (rendering essentials only) ────────────────────────────

    private fun pushCore() {
        pushSettings()
        pushBattery()
        pushSystem()
    }

    fun setWatchfacePath(path: String) {
        Log.i(TAG, "setWatchfacePath: $path")
        sendToLua("settings", JSONObject().apply {
            put("watchfacePath", path)
            put("is24", DateFormat.is24HourFormat(context))
        })
    }

    private fun pushSettings() {
        sendToLua("settings", JSONObject().apply {
            put("is24", DateFormat.is24HourFormat(context))
        })
    }

    private fun pushBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        batteryLevel = if (scale > 0) level * 100 / scale else 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        sendToLua("battery", JSONObject().apply {
            put("level", batteryLevel)
            put("status", status)
            put("scale", 100)
            put("plugged", plugged)
            put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)
            put("temperature", temp)
            put("voltage", voltage)
        })
    }

    private fun pushSystem() {
        sendToLua("system", JSONObject().apply {
            put("is24h", DateFormat.is24HourFormat(context))
        })
    }

    // ── Battery receiver (live updates) ───────────────────────────────────────

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                pushBattery()
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

    // ── Lua dispatch ──────────────────────────────────────────────────────────

    private fun sendToLua(category: String, data: JSONObject) {
        if (!isLuaReady) return
        val json = JSONObject().apply {
            put("category", category)
            put("data", data)
        }.toString()
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
