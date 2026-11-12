package com.SNDCPY.server.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * Advertises the SNDCPY control server over mDNS/Bonjour so clients can
 * discover it without typing an IP address.
 *
 * Service type: `_sndcpy._tcp.` on the local domain.
 * TXT records expose protocol metadata for capability negotiation.
 */
class NsdAdvertiser(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start(port: Int) {
        synchronized(lock) {
            if (registrationListener != null) return
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = defaultServiceName()
                serviceType = SERVICE_TYPE
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute(TXT_VERSION, PROTOCOL_VERSION)
                    setAttribute(TXT_PROTOCOL, "tcp")
                    setAttribute(TXT_SECURE, "1")
                }
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD registered as '${info.serviceName}' on port $port")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                    synchronized(lock) { registrationListener = null }
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD unregistered")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD unregistration failed: $errorCode")
                }
            }

            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
                registrationListener = listener
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register NSD service", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val listener = registrationListener ?: return
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "NSD unregister error: ${e.message}")
            }
            registrationListener = null
        }
    }

    fun defaultServiceName(): String =
        "SNDCPY-${android.os.Build.MODEL}".replace(WHITESPACE, "-").take(63)

    companion object {
        private const val TAG = "NsdAdvertiser"
        const val SERVICE_TYPE = "_sndcpy._tcp."
        const val TXT_VERSION = "v"
        const val TXT_PROTOCOL = "proto"
        const val TXT_SECURE = "secure"
        const val PROTOCOL_VERSION = "2"
        private val WHITESPACE = Regex("\\s+")
    }
}
