# BLE 蓝牙配网

固件 0.6.2 / Android App 0.4.2。设备最终仍接入 2.4GHz Wi-Fi；BLE 用于手机与设备之间传递配网信息。

## 0.6.1 连接后断开修复

小米 10 日志显示：连接、发现服务、订阅 indication 均成功，`GET /device` 后约一秒收到设备主动断开（状态 19）。
当前 Arduino-ESP32 3.3.10-cn 使用 NimBLE；其 `BLECharacteristic::indicate()` 同步等待信号量，
但 `BLEServer` 的 `BLE_GAP_EVENT_NOTIFY_TX` 确认事件只调用 `onStatus(SUCCESS_INDICATE)`，没有释放该信号量，
因此稍后又报告超时。0.6.0 把这个超时当成传输失败而断开连接。

0.6.1 在 NimBLE 下直接异步提交 indication，由真实确认回调推动主循环发送下一片；
每次仅允许一个分片在途，实际确认超时为 5 秒，断开或退出配网时清理发送状态。
不再调用这段同步等待逻辑，也不需要修改本机 SDK。Android 0.4.1 增加阶段日志并保留真实断连状态码。

## 操作

0.6.2 在手机通过 HTTP `/device` 选择热点配网后暂停 BLE；若需切回蓝牙，短按 BOOT 退出再长按 5 秒重新进入配网模式。App 0.4.2 保留热点 HTTP 原有执行方式，仅 BLE 使用可中断调用。

1. 长按 BOOT 5 秒，蓝灯呼吸，设备同时开放蓝牙与原 SoftAP。
2. App 首页选“蓝牙配网”，名称为 `esp32-xxx`（当前板子 `esp32-c9c`）。
3. 开启手机蓝牙并允许权限，点击连接。Android 10–11 还需开启定位。
4. 已有配置时先确认切换；选择路由器、输入密码，等待成功。
5. 新网络取得 IP 后才保存凭据；短按 BOOT 退出配网时保留旧配置。

手机返回首页会释放连接，板端配网仍开启，可继续重试。成功后设备关闭广播、断开 BLE 并关闭热点。
当前协议使用会话 token，但没有 BLE 配对加密或应用层加密，与开放热点 HTTP 一样仅适合受控原型环境。

## GATT

| 项目 | UUID / 属性 |
| --- | --- |
| Service | `a8e10001-73bd-4d74-a613-0b9c78d2f001` |
| RX | `a8e10002-73bd-4d74-a613-0b9c78d2f001` / WRITE（有响应） |
| TX | `a8e10003-73bd-4d74-a613-0b9c78d2f001` / INDICATE |
| TX CCCD | `00002902-0000-1000-8000-00805f9b34fb`，写入 `02 00` |

单连接、单请求串行。UTF-8 JSON 行以 `\n` 结尾，双向按最多 20 字节分片，无需 MTU 协商。每个写入等待回调，每个 indication 等待 ATT 确认。请求不含换行最多 1023 字节；App 最多接收 64 KiB 响应，45 秒总响应超时。断连后丢弃旧会话帧，重新读取 token。

请求示例（线上须追加一个实际换行）：

```json
{"method":"GET","path":"/device","token":"","body":{}}
```

响应沿用 HTTP 状态码和业务 JSON：

```json
{"status":200,"body":{"deviceId":"7CE8B1B1FC9C","provisioningToken":"<session-token>"}}
```

支持 `GET /device`、`GET /wifi/scan`、`POST /wifi/config`、`GET /wifi/status`。
除读取设备信息外均需要 `/device` 返回的 token。配置 body 为 `{"ssid":"...","password":"..."}`。
BLE 请求入 FreeRTOS 队列，由 Arduino 主循环调用已有 HTTP 业务处理函数，避免在蓝牙回调里阻塞扫描或修改 Wi-Fi/NVS。
响应完成前不执行配网成功关闭，配网期间延后新的 MQTT TLS 重连，以免阻塞结果返回。

## 验证

```sh
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

自动测试覆盖中文/Emoji SSID 跨任意字节边界、多分片响应、响应大小限制、断连标记和异常多帧。
真机验收应覆盖：初次配网、已有配置确认、密码错误后重试、蓝牙中途关闭、短按退出、再次长按重入，以及热点配网回归。Android BLE 扫描和权限弹窗需要真实手机验证。

### 本次验证（2026-09-10）

- Android debug 构建通过，4 项分包测试通过，Lint 无错误。
- ESP32-S3 编译通过：程序 1,553,575 字节，约占 3 MiB 应用分区的 49%。
- 已烧录设备 `7CE8B1B1FC9C`，Flash 写入哈希校验通过，新旧分区表一致。
- 串口确认固件 0.6.2、16 MB Flash / 8 MB PSRAM、已保存的 Wi-Fi 自动重连、MQTT TLS 连接和主题订阅成功。
- USB 桥控制 BOOT 长按后，串口确认 BLE `esp32-c9c` 和 SoftAP 同时启动；短按后退出配网。
- Android App 0.4.2 已安装到小米 10；蓝牙在 0.6.1 修复后用户反馈操作成功，完整异常场景仍需回归。
- 0.6.2 热点实测扫描耗时 3656 ms，返回 16 个网络，扫描前后均保持 1 个热点客户端；随后成功连接路由器并恢复 MQTT。本次流程通过，尚未完成多轮稳定性和跨设备兼容性测试。
