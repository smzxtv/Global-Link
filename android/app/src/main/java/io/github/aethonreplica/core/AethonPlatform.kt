package io.github.aethonreplica.core

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.WIFIState
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.LinkedList

/**
 * Helpers shared by AethonVpnService for libbox PlatformInterface bridging.
 * The TUN interface itself is opened inside AethonVpnService because
 * VpnService.Builder is an inner class of VpnService.
 */
class AethonPlatform(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "AethonPlatform"
    }

    fun getInterfaces(): NetworkInterfaceIterator {
        val list = LinkedList<NetworkInterface>()
        try {
            val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
            val javaNetworks = JavaNetworkInterface.getNetworkInterfaces().toList()
            for (network in connectivity.allNetworks) {
                val link = connectivity.getLinkProperties(network) ?: continue
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                val boxInterface = NetworkInterface()
                boxInterface.name = link.interfaceName
                val networkInterface = javaNetworks.find { it.name == boxInterface.name } ?: continue
                boxInterface.index = networkInterface.index
                boxInterface.addresses =
                    AethonStringIterator(
                        networkInterface.interfaceAddresses.toList().map { it.toPrefix() }.iterator()
                    )
                boxInterface.dnsServer =
                    AethonStringIterator(link.dnsServers.mapNotNull { it.hostAddress }.iterator())
                boxInterface.gateway =
                    AethonStringIterator(
                        link.routes
                            .filter { it.destination.prefixLength == 0 }
                            .mapNotNull { it.gateway }
                            .filterNot { it.isAnyLocalAddress }
                            .mapNotNull { it.hostAddress }
                            .iterator()
                    )
                boxInterface.type =
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                runCatching {
                    boxInterface.mtu = networkInterface.mtu
                }.onFailure {
                    Log.w(TAG, "failed to get mtu for " + boxInterface.name, it)
                }
                var flags = 0
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (networkInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (networkInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (networkInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                boxInterface.flags = flags
                boxInterface.metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                list.add(boxInterface)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getInterfaces failed", e)
        }
        return AethonInterfaceIterator(list.iterator())
    }

    fun readWIFIState(): WIFIState? = null

    fun clearDNSCache() {
    }

    private fun InterfaceAddress.toPrefix(): String =
        address.toPrefixString() + "/" + networkPrefixLength

    /**
     * hostAddress carries a zone suffix for link-local IPv6 addresses
     * (e.g. "fe80::1%wlan0"). sing-box parses these with netip.MustParsePrefix,
     * which panics when a zone is present, so strip it here.
     */
    private fun java.net.InetAddress.toPrefixString(): String =
        hostAddress.substringBefore('%')
}

class AethonStringIterator(private val values: Iterator<String>) : StringIterator {
    override fun len(): Int = 0

    override fun hasNext(): Boolean = values.hasNext()

    override fun next(): String = values.next()
}

class AethonInterfaceIterator(
    private val values: Iterator<NetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = values.hasNext()

    override fun next(): NetworkInterface = values.next()
}

class AethonLocalDnsTransport : LocalDNSTransport {
    override fun raw(): Boolean = true

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        ctx.errorCode(1)
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        ctx.errorCode(1)
    }
}
