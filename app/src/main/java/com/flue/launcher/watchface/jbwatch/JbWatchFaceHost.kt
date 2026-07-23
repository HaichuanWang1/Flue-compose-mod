package com.flue.launcher.watchface.jbwatch

import android.app.Activity
import android.graphics.BitmapFactory
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

// Face occlusion overlay — covers the GL surface when the face is hidden
// (app list, notifications, etc.). SurfaceView doesn't support Compose alpha
// compositing, so we fade a black overlay on top instead.
private const val FACE_OCCLUSION_ALPHA = 0.78f  // matches Material style face alpha=0.22
private const val OCCLUSION_FADE_IN_MS = 300    // face → hidden
private const val OCCLUSION_FADE_OUT_MS = 200   // hidden → face (faster for snappy return)

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

    // ── Face occlusion overlay (app list / notifications covering the face) ──
    // SurfaceView doesn't respond to Compose graphicsLayer alpha, so we simulate
    // the fade by overlaying a black Box. Keep 1 FPS during the fade-in animation,
    // restore normal FPS only after the overlay is fully opaque (avoids a flash of
    // the fully-bright face during the brief moment the overlay hasn't covered it).
    var occlusionAnimDone by remember { mutableStateOf(false) }
    var animatingToHidden by remember { mutableStateOf(false) }

    val faceOverlayAlpha by animateFloatAsState(
        targetValue = if (isFaceVisible) 0f else FACE_OCCLUSION_ALPHA,
        animationSpec = tween(
            durationMillis = if (isFaceVisible) OCCLUSION_FADE_OUT_MS else OCCLUSION_FADE_IN_MS
        ),
        finishedListener = {
            // Fade-in complete: overlay fully covers the surface → safe to drop to 1 FPS
            if (animatingToHidden) {
                occlusionAnimDone = true
                animatingToHidden = false
            }
        },
        label = "faceOverlayAlpha"
    )

    // Track fade-in animation lifecycle
    LaunchedEffect(isFaceVisible) {
        if (!isFaceVisible) {
            // Starting fade-in: keep full FPS until overlay is opaque
            animatingToHidden = true
            occlusionAnimDone = false
            if (isEngineReady) dev.axmol.lib.AxmolRenderer.setForceLowFps(false)
        } else {
            // Returning: cancel any pending fade-in state, start fade-out
            animatingToHidden = false
            occlusionAnimDone = false
            if (isEngineReady) dev.axmol.lib.AxmolRenderer.setForceLowFps(false)
        }
    }

    // Drop to 1 FPS after fade-in completes (not during — avoids flash)
    LaunchedEffect(occlusionAnimDone) {
        if (occlusionAnimDone && !isFaceVisible && isEngineReady) {
            dev.axmol.lib.AxmolRenderer.setForceLowFps(true)
        }
    }

    // Create bridge manager FIRST, register handler BEFORE engine starts
    val bridgeManager = remember(descriptor.stableKey, refreshToken) {
        WatchfaceBridgeManager(context).apply {
            luaReadyCallback = {
                val path = descriptor.sourceDirPath ?: ""
                if (path.isNotBlank()) {
                    setWatchfacePath(path)
                }
            }
            wfLoadedCallback = {
                showOverlay = false
            }
        }
    }

    // Safety timeout: hide overlay after SAFETY_TIMEOUT_MS even if wf_loaded never arrives
    LaunchedEffect(descriptor.stableKey, refreshToken) {
        showOverlay = true
        delay(SAFETY_TIMEOUT_MS)
        if (showOverlay) {
            showOverlay = false
        }
    }

    // Lifecycle: 后台降至 1 FPS，恢复时延迟 700ms 再全速
    // 让 Compose UI 重组先完成，避免两个渲染线程同时抢 CPU
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumeHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    bridgeManager.onHostPause()
                    resumeHandler.removeCallbacksAndMessages(null)
                    if (isEngineReady) dev.axmol.lib.AxmolRenderer.setForceLowFps(true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (isEngineReady) dev.axmol.lib.AxmolRenderer.setForceLowFps(true)
                    resumeHandler.postDelayed({
                        if (isEngineReady && isFaceVisible) {
                            dev.axmol.lib.AxmolRenderer.setForceLowFps(false)
                        }
                        bridgeManager.onHostResume()
                    }, 700)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            resumeHandler.removeCallbacksAndMessages(null)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize Axmol engine on first composition
    DisposableEffect(descriptor.stableKey, refreshToken) {
        try {
            if (activity != null) {
                val engineWasLive = CocosManager.getGlView() != null

                bridgeManager.start()

                CocosManager.init(activity)
                isEngineReady = true

                if (engineWasLive) {
                    bridgeManager.reattach()
                }
            }
        } catch (e: Exception) {
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
        bridgeManager.setWatchfacePath(path)
    }

    // Main content: GL surface + occlusion overlay + loading overlay
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

        // Face occlusion overlay: simulates SurfaceView alpha fade
        if (faceOverlayAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = faceOverlayAlpha }
                    .background(Color.Black)
            )
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
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
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
