#include <Arduino.h>
#include <Esp.h>
#include "esp_chip_info.h"
#include "esp_heap_caps.h"

static const char *flashModeName(FlashMode_t mode) {
  switch (mode) {
    case FM_QIO: return "QIO";
    case FM_QOUT: return "QOUT";
    case FM_DIO: return "DIO";
    case FM_DOUT: return "DOUT";
    case FM_FAST_READ: return "FAST_READ";
    case FM_SLOW_READ: return "SLOW_READ";
    default: return "UNKNOWN";
  }
}

static void printBytes(const char *label, uint64_t bytes) {
  Serial.printf("%-24s %llu bytes (%.2f MiB)\n", label,
                (unsigned long long)bytes, bytes / 1048576.0);
}

static bool testPsram() {
  const size_t testSize = 1024 * 1024;
  uint8_t *buffer = (uint8_t *)heap_caps_malloc(testSize, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (!buffer) return false;

  for (size_t i = 0; i < testSize; ++i) buffer[i] = (uint8_t)(i ^ (i >> 8));
  for (size_t i = 0; i < testSize; ++i) {
    if (buffer[i] != (uint8_t)(i ^ (i >> 8))) {
      free(buffer);
      return false;
    }
  }
  free(buffer);
  return true;
}

static void printReport() {
  esp_chip_info_t chipInfo;
  esp_chip_info(&chipInfo);

  Serial.println();
  Serial.println("========================================");
  Serial.println(" ESP32-S3 hardware check");
  Serial.println("========================================");
  Serial.printf("Chip model:              %s\n", ESP.getChipModel());
  Serial.printf("Chip revision:           %d\n", ESP.getChipRevision());
  Serial.printf("CPU cores:               %d\n", ESP.getChipCores());
  Serial.printf("CPU frequency:           %u MHz\n", ESP.getCpuFreqMHz());
  Serial.printf("ESP-IDF version:         %s\n", ESP.getSdkVersion());
  Serial.printf("Embedded flash feature:  %s\n",
                (chipInfo.features & CHIP_FEATURE_EMB_FLASH) ? "yes" : "no/unknown");
  Serial.printf("Embedded PSRAM feature:  %s\n",
                (chipInfo.features & CHIP_FEATURE_EMB_PSRAM) ? "yes" : "no/unknown");
  Serial.printf("eFuse MAC:               %012llX\n",
                (unsigned long long)ESP.getEfuseMac());

  Serial.println("----------------------------------------");
  printBytes("Flash chip size:", ESP.getFlashChipSize());
  Serial.printf("%-24s %u MHz\n", "Flash speed:", ESP.getFlashChipSpeed() / 1000000);
  Serial.printf("%-24s %s\n", "Flash mode:", flashModeName(ESP.getFlashChipMode()));
  printBytes("Sketch size:", ESP.getSketchSize());
  printBytes("Free sketch space:", ESP.getFreeSketchSpace());

  Serial.println("----------------------------------------");
  const bool psramPresent = psramFound();
  Serial.printf("%-24s %s\n", "PSRAM detected:", psramPresent ? "YES" : "NO");
  printBytes("PSRAM total:", ESP.getPsramSize());
  printBytes("PSRAM free:", ESP.getFreePsram());
  Serial.printf("%-24s %s\n", "1 MiB PSRAM test:",
                psramPresent ? (testPsram() ? "PASS" : "FAIL") : "SKIPPED");

  Serial.println("----------------------------------------");
  printBytes("Internal heap total:", ESP.getHeapSize());
  printBytes("Internal heap free:", ESP.getFreeHeap());
  Serial.printf("Temperature (rough):     %.1f C\n", temperatureRead());
  Serial.println("========================================");
  Serial.println("Expected for N16R8: Flash ~= 16 MiB, PSRAM ~= 8 MiB, PSRAM test PASS");
}

void setup() {
  Serial.begin(115200);
  delay(1500);
  printReport();
}

void loop() {
  static uint32_t lastReport = 0;
  if (millis() - lastReport >= 10000) {
    lastReport = millis();
    Serial.println("[alive] hardware check is running");
  }
  delay(20);
}
