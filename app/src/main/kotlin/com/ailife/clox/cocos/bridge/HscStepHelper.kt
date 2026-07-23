package com.ailife.clox.cocos.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.json.JSONObject

/**
 * Step counter — tries HSC proprietary HSystemAssist first (works on TK Watch
 * and other HSC-based watches that lack TYPE_STEP_COUNTER), then falls back to
 * the standard Android step counter sensor.
 */
class HscStepHelper(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HscStepHelper"
        private const val STRIDE_KM = 0.000762
        private const val KCAL_PER_STEP = 0.04
        private const val DEFAULT_STEP_GOAL = 10000
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val appContext = context.applicationContext
    private var callback: ((JSONObject) -> Unit)? = null
    private var useHsc = false
    private var hscService: Any? = null
    private var getSetpCountMethod: java.lang.reflect.Method? = null

    // Standard sensor fallback
    private var stepsBaseline: Float? = null
    private var currentSteps: Int = 0

    fun start(cb: (JSONObject) -> Unit) {
        callback = cb
        // Try HSystemAssist first
        if (tryInitHsc()) {
            useHsc = true
            Log.i(TAG, "Using HSC HSystemAssist for step counting")
            return
        }
        // Fallback to standard sensor
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Using standard TYPE_STEP_COUNTER")
        } else {
            Log.w(TAG, "No step counter available (neither HSC nor standard)")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        hscService = null
        getSetpCountMethod = null
    }

    fun push() {
        if (useHsc) {
            pushHsc()
        } else {
            callback?.invoke(buildJson(currentSteps))
        }
    }

    // ── HSC HSystemAssist ──────────────────────────────────────────────────

    private fun tryInitHsc(): Boolean {
        return try {
            val svc = appContext.getSystemService("hsystemassist") ?: return false
            hscService = svc
            // BHT uses getSetpCount (typo in the API: "Setp" not "Step")
            val method = svc.javaClass.getMethod("getSetpCount")
            getSetpCountMethod = method
            // Test call
            val result = method.invoke(svc)
            Log.i(TAG, "HSystemAssist.getSetpCount() = $result")
            true
        } catch (e: Exception) {
            Log.d(TAG, "HSystemAssist not available: ${e.message}")
            false
        }
    }

    private fun pushHsc() {
        try {
            val steps = getSetpCountMethod?.invoke(hscService) as? Int ?: 0
            callback?.invoke(buildJson(steps))
        } catch (e: Exception) {
            Log.w(TAG, "HSC read failed: ${e.message}")
            callback?.invoke(buildJson(0))
        }
    }

    // ── Standard sensor fallback ───────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            if (stepsBaseline == null) stepsBaseline = event.values[0]
            currentSteps = (event.values[0] - stepsBaseline!!).toInt()
            callback?.invoke(buildJson(currentSteps))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── JSON builder ───────────────────────────────────────────────────────

    private fun buildJson(steps: Int): JSONObject {
        return JSONObject().apply {
            put("steps", steps)
            put("distance", String.format("%.2f", steps * STRIDE_KM).toDouble())
            put("calories", String.format("%.1f", steps * KCAL_PER_STEP).toDouble())
            put("stepGoal", DEFAULT_STEP_GOAL)
        }
    }
}
