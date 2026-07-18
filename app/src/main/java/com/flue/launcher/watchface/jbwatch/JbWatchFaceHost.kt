package com.flue.launcher.watchface.jbwatch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.min

@Composable
fun JbWatchFaceHost(
    descriptor: LunchWatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLoadFailure: (LunchWatchFaceDescriptor, Throwable) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<Throwable?>(null) }
    val face by produceState<JbWatchFace?>(initialValue = null, descriptor.stableKey, refreshToken) {
        value = null
        loadError = null
        val sourceDir = descriptor.sourceDirPath?.let(::File)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(sourceDir != null && sourceDir.isDirectory) { ".watch 表盘目录不存在" }
                JbWatchParser.parse(sourceDir)
            }
        }
        result.onSuccess { value = it }.onFailure { loadError = it }
    }
    LaunchedEffect(loadError) {
        loadError?.let { onLoadFailure(descriptor, it) }
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val batteryLevel = rememberJbWatchBattery(context)
    val isDim = rememberDimMode(context)
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val latestFace by rememberUpdatedState(face)
    val latestNowMillis by rememberUpdatedState(nowMillis)
    val latestBatteryLevel by rememberUpdatedState(batteryLevel)
    LaunchedEffect(isFaceVisible, face?.needsHighRefresh, descriptor.stableKey, refreshToken) {
        while (isFaceVisible) {
            nowMillis = System.currentTimeMillis()
            delay(if (face?.needsHighRefresh == true) 80L else 1_000L)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(descriptor.stableKey, refreshToken, onLongPress, onDoubleTap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (up != null) {
                        if (onDoubleTap != null) {
                            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                            if (secondDown != null) {
                                val secondUp = withTimeoutOrNull(500L) {
                                    waitForUpOrCancellation()
                                }
                                if (secondUp != null) {
                                    onDoubleTap()
                                }
                            } else {
                                latestFace?.let {
                                    handleJbWatchTap(
                                        context = context,
                                        face = it,
                                        point = down.position,
                                        viewWidth = size.width.toFloat(),
                                        viewHeight = size.height.toFloat(),
                                        nowMillis = latestNowMillis,
                                        batteryLevel = latestBatteryLevel
                                    )
                                }
                            }
                        } else {
                            latestFace?.let {
                                handleJbWatchTap(
                                    context = context,
                                    face = it,
                                    point = down.position,
                                    viewWidth = size.width.toFloat(),
                                    viewHeight = size.height.toFloat(),
                                    nowMillis = latestNowMillis,
                                    batteryLevel = latestBatteryLevel
                                )
                            }
                        }
                    } else {
                        onLongPress?.invoke()
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            face != null -> {
                val backgroundColor = runCatching {
                    android.graphics.Color.parseColor("#${face!!.root.bgColor.trim().ifBlank { "000000" }}")
                }.getOrDefault(android.graphics.Color.BLACK)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.drawColor(backgroundColor)
                        JbWatchLayerRenderer.drawFace(
                            canvas = native,
                            face = face!!,
                            nowMillis = nowMillis,
                            batteryLevel = batteryLevel,
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            is24Hour = is24Hour,
                            isDim = isDim
                        )
                    }
                }
            }
            loadError == null -> {
                Text("Loading", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun rememberJbWatchBattery(context: Context): Int {
    var level by remember { mutableStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (rawLevel >= 0 && scale > 0) {
                    level = (rawLevel * 100 / scale).coerceIn(0, 100)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}

@Composable
private fun rememberDimMode(context: Context): Boolean {
    var dim by remember { mutableStateOf(!isScreenInteractive(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                dim = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> true
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> false
                    else -> !isScreenInteractive(context)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return dim
}

private fun isScreenInteractive(context: Context): Boolean {
    return runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    }.getOrDefault(true)
}

// ========== Tap handling ==========

private data class JbWatchAppAction(
    val packageName: String,
    val className: String
)

private fun handleJbWatchTap(
    context: Context,
    face: JbWatchFace,
    point: Offset,
    viewWidth: Float,
    viewHeight: Float,
    nowMillis: Long,
    batteryLevel: Int
) {
    val canvasWidth = face.designWidth.coerceAtLeast(1)
    val canvasHeight = face.designHeight.coerceAtLeast(1)
    val scaleX = viewWidth / canvasWidth
    val scaleY = viewHeight / canvasHeight
    val scale = min(scaleX, scaleY)
    val offsetX = (viewWidth - canvasWidth * scale) / 2f
    val offsetY = (viewHeight - canvasHeight * scale) / 2f
    val localX = (point.x - offsetX) / scale
    val localY = (point.y - offsetY) / scale
    face.layers
        .asReversed()
        .firstOrNull { layer ->
            val hitSize = JbWatchLayerRenderer.resolveHitContentSize(face, layer, nowMillis)
                !(layer.display == "d") &&
                !layer.tapAction.isNullOrBlank() &&
                JbWatchLayerRenderer.resolveRect(
                    face = face,
                    layer = layer,
                    contentWidth = hitSize.first,
                    contentHeight = hitSize.second
                ).contains(localX, localY)
        }
        ?.tapAction
        ?.let { action -> launchJbWatchTapAction(context, action, face.rootDir) }
}

private fun launchJbWatchTapAction(context: Context, action: String, rootDir: File): Boolean {
    val normalized = normalizeJbTapAction(action)
    return when {
        normalized.startsWith("widget_calendar") -> {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CALENDAR)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.isSuccess
        }
        normalized.startsWith("widget_weather") || normalized.startsWith("m_update_weather") -> {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_WEATHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.isSuccess
        }
        normalized.startsWith("script:") -> {
            playJbWatchScript(rootDir, normalized.removePrefix("script:").trim())
            true
        }
        else -> {
            parseJbWatchAppAction(normalized)?.let { appAction ->
                return launchJbWatchAppAction(context, appAction)
            }
            val parts = normalized.split('/')
            if (parts.size == 2) {
                val packageName = parts[0].trim()
                val className = parts[1].trim().let { value ->
                    if (value.startsWith(".")) packageName + value else value
                }
                launchJbWatchAppAction(context, JbWatchAppAction(packageName, className))
            } else {
                false
            }
        }
    }
}

private fun parseJbWatchAppAction(action: String): JbWatchAppAction? {
    val stripped = when {
        action.startsWith("w_app:") -> action.removePrefix("w_app:")
        action.startsWith("m_app:") -> action.removePrefix("m_app:")
        else -> return null
    }
    val parts = stripped.split('`').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size < 3) return null
    val packageName = parts[1]
    val className = parts[2]
    if (packageName.isBlank() || className.isBlank()) return null
    return JbWatchAppAction(
        packageName = packageName,
        className = if (className.startsWith(".")) packageName + className else className
    )
}

private fun launchJbWatchAppAction(context: Context, appAction: JbWatchAppAction): Boolean {
    val launchedComponent = runCatching {
        context.startActivity(Intent().apply {
            component = ComponentName(appAction.packageName, appAction.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.getOrDefault(false)
    if (launchedComponent) return true
    return context.packageManager.getLaunchIntentForPackage(appAction.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }?.let { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    } == true
}

private fun normalizeJbTapAction(raw: String): String {
    return raw
        .replace("x)f8\uFFFD_weatjr", "widget_weather")
        .replace("m_upfte_weatjr", "m_update_weather")
        .replace("scrn0\uFFFD", "script:")
        .replace("&ars;", "&apos;")
        .replace("weatjr", "weather")
        .replace("updte", "update")
        .replace(Regex("[^\\x09\\x0A\\x0D\\x20-\\x7E]"), "")
        .trim()
}

private fun playJbWatchScript(rootDir: File, script: String) {
    val match = Regex("wm_sfx\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").find(script) ?: return
    val soundName = match.groupValues.getOrNull(1)?.trim().orEmpty()
    if (soundName.isBlank()) return
    val candidate = listOf(
        File(rootDir, "sfx/$soundName.mp3"),
        File(rootDir, "sfx/$soundName.wav"),
        File(rootDir, "sfx/$soundName.ogg")
    ).firstOrNull { it.isFile } ?: return
    runCatching {
        MediaPlayer().apply {
            setDataSource(candidate.absolutePath)
            setOnCompletionListener {
                it.release()
            }
            prepare()
            start()
        }
    }
}
