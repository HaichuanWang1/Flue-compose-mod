package com.flue.launcher.ui.drawer

internal data class AppListEntryVisuals(
    val backgroundProgress: Float,
    val backgroundFillProgress: Float,
    val iconProgress: Float,
    val surfaceProgress: Float,
    val edgeProgress: Float
)

@Suppress("UNUSED_PARAMETER")
internal fun appListEntryVisuals(progress: Float): AppListEntryVisuals {
    val normalized = progress.coerceIn(0f, 1f)
    return AppListEntryVisuals(
        backgroundProgress = normalized,
        backgroundFillProgress = normalized,
        iconProgress = 1f,
        surfaceProgress = 1f,
        edgeProgress = 1f
    )
}
