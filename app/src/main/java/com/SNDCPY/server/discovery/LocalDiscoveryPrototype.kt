package com.example.remotecontrolserver.discovery

import java.net.NetworkInterface

class LocalDiscoveryPrototype {
    fun advertise(port: Int): String {
        val endpoints = buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val hostAddress = address.hostAddress ?: continue
                    if (!address.isLoopbackAddress && !hostAddress.contains(':')) {
                        add("$hostAddress:$port")
                    }
                }
            }
        }

        return if (endpoints.isEmpty()) {
            "Discovery prototype active -> no IPv4 address available"
        } else {
            "Discovery prototype active -> ${endpoints.joinToString()}"
        }
    }
}
