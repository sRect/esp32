package com.sleepwell.provisioning.data

data class DeviceInfo(
    val deviceModel: String,
    val deviceId: String,
    val firmwareVersion: String,
    val provisioningState: String,
    val hasProvisionedBefore: Boolean,
    val wifiMac: String,
    val apSsid: String,
    val provisioningToken: String,
)

data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val channel: Int,
    val security: String,
    val hidden: Boolean,
)

data class WifiStatus(
    val state: String,
    val connected: Boolean,
    val connectedSsid: String,
    val stationIp: String,
    val errorCode: String?,
    val errorMessage: String?,
)

class ProvisioningApiException(
    val code: String,
    override val message: String,
) : Exception(message)
