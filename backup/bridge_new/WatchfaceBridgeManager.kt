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
 * Pushes all data categories: battery, system, settings, device, location,
 * timezones, network, connectivity, notification, health, weather, sensor,
 * calendar, storage, alarm.
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
        Log.i(TAG, "eventListener: event='$event' payload='${payload.take(50)}'")
        when (event) {
            "wf_loaded" -> {
                Log.i(TAG, "wf_loaded — invoking callback, wfLoadedCallback=${wfLoadedCallback != null}")
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
        healthHelper.startSteps { data -> sendToLua("health_steps", data) }
        healthHelper.startHeartRate { data -> sendToLua("health_heartRate", data) }
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
        pushSystem()
        pushSettings()
        pushDevice()
        pushNetwork()
        pushConnectivity()
        pushNotification()
        healthHelper.pushSteps()
        healthHelper.pushHeartRate()
        sendToLua("calendar", CalendarHelper.getEvents(context))
    }

    // ── Push all data ─────────────────────────────────────────────────────────

    private fun pushAll() {
        // Sync: lightweight data only (no IO)
        sendToLua("device", DeviceInfoHelper.getInfo(context), kick = false)
        pushSettings()
        pushBattery()
        pushSystem()
        pushNetwork()
        pushConnectivity()
        sendToLua("timezones", LiveDataHelper.timezonesJson(context), kick = false)
        sendToLua("health_steps", healthHelper.getStepsJson(), kick = false)
        sendToLua("health_heartRate", healthHelper.getHeartRateJson(), kick = false)
        sendToLua("health_spo2", JSONObject().apply { put("percent", 0); put("timestamp", 0L); put("measuring", false) }, kick = false)
        sendToLua("health_sleep", JSONObject().apply { put("hasData", false) }, kick = false)
        sendToLua("health_stress", JSONObject().apply { put("score", 0) }, kick = false)
        kickFrame()

        // Async: IO-heavy data
        scope.launch {
            val storage = withContext(Dispatchers.IO) { LiveDataHelper.storageJson(context) }
            val alarm = withContext(Dispatchers.IO) { LiveDataHelper.alarmJson(context) }
            val cal = withContext(Dispatchers.IO) { CalendarHelper.getEvents(context) }
            val notif = withContext(Dispatchers.IO) { NotificationHelper.getNotifications(context) }
            sendToLua("storage", storage, kick = false)
            sendToLua("alarm", alarm, kick = false)
            sendToLua("calendar", cal, kick = false)
            sendToLua("notification", notif, kick = false)
            kickFrame()
        }

        // Async: weather
        scope.launch {
            val loc = getLastKnownLocation()
            val weather = WeatherRepository.fetchWeather(context, loc.first, loc.second)
            if (weather != null) sendToLua("weather", weather)
        }

        // Async: location
        scope.launch {
            sendToLua("location", LiveDataHelper.locationJson(context))
        }
    }

    private fun startPeriodicUpdates() {
        // Storage + alarm every 30 seconds
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (hostPaused) continue
                val storage = withContext(Dispatchers.IO) { LiveDataHelper.storageJson(context) }
                val alarm = withContext(Dispatchers.IO) { LiveDataHelper.alarmJson(context) }
                sendToLua("storage", storage)
                sendToLua("alarm", alarm)
            }
        }

        // Network + connectivity every 60 seconds
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (hostPaused) continue
                pushNetwork()
                pushConnectivity()
            }
        }

        // Notification every 30 seconds
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (hostPaused) continue
                pushNotification()
            }
        }

        // Weather every 10 minutes
        scope.launch {
            while (isActive) {
                delay(600_000)
                if (hostPaused) continue
                val loc = getLastKnownLocation()
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
                    }
                }
                "setWeatherEnabled" -> {
                    // Weather is already pushed periodically; no action needed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleAction failed: ${e.message}")
        }
    }

    // ── Individual push methods ───────────────────────────────────────────────

    private fun pushBattery() {
        sendToLua("battery", LiveDataHelper.batteryJson(context))
    }

    private fun pushSystem() {
        sendToLua("system", LiveDataHelper.systemJson(context))
    }

    private fun pushSettings(watchfacePath: String? = null) {
        sendToLua("settings", LiveDataHelper.settingsJson(context, watchfacePath))
    }

    private fun pushDevice() {
        sendToLua("device", DeviceInfoHelper.getInfo(context))
    }

    private fun pushNetwork() {
        sendToLua("network", NetworkHelper.networkJson(context))
    }

    private fun pushConnectivity() {
        sendToLua("connectivity", NetworkHelper.connectivityJson(context))
    }

    private fun pushNotification() {
        sendToLua("notification", NotificationHelper.getNotifications(context))
    }

    private fun pushLocation() {
        val loc = getLastKnownLocation()
        sendToLua("location", LiveDataHelper.locationJson(context, loc.first, loc.second))
    }

    fun setWatchfacePath(path: String) {
        Log.i(TAG, "setWatchfacePath: $path")
        pushSettings(path)
    }

    // ── Battery receiver ──────────────────────────────────────────────────────

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

    // ── Location helper ───────────────────────────────────────────────────────

    private fun getLastKnownLocation(): Pair<Double, Double> {
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
            39.9042 to 116.4074 // Default: Beijing
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
        queueLua("WatchfaceBridgeDispatch", json, kick)
    }

    private fun queueLua(funcName: String, json: String, kick: Boolean = true) {
        val glView = CocosManager.getGlView() as? android.opengl.GLSurfaceView ?: return
        glView.queueEvent {
            try {
                LuaBridge.callLuaFunction(funcName, json)
            } catch (e: Exception) {
                Log.w(TAG, "Lua call failed: ${e.message}")
            }
        }
        if (kick) kickFrame()
    }

    private fun kickFrame() {
        dev.axmol.lib.AxmolRenderer.kickFrame()
    }
}
