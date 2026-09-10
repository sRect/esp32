#pragma once

// Newline-delimited JSON over a private GATT service. Writes and indications
// use 20-byte fragments, so this works even with the default ATT MTU of 23.
constexpr char kBleServiceUuid[] = "a8e10001-73bd-4d74-a613-0b9c78d2f001";
constexpr char kBleRxUuid[] = "a8e10002-73bd-4d74-a613-0b9c78d2f001";
constexpr char kBleTxUuid[] = "a8e10003-73bd-4d74-a613-0b9c78d2f001";
struct BleRequestFrame {
  uint32_t generation;
  char json[1024];
};
QueueHandle_t bleRequests = nullptr;
BLEServer *bleServer = nullptr;
BLECharacteristic *bleTx = nullptr;
std::atomic<bool> bleEnabled{false};
std::atomic<bool> bleConnected{false};
std::atomic<uint32_t> bleGeneration{0};
// Host callbacks run on the BLE task, independently of the Arduino loop.
std::atomic<int> bleIndicationResult{0}; // 0 waiting, 1 acknowledged, -1 failed
bool bleTxPending = false;
uint32_t bleTxStartedAt = 0;
size_t bleTxCount = 0;
uint32_t bleHandledGeneration = 0;
size_t bleResponseOffset = 0;

class ProvisioningBleServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleGeneration.fetch_add(1);
    bleConnected = true;
    Serial.println("[ble] Client connected");
    if (!bleEnabled) server->disconnect(server->getConnId());
  }
  void onDisconnect(BLEServer *) override {
    Serial.println("[ble] Client disconnected");
    bleConnected = false;
    bleGeneration.fetch_add(1);
  }
};

class ProvisioningBleRxCallbacks : public BLECharacteristicCallbacks {
  String buffer;
  uint32_t generation = 0;
  bool oversized = false;
  void onWrite(BLECharacteristic *characteristic) override {
    if (!bleEnabled || !bleConnected) return;
    const uint32_t current = bleGeneration.load();
    if (current != generation) {
      buffer = "";
      oversized = false;
      generation = current;
    }
    const String fragment = characteristic->getValue();
    for (size_t i = 0; i < fragment.length(); ++i) {
      const char c = fragment[i];
      if (c == '\n') {
        BleRequestFrame frame{};
        frame.generation = current;
        // An invalid frame yields an explicit JSON error on the main loop.
        (oversized ? String("!") : buffer).toCharArray(frame.json, sizeof(frame.json));
        xQueueSend(bleRequests, &frame, 0);
        buffer = "";
        oversized = false;
      } else if (!oversized) {
        if (buffer.length() >= sizeof(BleRequestFrame::json) - 1) {
          oversized = true;
          buffer = "";
        } else {
          buffer += c;
        }
      }
    }
  }
};

class ProvisioningBleTxCallbacks : public BLECharacteristicCallbacks {
  void onStatus(BLECharacteristic *, Status status, uint32_t code) override {
    const bool acknowledged = status == SUCCESS_INDICATE;
    bleIndicationResult.store(acknowledged ? 1 : -1);
    if (!acknowledged) {
      Serial.printf("[ble] Indication failed: status=%d code=%lu\n",
                    static_cast<int>(status), static_cast<unsigned long>(code));
    }
  }
};

void startBleProvisioning() {
  if (bleServer == nullptr) {
    bleRequests = xQueueCreate(2, sizeof(BleRequestFrame));
    if (bleRequests == nullptr) {
      Serial.println("[ble] Unable to allocate request queue");
      return;
    }
    BLEDevice::init(apSsid);
    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new ProvisioningBleServerCallbacks());
    BLEService *service = bleServer->createService(kBleServiceUuid);
    BLECharacteristic *rx = service->createCharacteristic(kBleRxUuid, BLECharacteristic::PROPERTY_WRITE);
    rx->setCallbacks(new ProvisioningBleRxCallbacks());
    bleTx = service->createCharacteristic(kBleTxUuid, BLECharacteristic::PROPERTY_INDICATE);
    bleTx->addDescriptor(new BLE2902());
    bleTx->setCallbacks(new ProvisioningBleTxCallbacks());
    service->start();
    BLEAdvertising *advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(kBleServiceUuid);
    advertising->setScanResponse(true);
  }
  bleEnabled = true;
  bleGeneration.fetch_add(1);
  BLEDevice::startAdvertising();
  Serial.printf("[ble] Provisioning available: %s\n", apSsid.c_str());
}

void stopBleProvisioning() {
  if (bleServer == nullptr) return;
  bleEnabled = false;
  BLEDevice::stopAdvertising();
  if (bleConnected) bleServer->disconnect(bleServer->getConnId());
  bleGeneration.fetch_add(1);
  xQueueReset(bleRequests);
  bleResponse = "";
  bleResponseOffset = 0;
  bleTxPending = false;
  bleRequestBody = "";
  bleRequestToken = "";
}

void processBleProvisioning() {
  if (bleServer == nullptr) return;
  const uint32_t generation = bleGeneration.load();
  if (generation != bleHandledGeneration) {
    bleHandledGeneration = generation;
    bleResponse = "";
    bleResponseOffset = 0;
    bleTxPending = false;
    bleIndicationResult = 0;
    if (bleEnabled && !bleConnected) BLEDevice::startAdvertising();
  }
  if (!bleEnabled || !bleConnected) return;
  if (!bleResponse.isEmpty()) {
    if (bleTxPending) {
      const int result = bleIndicationResult.load();
      if (result == 0 && millis() - bleTxStartedAt < 5000) return;
      bleTxPending = false;
      if (result != 1) {
        Serial.println("[ble] Response acknowledgement failed; closing session");
        bleServer->disconnect(bleServer->getConnId());
        bleResponse = "";
        bleResponseOffset = 0;
        return;
      }
      bleResponseOffset += bleTxCount;
      if (bleResponseOffset == bleResponse.length()) {
        Serial.printf("[ble] Response acknowledged: %u bytes\n", static_cast<unsigned>(bleResponse.length()));
        bleResponse = "";
        bleResponseOffset = 0;
      }
      return;
    }
    const size_t count = min(static_cast<size_t>(20), bleResponse.length() - bleResponseOffset);
    bleIndicationResult = 0;
    bleTxPending = true;
    bleTxStartedAt = millis();
    bleTxCount = count;
#if defined(CONFIG_NIMBLE_ENABLED)
    // Arduino-ESP32 3.3.10's NimBLE indicate() waits on a semaphore which
    // BLE_GAP_EVENT_NOTIFY_TX never releases. It reports a false timeout even
    // after SUCCESS_INDICATE. Submit directly and advance only in the loop
    // after the real confirmation delivered through onStatus().
    os_mbuf *packet = ble_hs_mbuf_from_flat(bleResponse.c_str() + bleResponseOffset, count);
    if (packet == nullptr) {
      bleIndicationResult = -1;
    } else {
      const int rc = ble_gatts_indicate_custom(bleServer->getConnId(), bleTx->getHandle(), packet);
      if (rc != 0) {
        Serial.printf("[ble] Indication submission failed: %d\n", rc);
        bleIndicationResult = -1;
      }
    }
#else
    bleTx->setValue(reinterpret_cast<uint8_t *>(const_cast<char *>(bleResponse.c_str() + bleResponseOffset)), count);
    bleTx->indicate();
#endif
    return;
  }
  BleRequestFrame frame{};
  if (xQueueReceive(bleRequests, &frame, 0) != pdTRUE || frame.generation != generation) return;
  bleRequestActive = true;
  JsonDocument request;
  if (deserializeJson(request, frame.json)) {
    sendError(400, "INVALID_JSON", "Invalid or oversized BLE request");
  } else {
    bleRequestToken = request["token"] | "";
    bleRequestBody = "";
    serializeJson(request["body"], bleRequestBody);
    const String path = request["path"] | "";
    const String method = request["method"] | "";
    Serial.printf("[ble] Request %s %s\n", method.c_str(), path.c_str());
    if (path == "/device" && method == "GET") handleDeviceInfo();
    else if (path == "/wifi/scan" && method == "GET") handleWifiScan();
    else if (path == "/wifi/config" && method == "POST") handleWifiConfig();
    else if (path == "/wifi/status" && method == "GET") handleWifiStatus();
    else sendError(404, "NOT_FOUND", "Unknown BLE operation");
  }
  memset(frame.json, 0, sizeof(frame.json));
  bleRequestToken = "";
  bleRequestBody = "";
  bleRequestActive = false;
}
