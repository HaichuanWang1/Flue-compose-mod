package com.ailife.clox.cocos.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.cocos.LuaBridge
import org.json.JSONObject

/**
 * Bridge manager — coordinates all data pushes to the Lua watchface engine.
 *
 * Always pushed:
 *   settings  → triggers watchfacePath load in Lua
 *   device    → static device info (Build, locale, etc.)
 *   battery   → {bl} {bp} tags
 *   system    → volume, brightness, darkMode, lowPowerMode, is24h
 *   storage   → totalStorage, usedStorage, totalRam, usedRam (polled 60s)
 *   alarm     → next alarm clock (polled 60s)
 */
class WatchfaceBridgeManager(private val context: Context) {
    companion object {
        private const val TAG = "WatchfaceBridgeMgr"
        private const val PERIODIC_INTERVAL_MS = 60_000L
    }

    private var batteryLevel = 100
    private var batteryReceiver: BroadcastReceiver? = null
    private var isLuaReady = false
    private var healthHelper: HealthHelper? = null
    private val handler = Handler(Looper.getMainLooper())
    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!isLuaReady) return
            pushStorage()
            pushAlarm()
            handler.postDelayed(this, PERIODIC_INTERVAL_MS)
        }
    }

    /** Called when Lua engine signals readiness (wf_lua_ready event). */
    var luaReadyCallback: (() -> Unit)? = null

    /** Called when Lua finishes loading a watchface (wf_loaded event). */
    var wfLoadedCallback: (() -> Unit)? = null

    private val eventListener: (String, String) -> Unit = { event, _ ->
        when (event) {
            "wf_loaded" -> {
                Log.i(TAG, "wf_loaded — pushing data (no settings) + starting periodic timer")
                wfLoadedCallback?.invoke()
                // Push live data but NOT settings — settings carries watchfacePath
                // which would trigger a redundant reload. The face is already loaded.
                if (isLuaReady) {
                    sendToLua("device", DeviceInfoHelper.getInfo(context))
                    pushBattery()
                    sendToLua("system", SystemHelper.getState(context))
                    pushStorage()
                    pushAlarm()
                    handler.removeCallbacks(periodicRunnable)
                    handler.postDelayed(periodicRunnable, PERIODIC_INTERVAL_MS)
                }
            }
        }
    }

    fun start() {
        registerBatteryReceiver()
        startHealth()
        LuaBridge.luaReadyHandler = {
            Log.i(TAG, "Lua engine ready — pushing settings with path")
            isLuaReady = true
            // luaReadyCallback pushes settings(watchfacePath=...) via setWatchfacePath.
            // Do NOT also call pushSettings() — the second push (without path)
            // would overwrite the first and trigger loadDefault instead of the
            // real watchface.
            luaReadyCallback?.invoke()
        }
        LuaBridge.addEventListner(eventListener)
    }

    /**
     * Fast-path reattach: the Lua engine is already running (activity
     * recreation / Compose recomposition that reuses the live GL view).
     * Set isLuaReady so sendToLua() works, but do NOT push data yet —
     * the Lua VM may not have loaded watchface_bridge.lua at this point.
     * Data is pushed in the wf_loaded handler once the face is ready.
     */
    fun reattach() {
        Log.i(TAG, "reattach — engine already live")
        isLuaReady = true
        luaReadyCallback?.invoke()
        // pushAll() deferred to wf_loaded handler — WatchfaceBridgeDispatch
        // may not be defined yet (scripts load asynchronously after GL init).
    }

    fun stop() {
        handler.removeCallbacks(periodicRunnable)
        unregisterBatteryReceiver()
        stopHealth()
        LuaBridge.luaReadyHandler = null
        LuaBridge.removeEventListener(eventListener)
        isLuaReady = false
    }

    fun onHostPause() { handler.removeCallbacks(periodicRunnable) }
    fun onHostResume() {
        if (isLuaReady) handler.postDelayed(periodicRunnable, PERIODIC_INTERVAL_MS)
    }

    // ── Data push ─────────────────────────────────────────────────────────────

    private fun pushAll() {
        pushSettings()
        pushDevice()
        pushBattery()
        pushSystem()
        pushStorage()
        pushAlarm()
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

    private fun pushDevice() {
        sendToLua("device", DeviceInfoHelper.getInfo(context))
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
        sendToLua("system", SystemHelper.getState(context))
    }

    private fun pushStorage() {
        sendToLua("storage", StorageHelper.getInfo(context))
    }

    private fun pushAlarm() {
        sendToLua("alarm", AlarmHelper.getInfo(context))
    }

    // ── Health (steps + heart rate via SensorManager) ─────────────────────────

    private fun startHealth() {
        val helper = HealthHelper(context)
        healthHelper = helper
        helper.startSteps { stepsJson ->
            sendToLua("health_steps", stepsJson)
        }
        helper.startHeartRate { hrJson ->
            sendToLua("health_heartRate", hrJson)
        }
        // Trigger an initial HR measurement
        helper.measureHeartRate()
    }

    private fun stopHealth() {
        healthHelper?.stopHeartRate()
        healthHelper = null
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
