package app.scanforge.android.transfer

import app.scanforge.android.systemService
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

object NetworkInfo {
    /**
     * IPv4 address of the active Wi-Fi or Ethernet network, or null. The transfer server binds only to this
     * address, so it is never exposed on mobile data interfaces.
     */
    fun localLanAddress(context: Context): InetAddress? {
        val cm = context.systemService<ConnectivityManager>()
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }
}
