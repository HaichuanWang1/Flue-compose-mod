package com.flue.launcher.watchface.jbwatch

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object JbWatchTagEngine {

    fun buildTagMap(
        nowMillis: Long,
        batteryLevel: Int,
        is24Hour: Boolean,
        ucolor: String = "ffffff",
        ucolor2: String = "ffffff",
        ucolor3: String = "ffffff"
    ): Map<String, String> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val second = cal.get(Calendar.SECOND)
        val minute = cal.get(Calendar.MINUTE)
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val hour12Val = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val hourTens = if (is24Hour) (hour24 / 10).toString() else if (hour12Val >= 10) "1" else ""
        val hourOnes = if (is24Hour) (hour24 % 10).toString() else (hour12Val % 10).toString()
        val minuteFloat = minute + second / 60f
        val hourFloat = (hour24 % 12) + minuteFloat / 60f
        val secondFloat = second + cal.get(Calendar.MILLISECOND) / 1_000f
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val dayOfWeekZero = (dayOfWeek - Calendar.SUNDAY).coerceIn(0, 6)
        val weekdayShort = SimpleDateFormat("EEE", Locale.getDefault()).format(nowMillis)
        val weekdayLong = SimpleDateFormat("EEEE", Locale.getDefault()).format(nowMillis)
        val monthShort = SimpleDateFormat("MMM", Locale.getDefault()).format(nowMillis)
        val monthLong = SimpleDateFormat("MMMM", Locale.getDefault()).format(nowMillis)
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val ampmLower = if (cal.get(Calendar.AM_PM) == Calendar.AM) "am" else "pm"

        val weekdays = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val weekdayName = weekdays[dayOfWeek - 1]
        val stepCount = 0

        val timeFormat = if (is24Hour) "HH:mm" else "hh:mm"
        val timeStr = SimpleDateFormat(timeFormat, Locale.getDefault()).format(nowMillis)

        return buildMap {
            // ===== Time tags =====
            put("{dh}", hourFloat.toString())
            put("{dh23}", hour24.toString())
            put("{dh23z}", hour24.toString().padStart(2, '0'))
            put("{dh12}", hour12Val.toString())
            put("{dh12z}", hour12Val.toString().padStart(2, '0'))
            put("{dm}", minuteFloat.toString())
            put("{dmz}", minute.toString().padStart(2, '0'))
            put("{ds}", secondFloat.toString())
            put("{dsz}", second.toString().padStart(2, '0'))
            put("{dss}", second.toString())
            put("{drh}", (hourFloat * 30f).toString())
            put("{drm}", (minuteFloat * 6f).toString())
            put("{drss}", (secondFloat * 6f).toString())
            put("{drs}", (secondFloat * 6f).toString())
            put("{time}", timeStr)
            put("{tk}", timeStr)

            // ===== Digital clock digit tags =====
            put("{dhtt}", hourTens)
            put("{dhto}", hourOnes)
            put("{dmt}", (minute / 10).toString())
            put("{dmo}", (minute % 10).toString())

            // ===== Date tags =====
            put("{dd}", dayOfMonth.toString().padStart(2, '0'))
            put("{dnn}", dayOfMonth.toString())
            put("{ddd}", dayOfYear.toString().padStart(3, '0'))
            put("{ddm}", month.toString().padStart(2, '0'))
            put("{dmm}", monthShort)
            put("{dmmm}", monthLong)
            put("{dmon}", month.toString())
            put("{dyy}", (year % 100).toString().padStart(2, '0'))
            put("{dyyy}", year.toString())
            put("{ddw}", weekdayShort)
            put("{ddw2}", weekdayShort)
            put("{ddw0}", dayOfWeekZero.toString())
            put("{dwd}", weekdayName)
            put("{ddwn}", weekdayLong)

            // ===== AM/PM =====
            put("{dampm}", ampm)
            put("{dampl}", ampmLower)

            // ===== Battery tags =====
            put("{bl}", batteryLevel.toString())
            put("{blp}", batteryLevel.toString())
            put("{bll}", batteryLevel.toString())
            put("{pblp}", batteryLevel.toString())
            put("{blm}", "0") // battery minutes remaining (not implemented)
            put("{bc}", if (batteryLevel > 0) "0" else "0") // battery charging (hardcoded not charging)

            // ===== Health tags (stubs) =====
            put("{ssc}", stepCount.toString())
            put("{sscs}", stepCount.toString())
            put("{sscg}", stepCount.toString())
            put("{sscgoal}", "10000")
            put("{sscp}", "0")
            put("{shr}", "--")
            put("{shrb}", "--")
            put("{scal}", "0")
            put("{sdist}", "0")
            put("{sslp}", "0")
            put("{sslp_time}", "0")
            put("{sslp_goal}", "8")

            // ===== Weather tags (stubs) =====
            put("{wci}", "01d")
            put("{wct}", "1")
            put("{wt}", "--")
            put("{wtd}", "--°C")
            put("{wtld}", "--°C")
            put("{wthd}", "--°C")
            put("{wh}", "--")
            put("{wws}", "--")
            put("{wwd}", "--")
            put("{wlv}", "--")
            put("{wpr}", "--")
            put("{wdr}", "--")
            put("{wsr}", "--")
            put("{wss}", "--")
            put("{wmo}", "--")
            put("{wmph}", "--")

            // ===== Sensor tags (stubs) =====
            put("{sgx}", "0")
            put("{sgy}", "0")
            put("{sgz}", "0")
            put("{sax}", "0")
            put("{say}", "0")
            put("{saz}", "0")
            put("{scr}", "0")
            put("{spr}", "0")

            // ===== Color theme tags =====
            put("{ucolor}", ucolor)
            put("{ucolor2}", ucolor2)
            put("{ucolor3}", ucolor3)
            put("{uc}", ucolor)
            put("{uc2}", ucolor2)
            put("{uc3}", ucolor3)

            // ===== System info tags =====
            put("{aname}", android.os.Build.MODEL)
            put("{amodel}", android.os.Build.MODEL)
            put("{amanufacturer}", android.os.Build.MANUFACTURER)
            put("{aosv}", android.os.Build.VERSION.RELEASE)
            put("{asdk}", android.os.Build.VERSION.SDK_INT.toString())

            // ===== Stopwatch tags (stubs, always zero) =====
            put("{swh}", "0")
            put("{swm}", "0")
            put("{sws}", "0")
            put("{swms}", "0")
            put("{swr}", "0")
            put("{swt}", "0")
        }
    }

    private val tagPattern = Regex("""\{([^}]+)\}""")
    private val conditionalPattern = Regex("""\{(\w+)\}\s*(<=|>=|<|>|==|!=)\s*([\d.]+)\s+and\s+'([^']*)'""")
    private val fallbackOrPattern = Regex("""\bor\s+'([^']*)'\s*$""")

    fun resolveText(raw: String, nowMillis: Long, batteryLevel: Int, is24Hour: Boolean): String {
        val tagMap = buildTagMap(nowMillis, batteryLevel, is24Hour)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val dayOfWeekZero = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
        val source = resolveConditionalText(raw, tagMap)
            ?: resolveDayConditionalText(raw, dayOfWeekZero)
            ?: raw
        return tagPattern.replace(source) { match ->
            val tag = "{" + match.groupValues[1] + "}"
            tagMap[tag] ?: match.value
        }
    }

    private fun resolveConditionalText(raw: String, tagMap: Map<String, String>): String? {
        if (" and '" !in raw) return null
        val matches = conditionalPattern.findAll(raw)
        if (!matches.iterator().hasNext()) return null
        for (match in matches) {
            val tag = "{${match.groupValues[1]}}"
            val op = match.groupValues[2]
            val valueStr = match.groupValues[3]
            val result = match.groupValues[4]
            val tagValue = tagMap[tag]?.toFloatOrNull() ?: 0f
            val compareValue = valueStr.toFloatOrNull() ?: continue
            val matched = when (op) {
                "<" -> tagValue < compareValue
                ">" -> tagValue > compareValue
                "<=" -> tagValue <= compareValue
                ">=" -> tagValue >= compareValue
                "==" -> tagValue == compareValue
                "!=" -> tagValue != compareValue
                else -> false
            }
            if (matched) return result
        }
        return fallbackOrPattern.find(raw)?.groupValues?.getOrNull(1)
    }

    private fun resolveDayConditionalText(raw: String, dayOfWeekZero: Int): String? {
        if ("{ddw0}" !in raw && " and " !in raw && " or " !in raw) return null
        Regex("\\{ddw0\\}\\s*==\\s*(\\d+)\\s*and\\s*'([^']*)'")
            .findAll(raw)
            .forEach { match ->
                if (match.groupValues.getOrNull(1)?.toIntOrNull() == dayOfWeekZero) {
                    return match.groupValues.getOrNull(2).orEmpty()
                }
            }
        return Regex("or\\s*'([^']*)'\\s*$").find(raw)?.groupValues?.getOrNull(1)
    }

    fun evaluateNumericExpression(raw: String, nowMillis: Long, batteryLevel: Int): Float {
        val tagMap = buildTagMap(nowMillis, batteryLevel, true)
        val resolved = tagPattern.replace(raw) { match ->
            val tag = "{" + match.groupValues[1] + "}"
            tagMap[tag] ?: match.value
        }.replace(" ", "")
        return evaluateArithmetic(resolved)
    }

    private fun evaluateArithmetic(raw: String): Float {
        if (raw.isBlank()) return 0f
        val normalized = raw.replace(',', '.')
        normalized.toFloatOrNull()?.let { return it }
        val tokens = Regex("""[*/+-]?[^*/+-]+""").findAll(normalized).map { it.value }.toList()
        if (tokens.isEmpty()) return normalized.filter { it.isDigit() || it == '.' || it == '-' }
            .toFloatOrNull() ?: 0f
        var result = tokens.first().toFloatOrNull() ?: return 0f
        var cursor = tokens.first().length
        while (cursor < normalized.length) {
            val op = normalized[cursor]
            cursor += 1
            val nextStart = cursor
            while (cursor < normalized.length && normalized[cursor] !in charArrayOf('*', '/', '+', '-')) {
                cursor += 1
            }
            val number = normalized.substring(nextStart, cursor).toFloatOrNull() ?: continue
            result = when (op) {
                '*' -> result * number
                '/' -> if (number != 0f) result / number else result
                '+' -> result + number
                '-' -> result - number
                else -> result
            }
        }
        return result
    }

    fun shouldDisplayLayer(layer: JbWatchLayer, isDim: Boolean): Boolean {
        if (layer.display == "b" && isDim) return false
        if (layer.display == "d" && !isDim) return false
        val expr = layer.condValue?.takeIf { it.isNotBlank() } ?: return true
        return when {
            "{wci}" in expr -> evaluateWeatherCondition(expr, weatherCode = "01d")
            else -> true
        }
    }

    fun evaluateWeatherCondition(expr: String, weatherCode: String): Boolean {
        return evaluateWeatherIndex(expr, weatherCode) >= 0
    }

    fun evaluateWeatherIndex(expr: String?, weatherCode: String): Int {
        val value = expr?.takeIf { it.isNotBlank() } ?: return 0
        val code = weatherCode.lowercase(Locale.ROOT)
        Regex("'\\{wci\\}'\\s*==\\s*'([^']+)'\\s*and\\s*(\\d+)").findAll(value).forEach { match ->
            val codeMatch = match.groupValues.getOrNull(1)?.lowercase(Locale.ROOT)
            val numberMatch = match.groupValues.getOrNull(2)?.toIntOrNull()
            if (codeMatch == code && numberMatch != null) {
                return (numberMatch - 1).coerceAtLeast(0)
            }
        }
        if ("and 1" in value) return 0
        return 0
    }

    fun resolveGrid(raw: String?): Pair<Int, Int> {
        val grid = raw?.lowercase(Locale.ROOT)?.split('x') ?: emptyList()
        val cols = grid.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val rows = grid.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return cols to rows
    }

    fun resolveLayerOpacity(layer: JbWatchLayer): Int {
        return layer.opacity.coerceIn(0, 100) * 255 / 100
    }

    fun parseColor(raw: String): Int {
        val hex = raw.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: "ffffff"
        return runCatching { android.graphics.Color.parseColor("#$hex") }.getOrElse { android.graphics.Color.WHITE }
    }

    fun resolveWatchPath(rootDir: File, layer: JbWatchLayer, nowMillis: Long): File? {
        val rawPath = layer.path
        val normalized = rawPath?.trim().orEmpty()
        if (normalized.isBlank()) return null
        val candidates = if (layer.pathFrames.isNotEmpty()) {
            val frameDelayMs = layer.gifDelay.takeIf { it > 0 } ?: 120
            val frameStep = ((nowMillis / frameDelayMs).toInt()).coerceAtLeast(0)
            listOf(layer.pathFrames[frameStep.mod(layer.pathFrames.size)])
        } else {
            normalized.split('`').map { it.trim() }.filter { it.isNotBlank() }
        }
        candidates.forEach { candidate ->
            val normalizedCandidate = candidate.replace('\\', '/')
            val candidateVariants = listOf(
                normalizedCandidate,
                normalizedCandidate.removePrefix(".")
            ).distinct()
            candidateVariants.forEach { variant ->
                val direct = File(rootDir, variant)
                if (direct.isFile) return direct
                val inImages = File(rootDir, "images/$variant")
                if (inImages.isFile) return inImages
                val inFonts = File(rootDir, "fonts/$variant")
                if (inFonts.isFile) return inFonts
            }
        }
        return rootDir.walkTopDown().firstOrNull { file ->
            val name = candidates.firstOrNull()?.substringAfterLast('/') ?: ""
            file.isFile && (
                file.name.equals(name, ignoreCase = true) ||
                    file.name.equals(name.removePrefix("."), ignoreCase = true)
                )
        }
    }

    fun jbAlignmentAnchor(alignment: String): Pair<Float, Float> {
        return when (alignment.lowercase(Locale.ROOT)) {
            "cr" -> 1f to 0.5f
            "cl" -> 0f to 0.5f
            "br" -> 1f to 1f
            "bc" -> 0.5f to 1f
            "bl" -> 0f to 1f
            "tr" -> 1f to 0f
            "tc" -> 0.5f to 0f
            "tl" -> 0f to 0f
            else -> 0.5f to 0.5f
        }
    }

    fun jbAlignmentHorizontal(alignment: String): Float {
        return jbAlignmentAnchor(alignment).first
    }
}
