package com.ailife.clox.cocos.bridge

import android.content.ComponentName
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notification data for Lua bridge.
 * Requires NotificationListenerService to be enabled.
 * Data contract: { activeCount, notifications[10]: [{ title, text, packageName, postTime }] }
 */
object NotificationHelper {

    /** Read notifications from the app's own notification listener if available. */
    fun getNotifications(context: Context): JSONObject {
        // Try to get notifications via a simple approach: read active notifications
        // from the notification manager (requires POST_NOTIFICATIONS on Android 13+)
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val active = nm.activeNotifications
            val count = active.size
            val list = JSONArray()

            // Take up to 10 most recent
            val sorted = active.sortedByDescending { it.postTime }.take(10)
            for (n in sorted) {
                list.put(JSONObject().apply {
                    put("title", n.notification.extras.getCharSequence("android.title")?.toString() ?: "")
                    put("text", n.notification.extras.getCharSequence("android.text")?.toString() ?: "")
                    put("packageName", n.packageName)
                    put("postTime", n.postTime)
                })
            }

            JSONObject().apply {
                put("activeCount", count)
                put("notifications", list)
            }
        } catch (_: Exception) {
            emptyNotifications()
        }
    }

    private fun emptyNotifications(): JSONObject {
        return JSONObject().apply {
            put("activeCount", 0)
            put("notifications", JSONArray())
        }
    }
}
