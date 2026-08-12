#include <Arduino.h>
#include <ArduinoJson.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_system.h>

namespace Config {
constexpr char kFirmwareVersion[] = "0.3.2";
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

Preferences preferences;
WebServer server(Config::kHttpPort);
DNSServer dnsServer;

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

void addCommonHeaders() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type, X-Provisioning-Token");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Cache-Control", "no-store");
}

void sendJson(int statusCode, const JsonDocument &document) {
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
  if (!server.hasHeader("X-Provisioning-Token") ||
      server.header("X-Provisioning-Token") != provisioningToken) {
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

void handleDeviceInfo() {
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

  const int networkCount = WiFi.scanNetworks(false, true);
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

  const String body = server.arg("plain");
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

void setup() {
  Serial.begin(115200);
  delay(500);
  pinMode(Config::kBootButtonPin, INPUT_PULLUP);
  rgbLedWrite(RGB_BUILTIN, 0, 0, 0);

  deviceId = formatDeviceId();
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
  processConnectionState();
  processProvisioningLed();

  if (provisioningModeActive) {
    dnsServer.processNextRequest();
  }
  if (httpServerRunning) {
    server.handleClient();
  }

  if (apShutdownAt != 0 && static_cast<int32_t>(millis() - apShutdownAt) >= 0) {
    stopProvisioningMode();
  }
  if (rebootAt != 0 && static_cast<int32_t>(millis() - rebootAt) >= 0) {
    Serial.println("[system] Rebooting");
    delay(50);
    ESP.restart();
  }

  delay(2);
}
