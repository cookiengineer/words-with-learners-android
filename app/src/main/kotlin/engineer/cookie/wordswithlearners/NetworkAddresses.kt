package engineer.cookie.wordswithlearners

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddresses {
    @Suppress("DEPRECATION")
    fun lanIPv4(context: Context): List<String> {
        val result = LinkedHashSet<String>()

        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local) {
                    continue
                }
                val properties = manager.getLinkProperties(network) ?: continue
                for (link in properties.linkAddresses) {
                    val address = link.address
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        address.hostAddress?.let(result::add)
                    }
                }
            }
        }

        if (result.isEmpty()) {
            runCatching {
                for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                    if (!networkInterface.isUp || networkInterface.isLoopback) {
                        continue
                    }
                    for (address in networkInterface.inetAddresses) {
                        if (address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress &&
                            address.isSiteLocalAddress
                        ) {
                            address.hostAddress?.let(result::add)
                        }
                    }
                }
            }
        }

        return result.toList()
    }
}
