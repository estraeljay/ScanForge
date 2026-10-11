package app.scanforge.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

@Serializable
data class DiscoveryReply(
    val service: String = Protocol.SERVICE,
    @SerialName("protocol_version") val protocolVersion: Int = Protocol.VERSION,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_id") val deviceId: String,
    val port: Int,
    @SerialName("pairing_open") val pairingOpen: Boolean,
)

/**
 * Answers LAN broadcast probes so Blender can list nearby phones. Replies are unauthenticated hints only:
 * the TLS certificate is always verified against the pairing code or the pinned fingerprint.
 */
class DiscoveryResponder(private val listenPort: Int = Protocol.DISCOVERY_PORT, private val reply: () -> DiscoveryReply) : AutoCloseable {
    private var socket: DatagramSocket? = null

    fun start() {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.bind(InetSocketAddress(listenPort))
        socket = s
        Thread({
            val buf = ByteArray(256)
            while (!s.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try { s.receive(packet) } catch (_: SocketException) { break }
                if (String(packet.data, 0, packet.length, Charsets.US_ASCII).trim() != Protocol.DISCOVERY_REQUEST) continue
                val bytes = ProtocolJson.encodeToString(DiscoveryReply.serializer(), reply()).toByteArray()
                runCatching { s.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
            }
        }, "sf-discovery").apply { isDaemon = true; start() }
    }

    val localPort get() = socket?.localPort ?: -1

    override fun close() { socket?.close() }
}
