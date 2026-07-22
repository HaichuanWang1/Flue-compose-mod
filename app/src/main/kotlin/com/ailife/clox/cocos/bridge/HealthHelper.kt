package com.ailife.clox.cocos.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.json.JSONObject

/**
 * Health data bridge — steps + heart rate for HSC watches.
 * Steps: Sensor.TYPE_STEP_COUNTER (always-on, low power)
 * Heart rate: Sensor.TYPE_HEART_RATE (on-demand, user-triggered)
 *
 * Data contract:
 *   health_steps:     { steps, distance, calories, stepGoal }
 *   health_heartRate: { bpm, timestamp, maxHeartRate, history[] }
 */
class HealthHelper(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HealthHelper"
        private const val STRIDE_KM = 0.000762
        private const val KCAL_PER_STEP = 0.04
        private const val DEFAULT_STEP_GOAL = 10000
        private const val HR_COOLDOWN_MS = 60 * 60 * 1000L // 1 hour
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var onStepsUpdate: ((JSONObject) -> Unit)? = null
    private var onHeartRateUpdate: ((JSONObject) -> Unit)? = null

    // Steps
    private var stepsBaseline: Float? = null
    private var currentSteps: Int = 0

    // Heart rate
    private var heartRateSensor: Sensor? = null
    private var isMeasuringHR = false
    private var currentBpm: Int = 0
    private var lastMeasureTimeMs: Long = 0L
    private val hrHistory = mutableListOf<Pair<Int, Long>>() // (bpm, timestamp)
    private var hrStableCount = 0
    private var lastHrValue: Int = 0

    // ── Steps ─────────────────────────────────────────────────────────────────

    fun startSteps(callback: (JSONObject) -> Unit) {
        onStepsUpdate = callback
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Step counter registered")
        } else {
            Log.w(TAG, "Step counter sensor not available")
        }
    }

    fun pushSteps() {
        onStepsUpdate?.invoke(buildStepsJson(currentSteps))
    }

    private fun buildStepsJson(steps: Int): JSONObject {
        val distance = steps * STRIDE_KM
        val calories = steps * KCAL_PER_STEP
        return JSONObject().apply {
            put("steps", steps)
            put("distance", String.format("%.2f", distance).toDouble())
            put("calories", String.format("%.1f", calories).toDouble())
            put("stepGoal", DEFAULT_STEP_GOAL)
        }
    }

    // ── Heart rate ────────────────────────────────────────────────────────────

    fun startHeartRate(callback: (JSONObject) -> Unit) {
        onHeartRateUpdate = callback
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (heartRateSensor != null) {
            Log.i(TAG, "Heart rate sensor available: ${heartRateSensor?.name}")
        } else {
            Log.w(TAG, "Heart rate sensor not available on this device")
        }
    }

    fun measureHeartRate() {
        // 1-hour cooldown: reuse last result if within window
        val now = System.currentTimeMillis()
        if (currentBpm > 0 && (now - lastMeasureTimeMs) < HR_COOLDOWN_MS) {
            val remainMin = ((HR_COOLDOWN_MS - (now - lastMeasureTimeMs)) / 60_000).toInt()
            Log.i(TAG, "HR cooldown active, ${remainMin}min remaining — reusing cached bpm=$currentBpm")
            onHeartRateUpdate?.invoke(buildHrJson(currentBpm, false))
            return
        }

        if (heartRateSensor == null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        }
        if (heartRateSensor == null) {
            Log.w(TAG, "Cannot measure HR — no sensor")
            onHeartRateUpdate?.invoke(buildHrJson(0, false))
            return
        }
        if (isMeasuringHR) return

        isMeasuringHR = true
        hrStableCount = 0
        lastHrValue = 0
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST)
        Log.i(TAG, "Heart rate measurement started")

        // Auto-stop after 30 seconds if no stable reading
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isMeasuringHR) {
                Log.w(TAG, "Heart rate measurement timeout")
                lastMeasureTimeMs = System.currentTimeMillis()
                stopHeartRate()
                onHeartRateUpdate?.invoke(buildHrJson(currentBpm, false))
            }
        }, 30_000)
    }

    fun stopHeartRate() {
        if (!isMeasuringHR) return
        isMeasuringHR = false
        heartRateSensor?.let { sensorManager.unregisterListener(this, it) }
        Log.i(TAG, "Heart rate measurement stopped, bpm=$currentBpm")
    }

    fun pushHeartRate() {
        onHeartRateUpdate?.invoke(buildHrJson(currentBpm, isMeasuringHR))
    }

    private fun buildHrJson(bpm: Int, measuring: Boolean): JSONObject {
        val historyArray = org.json.JSONArray()
        for ((hBpm, hTimestamp) in hrHistory.takeLast(10)) {
            historyArray.put(JSONObject().apply {
                put("bpm", hBpm)
                put("timestamp", hTimestamp)
            })
        }
        return JSONObject().apply {
            put("bpm", bpm)
            put("timestamp", System.currentTimeMillis())
            put("measuring", measuring)
            put("history", historyArray)
            put("maxHeartRate", 200)
        }
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val raw = event.values[0]
                if (stepsBaseline == null) stepsBaseline = raw
                currentSteps = (raw - stepsBaseline!!).toInt().coerceAtLeast(0)
                onStepsUpdate?.invoke(buildStepsJson(currentSteps))
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values[0].toInt()
                if (bpm <= 0) return

                // HSC sensor: event.values[7] == 3 or 1 means not worn
                if (event.values.size > 7) {
                    val worn = event.values[7].toInt()
                    if (worn == 3 || worn == 1) {
                        Log.d(TAG, "Watch not worn (values[7]=$worn)")
                        return
                    }
                }

                // Stable reading: same value 3 times in a row
                if (bpm == lastHrValue) {
                    hrStableCount++
                } else {
                    hrStableCount = 1
                    lastHrValue = bpm
                }

                currentBpm = bpm
                Log.d(TAG, "HR sensor: bpm=$bpm, stableCount=$hrStableCount")

                if (hrStableCount >= 3 && isMeasuringHR) {
                    // Stable reading achieved
                    lastMeasureTimeMs = System.currentTimeMillis()
                    hrHistory.add(bpm to lastMeasureTimeMs)
                    if (hrHistory.size > 10) hrHistory.removeAt(0)
                    stopHeartRate()
                }

                onHeartRateUpdate?.invoke(buildHrJson(bpm, isMeasuringHR))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun stop() {
        sensorManager.unregisterListener(this)
        onStepsUpdate = null
        onHeartRateUpdate = null
        isMeasuringHR = false
        stepsBaseline = null
    }
}
