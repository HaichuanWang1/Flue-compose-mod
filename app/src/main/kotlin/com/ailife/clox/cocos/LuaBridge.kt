package com.ailife.clox.cocos

import android.util.Log

object LuaBridge {

    private const val TAG = "LuaBridge"

    // Called from Android → Lua
    external fun callLuaFunction(funcName: String, args: String): String

    // Called from Android → native touch pipeline
    external fun passTouchNative(x: Float, y: Float, action: Int)

    fun passTouch(x: Float, y: Float, action: Int) {
        // Callers are on the UI thread; the Lua VM is single-threaded on the
        // GL thread, so marshal like every other Lua call (see queueOnGL in
        // WatchfaceBridgeManager).
        val glView = CocosManager.getGlView() as? android.opengl.GLSurfaceView
        if (glView == null) {
            Log.w(TAG, "passTouch skipped — GL view not ready")
            return
        }
        glView.queueEvent {
            try {
                passTouchNative(x, y, action)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library not loaded — touch dropped")
            }
        }
        // wake the render thread out of its FPS-throttle wait — at the 1 FPS
        // static-face cadence a queued touch would otherwise wait up to 1s
        dev.axmol.lib.AxmolRenderer.kickFrame()
    }

    // Called from native (JNI) → Kotlin/Android
    @JvmStatic
    fun onLuaEvent(event: String, payload: String) {
        Log.d(TAG, "onLuaEvent: $event  payload=$payload")
        when (event) {
            "wf_lua_ready" -> luaReadyHandler?.invoke()
            "wf_action"    -> watchfaceActionHandler?.invoke(payload)
        }
        eventListeners.forEach { it(event, payload) }
    }

    private val eventListeners = mutableListOf<(String, String) -> Unit>()

    fun addEventListner(listener: (String, String) -> Unit) {
        eventListeners += listener
    }

    fun removeEventListener(listener: (String, String) -> Unit) {
        eventListeners -= listener
    }

    /** Called when Lua signals it is ready to receive data (wf_lua_ready event). */
    var luaReadyHandler: (() -> Unit)? = null

    /** Set by WatchfaceBridgeManager to handle actions sent from Lua (wf_action events). */
    var watchfaceActionHandler: ((String) -> Unit)? = null
}
