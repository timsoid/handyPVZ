package dev.handypvz.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayDeque

/**
 * mDNS (NsdManager) discovery of Wireless debugging services on the local network:
 *  - `_adb-tls-pairing._tcp`  — advertised while the "Pair new device" screen is open on the watch;
 *  - `_adb-tls-connect._tcp`  — advertised while Wireless debugging is on.
 *
 * Only IPv4 services that do not belong to this phone are reported.
 */
class AdbDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    fun interface Stoppable {
        fun stop()
    }

    /** Starts discovery; [onResolved] is called (on an NSD thread) for every resolved service. */
    @Suppress("DEPRECATION")
    fun watch(serviceType: String, onResolved: (host: String, port: Int) -> Unit): Stoppable {
        val own = ownAddresses()
        val pending = ArrayDeque<NsdServiceInfo>()
        var busy = false
        var stopped = false

        fun resolveNext() {
            val next: NsdServiceInfo
            synchronized(pending) {
                if (stopped || busy) return
                next = pending.poll() ?: return
                busy = true
            }
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "resolve failed ${info.serviceName}: $errorCode")
                    synchronized(pending) { busy = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host
                    if (host is Inet4Address && host.hostAddress !in own) {
                        Log.d(TAG, "$serviceType resolved ${host.hostAddress}:${info.port}")
                        onResolved(host.hostAddress!!, info.port)
                    }
                    synchronized(pending) { busy = false }
                    resolveNext()
                }
            }
            try {
                nsd.resolveService(next, listener)
            } catch (e: Exception) {
                Log.d(TAG, "resolveService threw: $e")
                synchronized(pending) { busy = false }
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.d(TAG, "start discovery failed $type: $errorCode")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(pending) { pending.add(info) }
                resolveNext()
            }
        }
        nsd.discoverServices("_$serviceType._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)

        return Stoppable {
            synchronized(pending) {
                stopped = true
                pending.clear()
            }
            try {
                nsd.stopServiceDiscovery(discovery)
            } catch (_: IllegalArgumentException) {
                // discovery was never registered / already stopped
            }
        }
    }

    private fun ownAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    companion object {
        private const val TAG = "AdbDiscovery"
        const val PAIRING = "adb-tls-pairing"
        const val CONNECT = "adb-tls-connect"
    }
}
