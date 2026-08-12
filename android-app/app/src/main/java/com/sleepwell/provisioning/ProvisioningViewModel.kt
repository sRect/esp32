package com.sleepwell.provisioning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepwell.provisioning.data.DeviceInfo
import com.sleepwell.provisioning.data.Esp32Api
import com.sleepwell.provisioning.data.EspWifiConnector
import com.sleepwell.provisioning.data.ProvisioningApiException
import com.sleepwell.provisioning.data.WifiNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProvisioningPage {
    INTRO,
    CONNECTING_DEVICE,
    WIFI_LIST,
    CONNECTING_ROUTER,
    SUCCESS,
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
)

class ProvisioningViewModel(application: Application) : AndroidViewModel(application) {
    private val connector = EspWifiConnector(application)
    private var api: Esp32Api? = null
    private var token: String = ""

    private val _uiState = MutableStateFlow(ProvisioningUiState())
    val uiState: StateFlow<ProvisioningUiState> = _uiState.asStateFlow()

    fun setDeviceSsid(value: String) {
        _uiState.update { it.copy(deviceSsid = value.trim(), errorMessage = null) }
    }

    fun setRouterPassword(value: String) {
        _uiState.update { it.copy(routerPassword = value, errorMessage = null) }
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

    private fun showMessage(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onCleared() {
        connector.disconnect()
        super.onCleared()
    }
}
