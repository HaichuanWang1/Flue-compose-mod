package com.ailife.clox.cocos

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import com.ailife.clox.crash.NativeCrashHandler
import dev.axmol.lib.AxmolEngine
import dev.axmol.lib.AxmolGLSurfaceView
import dev.axmol.lib.AxmolRenderer

object CocosManager {
    private const val TAG = "CocosManager"
    private var glView: AxmolGLSurfaceView? = null
    private var hostActivity: Activity? = null
    private var engineInited = false

    fun init(activity: Activity): SurfaceView {
        glView?.let { existing ->
            Log.i(TAG, "Reusing live AxmolSurfaceViewGL across activity recreation")
            (existing.parent as? ViewGroup)?.removeView(existing)
            existing.visibility = android.view.View.VISIBLE
            hostActivity = activity
            return existing
        }
        if (engineInited) {
            Log.e(TAG, "Axmol engine already ran — restarting for clean engine")
            restartProcess(activity)
        }
        NativeCrashHandler.install(activity.application)
        AxmolEngine.init(activity)
        val view = AxmolGLSurfaceView(activity)
        view.setEGLConfigChooser(5, 6, 5, 0, 16, 8)
        view.preserveEGLContextOnPause = true
        view.setRenderer(AxmolRenderer())
        glView = view
        hostActivity = activity
        engineInited = true
        Log.i(TAG, "AxmolSurfaceViewGL initialized")
        return view
    }

    fun getGlView(): SurfaceView? = glView

    @Volatile private var paused = false
    fun isPaused() = paused

    fun onResume() {
        paused = false
        glView?.let { v ->
            if (v.visibility != android.view.View.VISIBLE) {
                v.visibility = android.view.View.VISIBLE
            }
            v.onResume()
        }
    }
    fun onPause() { paused = true; glView?.onPause() }

    fun onDestroy(activity: Activity) {
        if (hostActivity === activity) {
            Log.i(TAG, "onDestroy — detaching GL view")
            glView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            hostActivity = null
        }
    }

    fun restartProcess(context: Context) {
        Log.e(TAG, "restartProcess — scheduling relaunch and exiting")
        try {
            // Launch Flue's launcher activity
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(context.packageName)
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(
                context, 42_017, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        } catch (e: Exception) {
            Log.e(TAG, "restartProcess failed", e)
        }
        Runtime.getRuntime().exit(0)
    }

    @JvmStatic external fun nativeSetBlurRadius(radius: Float)

    init { System.loadLibrary("clox") }
}
