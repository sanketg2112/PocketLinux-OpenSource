package com.sg.linuxgo.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort IPv4 address for Wi‑Fi / LAN ADB setup hints.
 */
object DeviceNetworkInfo {

    /**
     * Returns a dotted IPv4 string suitable for `adb connect IP:5555`, or null if unknown.
     */
    fun ipv4Address(context: Context): String? {
        wifiIpFromManager(context)?.let { return it }
        linkPropertiesIpv4(context)?.let { return it }
        return firstNonLoopbackIpv4()
    }

    @Suppress("DEPRECATION")
    private fun wifiIpFromManager(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val ip = wm.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun linkPropertiesIpv4(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    return null
                }
                val lp: LinkProperties = cm.getLinkProperties(network) ?: return null
                lp.linkAddresses
                    .mapNotNull { it.address as? Inet4Address }
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstNonLoopbackIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
