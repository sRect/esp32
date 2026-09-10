package com.sleepwell.provisioning.data

import android.net.Network
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Esp32Api(
    private val network: Network? = null,
    private val ble: EspBleConnector? = null,
) {
    val usesBluetooth: Boolean get() = ble != null

    companion object {
        private const val BASE_URL = "http://192.168.4.1/api/v1"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 25_000
    }

    fun getDeviceInfo(): DeviceInfo {
        val json = request("GET", "/device")
        return DeviceInfo(
            deviceModel = json.optString("deviceModel"),
            deviceId = json.optString("deviceId"),
            firmwareVersion = json.optString("firmwareVersion"),
            provisioningState = json.optString("provisioningState"),
            hasProvisionedBefore = json.optBoolean("hasProvisionedBefore"),
            wifiMac = json.optString("wifiMac"),
            apSsid = json.optString("apSsid"),
            provisioningToken = json.optString("provisioningToken"),
        ).also {
            if (it.provisioningToken.isBlank()) {
                throw ProvisioningApiException("TOKEN_MISSING", "设备未返回配网令牌")
            }
        }
    }

    fun scanWifi(token: String): List<WifiNetwork> {
        val json = request("GET", "/wifi/scan", token)
        val result = mutableListOf<WifiNetwork>()
        val networks = json.optJSONArray("networks") ?: return emptyList()
        for (index in 0 until networks.length()) {
            val item = networks.getJSONObject(index)
            val ssid = item.optString("ssid")
            if (ssid.isNotBlank()) {
                result += WifiNetwork(
                    ssid = ssid,
                    rssi = item.optInt("rssi"),
                    channel = item.optInt("channel"),
                    security = item.optString("security", "unknown"),
                    hidden = item.optBoolean("hidden"),
                )
            }
        }
        return result
            .distinctBy { it.ssid }
            .sortedByDescending { it.rssi }
    }

    fun submitWifi(token: String, ssid: String, password: String) {
        val body = JSONObject()
            .put("ssid", ssid)
            .put("password", password)
        request("POST", "/wifi/config", token, body)
    }

    fun getWifiStatus(token: String): WifiStatus {
        val json = request("GET", "/wifi/status", token)
        val error = json.optJSONObject("error")
        return WifiStatus(
            state = json.optString("provisioningState"),
            connected = json.optBoolean("connected"),
            connectedSsid = json.optString("connectedSsid"),
            stationIp = json.optString("stationIp"),
            errorCode = error?.optString("code")?.takeIf { it.isNotBlank() },
            errorMessage = error?.optString("message")?.takeIf { it.isNotBlank() },
        )
    }

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): JSONObject {
        ble?.let { return it.request(method, path, token, body) }
        Log.i("EspWifiProvisioning", "Request $method $path network=$network")
        val connection = requireNotNull(network).openConnection(URL(BASE_URL + path)) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            token?.let { connection.setRequestProperty("X-Provisioning-Token", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }

            val statusCode = connection.responseCode
            Log.i("EspWifiProvisioning", "Response $path status=$statusCode")
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (statusCode !in 200..299) {
                val error = response.optJSONObject("error")
                throw ProvisioningApiException(
                    error?.optString("code")?.ifBlank { "HTTP_$statusCode" } ?: "HTTP_$statusCode",
                    error?.optString("message")?.ifBlank { "设备请求失败 ($statusCode)" }
                        ?: "设备请求失败 ($statusCode)",
                )
            }
            return response
        } catch (error: java.io.IOException) {
            Log.w("EspWifiProvisioning", "Request $path failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
