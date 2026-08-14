# ESP32-S3 Wi-Fi Provisioning Firmware

适用于当前项目的 `YD-ESP32-23 / ESP32-S3-N16R8` 开发板。

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
- 订阅设备显示命令，暂时通过串口和短暂蓝灯反馈消息。
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
```

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

OLED 和蜂鸣器到货前，收到的 `text`、`displayDurationMs` 和
`buzzerDurationMs` 会输出到 115200 波特率的串口，板载 RGB 灯短暂闪蓝后恢复暗绿色。

## 当前安全边界

- SoftAP 是无密码开放热点，修改类接口需要每次启动随机生成的 token。
- 日志不会输出家庭 Wi-Fi 密码。
- Wi-Fi 密码当前由 Arduino Preferences 保存到普通 NVS。
- 正式量产前仍需评估 NVS Encryption、Flash Encryption 和 Secure Boot。
