package com.flue.launcher.watchface.jbwatch

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object JbWatchLayerRenderer {
    fun drawFace(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        nowMillis: Long,
        batteryLevel: Int,
        width: Int,
        height: Int,
        is24Hour: Boolean,
        isDim: Boolean
    ) {
        val canvasWidth = face.designWidth.coerceAtLeast(1)
        val canvasHeight = face.designHeight.coerceAtLeast(1)
        val scaleX = width.toFloat() / canvasWidth
        val scaleY = height.toFloat() / canvasHeight
        val scale = min(scaleX, scaleY)
        val offsetX = (width - canvasWidth * scale) / 2f
        val offsetY = (height - canvasHeight * scale) / 2f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        face.layers.forEach { layer ->
            drawLayer(canvas, face, layer, nowMillis, batteryLevel, is24Hour, isDim)
        }
        canvas.restore()
    }

    private fun drawLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int,
        is24Hour: Boolean,
        isDim: Boolean
    ) {
        if (!JbWatchTagEngine.shouldDisplayLayer(layer, isDim)) return
        val shaderPaint = applyLayerShader(layer)
        applyLayerShadow(layer, shaderPaint)
        when (layer.type) {
            "image", "image_gif", "image_cond", "gif", "image_tap" -> {
                drawBitmapLayer(canvas, face, layer, nowMillis, batteryLevel)
            }
            "text" -> drawTextLayer(canvas, face, layer, nowMillis, batteryLevel, is24Hour, isDim)
            "text_curved" -> drawCurvedTextLayer(canvas, face, layer, nowMillis, batteryLevel, is24Hour)
            "ring" -> drawRingLayer(canvas, face, layer, nowMillis, batteryLevel, shaderPaint)
            "ring_image" -> drawRingImageLayer(canvas, face, layer, nowMillis, batteryLevel)
            "progress" -> drawProgressLayer(canvas, face, layer, nowMillis, batteryLevel, shaderPaint)
            "progress_image" -> drawProgressImageLayer(canvas, face, layer, nowMillis, batteryLevel)
            "segments" -> drawSegmentsLayer(canvas, face, layer, nowMillis, batteryLevel)
            "markers", "markers_hm" -> drawMarkersLayer(canvas, face, layer)
            "shape" -> drawShapeLayer(canvas, face, layer)
        }
    }

    private fun drawBitmapLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int
    ) {
        val file = JbWatchTagEngine.resolveWatchPath(face.rootDir, layer, nowMillis) ?: return
        val bitmap = JbWatchBitmapCache.get(file) ?: return
        val displaySize = resolveBitmapDisplaySize(layer, bitmap.width, bitmap.height)
        val rect = resolveRect(face, layer, displaySize.first, displaySize.second)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
        }
        if (layer.type == "image_cond" && !layer.condGrid.isNullOrBlank()) {
            val index = JbWatchTagEngine.evaluateWeatherIndex(layer.condValue, weatherCode = "01d")
            drawConditionBitmap(canvas, bitmap, rect, layer.condGrid, index, paint)
        } else {
            canvas.save()
            rotateLayerEval(canvas, rect, layer.rotation, nowMillis, batteryLevel)
            canvas.drawBitmap(bitmap, null, rect, paint)
            canvas.restore()
        }
    }

    private fun drawTextLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int,
        is24Hour: Boolean,
        isDim: Boolean
    ) {
        val text = JbWatchTagEngine.resolveText(layer.text, nowMillis, batteryLevel, is24Hour)
        val fontName = layer.font.orEmpty().trim()
        val bmFont = if (fontName.startsWith("bm:")) JbBmFontCache.get(face.rootDir, fontName) else null

        val textColor = if (isDim) JbWatchTagEngine.parseColor(layer.colorDim) else JbWatchTagEngine.parseColor(layer.color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = layer.textSize
            textAlign = when (JbWatchTagEngine.jbAlignmentHorizontal(layer.alignment)) {
                0f -> Paint.Align.LEFT
                1f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
            typeface = JbWatchTypefaceCache.get(face.rootDir, layer.font)
        }
        val textDisplaySize = if (bmFont != null) {
            val mw = JbBmFontCache.measureWidth(bmFont, text)
            val mh = bmFont.lineHeight.toFloat().coerceAtLeast(1f)
            applyLayerScale(layer, mw, mh)
        } else {
            resolveTextDisplaySize(layer, text, paint)
        }
        val rect = resolveRect(
            face = face,
            layer = layer,
            contentWidth = textDisplaySize.first,
            contentHeight = textDisplaySize.second
        )
        val x = when (JbWatchTagEngine.jbAlignmentHorizontal(layer.alignment)) {
            0f -> rect.left
            1f -> rect.right
            else -> rect.centerX()
        }
        val y = if (bmFont != null) {
            rect.centerY() + bmFont.base - bmFont.lineHeight / 2f
        } else {
            rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        }

        if (layer.outline?.equals("Outline", ignoreCase = true) == true && layer.oSize > 0) {
            val outlinePaint = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = layer.oSize.toFloat()
                color = JbWatchTagEngine.parseColor(layer.oColor)
                alpha = ((layer.oOpacity.coerceIn(0, 100) / 100f) * 255).toInt()
            }
            canvas.save()
            rotateLayerEval(canvas, rect, layer.rotation, nowMillis, batteryLevel)
            if (bmFont != null) drawJbBitmapText(canvas, text, x, y, bmFont, paint.textAlign, outlinePaint)
            else canvas.drawText(text, x, y, outlinePaint)
            canvas.restore()
        }
        canvas.save()
        rotateLayerEval(canvas, rect, layer.rotation, nowMillis, batteryLevel)
        if (bmFont != null) drawJbBitmapText(canvas, text, x, y, bmFont, paint.textAlign, paint)
        else canvas.drawText(text, x, y, paint)
        canvas.restore()
    }

    private fun drawCurvedTextLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int,
        is24Hour: Boolean
    ) {
        val text = JbWatchTagEngine.resolveText(layer.text, nowMillis, batteryLevel, is24Hour)
        if (text.isBlank()) return
        val center = layerCenter(face, layer)
        val radius = layer.radius.takeIf { it > 0f } ?: 180f
        val angle = JbWatchTagEngine.evaluateNumericExpression(layer.rotation, nowMillis, batteryLevel)
        val radians = Math.toRadians((angle - 90f).toDouble())
        val x = center.x + kotlin.math.cos(radians).toFloat() * radius
        val y = center.y + kotlin.math.sin(radians).toFloat() * radius
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JbWatchTagEngine.parseColor(layer.color)
            textSize = layer.textSize
            textAlign = when (JbWatchTagEngine.jbAlignmentHorizontal(layer.alignment)) {
                0f -> Paint.Align.LEFT
                1f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
            typeface = JbWatchTypefaceCache.get(face.rootDir, layer.font)
        }
        canvas.save()
        canvas.rotate(angle + if (layer.curveDir.equals("Down", ignoreCase = true)) 180f else 0f, x, y)
        canvas.drawText(text, x, y - (paint.descent() + paint.ascent()) / 2f, paint)
        canvas.restore()
    }

    private fun drawRingLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int,
        shaderPaint: Paint? = null
    ) {
        val center = layerCenter(face, layer)
        val outer = layer.radiusOuter.takeIf { it > 0f } ?: layer.radius.takeIf { it > 0f } ?: 210f
        val inner = layer.radiusInner.takeIf { it > 0f } ?: (outer - 14f)
        val stroke = (outer - inner).coerceAtLeast(1f)
        val radius = inner + stroke / 2f
        val sweep = JbWatchTagEngine.evaluateNumericExpression(layer.angle, nowMillis, batteryLevel)
            .takeIf { it > 0f }
            ?: layer.angleTotal.takeIf { it > 0f }
            ?: 360f
        val start = JbWatchTagEngine.evaluateNumericExpression(layer.rotation, nowMillis, batteryLevel) - 90f
        val oval = RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

        if (shaderPaint != null) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = stroke
                color = JbWatchTagEngine.parseColor(layer.color3).takeUnless { it == android.graphics.Color.WHITE }
                    ?: android.graphics.Color.argb(30, 255, 255, 255)
                alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
            }
            val total = layer.angleTotal.takeIf { it > 0f } ?: 360f
            canvas.drawArc(oval, start, if (layer.isClockwise) total else -total, false, bgPaint)
            canvas.drawArc(oval, start, if (layer.isClockwise) sweep else -sweep, false, shaderPaint)
            return
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
            color = JbWatchTagEngine.parseColor(layer.color)
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
        }
        canvas.drawArc(oval, start, if (layer.isClockwise) sweep else -sweep, false, paint)
    }

    private fun drawMarkersLayer(canvas: AndroidCanvas, face: JbWatchFace, layer: JbWatchLayer) {
        val center = layerCenter(face, layer)
        val radius = layer.radius.takeIf { it > 0f } ?: 181f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3f
            color = JbWatchTagEngine.parseColor(layer.color)
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
        }
        repeat(60) { tick ->
            val radians = (tick * 6f - 90f) * (PI.toFloat() / 180f)
            val major = tick % 5 == 0
            val outer = radius
            val inner = radius - if (major) 14f else 7f
            canvas.drawLine(
                center.x + cos(radians) * inner,
                center.y + sin(radians) * inner,
                center.x + cos(radians) * outer,
                center.y + sin(radians) * outer,
                paint
            )
        }
    }

    private fun drawShapeLayer(canvas: AndroidCanvas, face: JbWatchFace, layer: JbWatchLayer) {
        val center = layerCenter(face, layer)
        val radius = layer.radius.takeIf { it > 0f }
            ?: (min(layer.width.takeIf { it > 0 } ?: 0, layer.height.takeIf { it > 0 } ?: 0) / 2f)
            .takeIf { it > 0f }
            ?: 24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = JbWatchTagEngine.parseColor(layer.color)
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
        }
        canvas.drawCircle(center.x, center.y, radius, paint)
    }

    private fun drawProgressLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int,
        shaderPaint: Paint? = null
    ) {
        val center = layerCenter(face, layer)
        val outer = layer.radiusOuter.takeIf { it > 0f } ?: layer.radius.takeIf { it > 0f } ?: 210f
        val inner = layer.radiusInner.takeIf { it > 0f } ?: (outer - 14f)
        val stroke = (outer - inner).coerceAtLeast(1f)
        val radius = inner + stroke / 2f
        val sweep = JbWatchTagEngine.evaluateNumericExpression(layer.angle, nowMillis, batteryLevel)
            .takeIf { it > 0f } ?: 360f
        val total = layer.angleTotal.takeIf { it > 0f } ?: 360f
        val progress = (sweep / total).coerceIn(0f, 1f)
        val start = JbWatchTagEngine.evaluateNumericExpression(layer.rotation, nowMillis, batteryLevel) - 90f
        val oval = RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

        if (shaderPaint != null) {
            canvas.drawArc(oval, start, if (layer.isClockwise) total else -total, false, shaderPaint)
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            strokeWidth = stroke
            color = JbWatchTagEngine.parseColor(layer.color)
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
        }
        val sweepAngle = if (layer.isClockwise) total * progress else -total * progress
        canvas.drawArc(oval, start, sweepAngle, false, fillPaint)
    }

    private fun drawProgressImageLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int
    ) {
        drawBitmapLayer(canvas, face, layer, nowMillis, batteryLevel)
    }

    private fun drawRingImageLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int
    ) {
        drawBitmapLayer(canvas, face, layer, nowMillis, batteryLevel)
    }

    private fun drawSegmentsLayer(
        canvas: AndroidCanvas,
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long,
        batteryLevel: Int
    ) {
        val center = layerCenter(face, layer)
        val outer = layer.radiusOuter.takeIf { it > 0f } ?: layer.radius.takeIf { it > 0f } ?: 210f
        val inner = layer.radiusInner.takeIf { it > 0f } ?: (outer - 14f)
        val stroke = (outer - inner).coerceAtLeast(1f)
        val radius = inner + stroke / 2f
        val sweep = JbWatchTagEngine.evaluateNumericExpression(layer.angle, nowMillis, batteryLevel)
            .takeIf { it > 0f } ?: 360f
        val total = layer.angleTotal.takeIf { it > 0f } ?: sweep
        val segmentCount = 12.coerceIn(1, 60)
        val gapDegrees = 2f
        val segmentSweep = (total - gapDegrees * segmentCount) / segmentCount
        val start = JbWatchTagEngine.evaluateNumericExpression(layer.rotation, nowMillis, batteryLevel) - 90f
        val oval = RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            strokeWidth = stroke
            color = JbWatchTagEngine.parseColor(layer.color)
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
        }
        val filledSegments = (sweep / total * segmentCount).toInt().coerceIn(0, segmentCount)
        for (i in 0 until filledSegments) {
            val segStart = start + i * (segmentSweep + gapDegrees)
            canvas.drawArc(oval, segStart, segmentSweep, false, paint)
        }
    }

    private fun applyLayerShader(layer: JbWatchLayer): Paint? {
        val shader = layer.shader?.takeIf { it.isNotBlank() } ?: return null
        if (layer.type != "ring" && layer.type != "progress") return null
        val color1 = JbWatchTagEngine.parseColor(layer.color)
        val color2 = JbWatchTagEngine.parseColor(layer.color2)
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (layer.radiusOuter.takeIf { it > 0f } ?: layer.radius.takeIf { it > 0f } ?: 210f) -
                (layer.radiusInner.takeIf { it > 0f } ?: 14f).coerceAtLeast(1f)
            alpha = JbWatchTagEngine.resolveLayerOpacity(layer)
            when {
                shader.contains("GradientLinear") || shader.contains("gradient") && shader.contains("linear") -> {
                    this.shader = LinearGradient(
                        0f, 0f, 100f, 100f,
                        color1, color2, Shader.TileMode.CLAMP
                    )
                }
                shader.contains("GradientRadial") || shader.contains("gradient") && shader.contains("radial") -> {
                    this.shader = RadialGradient(
                        50f, 50f, 100f,
                        color1, color2, Shader.TileMode.CLAMP
                    )
                }
            }
        }
    }

    private fun applyLayerShadow(layer: JbWatchLayer, paint: Paint?) {
        if (paint == null) return
        val shadowType = layer.shadowType?.takeIf { it.isNotBlank() } ?: return
        val shadowColor = JbWatchTagEngine.parseColor(layer.shadowColor)
        val shadowRadius = layer.shadowRadius.takeIf { it > 0 } ?: return
        paint.setShadowLayer(
            shadowRadius.toFloat(),
            layer.shadowOffsetX.toFloat(),
            layer.shadowOffsetY.toFloat(),
            shadowColor
        )
    }

    private fun drawConditionBitmap(
        canvas: AndroidCanvas,
        bitmap: Bitmap,
        dst: RectF,
        condGrid: String?,
        index: Int,
        paint: Paint?
    ) {
        val (cols, rows) = JbWatchTagEngine.resolveGrid(condGrid)
        if (cols == 1 && rows == 1) {
            canvas.drawBitmap(bitmap, null, dst, paint)
            return
        }
        val cellWidth = bitmap.width / cols
        val cellHeight = bitmap.height / rows
        val safeIndex = index.coerceIn(0, cols * rows - 1)
        val row = safeIndex / cols
        val col = safeIndex % cols
        val src = Rect(
            col * cellWidth,
            row * cellHeight,
            (col + 1) * cellWidth,
            (row + 1) * cellHeight
        )
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    fun resolveRect(face: JbWatchFace, layer: JbWatchLayer, contentWidth: Int, contentHeight: Int): RectF {
        return resolveRect(face, layer, contentWidth.toFloat(), contentHeight.toFloat())
    }

    fun resolveRect(face: JbWatchFace, layer: JbWatchLayer, contentWidth: Float, contentHeight: Float): RectF {
        val anchor = JbWatchTagEngine.jbAlignmentAnchor(layer.alignment)
        val positionX = face.designWidth / 2f + layer.x
        val positionY = face.designHeight / 2f + layer.y
        val left = positionX - contentWidth * anchor.first
        val top = positionY - contentHeight * anchor.second
        return RectF(left, top, left + contentWidth, top + contentHeight)
    }

    fun layerCenter(face: JbWatchFace, layer: JbWatchLayer): Offset {
        return Offset(
            x = face.designWidth / 2f + layer.x,
            y = face.designHeight / 2f + layer.y
        )
    }

    fun resolveHitContentSize(
        face: JbWatchFace,
        layer: JbWatchLayer,
        nowMillis: Long
    ): Pair<Float, Float> {
        if (layer.type == "text") {
            val fallbackWidth = max(layer.text.length.coerceAtLeast(4), 1) * layer.textSize * 0.56f
            val fallbackHeight = layer.textSize * 1.35f
            val width = layer.width.takeIf { it > 0 }?.toFloat() ?: fallbackWidth.coerceAtLeast(1f)
            val height = layer.height.takeIf { it > 0 }?.toFloat() ?: fallbackHeight.coerceAtLeast(1f)
            return applyLayerScale(layer, width, height)
        }
        if (layer.type == "ring" || layer.type == "progress" || layer.type == "segments") {
            val outer = layer.radiusOuter.takeIf { it > 0f } ?: layer.radius.takeIf { it > 0f } ?: 210f
            return (outer * 2f).coerceAtLeast(1f) to (outer * 2f).coerceAtLeast(1f)
        }
        val bitmapPath = JbWatchTagEngine.resolveWatchPath(face.rootDir, layer, nowMillis)
        val bitmapBounds = bitmapPath?.let { JbWatchBitmapCache.bounds(it) }
        return resolveBitmapDisplaySize(layer, bitmapBounds?.first ?: 64, bitmapBounds?.second ?: 64)
    }

    private fun resolveBitmapDisplaySize(layer: JbWatchLayer, bitmapWidth: Int, bitmapHeight: Int): Pair<Float, Float> {
        val grid = if (layer.type == "image_cond") JbWatchTagEngine.resolveGrid(layer.condGrid) else 1 to 1
        val sourceWidth = (bitmapWidth / grid.first.toFloat()).coerceAtLeast(1f)
        val sourceHeight = (bitmapHeight / grid.second.toFloat()).coerceAtLeast(1f)
        val aspect = sourceWidth / sourceHeight.coerceAtLeast(1f)
        val baseWidth = when {
            layer.width > 0 -> layer.width.toFloat()
            layer.height > 0 -> layer.height * aspect
            else -> sourceWidth
        }.coerceAtLeast(1f)
        val baseHeight = when {
            layer.height > 0 -> layer.height.toFloat()
            layer.width > 0 -> layer.width / aspect.coerceAtLeast(0.001f)
            else -> sourceHeight
        }.coerceAtLeast(1f)
        return applyLayerScale(layer, baseWidth, baseHeight)
    }

    private fun applyLayerScale(layer: JbWatchLayer, width: Float, height: Float): Pair<Float, Float> {
        val scaleX = (layer.animScaleX.takeIf { it > 0f } ?: 100f) / 100f
        val scaleY = (layer.animScaleY.takeIf { it > 0f } ?: 100f) / 100f
        return (width * scaleX).coerceAtLeast(1f) to (height * scaleY).coerceAtLeast(1f)
    }

    private fun rotateLayerEval(canvas: AndroidCanvas, rect: RectF, rotationRaw: String, nowMillis: Long, batteryLevel: Int) {
        val rotation = JbWatchTagEngine.evaluateNumericExpression(rotationRaw, nowMillis, batteryLevel)
        if (rotation == 0f) return
        canvas.rotate(rotation, rect.centerX(), rect.centerY())
    }

    private fun resolveTextDisplaySize(layer: JbWatchLayer, text: String, paint: Paint): Pair<Float, Float> {
        val measuredWidth = paint.measureText(text).coerceAtLeast(1f)
        val measuredHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
        val baseWidth = layer.width.takeIf { it > 0 }?.toFloat() ?: measuredWidth
        val baseHeight = layer.height.takeIf { it > 0 }?.toFloat() ?: measuredHeight
        return applyLayerScale(layer, baseWidth, baseHeight)
    }

    fun readBitmapBounds(file: java.io.File): Pair<Int, Int>? {
        if (!file.isFile) return null
        return JbWatchBitmapCache.bounds(file)
    }
}
