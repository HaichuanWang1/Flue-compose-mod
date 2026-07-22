package com.ailife.clox.crash

import android.app.Application
import android.util.Log
import java.io.File

/**
 * Simplified NativeCrashHandler for Flue integration.
 * Catches native signals and writes crash logs.
 */
object NativeCrashHandler {
    private const val TAG = "NativeCrashHandler"
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        try {
            // Set up uncaught exception handler for native crashes
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
                // Write crash log
                try {
                    val crashDir = File(app.filesDir, "crash_logs")
                    crashDir.mkdirs()
                    val crashFile = File(crashDir, "crash_${System.currentTimeMillis()}.log")
                    crashFile.writeText(buildString {
                        appendLine("Thread: ${thread.name}")
                        appendLine("Exception: ${throwable.message}")
                        appendLine(throwable.stackTraceToString())
                    })
                    Log.e(TAG, "Crash log written to ${crashFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write crash log", e)
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }
            Log.i(TAG, "NativeCrashHandler installed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install crash handler", e)
        }
    }
}
