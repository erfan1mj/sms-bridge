package com.smsbridge.gateway.service

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    /**
     * Discovers all non-loopback IPv4 addresses assigned to the Android device.
     * Categorizes into Hotspot and Wi-Fi where possible.
     */
    fun getLocalIpAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val inetAddresses = Collections.list(intf.inetAddresses)
                for (inetAddress in inetAddresses) {
                    if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            addresses.add(hostAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // If Android hotspot is enabled, 192.168.43.1 is the standard AP IP. Prioritize it.
        return addresses.sortedWith(Comparator { a, b ->
            when {
                a.startsWith("192.168.43.") -> -1
                b.startsWith("192.168.43.") -> 1
                a.startsWith("192.168.") -> -1
                b.startsWith("192.168.") -> 1
                else -> a.compareTo(b)
            }
        })
    }

    fun getWebSocketEndpoints(port: Int): List<String> {
        val ips = getLocalIpAddresses()
        return if (ips.isEmpty()) {
            listOf("wss://127.0.0.1:$port/ws")
        } else {
            ips.map { "wss://$it:$port/ws" }
        }
    }
}
