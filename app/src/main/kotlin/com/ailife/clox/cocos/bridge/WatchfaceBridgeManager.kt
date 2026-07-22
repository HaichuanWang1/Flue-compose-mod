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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Central coordinator for the Lua ↔ Android data bridge.
 * Pushes battery, system, health, weather, sensor, calendar, storage, and alarm data.
 */
class WatchfaceBridgeManager(private val context: Context) {
    companion object {
        private const val TAG = "WatchfaceBridgeMgr"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sensorHelper = SensorHelper(context)
    private val healthHelper = HealthHelper(context)

    private var batteryLevel = 100
    private var batteryReceiver: BroadcastReceiver? = null
    private var isLuaReady = false
    private var hostPaused = false

    /** Called when Lua engine signals readiness (wf_lua_ready event). */
    var luaReadyCallback: (() -> Unit)? = null

    /** Called when Lua finishes loading a watchface (wf_loaded event). */
    var wfLoadedCallback: (() -> Unit)? = null

    private val eventListener: (String, String) -> Unit = { event, payload ->
        when (event) {
            "wf_loaded" -> {
                Log.i(TAG, "wf_loaded — watchface rendering started")
                wfLoadedCallback?.invoke()
            }
            "wf_action" -> handleAction(payload)
        }
    }

    fun start() {
        registerBatteryReceiver()
        LuaBridge.luaReadyHandler = {
            Log.i(TAG, "Lua engine ready — pushing all data")
            isLuaReady = true
            luaReadyCallback?.invoke()
            pushAll()
            startPeriodicUpdates()
        }
        LuaBridge.watchfaceActionHandler = { payload -> handleAction(payload) }
        LuaBridge.addEventListner(eventListener)

        // Start health (step counter + heart rate)
        healthHelper.startSteps { data ->
            sendToLua("health_steps", data)
        }
        healthHelper.startHeartRate { data ->
            sendToLua("health_heartRate", data)
        }
    }

    fun stop() {
        scope.cancel()
        unregisterBatteryReceiver()
        LuaBridge.luaReadyHandler = null
        LuaBridge.watchfaceActionHandler = null
        LuaBridge.removeEventListener(eventListener)
        sensorHelper.stop()
        healthHelper.stop()
        isLuaReady = false
    }

    fun onHostPause() {
        hostPaused = true
        sensorHelper.suspend()
    }

    fun onHostResume() {
        hostPaused = false
        sensorHelper.resume()
        pushAll()
    }

    // ── Push all data ─────────────────────────────────────────────────────────

    private fun pushAll() {
        pushBattery()
        pushSystem()
        pushSettings(DateFormat.is24HourFormat(context))
        healthHelper.pushSteps()
        healthHelper.pushHeartRate()
        sendToLua("storage", LiveDataHelper.storageJson(context))
        sendToLua("alarm", LiveDataHelper.alarmJson(context))
        sendToLua("calendar", CalendarHelper.getEvents(context))

        // Weather — use last known location or default
        scope.launch {
            val loc = getLastKnownLocation()
            if (loc != null) {
                val weather = WeatherRepository.fetchWeather(context, loc.first, loc.second)
                if (weather != null) sendToLua("weather", weather)
            }
        }
    }

    private fun startPeriodicUpdates() {
        // Storage + alarm every 5 seconds
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (hostPaused) continue
                val storage = withContext(Dispatchers.IO) { LiveDataHelper.storageJson(context) }
                val alarm = withContext(Dispatchers.IO) { LiveDataHelper.alarmJson(context) }
                sendToLua("storage", storage)
                sendToLua("alarm", alarm)
            }
        }

        // Weather every 10 minutes
        scope.launch {
            while (isActive) {
                delay(600_000)
                if (hostPaused) continue
                val loc = getLastKnownLocation() ?: continue
                val weather = WeatherRepository.fetchWeather(context, loc.first, loc.second)
                if (weather != null) sendToLua("weather", weather)
            }
        }
    }

    // ── Action handler (from Lua) ─────────────────────────────────────────────

    private fun handleAction(payload: String) {
        try {
            val json = JSONObject(payload)
            val action = json.optString("action")
            when (action) {
                "setSensorEnabled" -> {
                    val sensor = json.optString("sensor")
                    val enabled = json.optBoolean("enabled", false)
                    Log.i(TAG, "setSensorEnabled: $sensor = $enabled")
                    sensorHelper.setEnabled(sensor, enabled) { name, data ->
                        sendToLua("sensor_$name", data)
                    }
                }
                "setHealthEnabled" -> {
                    val metric = json.optString("metric")
                    val enabled = json.optBoolean("enabled", false)
                    Log.i(TAG, "setHealthEnabled: $metric = $enabled")
                    when (metric) {
                        "heartRate" -> {
                            if (enabled) healthHelper.measureHeartRate()
                            else healthHelper.stopHeartRate()
                        }
                        "steps" -> {
                            // Steps are always-on via TYPE_STEP_COUNTER, no action needed
                        }
                    }
                }
                "measureHeartRate" -> {
                    healthHelper.measureHeartRate()
                }
                "stopHeartRate" -> {
                    healthHelper.stopHeartRate()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleAction failed: ${e.message}")
        }
    }

    // ── Battery ───────────────────────────────────────────────────────────────

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

    fun pushBattery() {
        sendToLua("battery", LiveDataHelper.batteryJson(context))
    }

    private fun pushSystem() {
        sendToLua("system", LiveDataHelper.systemJson(context))
    }

    fun pushSettings(is24Hour: Boolean, watchfacePath: String? = null) {
        sendToLua("settings", JSONObject().apply {
            put("is24", is24Hour)
            if (watchfacePath != null) put("watchfacePath", watchfacePath)
        })
    }

    fun setWatchfacePath(path: String) {
        Log.i(TAG, "setWatchfacePath: $path")
        sendToLua("settings", JSONObject().apply {
            put("watchfacePath", path)
            put("is24", DateFormat.is24HourFormat(context))
        })
    }

    // ── Location helper ───────────────────────────────────────────────────────

    private fun getLastKnownLocation(): Pair<Double, Double>? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) return loc.latitude to loc.longitude
            }
            // Default: Beijing
            39.9042 to 116.4074
        } catch (_: Exception) {
            39.9042 to 116.4074
        }
    }

    // ── Lua dispatch ──────────────────────────────────────────────────────────

    private fun sendToLua(category: String, data: JSONObject, kick: Boolean = true) {
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
