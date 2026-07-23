package com.flue.launcher.watchface.jbwatch

import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.cocos.bridge.WatchfaceBridgeManager
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import kotlinx.coroutines.delay
import java.io.File

private const val TAG = "JbWatchFaceHost"
private const val OVERLAY_FADE_OUT_MS = 400
private const val SAFETY_TIMEOUT_MS = 15_000L

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
    val activity = context as? Activity
    var isEngineReady by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }

    // Loading overlay state: true = show overlay, false = fade out
    var showOverlay by remember { mutableStateOf(true) }

    // Preview bitmap for the overlay
    val previewBitmap = remember(descriptor.previewFilePath) {
        descriptor.previewFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    BitmapFactory.decodeFile(path)?.copy(
                        android.graphics.Bitmap.Config.ARGB_8888, false
                    )
                } else null
            } catch (_: Exception) { null }
        }
    }

    // Animate overlay alpha: 1f → 0f when showOverlay becomes false
    val overlayAlpha by animateFloatAsState(
        targetValue = if (showOverlay) 1f else 0f,
        animationSpec = tween(durationMillis = OVERLAY_FADE_OUT_MS),
        label = "overlayAlpha"
    )

    // Create bridge manager FIRST, register handler BEFORE engine starts
    val bridgeManager = remember(descriptor.stableKey, refreshToken) {
        WatchfaceBridgeManager(context).apply {
            luaReadyCallback = {
                Log.i(TAG, "Lua ready — pushing path: ${descriptor.sourceDirPath}")
                val path = descriptor.sourceDirPath ?: ""
                if (path.isNotBlank()) {
                    setWatchfacePath(path)
                    Log.i(TAG, "Watchface path pushed: $path")
                } else {
                    Log.w(TAG, "No watchface path to push!")
                }
            }
            wfLoadedCallback = {
                Log.i(TAG, "wf_loaded — hiding loading overlay")
                showOverlay = false
            }
        }
    }

    // Safety timeout: hide overlay after SAFETY_TIMEOUT_MS even if wf_loaded never arrives
    LaunchedEffect(descriptor.stableKey, refreshToken) {
        showOverlay = true
        delay(SAFETY_TIMEOUT_MS)
        if (showOverlay) {
            Log.w(TAG, "Safety timeout — forcing overlay hide")
            showOverlay = false
        }
    }

    // Lifecycle: 息屏时暂停 GL 渲染，亮屏时恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    bridgeManager.onHostPause()
                    CocosManager.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    bridgeManager.onHostResume()
                    CocosManager.onResume()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 遮挡降帧：app 列表/通知/控件覆盖时降至 1 FPS，恢复时回到正常帧率
    LaunchedEffect(isFaceVisible) {
        if (isEngineReady) {
            dev.axmol.lib.AxmolRenderer.setForceLowFps(!isFaceVisible)
        }
    }

    // Initialize Axmol engine on first composition
    DisposableEffect(descriptor.stableKey, refreshToken) {
        try {
            if (activity != null) {
                // Detect whether the engine is already running (Compose
                // recomposition that reuses the live GL view). On a warm
                // re-entry the Lua scene already sent wf_lua_ready during its
                // onEnter and won't send it again — use reattach() to push
                // the path immediately instead of waiting for a signal that
                // will never arrive.
                val engineWasLive = CocosManager.getGlView() != null

                bridgeManager.start()
                Log.i(TAG, "Bridge handler registered before engine start")

                CocosManager.init(activity)
                isEngineReady = true
                Log.i(TAG, "CocosManager initialized, sourceDir=${descriptor.sourceDirPath}")

                if (engineWasLive) {
                    bridgeManager.reattach()
                    Log.i(TAG, "Engine was already live — reattached bridge")
                }
            } else {
                Log.e(TAG, "Activity is null — cannot initialize Axmol")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Axmol", e)
            loadError = e
        }

        onDispose {
            bridgeManager.stop()
        }
    }

    LaunchedEffect(loadError) {
        loadError?.let { onLoadFailure(descriptor, it) }
    }

    // Fallback: push path if wf_lua_ready never arrives
    LaunchedEffect(isEngineReady, descriptor.sourceDirPath, refreshToken) {
        if (!isEngineReady) return@LaunchedEffect
        val path = descriptor.sourceDirPath ?: ""
        if (path.isBlank()) return@LaunchedEffect
        delay(2_000)
        Log.i(TAG, "Fallback: pushing watchface path after timeout: $path")
        bridgeManager.setWatchfacePath(path)
    }

    // Main content: GL surface + loading overlay on top
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isEngineReady -> {
                AndroidView(
                    factory = { ctx ->
                        val glView = CocosManager.getGlView() as? View
                            ?: error("Axmol GL view not initialized")
                        (glView.parent as? ViewGroup)?.removeView(glView)
                        glView
                    },
                    modifier = Modifier.fillMaxSize().background(Color.Black)
                )
            }
            loadError != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Load failed: ${loadError?.message}",
                        color = Color.Red.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
                }
            }
        }

        // Loading overlay: small preview + spinner + "加载中" + fade-out
        if (overlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayAlpha }
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Small preview image
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize(0.55f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    // Spinning loader
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // "加载中" text
                    Text(
                        "加载中...",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
