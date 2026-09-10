#include <Arduino.h>
#include <BLEDevice.h>
#include <BLE2902.h>
#include <atomic>
#include <ArduinoJson.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <U8g2lib.h>
#include <WebServer.h>
#include <Wire.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <esp_system.h>
#include <time.h>

#include "mqtt_config.h"

#if __has_include("mqtt_secrets.h")
#include "mqtt_secrets.h"
#else
#define MQTT_PASSWORD ""
#endif

namespace Config {
constexpr char kFirmwareVersion[] = "0.6.2";
constexpr char kDeviceModel[] = "ESP32-S3-N16R8";
constexpr char kApiPrefix[] = "/api/v1";
constexpr char kPreferencesNamespace[] = "wifi-prov";
constexpr char kSsidKey[] = "ssid";
constexpr char kPasswordKey[] = "password";
constexpr char kProvisionedKey[] = "provisioned";
constexpr uint8_t kDnsPort = 53;
constexpr uint16_t kHttpPort = 80;
constexpr uint32_t kConnectionTimeoutMs = 20000;
constexpr uint32_t kApShutdownDelayMs = 15000;
constexpr uint32_t kApShutdownAfterStatusMs = 300;
constexpr uint32_t kRebootDelayMs = 800;
constexpr uint32_t kProvisioningHoldMs = 5000;
constexpr uint32_t kButtonDebounceMs = 30;
constexpr uint32_t kLedBreathingPeriodMs = 2000;
constexpr uint32_t kLedUpdateIntervalMs = 20;
constexpr uint8_t kLedMinimumBrightness = 2;
constexpr uint8_t kLedMaximumBrightness = 64;
constexpr uint8_t kConnectedLedBrightness = 8;
constexpr uint8_t kButtonHoldLedBrightness = 8;
constexpr uint8_t kBootButtonPin = 0;
constexpr uint8_t kBuzzerPin = 4;
constexpr uint8_t kOledSdaPin = 8;
constexpr uint8_t kOledSclPin = 9;
constexpr uint16_t kBuzzerBeepDurationMs = 120;
constexpr uint16_t kBuzzerGapDurationMs = 100;
constexpr uint32_t kMaxBuzzerDurationMs = 10000;
constexpr uint8_t kOledPrimaryAddress = 0x3C;
constexpr uint8_t kOledSecondaryAddress = 0x3D;
constexpr uint8_t kOledTopInset = 2;
constexpr uint8_t kOledMessageLineCount = 3;
constexpr uint8_t kOledMessageLineHeight = 13;
constexpr uint32_t kOledStatusRefreshIntervalMs = 500;
constexpr int32_t kChinaUtcOffsetSeconds = 8 * 60 * 60;
constexpr size_t kMaxRequestBodyBytes = 768;
constexpr size_t kMaxSsidBytes = 32;
constexpr size_t kMaxPasswordBytes = 63;
}  // namespace Config

enum class ProvisioningState {
  kUnprovisioned,
  kAwaitingConfig,
  kConnecting,
  kConnected,
  kFailed,
  kResetting,
};

enum class BuzzerPhase {
  kIdle,
  kFirstBeep,
  kGap,
  kSecondBeep,
};

Preferences preferences;
WebServer server(Config::kHttpPort);
DNSServer dnsServer;
WiFiClientSecure mqttTlsClient;
PubSubClient mqttClient(mqttTlsClient);
U8G2_SSD1306_128X64_NONAME_F_HW_I2C oled(U8G2_R0, U8X8_PIN_NONE);

ProvisioningState provisioningState = ProvisioningState::kUnprovisioned;
String deviceId;
String apSsid;
String provisioningToken;
String savedSsid;
String savedPassword;
String pendingSsid;
String pendingPassword;
String lastErrorCode;
String lastErrorMessage;

bool hasProvisionedBefore = false;
bool provisioningModeActive = false;
bool httpServerRunning = false;
bool connectionStartedFromApi = false;
uint32_t connectionStartedAt = 0;
uint32_t apShutdownAt = 0;
uint32_t rebootAt = 0;
uint32_t bootButtonPressedAt = 0;
uint32_t bootButtonChangedAt = 0;
uint32_t lastLedUpdateAt = 0;
bool bootLongPressHandled = false;
bool bootButtonRawPressed = false;
bool bootButtonPressed = false;
bool timeSyncStarted = false;
bool mqttPasswordWarningPrinted = false;
uint32_t lastMqttReconnectAt = 0;
uint32_t lastTimeSyncCheckAt = 0;
uint32_t notificationLedUntil = 0;
uint32_t buzzerPhaseEndsAt = 0;
uint16_t buzzerSecondBeepDurationMs = 0;
BuzzerPhase buzzerPhase = BuzzerPhase::kIdle;
uint32_t lastOledStatusRefreshAt = 0;
uint8_t mqttConsecutiveFailures = 0;
uint8_t oledAddress = 0;
bool oledAvailable = false;
bool oledHasMessage = false;
String lastOledStatusSignature;
String oledLastMessage;
uint64_t oledLastMessageSentAtMs = 0;
String mqttCommandTopic;
String mqttAckTopic;
String mqttStateTopic;
String mqttClientId;

void setBuzzer(bool enabled) {
  digitalWrite(Config::kBuzzerPin, enabled ? HIGH : LOW);
}

void startBuzzerNotification(uint32_t requestedDurationMs) {
  setBuzzer(false);
  buzzerPhase = BuzzerPhase::kIdle;
  buzzerPhaseEndsAt = 0;
  buzzerSecondBeepDurationMs = 0;

  const uint32_t safeDurationMs = min(requestedDurationMs, Config::kMaxBuzzerDurationMs);
  const uint16_t audibleDurationMs =
      min(safeDurationMs, static_cast<uint32_t>(Config::kBuzzerBeepDurationMs * 2));
  if (audibleDurationMs == 0) {
    return;
  }

  const uint16_t firstBeepDurationMs = (audibleDurationMs + 1) / 2;
  buzzerSecondBeepDurationMs = audibleDurationMs / 2;
  setBuzzer(true);
  buzzerPhase = BuzzerPhase::kFirstBeep;
  buzzerPhaseEndsAt = millis() + firstBeepDurationMs;
}

void processBuzzer() {
  if (buzzerPhase == BuzzerPhase::kIdle ||
      static_cast<int32_t>(millis() - buzzerPhaseEndsAt) < 0) {
    return;
  }

  switch (buzzerPhase) {
    case BuzzerPhase::kFirstBeep:
      setBuzzer(false);
      if (buzzerSecondBeepDurationMs == 0) {
        buzzerPhase = BuzzerPhase::kIdle;
      } else {
        buzzerPhase = BuzzerPhase::kGap;
        buzzerPhaseEndsAt = millis() + Config::kBuzzerGapDurationMs;
      }
      break;
    case BuzzerPhase::kGap:
      setBuzzer(true);
      buzzerPhase = BuzzerPhase::kSecondBeep;
      buzzerPhaseEndsAt = millis() + buzzerSecondBeepDurationMs;
      break;
    case BuzzerPhase::kSecondBeep:
      setBuzzer(false);
      buzzerPhase = BuzzerPhase::kIdle;
      break;
    case BuzzerPhase::kIdle:
      break;
  }
}

const char *stateName(ProvisioningState state) {
  switch (state) {
    case ProvisioningState::kUnprovisioned:
      return "unprovisioned";
    case ProvisioningState::kAwaitingConfig:
      return "awaiting_config";
    case ProvisioningState::kConnecting:
      return "connecting";
    case ProvisioningState::kConnected:
      return "connected";
    case ProvisioningState::kFailed:
      return "failed";
    case ProvisioningState::kResetting:
      return "resetting";
  }
  return "unknown";
}

String jsonString(const JsonDocument &document) {
  String output;
  serializeJson(document, output);
  return output;
}

bool bleRequestActive = false;
String bleRequestToken;
String bleRequestBody;
String bleResponse;

void addCommonHeaders() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type, X-Provisioning-Token");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Cache-Control", "no-store");
}

void sendJson(int statusCode, const JsonDocument &document) {
  if (bleRequestActive) {
    JsonDocument envelope;
    envelope["status"] = statusCode;
    envelope["body"] = document.as<JsonVariantConst>();
    bleResponse = jsonString(envelope) + "\n";
    return;
  }
  addCommonHeaders();
  server.send(statusCode, "application/json; charset=utf-8", jsonString(document));
}

void sendSuccess(int statusCode, const char *message) {
  JsonDocument document;
  document["success"] = true;
  document["message"] = message;
  sendJson(statusCode, document);
}

void sendError(int statusCode, const char *code, const char *message) {
  JsonDocument document;
  document["success"] = false;
  JsonObject error = document["error"].to<JsonObject>();
  error["code"] = code;
  error["message"] = message;
  sendJson(statusCode, document);
}

bool requireProvisioningToken() {
  const String token = bleRequestActive ? bleRequestToken : server.header("X-Provisioning-Token");
  if (!provisioningModeActive || token.isEmpty() || token != provisioningToken) {
    sendError(401, "INVALID_PROVISIONING_TOKEN", "Missing or invalid provisioning token");
    return false;
  }
  return true;
}

String formatDeviceId() {
  const uint64_t mac = ESP.getEfuseMac();
  char value[13];
  snprintf(value, sizeof(value), "%02X%02X%02X%02X%02X%02X",
           static_cast<uint8_t>(mac), static_cast<uint8_t>(mac >> 8),
           static_cast<uint8_t>(mac >> 16), static_cast<uint8_t>(mac >> 24),
           static_cast<uint8_t>(mac >> 32), static_cast<uint8_t>(mac >> 40));
  return String(value);
}

String makeProvisioningToken() {
  char value[33];
  for (size_t i = 0; i < 16; ++i) {
    const uint8_t randomByte = static_cast<uint8_t>(esp_random() & 0xFF);
    snprintf(value + (i * 2), 3, "%02X", randomByte);
  }
  value[32] = '\0';
  return String(value);
}

bool isI2cDevicePresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void initializeOled() {
  Wire.begin(Config::kOledSdaPin, Config::kOledSclPin);
  Wire.setClock(400000);

  if (isI2cDevicePresent(Config::kOledPrimaryAddress)) {
    oledAddress = Config::kOledPrimaryAddress;
  } else if (isI2cDevicePresent(Config::kOledSecondaryAddress)) {
    oledAddress = Config::kOledSecondaryAddress;
  } else {
    Serial.printf("[oled] SSD1306 not found on GPIO%u/GPIO%u (checked 0x%02X and 0x%02X)\n",
                  Config::kOledSdaPin, Config::kOledSclPin,
                  Config::kOledPrimaryAddress, Config::kOledSecondaryAddress);
    return;
  }

  oled.setI2CAddress(oledAddress << 1);
  oled.begin();
  oled.setPowerSave(0);
  oled.setFontMode(1);
  oled.setFontDirection(0);
  oled.setFontPosTop();
  oledAvailable = true;

  oled.clearBuffer();
  oled.setFont(u8g2_font_wqy12_t_gb2312);
  oled.drawUTF8(0, 2, "ESP32 启动中...");
  oled.setFont(u8g2_font_5x7_tr);
  oled.drawStr(0, 24, "OLED: SSD1306 128x64");
  oled.drawStr(0, 36, "SDA: GPIO8  SCL: GPIO9");
  oled.sendBuffer();
  Serial.printf("[oled] SSD1306 initialized at I2C address 0x%02X\n", oledAddress);
}

size_t utf8CharacterLength(uint8_t firstByte) {
  if ((firstByte & 0x80) == 0) {
    return 1;
  }
  if ((firstByte & 0xE0) == 0xC0) {
    return 2;
  }
  if ((firstByte & 0xF0) == 0xE0) {
    return 3;
  }
  if ((firstByte & 0xF8) == 0xF0) {
    return 4;
  }
  return 1;
}

String formatOledMessageTime(uint64_t sentAtMs) {
  if (sentAtMs == 0) {
    return "Time unavailable";
  }

  // Worker timestamps are Unix milliseconds (UTC). This device is used in
  // China, so render the user-facing time in UTC+8 without depending on the
  // process-wide TZ setting used by TLS/NTP.
  const time_t localEpoch = static_cast<time_t>(sentAtMs / 1000ULL) +
                            Config::kChinaUtcOffsetSeconds;
  struct tm timeInfo = {};
  gmtime_r(&localEpoch, &timeInfo);
  char value[20];
  snprintf(value, sizeof(value), "%04d-%02d-%02d %02d:%02d",
           timeInfo.tm_year + 1900, timeInfo.tm_mon + 1, timeInfo.tm_mday,
           timeInfo.tm_hour, timeInfo.tm_min);
  return String(value);
}

const char *oledWifiFooter() {
  if (provisioningModeActive) {
    return "WiFi provisioning";
  }
  if (provisioningState == ProvisioningState::kConnecting) {
    return "WiFi connecting";
  }
  return WiFi.status() == WL_CONNECTED ? "WiFi connected" : "WiFi offline";
}

void renderOledMessage() {
  if (!oledAvailable) {
    return;
  }

  oled.clearBuffer();
  oled.setFont(u8g2_font_wqy12_t_gb2312);
  oled.setFontPosTop();

  String line;
  const char *cursor = oledLastMessage.c_str();
  uint8_t lineIndex = 0;
  while (*cursor != '\0' && lineIndex < Config::kOledMessageLineCount) {
    if (*cursor == '\r') {
      ++cursor;
      continue;
    }
    if (*cursor == '\n') {
      oled.drawUTF8(0, Config::kOledTopInset + lineIndex * Config::kOledMessageLineHeight,
                    line.c_str());
      line = "";
      ++lineIndex;
      ++cursor;
      continue;
    }

    size_t characterLength = utf8CharacterLength(static_cast<uint8_t>(*cursor));
    const size_t remainingLength = strlen(cursor);
    if (characterLength > remainingLength) {
      characterLength = 1;
    }
    String character;
    for (size_t index = 0; index < characterLength; ++index) {
      character += cursor[index];
    }

    const String candidate = line + character;
    if (!line.isEmpty() && oled.getUTF8Width(candidate.c_str()) > oled.getDisplayWidth()) {
      oled.drawUTF8(0, Config::kOledTopInset + lineIndex * Config::kOledMessageLineHeight,
                    line.c_str());
      line = character;
      ++lineIndex;
    } else {
      line = candidate;
    }
    cursor += characterLength;
  }

  if (lineIndex < Config::kOledMessageLineCount && !line.isEmpty()) {
    oled.drawUTF8(0, Config::kOledTopInset + lineIndex * Config::kOledMessageLineHeight,
                  line.c_str());
  }

  oled.setFont(u8g2_font_5x7_tr);
  oled.drawStr(0, 43, formatOledMessageTime(oledLastMessageSentAtMs).c_str());
  oled.drawHLine(0, 53, oled.getDisplayWidth());
  oled.drawStr(0, 57, oledWifiFooter());
  oled.sendBuffer();
}

void showOledMessage(const String &message, uint64_t sentAtMs) {
  oledLastMessage = message;
  oledLastMessageSentAtMs = sentAtMs;
  oledHasMessage = true;
  lastOledStatusSignature = "";
  renderOledMessage();
  Serial.printf("[oled] Last message displayed persistently at %s\n",
                formatOledMessageTime(sentAtMs).c_str());
}

String oledStatusSignature() {
  if (provisioningModeActive) {
    return "provisioning:" + apSsid;
  }
  if (provisioningState == ProvisioningState::kConnecting) {
    return "wifi-connecting:" + pendingSsid;
  }
  if (WiFi.status() != WL_CONNECTED) {
    return hasProvisionedBefore ? "wifi-offline" : "unprovisioned";
  }
  return String("online:") + (mqttClient.connected() ? "mqtt" : "wifi") + ":" +
         WiFi.localIP().toString();
}

void drawOledStatus() {
  if (!oledAvailable) {
    return;
  }

  oled.clearBuffer();
  oled.setFontPosTop();
  oled.setFont(u8g2_font_wqy12_t_gb2312);
  oled.drawUTF8(0, Config::kOledTopInset, "ESP32 消息设备");

  if (provisioningModeActive) {
    oled.drawUTF8(0, 16, "配网模式");
    oled.setFont(u8g2_font_5x7_tr);
    oled.drawStr(0, 34, apSsid.c_str());
    oled.drawStr(0, 46, "192.168.4.1");
  } else if (provisioningState == ProvisioningState::kConnecting) {
    oled.drawUTF8(0, 18, "正在连接 WiFi...");
  } else if (WiFi.status() == WL_CONNECTED) {
    oled.drawUTF8(0, 16, mqttClient.connected() ? "设备在线，等待消息" : "WiFi 已连接");
    oled.setFont(u8g2_font_5x7_tr);
    oled.drawStr(0, 34, WiFi.localIP().toString().c_str());
    oled.drawStr(0, 46, mqttClient.connected() ? "MQTT: connected" : "MQTT: connecting...");
  } else {
    oled.drawUTF8(0, 18, hasProvisionedBefore ? "WiFi 已断开" : "长按 BOOT 配网");
  }
  oled.sendBuffer();
}

void processOled() {
  if (!oledAvailable) {
    return;
  }

  const uint32_t now = millis();
  if (now - lastOledStatusRefreshAt < Config::kOledStatusRefreshIntervalMs) {
    return;
  }
  lastOledStatusRefreshAt = now;
  const String signature = oledStatusSignature();
  if (signature != lastOledStatusSignature) {
    lastOledStatusSignature = signature;
    if (oledHasMessage) {
      renderOledMessage();
    } else {
      drawOledStatus();
    }
  }
}

const char *authModeName(wifi_auth_mode_t mode) {
  switch (mode) {
    case WIFI_AUTH_OPEN:
      return "open";
    case WIFI_AUTH_WEP:
      return "wep";
    case WIFI_AUTH_WPA_PSK:
      return "wpa_psk";
    case WIFI_AUTH_WPA2_PSK:
      return "wpa2_psk";
    case WIFI_AUTH_WPA_WPA2_PSK:
      return "wpa_wpa2_psk";
    case WIFI_AUTH_WPA2_ENTERPRISE:
      return "wpa2_enterprise";
    case WIFI_AUTH_WPA3_PSK:
      return "wpa3_psk";
    case WIFI_AUTH_WPA2_WPA3_PSK:
      return "wpa2_wpa3_psk";
    default:
      return "unknown";
  }
}

void loadPersistentState() {
  preferences.begin(Config::kPreferencesNamespace, true);
  savedSsid = preferences.getString(Config::kSsidKey, "");
  savedPassword = preferences.getString(Config::kPasswordKey, "");
  hasProvisionedBefore = preferences.getBool(Config::kProvisionedKey, false);
  preferences.end();
}

bool saveSuccessfulCredentials(const String &ssid, const String &password) {
  if (!preferences.begin(Config::kPreferencesNamespace, false)) {
    return false;
  }
  const size_t ssidBytes = preferences.putString(Config::kSsidKey, ssid);
  const size_t passwordBytes = preferences.putString(Config::kPasswordKey, password);
  const size_t provisionedBytes = preferences.putBool(Config::kProvisionedKey, true);
  preferences.end();

  if (ssidBytes == 0 || provisionedBytes == 0 || (password.length() > 0 && passwordBytes == 0)) {
    return false;
  }

  savedSsid = ssid;
  savedPassword = password;
  hasProvisionedBefore = true;
  return true;
}

void clearPersistentState() {
  preferences.begin(Config::kPreferencesNamespace, false);
  preferences.clear();
  preferences.end();
  savedSsid = "";
  savedPassword = "";
  pendingSsid = "";
  pendingPassword = "";
  hasProvisionedBefore = false;
}

void handleOptions() {
  addCommonHeaders();
  server.send(204, "text/plain", "");
}

void stopBleProvisioning();

void handleDeviceInfo() {
  // Once the phone selects HTTP/SoftAP, leave the radio to Wi-Fi. BLE starts
  // again on the next BOOT provisioning session.
  if (!bleRequestActive && provisioningModeActive) {
    stopBleProvisioning();
    Serial.println("[ap] HTTP provisioning selected; BLE paused");
  }
  JsonDocument document;
  document["deviceModel"] = Config::kDeviceModel;
  document["deviceId"] = deviceId;
  document["firmwareVersion"] = Config::kFirmwareVersion;
  document["provisioningState"] = stateName(provisioningState);
  document["hasProvisionedBefore"] = hasProvisionedBefore;
  document["wifiMac"] = WiFi.macAddress();
  document["apSsid"] = provisioningModeActive ? apSsid : "";
  document["provisioningToken"] = provisioningModeActive ? provisioningToken : "";
  document["apIp"] = provisioningModeActive ? WiFi.softAPIP().toString() : "";
  document["connectedSsid"] = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";
  document["stationIp"] = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "";
  document["rssi"] = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
  sendJson(200, document);
}

void handleWifiScan() {
  if (!requireProvisioningToken()) {
    return;
  }
  if (provisioningState == ProvisioningState::kConnecting) {
    sendError(409, "WIFI_CONNECTION_IN_PROGRESS", "Cannot scan while connecting to Wi-Fi");
    return;
  }

  const uint32_t scanStartedAt = millis();
  Serial.printf("[wifi] Scan started via %s; AP clients: %u\n",
                bleRequestActive ? "BLE" : "HTTP", WiFi.softAPgetStationNum());
  const int networkCount = WiFi.scanNetworks(false, true);
  Serial.printf("[wifi] Scan finished in %lu ms; result: %d; AP clients: %u\n",
                static_cast<unsigned long>(millis() - scanStartedAt), networkCount,
                WiFi.softAPgetStationNum());
  if (networkCount < 0) {
    sendError(500, "WIFI_SCAN_FAILED", "Unable to scan nearby Wi-Fi networks");
    return;
  }

  JsonDocument document;
  document["success"] = true;
  JsonArray networks = document["networks"].to<JsonArray>();
  for (int i = 0; i < networkCount; ++i) {
    JsonObject network = networks.add<JsonObject>();
    network["ssid"] = WiFi.SSID(i);
    network["rssi"] = WiFi.RSSI(i);
    network["channel"] = WiFi.channel(i);
    network["security"] = authModeName(WiFi.encryptionType(i));
    network["hidden"] = WiFi.SSID(i).isEmpty();
  }
  WiFi.scanDelete();
  sendJson(200, document);
}

void beginStationConnection(const String &ssid, const String &password, bool fromApi) {
  pendingSsid = ssid;
  pendingPassword = password;
  connectionStartedFromApi = fromApi;
  lastErrorCode = "";
  lastErrorMessage = "";
  provisioningState = ProvisioningState::kConnecting;
  connectionStartedAt = millis();
  apShutdownAt = 0;

  if (provisioningModeActive) {
    WiFi.mode(WIFI_AP_STA);
  } else {
    WiFi.mode(WIFI_STA);
  }
  WiFi.setSleep(false);
  WiFi.disconnect(false, false);
  delay(100);
  WiFi.begin(pendingSsid.c_str(), pendingPassword.c_str());
  Serial.printf("[wifi] Connecting to SSID '%s' (password omitted)\n", pendingSsid.c_str());
}

void handleWifiConfig() {
  if (!requireProvisioningToken()) {
    return;
  }
  if (provisioningState == ProvisioningState::kConnecting) {
    sendError(409, "WIFI_CONNECTION_IN_PROGRESS", "A Wi-Fi connection attempt is already running");
    return;
  }

  const String body = bleRequestActive ? bleRequestBody : server.arg("plain");
  if (body.isEmpty() || body.length() > Config::kMaxRequestBodyBytes) {
    sendError(400, "INVALID_REQUEST_BODY", "Request body is empty or too large");
    return;
  }

  JsonDocument request;
  const DeserializationError error = deserializeJson(request, body);
  if (error) {
    sendError(400, "INVALID_JSON", "Request body must be valid JSON");
    return;
  }

  const char *ssidValue = request["ssid"] | "";
  const char *passwordValue = request["password"] | "";
  const String ssid(ssidValue);
  const String password(passwordValue);

  if (ssid.isEmpty() || ssid.length() > Config::kMaxSsidBytes) {
    sendError(422, "INVALID_SSID", "SSID must contain between 1 and 32 bytes");
    return;
  }
  if (password.length() > Config::kMaxPasswordBytes ||
      (!password.isEmpty() && password.length() < 8)) {
    sendError(422, "INVALID_PASSWORD", "Password must be empty or contain between 8 and 63 bytes");
    return;
  }

  beginStationConnection(ssid, password, true);

  JsonDocument response;
  response["success"] = true;
  response["message"] = "Wi-Fi configuration accepted";
  response["provisioningState"] = stateName(provisioningState);
  response["statusUrl"] = String(Config::kApiPrefix) + "/wifi/status";
  sendJson(202, response);
}

void handleWifiStatus() {
  if (!requireProvisioningToken()) {
    return;
  }

  JsonDocument document;
  document["success"] = provisioningState != ProvisioningState::kFailed;
  document["provisioningState"] = stateName(provisioningState);
  document["hasProvisionedBefore"] = hasProvisionedBefore;
  document["connected"] = WiFi.status() == WL_CONNECTED;
  document["connectedSsid"] = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";
  document["stationIp"] = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "";
  document["rssi"] = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
  document["apShutdownInMs"] = apShutdownAt > millis() ? apShutdownAt - millis() : 0;
  if (!lastErrorCode.isEmpty()) {
    JsonObject error = document["error"].to<JsonObject>();
    error["code"] = lastErrorCode;
    error["message"] = lastErrorMessage;
  }
  sendJson(200, document);

  // The App has received the final success response, so the AP can now close
  // without racing the status request itself.
  if (provisioningModeActive && provisioningState == ProvisioningState::kConnected) {
    apShutdownAt = millis() + Config::kApShutdownAfterStatusMs;
  }
}

void handleWifiReset() {
  if (!requireProvisioningToken()) {
    return;
  }
  clearPersistentState();
  provisioningState = ProvisioningState::kResetting;
  rebootAt = millis() + Config::kRebootDelayMs;
  sendSuccess(202, "Wi-Fi configuration cleared; device will reboot");
}

void handleDeviceReboot() {
  if (!requireProvisioningToken()) {
    return;
  }
  rebootAt = millis() + Config::kRebootDelayMs;
  sendSuccess(202, "Device will reboot");
}

void handleRoot() {
  JsonDocument document;
  document["device"] = Config::kDeviceModel;
  document["firmwareVersion"] = Config::kFirmwareVersion;
  document["api"] = String(Config::kApiPrefix) + "/device";
  sendJson(200, document);
}

void handleNotFound() {
  if (provisioningModeActive && !server.uri().startsWith(Config::kApiPrefix)) {
    server.sendHeader("Location", String("http://") + WiFi.softAPIP().toString() + "/", true);
    server.send(302, "text/plain", "");
    return;
  }
  sendError(404, "NOT_FOUND", "API endpoint not found");
}

void configureHttpRoutes() {
  const char *headerKeys[] = {"X-Provisioning-Token", "Content-Type"};
  server.collectHeaders(headerKeys, 2);

  server.on("/", HTTP_GET, handleRoot);
  server.on(String(Config::kApiPrefix) + "/device", HTTP_GET, handleDeviceInfo);
  server.on(String(Config::kApiPrefix) + "/wifi/scan", HTTP_GET, handleWifiScan);
  server.on(String(Config::kApiPrefix) + "/wifi/config", HTTP_POST, handleWifiConfig);
  server.on(String(Config::kApiPrefix) + "/wifi/status", HTTP_GET, handleWifiStatus);
  server.on(String(Config::kApiPrefix) + "/wifi/reset", HTTP_POST, handleWifiReset);
  server.on(String(Config::kApiPrefix) + "/device/reboot", HTTP_POST, handleDeviceReboot);

  server.on("/generate_204", HTTP_GET, handleRoot);
  server.on("/hotspot-detect.html", HTTP_GET, handleRoot);
  server.on("/connecttest.txt", HTTP_GET, handleRoot);

  server.onNotFound([]() {
    if (server.method() == HTTP_OPTIONS) {
      handleOptions();
    } else {
      handleNotFound();
    }
  });
}

#include "ble_provisioning.h"

void startProvisioningMode() {
  if (provisioningModeActive) {
    provisioningState = ProvisioningState::kAwaitingConfig;
    return;
  }

  String ssidSuffix = deviceId.substring(deviceId.length() - 3);
  ssidSuffix.toLowerCase();
  apSsid = "esp32-" + ssidSuffix;
  provisioningToken = makeProvisioningToken();

  // ESP32 has one 2.4 GHz radio, so AP and STA must share a channel.  Starting
  // an AP on fixed channel 1 while the saved home Wi-Fi uses another channel
  // can make esp_wifi_set_config() fail when STA is already connected.
  const uint8_t stationChannel = WiFi.status() == WL_CONNECTED ? WiFi.channel() : 0;
  const uint8_t apChannel = stationChannel >= 1 && stationChannel <= 13 ? stationChannel : 1;

  WiFi.mode(WIFI_AP_STA);
  const IPAddress apIp(192, 168, 4, 1);
  const IPAddress gateway(192, 168, 4, 1);
  const IPAddress subnet(255, 255, 255, 0);

  if (!WiFi.softAP(apSsid.c_str(), nullptr, apChannel, false, 4)) {
    provisioningState = ProvisioningState::kFailed;
    lastErrorCode = "SOFT_AP_START_FAILED";
    lastErrorMessage = "Unable to start provisioning access point";
    rgbLedWrite(RGB_BUILTIN, Config::kButtonHoldLedBrightness, 0, 0);
    Serial.printf("[ap] Failed to start SoftAP on channel %u\n", apChannel);
    return;
  }

  if (!WiFi.softAPConfig(apIp, gateway, subnet)) {
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_STA);
    provisioningState = WiFi.status() == WL_CONNECTED ? ProvisioningState::kConnected
                                                       : ProvisioningState::kFailed;
    lastErrorCode = "SOFT_AP_IP_CONFIG_FAILED";
    lastErrorMessage = "Unable to configure provisioning access point IP";
    rgbLedWrite(RGB_BUILTIN, Config::kButtonHoldLedBrightness, 0, 0);
    Serial.println("[ap] Failed to configure SoftAP IP");
    return;
  }

  dnsServer.start(Config::kDnsPort, "*", apIp);
  server.begin();
  httpServerRunning = true;
  provisioningModeActive = true;
  startBleProvisioning();
  provisioningState = ProvisioningState::kAwaitingConfig;

  Serial.println("[ap] Provisioning mode started");
  Serial.printf("[ap] SSID: %s\n", apSsid.c_str());
  Serial.printf("[ap] Channel: %u\n", apChannel);
  Serial.println("[ap] Security: open (no password)");
  Serial.printf("[ap] API: http://%s%s/device\n", apIp.toString().c_str(), Config::kApiPrefix);
}

void stopProvisioningMode() {
  if (!provisioningModeActive) {
    return;
  }
  stopBleProvisioning();
  dnsServer.stop();
  server.stop();
  httpServerRunning = false;
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  provisioningModeActive = false;
  apShutdownAt = 0;
  lastLedUpdateAt = 0;

  if (connectionStartedFromApi && provisioningState == ProvisioningState::kConnecting) {
    WiFi.disconnect(false, false);
    pendingSsid = "";
    pendingPassword = "";
    connectionStartedFromApi = false;
    if (!savedSsid.isEmpty()) {
      beginStationConnection(savedSsid, savedPassword, false);
    } else {
      WiFi.mode(WIFI_OFF);
      provisioningState = ProvisioningState::kUnprovisioned;
    }
  } else {
    provisioningState = WiFi.status() == WL_CONNECTED ? ProvisioningState::kConnected
                                                       : ProvisioningState::kUnprovisioned;
  }
  if (provisioningState == ProvisioningState::kConnected) {
    rgbLedWrite(RGB_BUILTIN, 0, Config::kConnectedLedBrightness, 0);
  } else {
    rgbLedWrite(RGB_BUILTIN, 0, 0, 0);
  }
  Serial.println("[ap] Provisioning mode stopped");
}

void finishSuccessfulConnection() {
  const bool credentialsStored = saveSuccessfulCredentials(pendingSsid, pendingPassword);
  if (!credentialsStored) {
    provisioningState = ProvisioningState::kFailed;
    lastErrorCode = "NVS_WRITE_FAILED";
    lastErrorMessage = "Connected to Wi-Fi but failed to save credentials";
    Serial.println("[nvs] Failed to save Wi-Fi credentials");
    if (!provisioningModeActive) {
      startProvisioningMode();
    }
    return;
  }

  provisioningState = ProvisioningState::kConnected;
  lastErrorCode = "";
  lastErrorMessage = "";
  rgbLedWrite(RGB_BUILTIN, 0, Config::kConnectedLedBrightness, 0);
  Serial.printf("[wifi] Connected, IP: %s\n", WiFi.localIP().toString().c_str());

  if (provisioningModeActive && connectionStartedFromApi) {
    apShutdownAt = millis() + Config::kApShutdownDelayMs;
    Serial.printf("[ap] Will stop in %lu ms\n", Config::kApShutdownDelayMs);
  }
}

void finishFailedConnection() {
  const wl_status_t status = WiFi.status();
  if (status == WL_NO_SSID_AVAIL) {
    lastErrorCode = "WIFI_NOT_FOUND";
    lastErrorMessage = "Target Wi-Fi network was not found";
  } else if (status == WL_CONNECT_FAILED) {
    lastErrorCode = "WIFI_AUTH_OR_CONNECTION_FAILED";
    lastErrorMessage = "Authentication or connection failed";
  } else {
    lastErrorCode = "WIFI_CONNECTION_TIMEOUT";
    lastErrorMessage = "Timed out while connecting to target Wi-Fi";
  }

  WiFi.disconnect(false, false);
  provisioningState = ProvisioningState::kFailed;
  Serial.printf("[wifi] Connection failed: %s\n", lastErrorCode.c_str());

}

void processConnectionState() {
  if (provisioningState != ProvisioningState::kConnecting) {
    return;
  }
  if (WiFi.status() == WL_CONNECTED && WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
    finishSuccessfulConnection();
    return;
  }
  if (millis() - connectionStartedAt >= Config::kConnectionTimeoutMs) {
    finishFailedConnection();
  }
}

void processBootButton() {
  const uint32_t now = millis();
  const bool rawPressed = digitalRead(Config::kBootButtonPin) == LOW;

  if (rawPressed != bootButtonRawPressed) {
    bootButtonRawPressed = rawPressed;
    bootButtonChangedAt = now;
  }

  if (rawPressed != bootButtonPressed && now - bootButtonChangedAt >= Config::kButtonDebounceMs) {
    bootButtonPressed = rawPressed;
    if (bootButtonPressed) {
      bootButtonPressedAt = now;
      bootLongPressHandled = false;
      Serial.println("[button] BOOT pressed");
    } else {
      const uint32_t heldMs = now - bootButtonPressedAt;
      Serial.printf("[button] BOOT released after %lu ms\n", heldMs);
      if (!bootLongPressHandled && provisioningModeActive) {
        Serial.println("[button] Exiting provisioning mode");
        stopProvisioningMode();
      } else if (!provisioningModeActive && provisioningState == ProvisioningState::kConnected) {
        rgbLedWrite(RGB_BUILTIN, 0, Config::kConnectedLedBrightness, 0);
      }
      bootButtonPressedAt = 0;
      bootLongPressHandled = false;
    }
  }

  if (bootButtonPressed && !bootLongPressHandled && !provisioningModeActive) {
    // Immediate blue feedback confirms that the physical BOOT press was detected.
    rgbLedWrite(RGB_BUILTIN, 0, 0, Config::kButtonHoldLedBrightness);
    if (now - bootButtonPressedAt >= Config::kProvisioningHoldMs) {
      bootLongPressHandled = true;
      Serial.printf("[button] BOOT held for %lu ms; entering provisioning mode\n",
                    now - bootButtonPressedAt);
      startProvisioningMode();
    }
  }
}

void processProvisioningLed() {
  if (!provisioningModeActive || provisioningState == ProvisioningState::kConnected) {
    return;
  }

  const uint32_t now = millis();
  if (now - lastLedUpdateAt < Config::kLedUpdateIntervalMs) {
    return;
  }
  lastLedUpdateAt = now;

  const uint32_t halfPeriod = Config::kLedBreathingPeriodMs / 2;
  const uint32_t phase = now % Config::kLedBreathingPeriodMs;
  const uint32_t ramp = phase < halfPeriod ? phase : Config::kLedBreathingPeriodMs - phase;
  const uint8_t range = Config::kLedMaximumBrightness - Config::kLedMinimumBrightness;
  const uint8_t brightness = Config::kLedMinimumBrightness + (range * ramp) / halfPeriod;
  rgbLedWrite(RGB_BUILTIN, 0, 0, brightness);
}

bool hasValidSystemTime() {
  // TLS certificate validation requires a plausible wall-clock time.
  return time(nullptr) >= 1704067200;  // 2024-01-01T00:00:00Z
}

void publishMqttState(bool online) {
  if (!mqttClient.connected()) {
    return;
  }

  JsonDocument document;
  document["deviceId"] = deviceId;
  document["online"] = online;
  document["firmwareVersion"] = Config::kFirmwareVersion;
  document["ip"] = online ? WiFi.localIP().toString() : "";
  document["timestamp"] = static_cast<uint64_t>(time(nullptr)) * 1000ULL;
  const String payload = jsonString(document);
  mqttClient.publish(mqttStateTopic.c_str(), payload.c_str(), true);
}

void publishCommandAck(const char *messageId, const char *status, const char *errorCode = nullptr) {
  if (!mqttClient.connected()) {
    return;
  }

  JsonDocument document;
  document["messageId"] = messageId;
  document["deviceId"] = deviceId;
  document["status"] = status;
  document["receivedAt"] = static_cast<uint64_t>(time(nullptr)) * 1000ULL;
  if (errorCode != nullptr) {
    document["errorCode"] = errorCode;
  }
  const String payload = jsonString(document);
  if (!mqttClient.publish(mqttAckTopic.c_str(), payload.c_str(), false)) {
    Serial.println("[mqtt] Failed to publish command acknowledgement");
  }
}

void handleMqttMessage(char *topic, byte *payload, unsigned int length) {
  if (mqttCommandTopic != topic) {
    return;
  }

  JsonDocument document;
  const DeserializationError error = deserializeJson(document, payload, length);
  if (error) {
    Serial.printf("[mqtt] Invalid command JSON: %s\n", error.c_str());
    publishCommandAck("", "rejected", "INVALID_JSON");
    return;
  }

  const char *messageId = document["messageId"] | "";
  const char *targetDeviceId = document["deviceId"] | "";
  const char *type = document["type"] | "";
  const char *text = document["text"] | "";
  const uint32_t displayDurationMs = document["displayDurationMs"] | 10000;
  const uint32_t buzzerDurationMs = document["buzzerDurationMs"] | 3000;
  uint64_t sentAtMs = document["sentAt"].as<uint64_t>();
  if (sentAtMs == 0 && hasValidSystemTime()) {
    sentAtMs = static_cast<uint64_t>(time(nullptr)) * 1000ULL;
  }

  if (messageId[0] == '\0' || strcmp(targetDeviceId, deviceId.c_str()) != 0 ||
      strcmp(type, "display") != 0 || text[0] == '\0') {
    Serial.println("[mqtt] Rejected command with invalid fields");
    publishCommandAck(messageId, "rejected", "INVALID_COMMAND");
    return;
  }

  Serial.println();
  Serial.println("[mqtt] Display command received");
  Serial.printf("[mqtt] Message ID: %s\n", messageId);
  Serial.printf("[mqtt] Text: %s\n", text);
  Serial.printf("[mqtt] Display duration: %lu ms\n", displayDurationMs);
  Serial.printf("[mqtt] Buzzer duration budget: %lu ms\n", buzzerDurationMs);
  Serial.printf("[mqtt] Sent at: %llu\n", sentAtMs);

  // The latest message remains visible until another message replaces it.
  // displayDurationMs stays in the protocol for compatibility but no longer
  // clears the OLED.
  showOledMessage(String(text), sentAtMs);
  startBuzzerNotification(buzzerDurationMs);

  // A short blue flash accompanies the audible notification.
  if (!provisioningModeActive) {
    rgbLedWrite(RGB_BUILTIN, 0, 0, Config::kButtonHoldLedBrightness);
    notificationLedUntil = millis() + MqttConfig::kNotificationLedDurationMs;
  }
  publishCommandAck(messageId, "received");
}

void logMqttNetworkDiagnostics() {
  Serial.printf("[mqtt] Wi-Fi status=%d, RSSI=%d dBm, free heap=%u bytes\n",
                WiFi.status(), WiFi.RSSI(), ESP.getFreeHeap());
  Serial.printf("[mqtt] Gateway=%s, DNS=%s, epoch=%lld\n",
                WiFi.gatewayIP().toString().c_str(), WiFi.dnsIP().toString().c_str(),
                static_cast<long long>(time(nullptr)));

  IPAddress brokerIp;
  if (!WiFi.hostByName(MqttConfig::kHost, brokerIp)) {
    Serial.printf("[mqtt] DNS lookup failed for %s\n", MqttConfig::kHost);
  } else {
    Serial.printf("[mqtt] DNS resolved %s to %s\n", MqttConfig::kHost,
                  brokerIp.toString().c_str());
    constexpr uint16_t probePorts[] = {8883, 8084, 8443};
    for (const uint16_t port : probePorts) {
      WiFiClient tcpProbe;
      const bool tcpReachable = tcpProbe.connect(
          brokerIp, port, MqttConfig::kTcpProbeTimeoutMs);
      Serial.printf("[mqtt] TCP probe %s:%u: %s\n", brokerIp.toString().c_str(),
                    port, tcpReachable ? "reachable" : "failed");
      tcpProbe.stop();
    }
  }

  IPAddress cloudflareIp;
  if (WiFi.hostByName("odd-river-673a.srect2017.workers.dev", cloudflareIp)) {
    WiFiClient httpsProbe;
    const bool httpsReachable = httpsProbe.connect(
        cloudflareIp, 443, MqttConfig::kTcpProbeTimeoutMs);
    Serial.printf("[mqtt] HTTPS control probe %s:443: %s\n",
                  cloudflareIp.toString().c_str(),
                  httpsReachable ? "reachable" : "failed");
    httpsProbe.stop();
  } else {
    Serial.println("[mqtt] HTTPS control DNS lookup failed");
  }

  char tlsError[160] = {};
  const int tlsErrorCode = mqttTlsClient.lastError(tlsError, sizeof(tlsError));
  Serial.printf("[mqtt] TLS last error: %d (%s)\n", tlsErrorCode,
                tlsError[0] == '\0' ? "no detail" : tlsError);
}

void recoverMqttNetwork() {
  mqttConsecutiveFailures = 0;
  mqttTlsClient.stop();
  Serial.println("[mqtt] Reconnecting Wi-Fi after repeated MQTT failures");
  if (!savedSsid.isEmpty()) {
    beginStationConnection(savedSsid, savedPassword, false);
  }
}

void connectMqtt() {
  if (MQTT_PASSWORD[0] == '\0') {
    if (!mqttPasswordWarningPrinted) {
      Serial.println("[mqtt] MQTT_PASSWORD is not configured; MQTT is disabled");
      mqttPasswordWarningPrinted = true;
    }
    return;
  }

  const String offlinePayload = String("{\"deviceId\":\"") + deviceId +
                                "\",\"online\":false}";
  Serial.printf("[mqtt] Connecting to %s:%u\n", MqttConfig::kHost, MqttConfig::kPort);
  const bool connected = mqttClient.connect(
      mqttClientId.c_str(), mqttClientId.c_str(), MQTT_PASSWORD, mqttStateTopic.c_str(), 1, true,
      offlinePayload.c_str(), true);
  if (!connected) {
    mqttConsecutiveFailures++;
    Serial.printf("[mqtt] Connection failed, state=%d; retrying later\n", mqttClient.state());
    if (mqttConsecutiveFailures == 1 ||
        mqttConsecutiveFailures >= MqttConfig::kFailuresBeforeWifiRecovery) {
      logMqttNetworkDiagnostics();
    }
    if (mqttConsecutiveFailures >= MqttConfig::kFailuresBeforeWifiRecovery) {
      recoverMqttNetwork();
    }
    return;
  }

  mqttConsecutiveFailures = 0;
  Serial.println("[mqtt] TLS connection established");
  if (!mqttClient.subscribe(mqttCommandTopic.c_str(), 1)) {
    Serial.println("[mqtt] Failed to subscribe to command topic");
    mqttClient.disconnect();
    return;
  }
  Serial.printf("[mqtt] Subscribed: %s\n", mqttCommandTopic.c_str());
  publishMqttState(true);
}

void processMqtt() {
  if (WiFi.status() != WL_CONNECTED) {
    if (mqttClient.connected()) {
      mqttClient.disconnect();
    }
    timeSyncStarted = false;
    return;
  }

  const uint32_t now = millis();
  if (!hasValidSystemTime()) {
    if (!timeSyncStarted) {
      configTime(0, 0, "pool.ntp.org", "time.cloudflare.com");
      timeSyncStarted = true;
      Serial.println("[time] Synchronizing clock for TLS validation");
    }
    if (now - lastTimeSyncCheckAt >= MqttConfig::kTimeSyncRetryIntervalMs) {
      lastTimeSyncCheckAt = now;
      Serial.println("[time] Waiting for NTP synchronization");
    }
    return;
  }

  // TLS handshakes/diagnostics can block for seconds; finish local provisioning first.
  if (!mqttClient.connected() && provisioningModeActive) return;

  if (!mqttClient.connected()) {
    if (lastMqttReconnectAt == 0 || now - lastMqttReconnectAt >= MqttConfig::kReconnectIntervalMs) {
      lastMqttReconnectAt = now;
      connectMqtt();
    }
    return;
  }
  mqttClient.loop();
}

void processNotificationLed() {
  if (notificationLedUntil == 0 || provisioningModeActive || bootButtonPressed) {
    return;
  }
  if (static_cast<int32_t>(millis() - notificationLedUntil) >= 0) {
    notificationLedUntil = 0;
    if (WiFi.status() == WL_CONNECTED) {
      rgbLedWrite(RGB_BUILTIN, 0, Config::kConnectedLedBrightness, 0);
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);
  pinMode(Config::kBootButtonPin, INPUT_PULLUP);
  digitalWrite(Config::kBuzzerPin, LOW);
  pinMode(Config::kBuzzerPin, OUTPUT);
  WiFi.setSleep(false);
  rgbLedWrite(RGB_BUILTIN, 0, 0, 0);
  initializeOled();

  deviceId = formatDeviceId();
  mqttClientId = "esp32-" + deviceId;
  mqttCommandTopic = "devices/" + deviceId + "/commands/display";
  mqttAckTopic = "devices/" + deviceId + "/ack";
  mqttStateTopic = "devices/" + deviceId + "/state";
  mqttTlsClient.setCACert(MqttConfig::kCaCertificate);
  mqttTlsClient.setHandshakeTimeout(MqttConfig::kTlsHandshakeTimeoutSeconds);
  mqttClient.setServer(MqttConfig::kHost, MqttConfig::kPort);
  mqttClient.setCallback(handleMqttMessage);
  mqttClient.setKeepAlive(MqttConfig::kKeepAliveSeconds);
  mqttClient.setBufferSize(MqttConfig::kPacketBufferBytes);
  configureHttpRoutes();
  loadPersistentState();

  Serial.println();
  Serial.println("============================================");
  Serial.printf("%s Wi-Fi provisioning firmware %s\n", Config::kDeviceModel,
                Config::kFirmwareVersion);
  Serial.printf("Device ID: %s\n", deviceId.c_str());
  Serial.printf("Flash: %u MB, PSRAM: %u MB\n", ESP.getFlashChipSize() / 1048576,
                ESP.getPsramSize() / 1048576);
  Serial.printf("Previously provisioned: %s\n", hasProvisionedBefore ? "yes" : "no");
  Serial.println("Hold BOOT for 5 seconds to enter provisioning mode");
  Serial.println("Press BOOT once while provisioning to stop the access point");
  Serial.printf("MQTT command topic: %s\n", mqttCommandTopic.c_str());
  Serial.printf("MQTT client ID: %s\n", mqttClientId.c_str());
  Serial.printf("OLED: %s\n", oledAvailable ? "SSD1306 ready" : "not detected");
  Serial.printf("Buzzer: GPIO%u, high-level trigger\n", Config::kBuzzerPin);
  Serial.println("============================================");

  if (!savedSsid.isEmpty()) {
    beginStationConnection(savedSsid, savedPassword, false);
  } else {
    WiFi.mode(WIFI_OFF);
    provisioningState = ProvisioningState::kUnprovisioned;
    Serial.println("[wifi] No saved credentials; hold BOOT for 5 seconds to provision");
  }
}

void loop() {
  processBootButton();
  processBleProvisioning();
  processConnectionState();
  processMqtt();
  processProvisioningLed();
  processNotificationLed();
  processBuzzer();
  processOled();

  if (provisioningModeActive) {
    dnsServer.processNextRequest();
  }
  if (httpServerRunning) {
    server.handleClient();
  }

  if (bleResponse.isEmpty() && apShutdownAt != 0 && static_cast<int32_t>(millis() - apShutdownAt) >= 0) {
    stopProvisioningMode();
  }
  if (rebootAt != 0 && static_cast<int32_t>(millis() - rebootAt) >= 0) {
    Serial.println("[system] Rebooting");
    delay(50);
    ESP.restart();
  }

  delay(2);
}
