package com.ailife.clox.cocos.bridge

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
 */
class WatchfaceBridgeManager(private val context: Context) {
    companion object {
        private const val TAG = "WatchfaceBridgeMgr"
        private const val PERIODIC_INTERVAL_MS = 60_000L
    }

    private var batteryLevel = 100
    private var isLuaReady = false
    private var healthHelper: HealthHelper? = null
    private var hscStepHelper: HscStepHelper? = null
    private val handler = Handler(Looper.getMainLooper())
    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!isLuaReady) return
            pushBattery()
            pushStorage()
            pushAlarm()
            hscStepHelper?.push()
            handler.postDelayed(this, PERIODIC_INTERVAL_MS)
        }
    }

    var luaReadyCallback: (() -> Unit)? = null
    var wfLoadedCallback: (() -> Unit)? = null

    private val eventListener: (String, String) -> Unit = { event, _ ->
        when (event) {
            "wf_loaded" -> {
                Log.i(TAG, "wf_loaded — pushing data + starting periodic timer")
                wfLoadedCallback?.invoke()
                if (isLuaReady) {
                    sendToLua("device", DeviceInfoHelper.getInfo(context))
                    pushBattery()
                    sendToLua("system", SystemHelper.getState(context))
                    pushStorage()
                    pushAlarm()
                    hscStepHelper?.push()
                    healthHelper?.pushHeartRate()
                    handler.removeCallbacks(periodicRunnable)
                    handler.postDelayed(periodicRunnable, PERIODIC_INTERVAL_MS)
                }
            }
        }
    }

    fun start() {
        startHealth()
        LuaBridge.luaReadyHandler = {
            Log.i(TAG, "Lua engine ready — pushing settings with path")
            isLuaReady = true
            luaReadyCallback?.invoke()
        }
        LuaBridge.addEventListner(eventListener)
    }

    fun reattach() {
        Log.i(TAG, "reattach — engine already live")
        isLuaReady = true
        luaReadyCallback?.invoke()
    }

    fun stop() {
        handler.removeCallbacks(periodicRunnable)
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

    fun setWatchfacePath(path: String) {
        Log.i(TAG, "setWatchfacePath: $path")
        sendToLua("settings", JSONObject().apply {
            put("watchfacePath", path)
            put("is24", DateFormat.is24HourFormat(context))
        })
    }

    private fun pushBattery() {
        // Sticky broadcast — no receiver needed, avoids RECEIVER_EXPORTED issues
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

    private fun pushStorage() {
        sendToLua("storage", StorageHelper.getInfo(context))
    }

    private fun pushAlarm() {
        sendToLua("alarm", AlarmHelper.getInfo(context))
    }

    // ── Health (steps + heart rate) ────────────────────────────────────────────

    private fun startHealth() {
        val stepHelper = HscStepHelper(context)
        hscStepHelper = stepHelper
        stepHelper.start { stepsJson ->
            sendToLua("health_steps", stepsJson)
        }
        val hrHelper = HealthHelper(context)
        healthHelper = hrHelper
        hrHelper.startHeartRate { hrJson ->
            sendToLua("health_heartRate", hrJson)
        }
        hrHelper.measureHeartRate()
    }

    private fun stopHealth() {
        hscStepHelper?.stop()
        hscStepHelper = null
        healthHelper?.stopHeartRate()
        healthHelper = null
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
