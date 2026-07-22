package com.ailife.clox.cocos.bridge

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * Network + connectivity data for Lua bridge.
 * Categories: "network" (wifi/data), "connectivity" (bluetooth/gps)
 */
object NetworkHelper {

    fun networkJson(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        val wifiEnabled = wm.isWifiEnabled
        @Suppress("DEPRECATION")
        val wifiConnected = wm.connectionInfo?.networkId != -1
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
        @Suppress("DEPRECATION")
        val frequency = wm.connectionInfo?.frequency?.toString() ?: ""
        @Suppress("DEPRECATION")
        val signalLevel = WifiManager.calculateSignalLevel(wm.connectionInfo?.rssi ?: 0, 5)

        @Suppress("DEPRECATION")
        val ipInt = wm.connectionInfo?.ipAddress ?: 0
        val ip = if (ipInt != 0) {
            String.format("%d.%d.%d.%d", ipInt and 0xFF, (ipInt shr 8) and 0xFF,
                (ipInt shr 16) and 0xFF, (ipInt shr 24) and 0xFF)
        } else ""

        // Mobile data
        val activeNetwork = cm.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val dataConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val dataType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobile"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            else -> ""
        }

        return JSONObject().apply {
            put("wifiEnabled", wifiEnabled)
            put("wifiConnected", wifiConnected)
            put("wifiSsid", ssid)
            put("wifiFrequency", frequency)
            put("wifiSignal", signalLevel)
            put("wifiIp", ip)
            put("dataEnabled", true) // Assume enabled if we can check
            put("dataConnected", dataConnected)
            put("dataType", dataType)
            put("dataSignal", 0)
            put("dataOperator", "")
        }
    }

    fun connectivityJson(context: Context): JSONObject {
        val bluetoothEnabled = try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (_: Exception) { false }

        val gpsEnabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) != 0
        } catch (_: Exception) { false }

        return JSONObject().apply {
            put("bluetoothEnabled", bluetoothEnabled)
            put("bluetoothDevice", "")
            put("gpsEnabled", gpsEnabled)
        }
    }
}
