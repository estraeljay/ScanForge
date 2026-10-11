package app.scanforge.transfer

import kotlinx.serialization.KSerializer
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.logging.Level
import java.util.logging.Logger

data class ServerConfig(
    val appName: String = "ScanForge 3D",
    val appVersion: String = "0.1.0",
    val deviceName: String,
    val deviceId: String,
    val port: Int = Protocol.DEFAULT_PORT,
    /** Bind address; must be a local-network interface, never 0.0.0.0 on untrusted networks. */
    val bindAddress: InetAddress? = null,
    val chunkSize: Int = Protocol.DEFAULT_CHUNK_SIZE,
    val pairingTimeout: Duration = Duration.ofMinutes(2),
    val socketTimeout: Duration = Duration.ofSeconds(30),
    val maxConnections: Int = 8,
    val maxAuthFailuresPerAddress: Int = 10,
    val authLockout: Duration = Duration.ofMinutes(1),
)

/**
 * Transfer server run by the phone. The [serverSocketFactory] must produce a TLS server socket
 * (AndroidKeyStore-backed on Android, PKCS#12-backed on desktop); see [TlsSupport].
 *
 * Network threads never touch UI state directly: everything the UI needs is published on [events].
 */
class TransferServer(
    private val config: ServerConfig,
    private val registry: ClientRegistry,
    private val serverSocketFactory: (port: Int, bind: InetAddress?) -> ServerSocket,
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {
    private val log = Logger.getLogger("ScanForge.Transfer")
    val events = EventBus()

    @Volatile var pairingOpen = false
        private set
    private val pending = ConcurrentHashMap<String, PendingPairing>()
    private val offers = ConcurrentHashMap<String, Offer>()
    private val authFailures = ConcurrentHashMap<String, Pair<Int, Instant>>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val slots = Semaphore(config.maxConnections)

    val localPort: Int get() = serverSocket?.localPort ?: -1
    val isRunning get() = serverSocket?.isClosed == false

    @Synchronized fun start() {
        check(serverSocket == null) { "Already started" }
        val ss = serverSocketFactory(config.port, config.bindAddress)
        serverSocket = ss
        pool = Executors.newFixedThreadPool(config.maxConnections) { r -> Thread(r, "sf-transfer-conn").apply { isDaemon = true } }
        acceptThread = Thread({ acceptLoop(ss) }, "sf-transfer-accept").apply { isDaemon = true; start() }
        log.info("Transfer server listening on ${ss.localSocketAddress}")
    }

    override fun close() {
        serverSocket?.close()
        pool?.shutdownNow()
        serverSocket = null
    }

    // ---- Phone-side API ---------------------------------------------------------------------------

    fun setPairingOpen(open: Boolean) {
        pairingOpen = open
        if (!open) pending.values.filter { it.status == "pending" }.forEach { it.status = "expired" }
    }

    fun pendingPairings(): List<PendingPairing> = pending.values.filter { it.status == "pending" }

    /** Called after the phone user explicitly approves the device shown in the pairing dialog. */
    fun approvePairing(requestId: String): PairedClient {
        val p = pending[requestId] ?: throw IllegalArgumentException("Unknown pairing request")
        check(p.status == "pending") { "Request is ${p.status}" }
        val token = Tokens.newToken()
        val client = PairedClient(p.request.clientId, p.request.clientName.take(64), Tokens.hash(token), clock().toString(), lastAddress = p.address)
        registry.add(client)
        p.token = token
        p.status = "approved"
        return client
    }

    fun denyPairing(requestId: String) { pending[requestId]?.status = "denied" }

    fun offer(file: File, projectId: String, projectName: String, format: String = "sf3d"): Offer {
        // Re-offering the same file content replaces the earlier offer (duplicate detection by hash).
        val o = Offer.create(Tokens.newId(), file, projectId, projectName, format, config.chunkSize, clock())
        offers.values.filter { it.sha256 == o.sha256 && it.status != OfferStatus.VERIFIED }.forEach { offers.remove(it.id) }
        offers[o.id] = o
        return o
    }

    fun cancelOffer(id: String) { offers[id]?.let { it.status = OfferStatus.CANCELLED; offers.remove(id) } }
    fun offers(): List<Offer> = offers.values.sortedBy { it.offeredAt }

    // ---- Networking ---------------------------------------------------------------------------------

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) {
                events.emit(TransferEvent.ServerError(e.message ?: "accept failed")); continue
            }
            if (!slots.tryAcquire()) { runCatching { socket.close() }; continue }
            pool?.execute {
                try { handleConnection(socket) } finally { slots.release() }
            } ?: run { slots.release(); socket.close() }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = config.socketTimeout.toMillis().toInt()
            val address = s.inetAddress.hostAddress
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream().buffered(64 * 1024)
            try {
                while (true) {
                    val req = try { MiniHttp.readRequest(input, Protocol.MAX_JSON_BODY) } catch (e: HttpProtocolException) {
                        MiniHttp.writeResponse(output, error(e.status, "bad_request", e.message ?: ""), false); return
                    } ?: return
                    val keepAlive = !req.header("connection").equals("close", ignoreCase = true)
                    val resp = try { route(req, address) } catch (e: Exception) {
                        log.log(Level.WARNING, "Request failed: ${req.method} ${req.path}", e)
                        error(500, "internal", "Internal error")
                    }
                    MiniHttp.writeResponse(output, resp, keepAlive)
                    if (!keepAlive) return
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: SocketException) {
            } catch (e: javax.net.ssl.SSLException) {
                log.fine("TLS error from $address: ${e.message}")
            }
        }
    }

    private fun <T> json(status: Int, serializer: KSerializer<T>, value: T) = HttpResponse(status, ProtocolJson.encodeToString(serializer, value).toByteArray())
    private fun error(status: Int, code: String, message: String) = json(status, ErrorResponse.serializer(), ErrorResponse(code, message))

    private fun <T> parse(req: HttpRequest, serializer: KSerializer<T>): T? =
        runCatching { ProtocolJson.decodeFromString(serializer, req.body.decodeToString()) }.getOrNull()

    internal fun route(req: HttpRequest, address: String): HttpResponse {
        val path = req.path.substringBefore('?').trimEnd('/')
        val seg = path.split('/').filter { it.isNotEmpty() }
        if (seg.firstOrNull() != "v1") return error(404, "not_found", "Unknown endpoint")
        // Unauthenticated endpoints.
        when {
            req.method == "GET" && seg == listOf("v1", "info") -> return json(200, InfoResponse.serializer(),
                InfoResponse(appName = config.appName, appVersion = config.appVersion, deviceName = config.deviceName, deviceId = config.deviceId, pairingOpen = pairingOpen))
            req.method == "POST" && seg == listOf("v1", "pair") -> return startPairing(req, address)
            req.method == "GET" && seg.size == 3 && seg[1] == "pair" -> return pairStatus(seg[2])
        }
        // Everything else requires a paired client.
        if (isLockedOut(address)) return error(429, "locked_out", "Too many failed attempts; try again later")
        val token = req.header("authorization")?.removePrefix("Bearer ")?.trim()
        val client = token?.let(registry::authenticate)
        if (client == null) {
            recordAuthFailure(address)
            return error(401, "unauthorized", "Device is not paired or pairing was revoked")
        }
        authFailures.remove(address)
        registry.touch(client.clientId, address, clock())
        events.emit(TransferEvent.ClientSeen(client))
        return when {
            req.method == "POST" && seg == listOf("v1", "ping") -> json(200, InfoResponse.serializer(),
                InfoResponse(appName = config.appName, appVersion = config.appVersion, deviceName = config.deviceName, deviceId = config.deviceId, pairingOpen = pairingOpen))
            req.method == "DELETE" && seg == listOf("v1", "pairing") -> { registry.revoke(client.clientId); HttpResponse(204) }
            req.method == "GET" && seg == listOf("v1", "offers") ->
                json(200, OffersResponse.serializer(), OffersResponse(offers().filter { it.status != OfferStatus.CANCELLED }.map { it.summary() }))
            req.method == "GET" && seg.size == 3 && seg[1] == "offers" -> {
                val o = offers[seg[2]] ?: return error(410, "gone", "Offer no longer available")
                json(200, OfferManifest.serializer(), OfferManifest(o.summary(), o.chunkSize, o.chunkSha256))
            }
            req.method == "GET" && seg.size == 5 && seg[1] == "offers" && seg[3] == "chunks" -> chunk(seg[2], seg[4], client)
            req.method == "POST" && seg.size == 4 && seg[1] == "offers" && seg[3] == "receipt" -> receipt(seg[2], req, client)
            else -> error(404, "not_found", "Unknown endpoint")
        }
    }

    private fun startPairing(req: HttpRequest, address: String): HttpResponse {
        if (!pairingOpen) return error(403, "pairing_closed", "Open 'Pair new device' on the phone first")
        val body = parse(req, PairRequest.serializer()) ?: return error(400, "bad_request", "Invalid pairing request")
        if (body.clientId.length !in 8..128 || body.clientName.isBlank()) return error(400, "bad_request", "Invalid client id or name")
        expirePairings()
        if (pending.values.count { it.status == "pending" } >= 3) return error(429, "too_many_requests", "Too many pending pairing requests")
        val p = PendingPairing(Tokens.newId(), body.copy(clientName = body.clientName.take(64)), address, clock())
        pending[p.requestId] = p
        events.emit(TransferEvent.PairingRequested(p))
        return json(202, PairStatusResponse.serializer(), PairStatusResponse(p.requestId, "pending"))
    }

    private fun pairStatus(id: String): HttpResponse {
        expirePairings()
        val p = pending[id] ?: return error(404, "not_found", "Unknown pairing request")
        val token = p.token
        if (p.status == "approved") { pending.remove(id); p.token = null } // token is delivered once
        if (p.status == "denied" || p.status == "expired") pending.remove(id)
        return json(200, PairStatusResponse.serializer(), PairStatusResponse(id, p.status, token))
    }

    private fun expirePairings() {
        val cutoff = clock().minus(config.pairingTimeout)
        pending.values.filter { it.status == "pending" && it.createdAt.isBefore(cutoff) }.forEach { it.status = "expired" }
    }

    private fun chunk(offerId: String, indexText: String, client: PairedClient): HttpResponse {
        val o = offers[offerId] ?: return error(410, "gone", "Offer was cancelled on the phone")
        val index = indexText.toIntOrNull() ?: return error(400, "bad_request", "Invalid chunk index")
        if (index !in 0 until o.chunkCount) return error(404, "not_found", "Chunk out of range")
        if (!o.file.isFile) return error(410, "gone", "Package file no longer exists")
        val offset = index.toLong() * o.chunkSize
        val len = minOf(o.chunkSize.toLong(), o.bytes - offset)
        if (o.status == OfferStatus.OFFERED || o.status == OfferStatus.FAILED) o.status = OfferStatus.IN_PROGRESS
        o.receivedBy = client.clientName
        o.chunksServed = maxOf(o.chunksServed, index + 1)
        events.emit(TransferEvent.OfferProgress(o.id, o.chunksServed, o.chunkCount))
        return HttpResponse(200, contentType = "application/octet-stream", extraHeaders = mapOf("X-Chunk-SHA256" to o.chunkSha256[index]),
            streamLength = len, stream = { out ->
                RandomAccessFile(o.file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(64 * 1024)
                    var remaining = len
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw java.io.IOException("Package truncated")
                        out.write(buf, 0, n); remaining -= n
                    }
                }
            })
    }

    private fun receipt(offerId: String, req: HttpRequest, client: PairedClient): HttpResponse {
        val o = offers[offerId] ?: return error(410, "gone", "Offer no longer available")
        val r = parse(req, ReceiptRequest.serializer()) ?: return error(400, "bad_request", "Invalid receipt")
        // Success only counts when the receiver's independent hash matches ours.
        val verified = r.status == "verified" && Tokens.equalsConstantTime(r.sha256, o.sha256) && r.bytes == o.bytes
        o.status = if (verified) OfferStatus.VERIFIED else OfferStatus.FAILED
        o.detail = if (verified) "Verified by ${client.clientName}" else (r.detail ?: "Receiver reported a mismatch")
        events.emit(TransferEvent.OfferCompleted(o.id, verified, o.detail))
        return if (verified) json(200, OfferSummary.serializer(), o.summary())
        else json(409, ErrorResponse.serializer(), ErrorResponse("verification_failed", o.detail ?: "mismatch"))
    }

    private fun isLockedOut(address: String): Boolean {
        val (count, since) = authFailures[address] ?: return false
        if (clock().isAfter(since.plus(config.authLockout))) { authFailures.remove(address); return false }
        return count >= config.maxAuthFailuresPerAddress
    }

    private fun recordAuthFailure(address: String) {
        authFailures.compute(address) { _, old -> if (old == null) 1 to clock() else (old.first + 1) to old.second }
    }
}
