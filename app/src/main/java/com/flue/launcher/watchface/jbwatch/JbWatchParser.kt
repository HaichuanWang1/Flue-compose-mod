package com.flue.launcher.watchface.jbwatch

import android.util.Base64
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

object JbWatchParser {
    fun parse(rootDir: File): JbWatchFace {
        val watchXml = File(rootDir, "watch.xml")
        require(watchXml.isFile) { "缺少 watch.xml" }
        val root = parseRoot(watchXml)
        val pxmlFile = File(rootDir, "watch.pxml")
        val isProtected = root.protection == "y"
        val layersFile = if (isProtected) {
            require(pxmlFile.isFile) { "缺少 watch.pxml (protection=y)" }
            pxmlFile
        } else {
            watchXml
        }
        val layers = parseLayers(layersFile, rootDir)
        return JbWatchFace(
            root = root,
            layers = layers,
            rootDir = rootDir,
            xmlFile = watchXml,
            pxmlFile = pxmlFile,
            designWidth = JB_LAYOUT_CANVAS_WIDTH,
            designHeight = JB_LAYOUT_CANVAS_HEIGHT
        )
    }

    fun parseRoot(file: File): JbWatchRoot {
        val document = newSecureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(file)
        val root = document.documentElement ?: error("watch.xml 无根节点")
        return JbWatchRoot(
            name = sanitizeVisibleText(root.getAttribute("name")).ifBlank { file.parentFile?.name.orEmpty() },
            description = sanitizeVisibleText(root.getAttribute("description")),
            tags = sanitizeVisibleText(root.getAttribute("tags")),
            features = sanitizeVisibleText(root.getAttribute("features")),
            shape = sanitizeVisibleText(root.getAttribute("shape")),
            bgColor = sanitizeVisibleText(root.getAttribute("bg_color")).ifBlank { "000000" },
            ucolorDefault = sanitizeVisibleText(root.getAttribute("ucolor_default")).ifBlank { "ffffff" },
            mode3d = sanitizeVisibleText(root.getAttribute("mode_3d")).ifBlank { "0" },
            indLoc = sanitizeVisibleText(root.getAttribute("ind_loc")).ifBlank { "tc" },
            indBg = sanitizeVisibleText(root.getAttribute("ind_bg")).ifBlank { "Y" },
            hotwordLoc = sanitizeVisibleText(root.getAttribute("hotword_loc")).ifBlank { "tc" },
            hotwordBg = sanitizeVisibleText(root.getAttribute("hotword_bg")).ifBlank { "Y" },
            protection = sanitizeVisibleText(root.getAttribute("protection")).lowercase(Locale.ROOT)
        )
    }

    fun parseLayers(file: File, rootDir: File): List<JbWatchLayer> {
        val decoded = decodePxml(file)
        val document = newSecureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(decoded)))
        val layerNodes = document.getElementsByTagName("Layer")
        return buildList {
            for (index in 0 until layerNodes.length) {
                val element = layerNodes.item(index) as? Element ?: continue
                add(parseLayerElement(element))
            }
        }
    }

    private fun parseLayerElement(element: Element): JbWatchLayer {
        return JbWatchLayer(
            type = element.getAttribute("type").ifBlank { "image" },
            name = sanitizeVisibleText(element.getAttribute("name")).takeIf { it.isNotBlank() },
            x = element.getAttribute("x").toFloatOrZero(),
            y = element.getAttribute("y").toFloatOrZero(),
            gyro = element.getAttribute("gyro").toFloatOrZero(),
            rotation = element.getAttribute("rotation").ifBlank { "0" },
            skewX = element.getAttribute("skew_x").toFloatOrZero(),
            skewY = element.getAttribute("skew_y").toFloatOrZero(),
            opacity = element.getAttribute("opacity").toIntOrZero(default = 100),
            alignment = element.getAttribute("alignment").ifBlank { "cc" },
            path = sanitizeVisibleText(element.getAttribute("path")).takeIf { it.isNotBlank() },
            pathFrames = parsePaths(element.getAttribute("path")),
            width = element.getAttribute("width").toIntOrZero(),
            height = element.getAttribute("height").toIntOrZero(),
            gifDelay = element.getAttribute("gif_delay").toIntOrZero(),
            display = element.getAttribute("display").ifBlank { "bd" },
            text = sanitizeVisibleText(element.getAttribute("text")),
            textSize = element.getAttribute("text_size").toFloatOrZero(default = 24f),
            animScaleX = element.getAttribute("anim_scale_x").toFloatOrZero(default = 100f),
            animScaleY = element.getAttribute("anim_scale_y").toFloatOrZero(default = 100f),
            font = sanitizeVisibleText(element.getAttribute("font")).takeIf { it.isNotBlank() },
            transform = sanitizeVisibleText(element.getAttribute("transform")).ifBlank { "n" },
            colorDim = sanitizeVisibleText(element.getAttribute("color_dim")).ifBlank { "ffffff" },
            color = sanitizeVisibleText(element.getAttribute("color")).ifBlank { "ffffff" },
            outline = sanitizeVisibleText(element.getAttribute("outline")).takeIf { it.isNotBlank() },
            oColor = sanitizeVisibleText(element.getAttribute("o_color")).ifBlank { "000000" },
            oSize = element.getAttribute("o_size").toIntOrZero(),
            oOpacity = element.getAttribute("o_opacity").toIntOrZero(),
            tapAction = sanitizeVisibleText(element.getAttribute("tap_action")).takeIf { it.isNotBlank() },
            condValue = sanitizeVisibleText(element.getAttribute("cond_value")).takeIf { it.isNotBlank() },
            condGrid = sanitizeVisibleText(element.getAttribute("cond_grid")).takeIf { it.isNotBlank() },
            weatherNight = sanitizeVisibleText(element.getAttribute("weather_night")).takeIf { it.isNotBlank() },
            radiusOuter = element.getAttribute("radius_outer").toFloatOrZero(),
            radiusInner = element.getAttribute("radius_inner").toFloatOrZero(),
            radius = element.getAttribute("radius").toFloatOrZero(),
            angle = sanitizeVisibleText(element.getAttribute("angle")).ifBlank { "0" },
            angleTotal = element.getAttribute("angle_total").toFloatOrZero(),
            isClockwise = !element.getAttribute("is_clockwise").equals("N", ignoreCase = true),
            color2 = sanitizeVisibleText(element.getAttribute("color2")).ifBlank { "ffffff" },
            color3 = sanitizeVisibleText(element.getAttribute("color3")).ifBlank { "ffffff" },
            outsideOpacity = element.getAttribute("outside_opacity").toIntOrZero(default = 100),
            curveDir = sanitizeVisibleText(element.getAttribute("curve_dir")).ifBlank { "Up" },
            shader = sanitizeVisibleText(element.getAttribute("shader")).takeIf { it.isNotBlank() },
            shadowType = sanitizeVisibleText(element.getAttribute("shadow")).takeIf { it.isNotBlank() },
            shadowColor = sanitizeVisibleText(element.getAttribute("shadow_color")).ifBlank { "000000" },
            shadowRadius = element.getAttribute("shadow_radius").toIntOrZero(),
            shadowOffsetX = element.getAttribute("shadow_offset_x").toIntOrZero(),
            shadowOffsetY = element.getAttribute("shadow_offset_y").toIntOrZero(),
            parentId = sanitizeVisibleText(element.getAttribute("parentId")).takeIf { it.isNotBlank() },
            themeSetIndex = element.getAttribute("themeSet").toIntOrZero(),
            animIn = sanitizeVisibleText(element.getAttribute("anim_in")).takeIf { it.isNotBlank() },
            animOut = sanitizeVisibleText(element.getAttribute("anim_out")).takeIf { it.isNotBlank() },
            durIn = element.getAttribute("dur_in").toIntOrZero(),
            durOn = element.getAttribute("dur_on").toIntOrZero(),
            durOut = element.getAttribute("dur_out").toIntOrZero(),
            durOff = element.getAttribute("dur_off").toIntOrZero(),
            delayStart = element.getAttribute("delay_start").toIntOrZero(),
            repeatCount = element.getAttribute("repeat_count").toIntOrZero(),
            restartOnLoad = element.getAttribute("restart_on_load").equals("Y", ignoreCase = true),
            restartOnBright = element.getAttribute("restart_on_bright").equals("Y", ignoreCase = true),
            restartOnTextChange = element.getAttribute("restart_on_text_change").equals("Y", ignoreCase = true)
        )
    }

    fun decodePxml(file: File): String {
        val bytes = file.readBytes()
        val rawText = detectAndDecode(bytes).trim()
        if (rawText.startsWith("<Watch")) return sanitizeJbXml(rawText)
        val decodeMap = JB_CUSTOM_BASE64.mapIndexed { i, c -> c to JB_STANDARD_BASE64[i] }.toMap()
        val swapped = rawText.map { decodeMap[it] ?: it }.joinToString("")
        return runCatching {
            sanitizeJbXml(Base64.decode(swapped, Base64.DEFAULT).toString(Charsets.UTF_8))
        }.recoverCatching {
            val std = rawText.filter { ch -> ch in JB_STANDARD_BASE64 || ch == '=' }
            sanitizeJbXml(Base64.decode(std, Base64.DEFAULT).toString(Charsets.UTF_8))
        }.getOrElse { error("watch.pxml 解码失败") }
    }

    private fun detectAndDecode(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        if ('\uFFFD' !in utf8) return utf8
        return bytes.toString(Charsets.ISO_8859_1)
    }

    fun sanitizeJbXml(raw: String): String {
        return raw.filter { ch ->
            ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20
        }.trim()
    }

    fun isWellFormedJbXml(xml: String): Boolean {
        return runCatching {
            val document = newSecureDocumentBuilderFactory()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            document.documentElement?.tagName == "Watch"
        }.getOrDefault(false)
    }

    fun sanitizeVisibleText(raw: String?): String {
        return raw.orEmpty()
            .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }
            .replace("�", "")
            .trim()
    }

    fun parsePaths(raw: String?): List<String> {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank()) return emptyList()
        return normalized
            .split('`', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun String.toFloatOrZero(default: Float = 0f): Float = toFloatOrNull() ?: default
    fun String.toIntOrZero(default: Int = 0): Int = toIntOrNull() ?: default

    fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
    }
}
