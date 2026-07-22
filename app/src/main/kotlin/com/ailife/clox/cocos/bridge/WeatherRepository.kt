package com.ailife.clox.cocos.bridge

import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Weather data provider using Open-Meteo API (free, no API key).
 * Data contract matches Lua WF.weather:
 *   currentTemp, highTemp, lowTemp, conditionText, conditionIcon,
 *   humidity, pressure, windSpeed, windDirection, cloudiness, rainVolume,
 *   isDaytime, sunrise, sunset, moonPhase, lastUpdate, locationName, tempUnit,
 *   hourly[], daily[]
 */
object WeatherRepository {

    private var latestJson: JSONObject? = null

    fun getLatestJson(): JSONObject? = latestJson

    suspend fun fetchWeather(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(latitude, longitude)
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val result = parseWeather(json, latitude, longitude)
            latestJson = result
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun buildUrl(lat: Double, lon: Double): String {
        return "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,cloud_cover" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,moon_phase" +
            "&hourly=temperature_2m,weather_code" +
            "&forecast_days=7&timezone=auto"
    }

    private fun parseWeather(json: JSONObject, lat: Double, lon: Double): JSONObject {
        val current = json.optJSONObject("current") ?: JSONObject()
        val daily = json.optJSONObject("daily") ?: JSONObject()
        val hourly = json.optJSONObject("hourly") ?: JSONObject()

        val temp = current.optDouble("temperature_2m", 0.0)
        val humidity = current.optInt("relative_humidity_2m", 0)
        val weatherCode = current.optInt("weather_code", 0)
        val windSpeed = current.optDouble("wind_speed_10m", 0.0)
        val windDir = current.optDouble("wind_direction_10m", 0.0)
        val pressure = current.optDouble("surface_pressure", 0.0)
        val cloudCover = current.optInt("cloud_cover", 0)

        // Daily high/low
        val tempsMax = daily.optJSONArray("temperature_2m_max")
        val tempsMin = daily.optJSONArray("temperature_2m_min")
        val highTemp = if (tempsMax != null && tempsMax.length() > 0) tempsMax.getDouble(0) else temp
        val lowTemp = if (tempsMin != null && tempsMin.length() > 0) tempsMin.getDouble(0) else temp

        // Sunrise/sunset
        val sunrises = daily.optJSONArray("sunrise")
        val sunsets = daily.optJSONArray("sunset")
        val sunrise = if (sunrises != null && sunrises.length() > 0) formatTime(sunrises.getString(0)) else ""
        val sunset = if (sunsets != null && sunsets.length() > 0) formatTime(sunsets.getString(0)) else ""

        // Moon phase
        val moonPhases = daily.optJSONArray("moon_phase")
        val moonPhase = if (moonPhases != null && moonPhases.length() > 0) moonPhases.getDouble(0) else 0.0

        // Is daytime
        val now = System.currentTimeMillis()
        val isDaytime = isDaytime(sunrise, sunset)

        // Hourly forecast (12 hours)
        val hourlyArray = JSONArray()
        val hTimes = hourly.optJSONArray("time")
        val hTemps = hourly.optJSONArray("temperature_2m")
        val hCodes = hourly.optJSONArray("weather_code")
        if (hTimes != null && hTemps != null) {
            val currentHour = SimpleDateFormat("HH", Locale.getDefault()).format(Date(now)).toInt()
            for (i in 0 until minOf(12, hTimes.length())) {
                val hourOfDay = (currentHour + i) % 24
                hourlyArray.put(JSONObject().apply {
                    put("temp", hTemps.optDouble(i, 0.0).toInt())
                    put("conditionText", codeToText(hCodes?.optInt(i, 0) ?: 0))
                    put("conditionIcon", hCodes?.optInt(i, 0) ?: 0)
                    put("hourOfDay", hourOfDay)
                })
            }
        }

        // Daily forecast (6 days)
        val dailyArray = JSONArray()
        val dCodes = daily.optJSONArray("weather_code")
        if (dCodes != null) {
            for (i in 0 until minOf(6, dCodes.length())) {
                dailyArray.put(JSONObject().apply {
                    put("avgTemp", ((tempsMax?.optDouble(i, 0.0) ?: 0.0) + (tempsMin?.optDouble(i, 0.0) ?: 0.0)) / 2)
                    put("high", tempsMax?.optDouble(i, 0.0) ?: 0.0)
                    put("low", tempsMin?.optDouble(i, 0.0) ?: 0.0)
                    put("conditionText", codeToText(dCodes.optInt(i, 0)))
                    put("conditionIcon", dCodes.optInt(i, 0))
                })
            }
        }

        return JSONObject().apply {
            put("currentTemp", temp.toInt())
            put("highTemp", highTemp.toInt())
            put("lowTemp", lowTemp.toInt())
            put("conditionText", codeToText(weatherCode))
            put("conditionIcon", weatherCode)
            put("humidity", humidity)
            put("pressure", pressure.toInt())
            put("windSpeed", windSpeed.toInt())
            put("windDirection", windDir.toInt())
            put("cloudiness", cloudCover)
            put("rainVolume", 0)
            put("isDaytime", isDaytime)
            put("sunrise", sunrise)
            put("sunset", sunset)
            put("moonPhase", moonPhase)
            put("lastUpdate", now)
            put("locationName", "")
            put("tempUnit", "C")
            put("hourly", hourlyArray)
            put("daily", dailyArray)
        }
    }

    private fun formatTime(isoTime: String): String {
        return try {
            val parts = isoTime.split("T")
            if (parts.size >= 2) parts[1].take(5) else ""
        } catch (_: Exception) { "" }
    }

    private fun isDaytime(sunrise: String, sunset: String): Boolean {
        if (sunrise.isBlank() || sunset.isBlank()) return true
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return now >= sunrise && now < sunset
    }

    /** WMO weather code → human-readable text */
    private fun codeToText(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }
}
