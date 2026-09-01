package com.sleepwell.provisioning

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepwell.provisioning.data.DeviceInfo
import com.sleepwell.provisioning.data.WifiNetwork
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF17221C)
private val Forest = Color(0xFF245A42)
private val Mint = Color(0xFFD7E8DC)
private val Paper = Color(0xFFF6F7F2)
private val WarmWhite = Color(0xFFFFFEFA)
private val Coral = Color(0xFFE66D4B)
private val Muted = Color(0xFF66736B)

class MainActivity : ComponentActivity() {
    private val viewModel: ProvisioningViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProvisioningTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val permissions = if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    if (permissions.all { grants[it] == true }) {
                        viewModel.connectToDevice()
                    } else {
                        viewModel.permissionDenied()
                    }
                }

                ProvisioningApp(
                    state = state,
                    onDeviceSsidChange = viewModel::setDeviceSsid,
                    onConnectDevice = {
                        if (permissions.all {
                                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                            }
                        ) {
                            viewModel.connectToDevice()
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    },
                    onConfirmSwitch = viewModel::confirmSwitchWifi,
                    onCancelSwitch = viewModel::cancelSwitchWifi,
                    onRefresh = viewModel::scanNetworks,
                    onSelectNetwork = viewModel::selectNetwork,
                    onDismissNetwork = viewModel::dismissNetworkPassword,
                    onRouterPasswordChange = viewModel::setRouterPassword,
                    onSubmitNetwork = viewModel::submitSelectedNetwork,
                    onStartOver = viewModel::startOver,
                    onOpenMessages = viewModel::openMessagePage,
                    onCloseMessages = viewModel::closeMessagePage,
                    onMessageTextChange = viewModel::setMessageText,
                    onMessageDeliveryChannelChange = viewModel::setMessageDeliveryChannel,
                    onSendMessage = viewModel::sendMessage,
                )
            }
        }
    }
}

@Composable
private fun ProvisioningTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Forest,
            onPrimary = Color.White,
            background = Paper,
            surface = WarmWhite,
            onSurface = Ink,
            error = Coral,
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvisioningApp(
    state: ProvisioningUiState,
    onDeviceSsidChange: (String) -> Unit,
    onConnectDevice: () -> Unit,
    onConfirmSwitch: () -> Unit,
    onCancelSwitch: () -> Unit,
    onRefresh: () -> Unit,
    onSelectNetwork: (WifiNetwork) -> Unit,
    onDismissNetwork: () -> Unit,
    onRouterPasswordChange: (String) -> Unit,
    onSubmitNetwork: () -> Unit,
    onStartOver: () -> Unit,
    onOpenMessages: () -> Unit,
    onCloseMessages: () -> Unit,
    onMessageTextChange: (String) -> Unit,
    onMessageDeliveryChannelChange: (MessageDeliveryChannel) -> Unit,
    onSendMessage: () -> Unit,
) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DEVICE SETUP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Forest)
                        Text("ESP32 配网", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            if (state.page != ProvisioningPage.MESSAGE) {
                StepRail(state.page)
            }
            AnimatedVisibility(state.errorMessage != null) {
                ErrorBanner(state.errorMessage.orEmpty())
            }
            Spacer(Modifier.height(12.dp))

            when (state.page) {
                ProvisioningPage.INTRO -> IntroPage(
                    state,
                    onDeviceSsidChange,
                    onConnectDevice,
                    onOpenMessages,
                )
                ProvisioningPage.CONNECTING_DEVICE -> LoadingPage(
                    eyebrow = "连接设备",
                    title = "请在系统弹窗中确认",
                    detail = state.statusText,
                    hint = "ESP32 热点没有互联网，这是正常现象。请保持连接。",
                )
                ProvisioningPage.WIFI_LIST -> WifiListPage(state, onRefresh, onSelectNetwork)
                ProvisioningPage.CONNECTING_ROUTER -> LoadingPage(
                    eyebrow = "写入网络",
                    title = "设备正在加入 Wi-Fi",
                    detail = state.statusText,
                    hint = "请不要退出 App 或短按 BOOT，过程通常需要 5–20 秒。",
                )
                ProvisioningPage.SUCCESS -> SuccessPage(
                    state = state,
                    onMessageTextChange = onMessageTextChange,
                    onMessageDeliveryChannelChange = onMessageDeliveryChannelChange,
                    onSendMessage = onSendMessage,
                    onStartOver = onStartOver,
                )
                ProvisioningPage.MESSAGE -> MessagePage(
                    state = state,
                    onMessageTextChange = onMessageTextChange,
                    onMessageDeliveryChannelChange = onMessageDeliveryChannelChange,
                    onSendMessage = onSendMessage,
                    onBack = onCloseMessages,
                )
            }
        }
    }

    if (state.showSwitchWifiConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelSwitch,
            title = { Text("设备已配过网") },
            text = { Text("继续操作会将设备切换到新的 Wi-Fi。旧配置只会在新网络连接成功后被替换。") },
            confirmButton = { TextButton(onClick = onConfirmSwitch) { Text("继续切换") } },
            dismissButton = { TextButton(onClick = onCancelSwitch) { Text("取消") } },
        )
    }

    state.selectedNetwork?.let { network ->
        WifiPasswordDialog(
            network = network,
            password = state.routerPassword,
            onPasswordChange = onRouterPasswordChange,
            onDismiss = onDismissNetwork,
            onSubmit = onSubmitNetwork,
        )
    }
}

@Composable
private fun StepRail(page: ProvisioningPage) {
    val current = when (page) {
        ProvisioningPage.INTRO, ProvisioningPage.CONNECTING_DEVICE -> 0
        ProvisioningPage.WIFI_LIST -> 1
        ProvisioningPage.CONNECTING_ROUTER -> 2
        ProvisioningPage.SUCCESS, ProvisioningPage.MESSAGE -> 3
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("设备", "网络", "连接", "完成").forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(if (index == current) 26.dp else 20.dp)
                        .background(if (index <= current) Forest else Mint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (index < current) Text("✓", color = Color.White, fontSize = 11.sp)
                }
                Text(label, fontSize = 10.sp, color = if (index == current) Ink else Muted)
            }
            if (index < 3) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f).padding(horizontal = 7.dp),
                    color = if (index < current) Forest else Mint,
                    thickness = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = Color(0xFFFFE9E1), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("!", color = Coral, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(message, color = Color(0xFF7B2F20), fontSize = 14.sp)
        }
    }
}

@Composable
private fun IntroPage(
    state: ProvisioningUiState,
    onSsidChange: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("让设备联网", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, color = Ink)
            Text(
                "先让 ESP32 进入配网模式，再由系统建立一条仅供设备通信的 Wi-Fi 连接。",
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            InstructionCard()
        }
        item {
            OutlinedTextField(
                value = state.deviceSsid,
                onValueChange = onSsidChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("设备热点") },
                supportingText = { Text("格式：esp32-xxx") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
            ) {
                Text("连接设备热点", fontWeight = FontWeight.Bold)
            }
            Text(
                "设备热点无需密码。Android 会显示系统 Wi-Fi 确认框；App 不会修改或保存你手机原来的网络。",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
        item {
            OutlinedButton(
                onClick = onOpenMessages,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("设备已联网？发送文字消息", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InstructionCard() {
    Surface(color = Ink, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("板端准备", color = Color(0xFFAAD5B7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            InstructionRow("01", "长按 BOOT 键 5 秒")
            InstructionRow("02", "看到蓝色呼吸灯后松开")
            InstructionRow("03", "确认热点名称以 esp32- 开头")
        }
    }
}

@Composable
private fun InstructionRow(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).border(1.dp, Color(0xFF66796E), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(number, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun LoadingPage(eyebrow: String, title: String, detail: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(112.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(58.dp), color = Forest, strokeWidth = 5.dp)
        }
        Spacer(Modifier.height(28.dp))
        Text(eyebrow.uppercase(), color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
        Text(detail, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        Surface(
            color = WarmWhite,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(top = 28.dp),
        ) {
            Text(hint, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun WifiListPage(
    state: ProvisioningUiState,
    onRefresh: () -> Unit,
    onSelect: (WifiNetwork) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DeviceStrip(state.deviceInfo)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("选择 Wi-Fi", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(state.statusText, color = Muted, fontSize = 13.sp)
            }
            TextButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                Text(if (state.isRefreshing) "扫描中…" else "刷新")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (state.isRefreshing && state.networks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Forest)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(state.networks, key = { it.ssid }) { network ->
                    NetworkRow(network, onClick = { onSelect(network) })
                }
                item {
                    Text(
                        "ESP32-S3 仅支持 2.4GHz；列表由设备扫描并按信号强度排序。",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceStrip(device: DeviceInfo?) {
    Surface(color = Mint, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Forest, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(device?.apSsid ?: "ESP32", color = Ink, fontWeight = FontWeight.Bold)
                Text("固件 ${device?.firmwareVersion.orEmpty()} · ${device?.wifiMac.orEmpty()}", color = Muted, fontSize = 11.sp)
            }
            Text("已连接", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NetworkRow(network: WifiNetwork, onClick: () -> Unit) {
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Paper, CircleShape), contentAlignment = Alignment.Center) {
                Text(signalGlyph(network.rssi), color = Forest, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(network.ssid, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("信道 ${network.channel} · ${securityLabel(network.security)}", color = Muted, fontSize = 12.sp)
            }
            Text("›", color = Forest, fontSize = 28.sp)
        }
    }
}

private fun signalGlyph(rssi: Int): String = when {
    rssi >= -55 -> "▰▰▰"
    rssi >= -70 -> "▰▰▫"
    else -> "▰▫▫"
}

private fun securityLabel(value: String): String = when (value) {
    "open" -> "开放网络"
    "wpa3_psk", "wpa2_wpa3_psk" -> "WPA3"
    "wpa2_psk", "wpa_wpa2_psk" -> "WPA2"
    else -> value.uppercase()
}

@Composable
private fun WifiPasswordDialog(
    network: WifiNetwork,
    password: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val openNetwork = network.security == "open"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(network.ssid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(if (openNetwork) "这是开放网络，无需输入密码。" else "输入该 Wi-Fi 的密码，密码仅发送给当前 ESP32。")
                if (!openNetwork) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        label = { Text("Wi-Fi 密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSubmit) { Text("开始连接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SuccessPage(
    state: ProvisioningUiState,
    onMessageTextChange: (String) -> Unit,
    onMessageDeliveryChannelChange: (MessageDeliveryChannel) -> Unit,
    onSendMessage: () -> Unit,
    onStartOver: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Box(Modifier.size(82.dp).background(Forest, CircleShape), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Light)
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("连接成功", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(state.statusText, color = Forest, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
        item {
            MessageComposer(
                state,
                onMessageTextChange,
                onMessageDeliveryChannelChange,
                onSendMessage,
            )
        }
        item {
            RecentMessages(state.recentMessages)
        }
        item {
            OutlinedButton(
                onClick = onStartOver,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp),
            ) { Text("配置另一台设备") }
        }
    }
}

@Composable
private fun MessagePage(
    state: ProvisioningUiState,
    onMessageTextChange: (String) -> Unit,
    onMessageDeliveryChannelChange: (MessageDeliveryChannel) -> Unit,
    onSendMessage: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("发送到设备", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(
                when (state.messageDeliveryChannel) {
                    MessageDeliveryChannel.CLOUDFLARE_WORKER ->
                        "默认通过 Cloudflare Worker 和 MQTT 实时下发，无需连接 ESP32 热点。"
                    MessageDeliveryChannel.GITHUB_ACTIONS ->
                        "备用路径会先触发 GitHub Actions，Runner 启动后再发布到 MQTT。"
                },
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            MessageComposer(
                state,
                onMessageTextChange,
                onMessageDeliveryChannelChange,
                onSendMessage,
            )
        }
        item {
            RecentMessages(state.recentMessages)
        }
        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回配网页")
            }
        }
    }
}

@Composable
private fun MessageComposer(
    state: ProvisioningUiState,
    onMessageTextChange: (String) -> Unit,
    onMessageDeliveryChannelChange: (MessageDeliveryChannel) -> Unit,
    onSendMessage: () -> Unit,
) {
    Surface(color = WarmWhite, shape = RoundedCornerShape(22.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("文字消息", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "ESP32 收到消息后会更新 OLED、发出两声短促蜂鸣并短暂闪蓝灯。",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Text("发送路径", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DeliveryChannelButton(
                    text = "Cloudflare",
                    selected = state.messageDeliveryChannel == MessageDeliveryChannel.CLOUDFLARE_WORKER,
                    enabled = !state.isSendingMessage,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onMessageDeliveryChannelChange(MessageDeliveryChannel.CLOUDFLARE_WORKER)
                    },
                )
                DeliveryChannelButton(
                    text = "GitHub Actions",
                    selected = state.messageDeliveryChannel == MessageDeliveryChannel.GITHUB_ACTIONS,
                    enabled = !state.isSendingMessage,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onMessageDeliveryChannelChange(MessageDeliveryChannel.GITHUB_ACTIONS)
                    },
                )
            }
            Text(
                when (state.messageDeliveryChannel) {
                    MessageDeliveryChannel.CLOUDFLARE_WORKER -> "推荐 · 实时发送并等待 Broker 接受"
                    MessageDeliveryChannel.GITHUB_ACTIONS -> "备用 · 会有 Actions 排队和启动延迟"
                },
                color = Muted,
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = state.messageText,
                onValueChange = onMessageTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入要显示的文字") },
                supportingText = {
                    Text("${state.messageText.codePointCount(0, state.messageText.length)}/120")
                },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                enabled = !state.isSendingMessage,
            )
            Button(
                onClick = onSendMessage,
                enabled = state.messageText.isNotBlank() && !state.isSendingMessage,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
            ) {
                if (state.isSendingMessage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when {
                        state.isSendingMessage -> "发送中…"
                        state.messageDeliveryChannel == MessageDeliveryChannel.GITHUB_ACTIONS ->
                            "通过 GitHub Actions 发送"
                        else -> "发送到 ESP32"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            state.messageStatus?.let { status ->
                Text(status, color = Forest, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DeliveryChannelButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Forest),
        ) {
            Text(text, fontSize = 12.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RecentMessages(messages: List<SentMessage>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("最近发送", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${messages.size}/10", color = Muted, fontSize = 12.sp)
        }
        if (messages.isEmpty()) {
            Surface(color = Mint, shape = RoundedCornerShape(16.dp)) {
                Text(
                    "还没有发送记录",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        } else {
            messages.forEach { message ->
                RecentMessageRow(message)
            }
        }
    }
}

@Composable
private fun RecentMessageRow(message: SentMessage) {
    Surface(color = WarmWhite, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = message.text,
                color = Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatSentTime(message.sentAtMillis),
                    color = Muted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = message.messageId.take(8),
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private val sentTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private fun formatSentTime(sentAtMillis: Long): String =
    Instant.ofEpochMilli(sentAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(sentTimeFormatter)
