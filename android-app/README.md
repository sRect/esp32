# ESP32 Wi-Fi 配网 Android App

配套固件：`embedded/firmware/wifi_provisioning` 0.4.0 及以上。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Android 10（API 29）及以上
- `WifiNetworkSpecifier` 连接 ESP32 SoftAP
- `Network.openConnection()` 将本地 HTTP 请求固定路由到设备热点
- 声明 `CHANGE_NETWORK_STATE`，允许 `ConnectivityManager` 发起临时网络请求

App 不依赖 Retrofit 等第三方网络库，设备协议使用 Android 自带的
`HttpURLConnection` 与 `org.json`。

## 当前流程

1. 提示用户长按 ESP32 的 BOOT 键5秒。
2. 用户确认蓝色呼吸灯亮起。
3. App 请求附近 Wi-Fi 权限。
4. Android 系统弹窗确认连接 `esp32-xxx`。
5. App 请求 `/api/v1/device` 并取得本次配网 token。
6. 若 `hasProvisionedBefore` 为 `true`，先提示用户确认切换 Wi-Fi。
7. ESP32 扫描附近 2.4GHz Wi-Fi，App 展示并按信号强度排序。
8. 用户选择网络并输入密码。
9. App 提交配置并轮询连接状态。
10. 成功后释放设备网络，Android 自动恢复手机原网络。
11. 成功页可通过 Cloudflare Worker 向设备发送文字消息。
12. App 在本机保留最近 10 条成功发送的消息，并展示本地发送时间。

设备已经联网时，也可以从 App 首页直接进入“发送文字消息”，无需重新配网或连接
ESP32 热点。设备收到消息后会更新 OLED、发出两声短促蜂鸣并短暂闪蓝灯。

## Worker 消息接口

App 使用以下 HTTPS 接口发送消息：

```text
POST https://odd-river-673a.srect2017.workers.dev/api/v1/devices/7CE8B1B1FC9C/messages
```

将 Cloudflare Worker 的 `APP_API_TOKEN` 添加到本机的 `android-app/local.properties`：

```properties
APP_API_TOKEN=替换为本地保存的Token
```

`local.properties` 已被 Git 忽略，真实 Token 不会提交到仓库。仓库中的
`local.properties.example` 仅包含占位符。修改 Token 后需要重新构建并安装 App。

## 当前开发板默认值

```text
设备热点：esp32-c9c
设备密码：无（开放热点）
设备 API：http://192.168.4.1/api/v1
```

热点名称可以在 App 首页修改。量产版本建议改为扫描设备二维码，避免手工输入。

## 打开工程

在 Android Studio 中选择：

```text
File → Open → android-app
```

首次打开需要安装 Android SDK 36 并完成 Gradle Sync。之后连接一台开启 USB 调试的
Android 手机，选择 `app` 运行配置后点击 Run。

## 真机测试注意事项

- 模拟器无法可靠测试附近 Wi-Fi 与 `WifiNetworkSpecifier`，必须使用真机。
- Android 会显示系统确认弹窗，App 不能绕过该弹窗。
- ESP32 热点没有互联网，系统提示属于正常现象。
- Android 13 及以上请求“附近的 Wi-Fi 设备”权限。
- Android 10–12 请求位置权限，这是旧版系统对 Wi-Fi API 的要求。
- 家庭路由器必须启用 2.4GHz；ESP32-S3 不支持 5GHz。
- 配网成功前不要短按 BOOT，否则固件会关闭热点并取消当前配网。

## 安全边界

- 家庭 Wi-Fi 密码仅通过手机与 ESP32 之间的本地热点发送。
- App 不写入日志、不持久化家庭 Wi-Fi 密码。
- 修改类接口使用每次设备启动随机生成的 `X-Provisioning-Token`。
- 当前设备 HTTP 为局域网明文传输，正式量产前仍需评估二维码密钥、应用层加密及固件安全启动。
- 当前消息 Token 会编译进 APK，仅适合单设备原型测试；正式发布前应改为用户登录、短期访问令牌和设备归属校验。
