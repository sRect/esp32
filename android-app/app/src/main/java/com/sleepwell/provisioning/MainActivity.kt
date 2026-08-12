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
            StepRail(state.page)
            AnimatedVisibility(state.errorMessage != null) {
                ErrorBanner(state.errorMessage.orEmpty())
            }
            Spacer(Modifier.height(12.dp))

            when (state.page) {
                ProvisioningPage.INTRO -> IntroPage(
                    state,
                    onDeviceSsidChange,
                    onConnectDevice,
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
                ProvisioningPage.SUCCESS -> SuccessPage(state, onStartOver)
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
        ProvisioningPage.SUCCESS -> 3
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
private fun SuccessPage(state: ProvisioningUiState, onStartOver: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(112.dp).background(Forest, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(28.dp))
        Text("连接成功", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text(state.statusText, color = Forest, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "手机已释放设备热点并恢复原网络。ESP32 的蓝色呼吸灯会熄灭。",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 20.dp, start = 24.dp, end = 24.dp),
        )
        OutlinedButton(
            onClick = onStartOver,
            modifier = Modifier.fillMaxWidth().padding(top = 30.dp).height(52.dp),
            shape = RoundedCornerShape(15.dp),
        ) { Text("配置另一台设备") }
    }
}
