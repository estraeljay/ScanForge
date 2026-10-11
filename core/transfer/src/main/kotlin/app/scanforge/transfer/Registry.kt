package app.scanforge.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class PairedClient(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("token_sha256") val tokenSha256: String,
    @SerialName("approved_at") val approvedAt: String,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("last_address") val lastAddress: String? = null,
)

@Serializable
private data class RegistryFile(val clients: List<PairedClient> = emptyList())

/** Persisted list of approved clients. Only token hashes are stored. */
class ClientRegistry(private val file: File?) {
    private val clients = LinkedHashMap<String, PairedClient>()

    init {
        file?.takeIf { it.isFile }?.let { f ->
            runCatching { ProtocolJson.decodeFromString(RegistryFile.serializer(), f.readText()) }.getOrNull()
                ?.clients?.forEach { clients[it.clientId] = it }
        }
    }

    @Synchronized fun all(): List<PairedClient> = clients.values.toList()

    @Synchronized fun add(client: PairedClient) { clients[client.clientId] = client; persist() }

    @Synchronized fun revoke(clientId: String): Boolean = (clients.remove(clientId) != null).also { if (it) persist() }

    /** Returns the client owning [token], or null. */
    @Synchronized fun authenticate(token: String): PairedClient? {
        val h = Tokens.hash(token)
        return clients.values.firstOrNull { MessageDigest.isEqual(it.tokenSha256.toByteArray(), h.toByteArray()) }
    }

    @Synchronized fun touch(clientId: String, address: String, now: Instant) {
        clients[clientId]?.let { clients[clientId] = it.copy(lastSeen = now.toString(), lastAddress = address); persist() }
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ProtocolJson.encodeToString(RegistryFile.serializer(), RegistryFile(clients.values.toList())))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/** A pairing request awaiting the phone user's decision. */
data class PendingPairing(
    val requestId: String,
    val request: PairRequest,
    val address: String,
    val createdAt: Instant,
    @Volatile var status: String = "pending",
    @Volatile var token: String? = null,
)

enum class OfferStatus { OFFERED, IN_PROGRESS, VERIFIED, FAILED, CANCELLED }

/** A package the phone user chose to send. Chunk hashes are computed once when offered. */
class Offer(
    val id: String,
    val file: File,
    val projectId: String,
    val projectName: String,
    val format: String,
    val sha256: String,
    val chunkSize: Int,
    val chunkSha256: List<String>,
    val offeredAt: Instant,
) {
    @Volatile var status: OfferStatus = OfferStatus.OFFERED
    @Volatile var chunksServed: Int = 0
    @Volatile var detail: String? = null
    @Volatile var receivedBy: String? = null
    val bytes: Long = file.length()
    val chunkCount get() = chunkSha256.size

    fun summary() = OfferSummary(id, projectId, projectName, file.name, format, bytes, sha256, offeredAt.toString(), status.name.lowercase())

    companion object {
        fun create(id: String, file: File, projectId: String, projectName: String, format: String, chunkSize: Int, now: Instant): Offer {
            require(file.isFile) { "Package ${file.path} does not exist" }
            require(chunkSize in 64 * 1024..8 * 1024 * 1024) { "Chunk size out of range" }
            val whole = MessageDigest.getInstance("SHA-256")
            val chunks = ArrayList<String>()
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(chunkSize)
                while (true) {
                    var n = 0
                    while (n < chunkSize) { val r = input.read(buf, n, chunkSize - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    whole.update(buf, 0, n)
                    chunks += MessageDigest.getInstance("SHA-256").digest(buf.copyOf(n)).joinToString("") { "%02x".format(it) }
                    if (n < chunkSize) break
                }
            }
            return Offer(id, file, projectId, projectName, format, whole.digest().joinToString("") { "%02x".format(it) }, chunkSize, chunks, now)
        }
    }
}

/** Events for the phone UI. */
sealed interface TransferEvent {
    data class PairingRequested(val pending: PendingPairing) : TransferEvent
    data class ClientSeen(val client: PairedClient) : TransferEvent
    data class OfferProgress(val offerId: String, val chunksServed: Int, val chunkCount: Int) : TransferEvent
    data class OfferCompleted(val offerId: String, val verified: Boolean, val detail: String?) : TransferEvent
    data class ServerError(val message: String) : TransferEvent
}

class EventBus {
    private val listeners = CopyOnWriteArrayList<(TransferEvent) -> Unit>()
    fun subscribe(l: (TransferEvent) -> Unit): () -> Unit { listeners += l; return { listeners -= l } }
    fun emit(e: TransferEvent) = listeners.forEach { runCatching { it(e) } }
}
