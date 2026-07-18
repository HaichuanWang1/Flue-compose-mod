package com.flue.launcher.watchface.jbwatch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import java.io.File

object JbWatchBitmapCache {
    private const val MAX_CACHE_KB = 24 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(file: File): Bitmap? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it }
        val bitmap = decodeBitmap(file) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun bounds(file: File): Pair<Int, Int>? {
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeBitmap(file, options)
        val width = options.outWidth.takeIf { it > 0 } ?: return null
        val height = options.outHeight.takeIf { it > 0 } ?: return null
        return width to height
    }

    private fun decodeBitmap(
        file: File,
        options: BitmapFactory.Options? = null
    ): Bitmap? {
        BitmapFactory.decodeFile(file.absolutePath, options)?.let { return it }
        if (!file.extension.equals("ppng", ignoreCase = true) &&
            !file.extension.equals("pjpg", ignoreCase = true)
        ) {
            return null
        }
        val encoded = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoded = ByteArray(encoded.size) { index ->
            (encoded[index].toInt() xor JB_PROTECTED_IMAGE_KEY[index % JB_PROTECTED_IMAGE_KEY.size].toInt()).toByte()
        }
        return BitmapFactory.decodeByteArray(decoded, 0, decoded.size, options)
    }

    fun evictAll() {
        cache.evictAll()
    }
}

object JbWatchTypefaceCache {
    private val cache = mutableMapOf<String, android.graphics.Typeface?>()

    fun get(rootDir: File, fontName: String?): android.graphics.Typeface? {
        val key = fontName?.trim().orEmpty()
        if (key.isBlank()) return android.graphics.Typeface.DEFAULT
        synchronized(cache) {
            cache[key]?.let { return it }
            val candidates = listOf(
                "fonts/$key",
                key,
                "fonts/$key.ttf",
                "$key.ttf",
                "fonts/$key.otf",
                "$key.otf"
            )
            val file = candidates.firstNotNullOfOrNull { candidate ->
                JbWatchTagEngine.resolveWatchPath(rootDir, JbWatchLayer(type = "image", path = candidate), 0L)
            }
            val typeface = file?.takeIf { it.isFile }?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
                ?: android.graphics.Typeface.DEFAULT
            cache[key] = typeface
            return typeface
        }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}

object JbBmFontCache {
    private val cache = mutableMapOf<String, JbBitmapFont?>()

    fun get(rootDir: File, fontName: String): JbBitmapFont? {
        val key = fontName.trim()
        if (!key.startsWith("bm:")) return null
        return cache.getOrPut(key) { load(rootDir, key.removePrefix("bm:")) }
    }

    private fun load(rootDir: File, name: String): JbBitmapFont? {
        val fntFile = listOf(
            File(rootDir, "fonts_bm/$name.fnt"),
            File(rootDir, "fonts/$name.fnt")
        ).firstOrNull { it.isFile } ?: return null

        val dir = fntFile.parentFile ?: rootDir
        val lines = runCatching { fntFile.readLines() }.getOrNull() ?: return null

        var lineHeight = 0; var base = 0; var scaleW = 0; var scaleH = 0
        var pageFile: String? = null
        val glyphs = mutableMapOf<Int, JbGlyph>()

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("common ") -> {
                    lineHeight = extractInt(t, "lineHeight=") ?: lineHeight
                    base = extractInt(t, "base=") ?: base
                    scaleW = extractInt(t, "scaleW=") ?: scaleW
                    scaleH = extractInt(t, "scaleH=") ?: scaleH
                }
                t.startsWith("page ") -> {
                    val s = t.indexOf("file=\"")
                    if (s >= 0) {
                        val start = s + 6
                        val end = t.indexOf('"', start)
                        if (end >= 0) pageFile = t.substring(start, end)
                    }
                }
                t.startsWith("char ") -> {
                    val id = extractInt(t, "id=") ?: continue
                    glyphs[id] = JbGlyph(
                        id = id,
                        x = extractInt(t, "x=") ?: 0,
                        y = extractInt(t, "y=") ?: 0,
                        width = extractInt(t, "width=") ?: 0,
                        height = extractInt(t, "height=") ?: 0,
                        xoffset = extractInt(t, "xoffset=") ?: 0,
                        yoffset = extractInt(t, "yoffset=") ?: 0,
                        xadvance = extractInt(t, "xadvance=") ?: 0
                    )
                }
            }
        }
        if (glyphs.isEmpty()) return null

        val pngFile = pageFile?.let { p ->
            listOf(File(dir, p), File(rootDir, "fonts_bm/$p"), File(rootDir, "fonts/$p"))
                .firstOrNull { it.isFile }
        } ?: return null

        val bitmap = JbWatchBitmapCache.get(pngFile) ?: return null
        return JbBitmapFont(lineHeight, base, bitmap, glyphs)
    }

    private fun extractInt(line: String, key: String): Int? {
        val s = line.indexOf(key) + key.length
        if (s < key.length) return null
        var e = s; while (e < line.length && (line[e].isDigit() || line[e] == '-')) e++
        return line.substring(s, e).toIntOrNull()
    }

    fun measureWidth(font: JbBitmapFont, text: String): Float {
        var w = 0f
        for (ch in text) w += font.glyphs[ch.code]?.xadvance?.toFloat() ?: 0f
        return w.coerceAtLeast(1f)
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}

fun drawJbBitmapText(canvas: AndroidCanvas, text: String, cx: Float, cy: Float, font: JbBitmapFont, align: Paint.Align, paint: Paint) {
    val totalW = JbBmFontCache.measureWidth(font, text)
    val startX = when (align) {
        Paint.Align.CENTER -> cx - totalW / 2f
        Paint.Align.RIGHT -> cx - totalW
        else -> cx
    }
    var cursorX = startX
    for (ch in text) {
        val g = font.glyphs[ch.code] ?: continue
        val src = Rect(g.x, g.y, g.x + g.width, g.y + g.height)
        val dst = RectF(cursorX + g.xoffset, cy + g.yoffset, cursorX + g.xoffset + g.width, cy + g.yoffset + g.height)
        canvas.drawBitmap(font.bitmap, src, dst, paint)
        cursorX += g.xadvance
    }
}
