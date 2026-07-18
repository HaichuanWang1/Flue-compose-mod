package com.flue.launcher.watchface.jbwatch

internal const val JB_LAYOUT_CANVAS_WIDTH = 512
internal const val JB_LAYOUT_CANVAS_HEIGHT = 512
internal val JB_PROTECTED_IMAGE_KEY = byteArrayOf(0x53, 0x57, 0x48, 0x6E, 0x2D)
internal const val JB_STANDARD_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
internal const val JB_CUSTOM_BASE64 = "ABCgEFGHIJK4MNOPQRSTUVWXYZabcdefDhijklmnopqrstuvwxyz0123L56789+/"

data class JbWatchRoot(
    val name: String,
    val description: String,
    val tags: String,
    val features: String,
    val shape: String,
    val bgColor: String,
    val ucolorDefault: String,
    val mode3d: String,
    val indLoc: String,
    val indBg: String,
    val hotwordLoc: String,
    val hotwordBg: String,
    val protection: String = ""
)

data class JbWatchLayer(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val gyro: Float = 0f,
    val rotation: String = "0",
    val skewX: Float = 0f,
    val skewY: Float = 0f,
    val opacity: Int = 100,
    val alignment: String = "cc",
    val path: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val gifDelay: Int = 0,
    val display: String = "bd",
    val text: String = "",
    val textSize: Float = 24f,
    val animScaleX: Float = 100f,
    val animScaleY: Float = 100f,
    val font: String? = null,
    val transform: String = "n",
    val colorDim: String = "ffffff",
    val color: String = "ffffff",
    val outline: String? = null,
    val oColor: String = "000000",
    val oSize: Int = 0,
    val oOpacity: Int = 0,
    val tapAction: String? = null,
    val condValue: String? = null,
    val condGrid: String? = null,
    val weatherNight: String? = null,
    val name: String? = null,
    val radiusOuter: Float = 0f,
    val radiusInner: Float = 0f,
    val radius: Float = 0f,
    val angle: String = "0",
    val angleTotal: Float = 0f,
    val isClockwise: Boolean = true,
    val color2: String = "ffffff",
    val color3: String = "ffffff",
    val outsideOpacity: Int = 100,
    val curveDir: String = "Up",
    val pathFrames: List<String> = emptyList(),
    val shader: String? = null,
    val shadowType: String? = null,
    val shadowColor: String = "000000",
    val shadowRadius: Int = 0,
    val shadowOffsetX: Int = 0,
    val shadowOffsetY: Int = 0,
    val parentId: String? = null,
    val themeSetIndex: Int = 0,
    val animIn: String? = null,
    val animOut: String? = null,
    val durIn: Int = 0,
    val durOn: Int = 0,
    val durOut: Int = 0,
    val durOff: Int = 0,
    val delayStart: Int = 0,
    val repeatCount: Int = 0,
    val restartOnLoad: Boolean = false,
    val restartOnBright: Boolean = false,
    val restartOnTextChange: Boolean = false
)

data class JbWatchFace(
    val root: JbWatchRoot,
    val layers: List<JbWatchLayer>,
    val rootDir: java.io.File,
    val xmlFile: java.io.File,
    val pxmlFile: java.io.File,
    val designWidth: Int,
    val designHeight: Int
) {
    val needsHighRefresh: Boolean = layers.any { layer ->
        layer.type == "image_gif" || layer.tapAction?.startsWith("script:") == true || layer.rotation != "0"
    }
}

data class JbGlyph(
    val id: Int, val x: Int, val y: Int,
    val width: Int, val height: Int,
    val xoffset: Int, val yoffset: Int, val xadvance: Int
)

data class JbBitmapFont(
    val lineHeight: Int, val base: Int,
    val bitmap: android.graphics.Bitmap, val glyphs: Map<Int, JbGlyph>
)
