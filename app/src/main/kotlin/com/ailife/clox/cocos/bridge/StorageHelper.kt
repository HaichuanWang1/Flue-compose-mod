package com.ailife.clox.cocos.bridge

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject

object StorageHelper {

    fun getInfo(ctx: Context): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes
        val usedStorage = totalStorage - stat.availableBytes

        val actMgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memInfo)
        val totalRam = memInfo.totalMem
        val usedRam = totalRam - memInfo.availMem

        return JSONObject().apply {
            put("totalStorage", totalStorage)
            put("usedStorage",  usedStorage)
            put("totalRam",     totalRam)
            put("usedRam",      usedRam)
        }
    }
}
