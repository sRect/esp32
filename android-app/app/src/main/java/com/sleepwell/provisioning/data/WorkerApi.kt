package com.sleepwell.provisioning.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WorkerApi(
    baseUrl: String,
    private val apiToken: String,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    fun sendDisplayMessage(
        deviceId: String,
        text: String,
        displayDurationMs: Int = 10_000,
        buzzerDurationMs: Int = 3_000,
    ): MessageDeliveryResult {
        if (apiToken.isBlank()) {
            throw WorkerApiException(
                "APP_API_TOKEN_MISSING",
                "App 尚未配置 Worker API Token",
            )
        }

        val encodedDeviceId = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val url = URL("$normalizedBaseUrl/api/v1/devices/$encodedDeviceId/messages")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiToken")

            val body = JSONObject()
                .put("text", text)
                .put("displayDurationMs", displayDurationMs)
                .put("buzzerDurationMs", buzzerDurationMs)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (statusCode !in 200..299) {
                val error = response.optJSONObject("error")
                throw WorkerApiException(
                    error?.optString("code")?.ifBlank { "HTTP_$statusCode" } ?: "HTTP_$statusCode",
                    error?.optString("message")?.ifBlank { "消息发送失败 ($statusCode)" }
                        ?: "消息发送失败 ($statusCode)",
                )
            }

            return MessageDeliveryResult(
                messageId = response.optString("messageId"),
                deviceId = response.optString("deviceId", deviceId),
                accepted = response.optBoolean("accepted"),
            ).also {
                if (!it.accepted || it.messageId.isBlank()) {
                    throw WorkerApiException("INVALID_WORKER_RESPONSE", "Worker 未确认接收消息")
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
