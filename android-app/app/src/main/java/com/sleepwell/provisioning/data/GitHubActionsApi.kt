package com.sleepwell.provisioning.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubActionsApi(
    private val owner: String,
    private val repository: String,
    private val workflowFile: String,
    private val ref: String,
    private val token: String,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val API_VERSION = "2022-11-28"
    }

    fun dispatchDisplayMessage(
        messageId: String,
        deviceId: String,
        text: String,
        sentAtMillis: Long,
        displayDurationMs: Int = 10_000,
        buzzerDurationMs: Int = 3_000,
    ): MessageDeliveryResult {
        if (token.isBlank()) {
            throw GitHubActionsApiException(
                "GITHUB_TOKEN_MISSING",
                "App 尚未配置 GitHub Actions Token",
            )
        }
        if (owner.isBlank() || repository.isBlank() || workflowFile.isBlank() || ref.isBlank()) {
            throw GitHubActionsApiException(
                "GITHUB_CONFIG_MISSING",
                "GitHub Actions 发送配置不完整",
            )
        }

        val encodedOwner = java.net.URLEncoder.encode(owner, Charsets.UTF_8.name())
        val encodedRepository = java.net.URLEncoder.encode(repository, Charsets.UTF_8.name())
        val encodedWorkflow = java.net.URLEncoder.encode(workflowFile, Charsets.UTF_8.name())
        val url = URL(
            "https://api.github.com/repos/$encodedOwner/$encodedRepository/actions/workflows/" +
                "$encodedWorkflow/dispatches",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            connection.setRequestProperty("User-Agent", "esp32-provisioning-android")

            val inputs = JSONObject()
                .put("message_id", messageId)
                .put("device_id", deviceId)
                .put("text", text)
                .put("sent_at_ms", sentAtMillis.toString())
                .put("display_duration_ms", displayDurationMs.toString())
                .put("buzzer_duration_ms", buzzerDurationMs.toString())
            val body = JSONObject()
                .put("ref", ref)
                .put("inputs", inputs)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val responseText = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                val githubMessage = runCatching {
                    JSONObject(responseText).optString("message")
                }.getOrNull().orEmpty()
                throw GitHubActionsApiException(
                    "GITHUB_HTTP_$statusCode",
                    githubMessage.ifBlank { "GitHub Actions 触发失败 ($statusCode)" },
                )
            }

            return MessageDeliveryResult(
                messageId = messageId,
                deviceId = deviceId,
                accepted = true,
            )
        } finally {
            connection.disconnect()
        }
    }
}
