package com.flue.launcher.util

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object RecentsVisibility {
    @Volatile
    private var cachedHideFromRecents: Boolean = true

    /** 异步从 DataStore 加载偏好值，首次读入前使用默认值 true */
    fun init(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            cachedHideFromRecents = context.applicationContext.dataStore.data.first()[
                LauncherViewModel.KEY_HIDE_FROM_RECENTS
            ] ?: true
        }
    }

    @JvmStatic
    fun readPreference(context: Context): Boolean = cachedHideFromRecents

    @JvmStatic
    fun apply(activity: Activity) {
        apply(activity, cachedHideFromRecents)
    }

    @JvmStatic
    fun apply(activity: Activity, enabled: Boolean) {
        val activityManager = activity.getSystemService(ActivityManager::class.java) ?: return
        activityManager.appTasks.forEach { task ->
            runCatching { task.setExcludeFromRecents(enabled) }
        }
    }
}
