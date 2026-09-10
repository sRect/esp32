# ESP32-S3 Wi-Fi / BLE Provisioning Firmware

适用于当前项目的 `YD-ESP32-23 / ESP32-S3-N16R8` 开发板。

## 蓝牙配网（0.6.2）

0.6.1 修复 NimBLE indication 同步等待误超时导致的首次连接断开，改为按真实异步确认推进分片。

0.6.2 在手机通过 HTTP 读取设备信息、选择热点配网时暂停 BLE，下一次进入配网模式时恢复。保留原有同步 Wi-Fi 扫描流程，并记录扫描耗时和热点客户端数量。同步扫描期间主循环无法刷新呼吸灯，因此灯效会短暂停顿。

2026-09-10 真机验证（App 0.4.2）：热点扫描耗时 3656 ms，返回 16 个网络，扫描前后均有 1 个热点客户端；随后收到配网信息、连接路由器成功并恢复 MQTT。本次验证通过，尚未完成多轮稳定性测试。

长按 BOOT 5 秒后，同时开放 SoftAP 与 BLE，蓝牙名称与热点名称一致（例如 `esp32-c9c`）。
Android App 首页选择“蓝牙配网”，无需切换手机 Wi-Fi，即可读取设备、扫描路由器、提交凭据并查询结果。
短按 BOOT 或配网成功后，两种配网入口都会关闭；失败不会覆盖旧凭据。
BLE 响应完整发送后才允许执行成功后的关闭动作。

协议详见 [BLE_PROVISIONING.md](../../../docs/BLE_PROVISIONING.md)。

## 功能

- 长按 BOOT 5 秒手动启动 SoftAP 配网模式。
- 有历史配置时优先连接已保存的 Wi-Fi。
- 配网模式中短按一次 BOOT，退出配网并关闭热点。
- 配网模式中板载 RGB 指示灯呈蓝色呼吸效果。
- 配网成功后热点关闭，RGB 指示灯变为绿色常亮。
- 已联网时再次长按 BOOT 5 秒，可重新进入配网模式。
- 通过 HTTP JSON API 扫描和切换 Wi-Fi。
- 新凭据仅在成功联网并取得 IP 后写入 NVS。
- `hasProvisionedBefore` 持久化记录历史配网状态。
- 进入和退出配网不会清除已保存的旧 Wi-Fi 配置。
- 配网成功后保留热点15秒，供 App 查询最终状态，然后关闭热点。
- 支持 Captive Portal 常见探测路径。
- Wi-Fi 联网后通过 TLS 连接 EMQX，并自动重连。
- MQTT 连续连接失败时输出 DNS、TCP、TLS 诊断并自动刷新 Wi-Fi 连接。
- 自动探测 I²C SSD1306 OLED（地址 `0x3C` 或 `0x3D`）。
- 订阅设备显示命令，在 OLED 上显示 App 发送的 UTF-8 中英文消息并自动换行。
- 消息下方显示 Worker `sentAt` 对应的北京时间，底部固定显示 Wi-Fi 状态。
- 最后一条消息持续显示，直到新消息替换；仅本次启动尚未收到消息时显示初始状态页。
- 收到消息时蜂鸣器发出两声短促“滴滴”提示，同时短暂闪烁蓝灯。
- 向设备 ACK 和在线状态主题发布处理结果。

## 默认配网热点

热点名称：

```text
esp32-xxx
```

其中 `xxx` 是设备 ID 最后3位的小写形式，例如当前开发板为 `esp32-c9c`。

热点为开放网络，不需要密码。配网 API 仍使用每次启动随机生成的 token 保护修改操作。

## BOOT 键与指示灯

```text
正常模式按下 BOOT：指示灯立即变为暗蓝色，表示按键已被识别；持续按满 5 秒后进入配网模式，开启热点并开始蓝色呼吸
配网模式短按 BOOT 一次：退出配网模式并关闭热点；已联网时恢复绿色常亮
配网成功：关闭热点，指示灯以较低亮度绿色常亮
已联网状态长按 BOOT 5 秒：再次进入配网模式，蓝色呼吸灯亮起
```

长按进入配网不会清除旧凭据。若切换 Wi-Fi 失败或主动退出，旧凭据仍然保留。

## API

基础地址：

```text
http://192.168.4.1
```

首先请求：

```http
GET /api/v1/device
```

响应中的 `provisioningToken` 需要放入后续请求头：

```http
X-Provisioning-Token: <token>
```

接口列表：

```text
GET  /api/v1/device
GET  /api/v1/wifi/scan
POST /api/v1/wifi/config
GET  /api/v1/wifi/status
POST /api/v1/wifi/reset
POST /api/v1/device/reboot
```

提交 Wi-Fi 配置示例：

```json
{
  "ssid": "HomeWiFi",
  "password": "example-password"
}
```

`POST /api/v1/wifi/config` 返回 `202 Accepted`，App 随后轮询
`GET /api/v1/wifi/status` 获取连接结果。

## Arduino IDE 配置

```text
Board: ESP32S3 Dev Module
Flash Size: 16MB (128Mb)
Flash Mode: QIO 80MHz
PSRAM: OPI PSRAM
Partition Scheme: 16M Flash (3MB APP/9.9MB FATFS)
CPU Frequency: 240MHz (WiFi)
Upload Speed: 460800
Upload Mode: UART0 / Hardware CDC
USB CDC On Boot: Disabled
```

依赖库：

```text
ArduinoJson
PubSubClient
U8g2
```

## OLED 接线

当前使用 0.96 英寸、128×64、I²C SSD1306 OLED。屏幕排针丝印从左到右为
`GND`、`VCC`、`SCL`、`SDA`：

| OLED 针脚 | 当前线色 | ESP32-S3 针脚 | 作用 |
|---|---|---|---|
| `GND` | 黄线 | `GND` | 公共地 |
| `VCC` | 绿线 | `3V3` | 3.3V 供电 |
| `SCL` | 蓝线 | `GPIO9` | I²C 时钟 |
| `SDA` | 紫线 | `GPIO8` | I²C 数据 |

插拔杜邦线前应先断开 USB 电源。`VCC` 不要接 `5V`，因为 ESP32-S3 GPIO 不耐受
5V 电平。本开发板上的 `GPIO8` 和 `GPIO9` 不相邻，中间隔着 `GPIO3` 和 `GPIO46`；
接线时应以开发板丝印为准，不要仅依赖杜邦线颜色。

固件启动时依次探测 `0x3C` 和 `0x3D`，串口会输出实际检测到的地址。
显示字体使用 U8g2 文泉驿 GB2312 字体，支持常用简体中文和英文。收到消息后，上方
最多显示 3 行文字，中间显示北京时间，底部显示 Wi-Fi 状态。超出 128×64 可视范围
的内容会截断；Emoji 等字体未收录字符可能无法显示。

## 蜂鸣器接线

当前使用高电平触发的三针有源蜂鸣器模块，模块板上已带三极管驱动：

| 蜂鸣器针脚 | 当前线色 | ESP32-S3 针脚 | 作用 |
|---|---|---|---|
| `GND` | 棕色 | `GND` | 公共地 |
| `I/O` | 红色 | `GPIO4` | 高电平触发 |
| `VCC` | 橙色 | `3V3` | 3.3V 供电 |

收到有效 MQTT 新消息后，固件以非阻塞方式发出两声短提示，每声最长 120ms，中间间隔
100ms。`buzzerDurationMs=0` 会关闭本条消息的声音；非零值作为响声时长预算，固件最多
产生 240ms 的实际鸣响，避免异常消息使蜂鸣器长时间持续发声。

## MQTT 本地配置

复制示例文件并填写该设备的 MQTT 认证密码：

```text
mqtt_secrets.h.example -> mqtt_secrets.h
```

`mqtt_secrets.h` 已加入 `.gitignore`，不得提交到 Git。固件通过 TLS 8883 端口连接
EMQX，使用内置的 DigiCert Global Root G2 公共 CA 验证服务器证书。
MQTT 用户名和客户端 ID 均由 `esp32-<deviceId>` 生成，当前设备为
`esp32-7CE8B1B1FC9C`。

当前主题：

```text
订阅：devices/7CE8B1B1FC9C/commands/display
发布：devices/7CE8B1B1FC9C/ack
发布：devices/7CE8B1B1FC9C/state
```

收到消息后，`text` 和发送日期时间会立即显示在 OLED 上，最后一条消息持续保留，
直到下一条消息替换。底部固定显示 `WiFi connected`、`WiFi connecting`、
`WiFi provisioning` 或 `WiFi offline`。`displayDurationMs` 为兼容现有协议继续接收，
但不再用于清屏。`buzzerDurationMs=0` 表示静音，非零时触发两声短促蜂鸣。板载 RGB
灯会短暂闪蓝后恢复暗绿色。

## 当前安全边界

- SoftAP 是无密码开放热点，修改类接口需要每次启动随机生成的 token。
- 日志不会输出家庭 Wi-Fi 密码。
- BLE 仅在手动开启的配网模式工作，使用同一会话 token；当前未启用 BLE 配对加密或应用层加密。
- Wi-Fi 密码当前由 Arduino Preferences 保存到普通 NVS。
- 正式量产前仍需评估 NVS Encryption、Flash Encryption 和 Secure Boot。
