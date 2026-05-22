package com.flue.launcher.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun BoxScope.AppListEntryBackground(
    maxWidth: Dp,
    maxHeight: Dp,
    visuals: AppListEntryVisuals,
    color: Color
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = visuals.backgroundFillProgress.coerceIn(0f, 1f)
            }
            .background(color)
    )
}
