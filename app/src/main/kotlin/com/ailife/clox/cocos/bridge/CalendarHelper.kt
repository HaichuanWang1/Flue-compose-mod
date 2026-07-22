package com.ailife.clox.cocos.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calendar event bridge — pushes upcoming events to Lua.
 * Data contract: { hasEvents (bool), events[10]: [{ exists, title, beginTime, endTime, ... }] }
 */
object CalendarHelper {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun getEvents(context: Context): JSONObject {
        if (!hasPermission(context)) return emptyResult()

        val now = System.currentTimeMillis()
        val end = now + 30L * 24 * 3600 * 1000

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_COLOR,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?" +
                " AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(now.toString(), end.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        ) ?: return emptyResult()

        val events = JSONArray()
        var count = 0
        cursor.use {
            while (it.moveToNext() && count < 10) {
                val dtStart = it.getLong(2)
                val dtEnd = it.getLong(3)
                val color = it.getInt(6)
                events.put(JSONObject().apply {
                    put("exists", true)
                    put("id", it.getString(0) ?: "")
                    put("title", it.getString(1) ?: "")
                    put("beginTime", timeFmt.format(Date(dtStart)))
                    put("endTime", timeFmt.format(Date(dtEnd)))
                    put("beginDate", dateFmt.format(Date(dtStart)))
                    put("endDate", dateFmt.format(Date(dtEnd)))
                    put("beginTimeMs", dtStart)
                    put("endTimeMs", dtEnd)
                    put("allDay", it.getInt(4) == 1)
                    put("location", it.getString(5) ?: "")
                    put("color", String.format("%06X", color and 0xFFFFFF))
                    put("calendar", it.getString(7) ?: "")
                })
                count++
            }
        }

        repeat(10 - count) { events.put(JSONObject().put("exists", false)) }

        return JSONObject().apply {
            put("hasEvents", count > 0)
            put("events", events)
        }
    }

    private fun emptyResult(): JSONObject {
        val events = JSONArray()
        repeat(10) { events.put(JSONObject().put("exists", false)) }
        return JSONObject().apply {
            put("hasEvents", false)
            put("events", events)
        }
    }
}
