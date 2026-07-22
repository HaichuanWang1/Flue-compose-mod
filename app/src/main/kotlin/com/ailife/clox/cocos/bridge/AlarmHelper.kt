package com.ailife.clox.cocos.bridge

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlarmHelper {

    fun getInfo(ctx: Context): JSONObject {
        val alarmMgr = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            alarmMgr.nextAlarmClock
        } else null

        val now = System.currentTimeMillis()
        val withinDay = next != null && (next.triggerTime - now) in 0..(24 * 3600 * 1000)
        val alarmTime = if (withinDay) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next!!.triggerTime))
        } else ""

        return JSONObject().apply {
            put("hasAlarm",      withinDay)
            put("alarmTime",     alarmTime)
            put("alarmTimestamp", if (withinDay) next!!.triggerTime else 0L)
        }
    }
}
