package com.flue.launcher.data.model

import android.content.ComponentName
import androidx.compose.ui.graphics.ImageBitmap

data class WidgetInfo(
    val label: String,
    val packageName: String,
    val providerClassName: String,
    val widgetId: Int = -1,
    val minHeightDp: Int = 0,
    val configureClassName: String? = null,
    val appLabel: String = packageName.substringAfterLast('.'),
    val appIcon: ImageBitmap? = null,
    val previewImage: ImageBitmap? = null
) {
    val componentName: ComponentName
        get() = ComponentName(packageName, providerClassName)

    val widgetKey: String
        get() = "$packageName/$providerClassName"
}

sealed interface SideScreenItem {
    data class App(val app: AppInfo) : SideScreenItem
    data class Widget(val widget: WidgetInfo) : SideScreenItem
}
