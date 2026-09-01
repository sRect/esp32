package com.sleepwell.provisioning

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepwell.provisioning.data.DeviceInfo
import com.sleepwell.provisioning.data.Esp32Api
import com.sleepwell.provisioning.data.EspWifiConnector
import com.sleepwell.provisioning.data.GitHubActionsApi
import com.sleepwell.provisioning.data.GitHubActionsApiException
import com.sleepwell.provisioning.data.ProvisioningApiException
import com.sleepwell.provisioning.data.WifiNetwork
import com.sleepwell.provisioning.data.WorkerApi
import com.sleepwell.provisioning.data.WorkerApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ProvisioningPage {
    INTRO,
    CONNECTING_DEVICE,
    WIFI_LIST,
    CONNECTING_ROUTER,
    SUCCESS,
    MESSAGE,
}

enum class MessageDeliveryChannel {
    CLOUDFLARE_WORKER,
    GITHUB_ACTIONS,
}

data class ProvisioningUiState(
    val page: ProvisioningPage = ProvisioningPage.INTRO,
    val deviceSsid: String = "esp32-c9c",
    val deviceInfo: DeviceInfo? = null,
    val networks: List<WifiNetwork> = emptyList(),
    val selectedNetwork: WifiNetwork? = null,
    val routerPassword: String = "",
    val showSwitchWifiConfirmation: Boolean = false,
    val isRefreshing: Boolean = false,
    val statusText: String = "",
    val errorMessage: String? = null,
    val messageText: String = "",
    val messageDeliveryChannel: MessageDeliveryChannel = MessageDeliveryChannel.CLOUDFLARE_WORKER,
    val isSendingMessage: Boolean = false,
    val messageStatus: String? = null,
    val recentMessages: List<SentMessage> = emptyList(),
)

data class SentMessage(
    val messageId: String,
    val text: String,
    val sentAtMillis: Long,
)

class ProvisioningViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val MESSAGE_HISTORY_PREFERENCES = "message-history"
        private const val MESSAGE_HISTORY_KEY = "recent-messages"
        private const val MAX_RECENT_MESSAGES = 10
    }

    private val connector = EspWifiConnector(application)
    private val workerApi = WorkerApi(BuildConfig.WORKER_BASE_URL, BuildConfig.WORKER_API_TOKEN)
    private val githubActionsApi = GitHubActionsApi(
        owner = BuildConfig.GITHUB_ACTIONS_OWNER,
        repository = BuildConfig.GITHUB_ACTIONS_REPOSITORY,
        workflowFile = BuildConfig.GITHUB_ACTIONS_WORKFLOW,
        ref = BuildConfig.GITHUB_ACTIONS_REF,
        token = BuildConfig.GITHUB_ACTIONS_TOKEN,
    )
    private val messageHistoryPreferences = application.getSharedPreferences(
        MESSAGE_HISTORY_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private var api: Esp32Api? = null
    private var token: String = ""

    private val _uiState = MutableStateFlow(
        ProvisioningUiState(recentMessages = loadRecentMessages()),
    )
    val uiState: StateFlow<ProvisioningUiState> = _uiState.asStateFlow()

    fun setDeviceSsid(value: String) {
        _uiState.update { it.copy(deviceSsid = value.trim(), errorMessage = null) }
    }

    fun setRouterPassword(value: String) {
        _uiState.update { it.copy(routerPassword = value, errorMessage = null) }
    }

    fun setMessageText(value: String) {
        if (value.codePointCount(0, value.length) <= 120) {
            _uiState.update {
                it.copy(messageText = value, messageStatus = null, errorMessage = null)
            }
        }
    }

    fun setMessageDeliveryChannel(channel: MessageDeliveryChannel) {
        if (_uiState.value.isSendingMessage) return
        _uiState.update {
            it.copy(
                messageDeliveryChannel = channel,
                messageStatus = null,
                errorMessage = null,
            )
        }
    }

    fun openMessagePage() {
        _uiState.update {
            it.copy(
                page = ProvisioningPage.MESSAGE,
                messageStatus = null,
                errorMessage = null,
            )
        }
    }

    fun closeMessagePage() {
        _uiState.update {
            it.copy(
                page = ProvisioningPage.INTRO,
                messageStatus = null,
                errorMessage = null,
            )
        }
    }

    fun sendMessage() {
        val current = _uiState.value
        val text = current.messageText.trim()
        if (text.isEmpty()) {
            return showMessage("请输入要发送的文字")
        }
        if (text.codePointCount(0, text.length) > 120) {
            return showMessage("文字不能超过 120 个字符")
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isSendingMessage = true, messageStatus = null, errorMessage = null)
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    when (current.messageDeliveryChannel) {
                        MessageDeliveryChannel.CLOUDFLARE_WORKER ->
                            workerApi.sendDisplayMessage(BuildConfig.DEVICE_ID, text)
                        MessageDeliveryChannel.GITHUB_ACTIONS -> {
                            val now = System.currentTimeMillis()
                            githubActionsApi.dispatchDisplayMessage(
                                messageId = UUID.randomUUID().toString(),
                                deviceId = BuildConfig.DEVICE_ID,
                                text = text,
                                sentAtMillis = now,
                            )
                        }
                    }
                }
            }.onSuccess { result ->
                val recentMessages = listOf(
                    SentMessage(
                        messageId = result.messageId,
                        text = text,
                        sentAtMillis = System.currentTimeMillis(),
                    ),
                ) + _uiState.value.recentMessages.take(MAX_RECENT_MESSAGES - 1)
                saveRecentMessages(recentMessages)
                _uiState.update {
                    it.copy(
                        messageText = "",
                        isSendingMessage = false,
                        messageStatus = when (current.messageDeliveryChannel) {
                            MessageDeliveryChannel.CLOUDFLARE_WORKER ->
                                "发送成功 · ${result.messageId.take(8)}"
                            MessageDeliveryChannel.GITHUB_ACTIONS ->
                                "已提交到 GitHub Actions 队列 · ${result.messageId.take(8)}"
                        },
                        recentMessages = recentMessages,
                    )
                }
            }.onFailure { throwable ->
                val message = when (throwable) {
                    is WorkerApiException -> translateWorkerError(throwable.code, throwable.message)
                    is GitHubActionsApiException ->
                        translateGitHubActionsError(throwable.code, throwable.message)
                    else -> throwable.message ?: "消息发送失败，请检查手机网络"
                }
                _uiState.update {
                    it.copy(isSendingMessage = false, messageStatus = null, errorMessage = message)
                }
            }
        }
    }

    fun permissionDenied() {
        _uiState.update {
            it.copy(errorMessage = "需要“附近设备/Wi-Fi”权限才能请求连接 ESP32 热点")
        }
    }

    fun connectToDevice() {
        val current = _uiState.value
        if (!Regex("^esp32-[0-9a-fA-F]{3}$").matches(current.deviceSsid)) {
            _uiState.update { it.copy(errorMessage = "设备热点名称格式应为 esp32-xxx") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    page = ProvisioningPage.CONNECTING_DEVICE,
                    statusText = "等待系统确认连接 ${current.deviceSsid}",
                    errorMessage = null,
                )
            }
            runCatching {
                val network = connector.connect(current.deviceSsid)
                _uiState.update { it.copy(statusText = "已连接设备，正在读取信息") }
                val newApi = Esp32Api(network)
                val info = withContext(Dispatchers.IO) { newApi.getDeviceInfo() }
                api = newApi
                token = info.provisioningToken
                info
            }.onSuccess { info ->
                _uiState.update {
                    it.copy(
                        deviceInfo = info,
                        showSwitchWifiConfirmation = info.hasProvisionedBefore,
                        statusText = if (info.hasProvisionedBefore) "设备已有 Wi-Fi 配置" else "正在扫描附近 Wi-Fi",
                    )
                }
                if (!info.hasProvisionedBefore) scanNetworks()
            }.onFailure(::showFailure)
        }
    }

    fun confirmSwitchWifi() {
        _uiState.update { it.copy(showSwitchWifiConfirmation = false) }
        scanNetworks()
    }

    fun cancelSwitchWifi() {
        connector.disconnect()
        api = null
        token = ""
        _uiState.update {
            ProvisioningUiState(
                deviceSsid = it.deviceSsid,
                statusText = "已取消切换 Wi-Fi",
                recentMessages = it.recentMessages,
            )
        }
    }

    fun scanNetworks() {
        val currentApi = api ?: return showMessage("尚未连接到 ESP32")
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    page = ProvisioningPage.WIFI_LIST,
                    isRefreshing = true,
                    statusText = "正在扫描 2.4GHz Wi-Fi",
                    errorMessage = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) { currentApi.scanWifi(token) }
            }.onSuccess { networks ->
                _uiState.update {
                    it.copy(
                        networks = networks,
                        isRefreshing = false,
                        statusText = if (networks.isEmpty()) "未发现 Wi-Fi，请刷新重试" else "选择设备要连接的 2.4GHz Wi-Fi",
                    )
                }
            }.onFailure(::showFailure)
        }
    }

    fun selectNetwork(network: WifiNetwork) {
        _uiState.update {
            it.copy(
                selectedNetwork = network,
                routerPassword = "",
                errorMessage = null,
            )
        }
    }

    fun dismissNetworkPassword() {
        _uiState.update { it.copy(selectedNetwork = null, routerPassword = "") }
    }

    fun submitSelectedNetwork() {
        val currentApi = api ?: return showMessage("与 ESP32 的连接已断开")
        val current = _uiState.value
        val network = current.selectedNetwork ?: return
        val requiresPassword = network.security != "open"
        if (requiresPassword && current.routerPassword.length !in 8..63) {
            return showMessage("Wi-Fi 密码应为 8–63 个字符")
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedNetwork = null,
                    page = ProvisioningPage.CONNECTING_ROUTER,
                    statusText = "正在将 ${network.ssid} 发送给设备",
                    errorMessage = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentApi.submitWifi(token, network.ssid, current.routerPassword)
                }
                pollConnectionStatus(currentApi, network.ssid)
            }.onFailure(::showFailure)
        }
    }

    private suspend fun pollConnectionStatus(currentApi: Esp32Api, targetSsid: String) {
        repeat(22) { attempt ->
            delay(if (attempt == 0) 600 else 1_000)
            _uiState.update {
                it.copy(statusText = "设备正在连接 $targetSsid · ${attempt + 1}s")
            }
            val status = withContext(Dispatchers.IO) { currentApi.getWifiStatus(token) }
            if (status.connected && status.state == "connected") {
                connector.disconnect()
                api = null
                token = ""
                _uiState.update {
                    it.copy(
                        page = ProvisioningPage.SUCCESS,
                        statusText = "${status.connectedSsid} 已配置成功",
                        routerPassword = "",
                    )
                }
                return
            }
            if (status.state == "failed") {
                throw ProvisioningApiException(
                    status.errorCode ?: "WIFI_CONNECTION_FAILED",
                    status.errorMessage ?: "设备连接路由器失败",
                )
            }
        }
        throw ProvisioningApiException("WIFI_CONNECTION_TIMEOUT", "连接超时，请检查 Wi-Fi 密码和信号")
    }

    fun startOver() {
        connector.disconnect()
        api = null
        token = ""
        val current = _uiState.value
        _uiState.value = ProvisioningUiState(
            deviceSsid = current.deviceSsid,
            recentMessages = current.recentMessages,
        )
    }

    private fun showFailure(throwable: Throwable) {
        val message = when (throwable) {
            is ProvisioningApiException -> translateError(throwable.code, throwable.message)
            is SecurityException -> "系统拒绝了网络请求：${throwable.message ?: "请检查附近设备权限"}"
            else -> throwable.message ?: "操作失败，请重试"
        }
        _uiState.update {
            it.copy(
                page = if (api == null) ProvisioningPage.INTRO else ProvisioningPage.WIFI_LIST,
                isRefreshing = false,
                statusText = "",
                errorMessage = message,
            )
        }
    }

    private fun translateError(code: String, fallback: String): String = when (code) {
        "DEVICE_WIFI_UNAVAILABLE" -> "未能连接设备热点，请确认蓝色呼吸灯已亮起"
        "WIFI_NOT_FOUND" -> "设备没有找到该 Wi-Fi，请靠近路由器后重试"
        "WIFI_AUTH_OR_CONNECTION_FAILED" -> "Wi-Fi 密码错误或路由器拒绝连接"
        "WIFI_CONNECTION_TIMEOUT" -> "设备连接 Wi-Fi 超时，请检查密码和 2.4GHz 信号"
        "INVALID_PROVISIONING_TOKEN" -> "本次配网会话已失效，请重新连接设备"
        else -> fallback
    }

    private fun translateWorkerError(code: String, fallback: String): String = when (code) {
        "APP_API_TOKEN_MISSING" -> "App 尚未配置消息服务 Token，请检查本地构建配置"
        "UNAUTHORIZED" -> "消息服务认证失败，请检查 App API Token"
        "DEVICE_NOT_FOUND" -> "消息服务中没有找到这台设备"
        "MQTT_PUBLISH_FAILED" -> "消息服务暂时无法连接 MQTT，请稍后重试"
        else -> fallback
    }

    private fun translateGitHubActionsError(code: String, fallback: String): String = when (code) {
        "GITHUB_TOKEN_MISSING" -> "App 尚未配置 GitHub Actions Token，请检查本地构建配置"
        "GITHUB_CONFIG_MISSING" -> "GitHub Actions 发送配置不完整"
        "GITHUB_HTTP_401" -> "GitHub Token 无效或已经过期"
        "GITHUB_HTTP_403" -> "GitHub Token 没有 Actions 写入权限"
        "GITHUB_HTTP_404" -> "没有找到 GitHub 工作流，请确认它已存在于默认分支"
        "GITHUB_HTTP_422" -> "GitHub 工作流分支或输入配置不正确"
        else -> fallback
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private fun loadRecentMessages(): List<SentMessage> = runCatching {
        val stored = messageHistoryPreferences.getString(MESSAGE_HISTORY_KEY, null)
            ?: return@runCatching emptyList()
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until minOf(array.length(), MAX_RECENT_MESSAGES)) {
                val item = array.optJSONObject(index) ?: continue
                val messageId = item.optString("messageId")
                val text = item.optString("text")
                val sentAtMillis = item.optLong("sentAtMillis")
                if (messageId.isNotBlank() && text.isNotBlank() && sentAtMillis > 0) {
                    add(SentMessage(messageId, text, sentAtMillis))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun saveRecentMessages(messages: List<SentMessage>) {
        val array = JSONArray()
        messages.take(MAX_RECENT_MESSAGES).forEach { message ->
            array.put(
                JSONObject()
                    .put("messageId", message.messageId)
                    .put("text", message.text)
                    .put("sentAtMillis", message.sentAtMillis),
            )
        }
        messageHistoryPreferences.edit().putString(MESSAGE_HISTORY_KEY, array.toString()).apply()
    }

    override fun onCleared() {
        connector.disconnect()
        super.onCleared()
    }
}
