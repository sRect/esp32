package com.sleepwell.provisioning.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EspWifiConnector(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    suspend fun connect(ssid: String): Network = suspendCancellableCoroutine { continuation ->
        disconnect()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (continuation.isActive) continuation.resume(network)
            }

            override fun onUnavailable() {
                activeCallback = null
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        ProvisioningApiException(
                            "DEVICE_WIFI_UNAVAILABLE",
                            "未连接到设备热点，请确认热点已开启并在系统弹窗中允许连接",
                        ),
                    )
                }
            }

            override fun onLost(network: Network) {
                // The ESP32 intentionally closes its AP after provisioning succeeds.
            }
        }

        activeCallback = callback
        connectivityManager.requestNetwork(request, callback, 30_000)
        continuation.invokeOnCancellation { disconnect() }
    }

    fun disconnect() {
        activeCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        activeCallback = null
    }
}
