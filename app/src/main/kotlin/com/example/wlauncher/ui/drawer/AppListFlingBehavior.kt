package com.flue.launcher.ui.drawer

import android.content.Context
import android.widget.OverScroller
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun rememberAppListOverScrollerFlingBehavior(
    onBoundaryDelta: suspend (Float) -> Unit = {}
): FlingBehavior {
    val context = LocalContext.current.applicationContext
    val latestOnBoundaryDelta = rememberUpdatedState(onBoundaryDelta)
    return remember(context) {
        AppListOverScrollerFlingBehavior(
            context = context,
            onBoundaryDelta = { latestOnBoundaryDelta.value(it) }
        )
    }
}

private class AppListOverScrollerFlingBehavior(
    context: Context,
    private val onBoundaryDelta: suspend (Float) -> Unit
) : FlingBehavior {
    private val scroller = OverScroller(context)

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return initialVelocity
        scroller.forceFinished(true)
        scroller.fling(
            0,
            0,
            0,
            initialVelocity.roundToInt().coerceIn(-80_000, 80_000),
            0,
            0,
            Int.MIN_VALUE,
            Int.MAX_VALUE
        )

        var lastY = 0
        while (!scroller.isFinished) {
            withFrameNanos { }
            if (!scroller.computeScrollOffset()) break
            val y = scroller.currY
            val delta = (y - lastY).toFloat()
            lastY = y
            if (delta == 0f) continue
            val consumed = scrollBy(delta)
            if (abs(consumed - delta) > 0.5f) {
                onBoundaryDelta(delta - consumed)
                scroller.forceFinished(true)
                val sign = if (initialVelocity >= 0f) 1f else -1f
                return sign * scroller.currVelocity
            }
        }
        return 0f
    }
}
