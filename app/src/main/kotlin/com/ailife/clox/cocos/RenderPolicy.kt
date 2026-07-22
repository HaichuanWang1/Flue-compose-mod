package com.ailife.clox.cocos

/**
 * Simplified render policy for Flue integration.
 */
object RenderPolicy {
    @Volatile var dynamicFps: Boolean = true
        private set

    @Volatile var aodDimmed: Boolean = false
        private set

    fun load(@Suppress("UNUSED_PARAMETER") ctx: Any?) {
        dynamicFps = true
        aodDimmed = false
    }
}
