package com.flue.launcher.data.repository

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.data.model.WidgetInfo
import java.util.concurrent.ConcurrentHashMap

class WidgetRepository(private val context: Context) {
    private data class ProviderSnapshot(
        val widgets: List<WidgetInfo>,
        val providersByKey: Map<String, AppWidgetProviderInfo>
    )

    @Volatile private var providerSnapshot: ProviderSnapshot? = null
    private val packageIconCache = ConcurrentHashMap<String, ImageBitmap>()
    private val widgetPreviewCache = ConcurrentHashMap<String, ImageBitmap>()

    private val appWidgetManager: AppWidgetManager
        get() = AppWidgetManager.getInstance(context)

    fun getAllWidgets(): List<WidgetInfo> {
        return snapshot().widgets
    }

    fun getWidgetByKey(widgetKey: String, widgetId: Int = -1): WidgetInfo? {
        return findProviderInfo(widgetKey)?.toWidgetInfo(widgetId = widgetId)
    }

    fun findProviderInfo(widgetKey: String): AppWidgetProviderInfo? {
        return snapshot().providersByKey[widgetKey]
    }

    fun invalidateProviders() {
        providerSnapshot = null
    }

    fun parseSlotValue(raw: String?): WidgetInfo? {
        if (raw.isNullOrBlank()) return null
        val baseValue = raw.substringBefore('#')
        val separatorIndex = baseValue.indexOf(':')
        val widgetId = if (separatorIndex > 0) baseValue.substring(0, separatorIndex).toIntOrNull() else null
        val widgetKey = if (separatorIndex > 0) baseValue.substring(separatorIndex + 1) else baseValue
        return getWidgetByKey(widgetKey = widgetKey, widgetId = widgetId ?: -1)
    }

    fun extractWidgetId(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val baseValue = raw.substringBefore('#')
        val separatorIndex = baseValue.indexOf(':')
        return if (separatorIndex <= 0) {
            null
        } else {
            baseValue.substring(0, separatorIndex).toIntOrNull()
        }
    }

    fun serializeSlotValue(widget: WidgetInfo): String {
        return "${widget.widgetId}:${widget.widgetKey}"
    }

    fun loadProviderPackageIcon(packageName: String): ImageBitmap? {
        packageIconCache[packageName]?.let { return it }
        return loadProviderPackageIconInternal(packageName)
            ?.also { packageIconCache[packageName] = it }
    }

    fun loadWidgetPreviewImage(widget: WidgetInfo): ImageBitmap? {
        widgetPreviewCache[widget.widgetKey]?.let { return it }
        val providerInfo = findProviderInfo(widget.widgetKey) ?: return null
        return loadWidgetPreviewImageInternal(providerInfo)
            ?.also { widgetPreviewCache[widget.widgetKey] = it }
    }

    private fun AppWidgetProviderInfo.toWidgetInfo(widgetId: Int = -1): WidgetInfo {
        val label = runCatching { loadLabel(context.packageManager) }.getOrDefault(provider.shortClassName)
        val packageLabel = loadProviderPackageLabel(provider.packageName)
        return WidgetInfo(
            label = label,
            packageName = provider.packageName,
            providerClassName = provider.className,
            widgetId = widgetId,
            minHeightDp = minHeight.coerceAtLeast(0),
            configureClassName = configure?.className,
            appLabel = packageLabel
        )
    }

    private fun loadProviderPackageLabel(packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
    }

    private fun loadProviderPackageIconInternal(packageName: String): ImageBitmap? {
        return runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96, config = Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }.getOrNull()
    }

    private fun loadWidgetPreviewImageInternal(providerInfo: AppWidgetProviderInfo): ImageBitmap? {
        return runCatching {
            providerInfo.loadPreviewImage(context, 0)
                ?.toBitmap(width = 176, height = 112, config = Bitmap.Config.ARGB_8888)
                ?.asImageBitmap()
        }.getOrNull()
    }

    private fun snapshot(): ProviderSnapshot {
        providerSnapshot?.let { return it }
        return synchronized(this) {
            providerSnapshot ?: appWidgetManager.installedProviders
                .associateBy { "${it.provider.packageName}/${it.provider.className}" }
                .let { providers ->
                    ProviderSnapshot(
                        widgets = providers.values
                            .map { providerInfo -> providerInfo.toWidgetInfo() }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
                        providersByKey = providers
                    )
                }
                .also { providerSnapshot = it }
        }
    }
}
