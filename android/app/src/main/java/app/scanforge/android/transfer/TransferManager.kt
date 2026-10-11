package app.scanforge.android.transfer

import app.scanforge.android.systemService
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import app.scanforge.android.ScanForgeApp
import app.scanforge.project.ProjectPackager
import app.scanforge.project.TransferRecord
import app.scanforge.transfer.ClientRegistry
import app.scanforge.transfer.DiscoveryReply
import app.scanforge.transfer.DiscoveryResponder
import app.scanforge.transfer.Offer
import app.scanforge.transfer.PairedClient
import app.scanforge.transfer.PairingCode
import app.scanforge.transfer.PendingPairing
import app.scanforge.transfer.ServerConfig
import app.scanforge.transfer.TlsSupport
import app.scanforge.transfer.TransferEvent
import app.scanforge.transfer.TransferServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class OfferUi(val id: String, val projectId: String, val name: String, val bytes: Long, val status: String, val progress: Float, val detail: String?)

data class TransferUiState(
    val running: Boolean = false,
    val address: String? = null,
    val port: Int = 0,
    val pairingCode: String = "",
    val pairingOpen: Boolean = false,
    val pendingPairings: List<PendingPairing> = emptyList(),
    val clients: List<PairedClient> = emptyList(),
    val offers: List<OfferUi> = emptyList(),
    val error: String? = null,
    val lastSuccess: String? = null,
)

/**
 * Phone-side transfer: runs the core [TransferServer] over TLS on the Wi-Fi interface only, plus LAN discovery.
 * Packages are built on demand into app storage; the user explicitly chooses what to send.
 */
class TransferManager(private val app: ScanForgeApp) {
    private val tag = "ScanForge.Transfer"
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()
    private val registry = ClientRegistry(File(app.filesDir, "scanforge/transfer/clients.json"))
    private var server: TransferServer? = null
    private var discovery: DiscoveryResponder? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val outbox = File(app.filesDir, "scanforge/transfer/outbox").apply { mkdirs() }
    private val deviceId by lazy {
        val prefs = app.getSharedPreferences("scanforge_transfer", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }

    @Synchronized fun start(): String? {
        if (server?.isRunning == true) return null
        val address = NetworkInfo.localLanAddress(app)
            ?: return "Connect to Wi-Fi (the same network as the computer). Transfers never use mobile data.".also { setError(it) }
        return try {
            val cert = AndroidTls.ensureIdentity()
            val s = TransferServer(
                ServerConfig(deviceName = "${Build.MANUFACTURER} ${Build.MODEL}", deviceId = deviceId, bindAddress = address),
                registry, TlsSupport.serverSocketFactory(AndroidTls.sslContext()),
            )
            s.events.subscribe(::onEvent)
            s.start()
            server = s
            multicastLock = app.systemService<WifiManager>().createMulticastLock("scanforge-discovery").apply { setReferenceCounted(false); acquire() }
            discovery = runCatching {
                DiscoveryResponder { DiscoveryReply(deviceName = "${Build.MANUFACTURER} ${Build.MODEL}", deviceId = deviceId, port = s.localPort, pairingOpen = s.pairingOpen) }.also { it.start() }
            }.onFailure { Log.w(tag, "Discovery unavailable", it) }.getOrNull()
            _state.value = _state.value.copy(running = true, address = address.hostAddress, port = s.localPort,
                pairingCode = PairingCode.fromCertificate(cert.encoded), clients = registry.all(), error = null)
            null
        } catch (e: Exception) {
            Log.e(tag, "Transfer server failed", e)
            "Could not start the transfer server: ${e.message}".also { setError(it) }
        }
    }

    @Synchronized fun stop() {
        server?.close(); server = null
        discovery?.close(); discovery = null
        multicastLock?.release(); multicastLock = null
        _state.value = _state.value.copy(running = false, pairingOpen = false, pendingPairings = emptyList())
    }

    fun setPairingOpen(open: Boolean) {
        server?.setPairingOpen(open)
        _state.value = _state.value.copy(pairingOpen = open, pendingPairings = server?.pendingPairings().orEmpty())
    }

    fun approve(requestId: String) { server?.approvePairing(requestId); refresh() }
    fun deny(requestId: String) { server?.denyPairing(requestId); refresh() }
    fun revoke(clientId: String) { registry.revoke(clientId); refresh() }

    /** Packages the project (current model + metadata) and offers it to paired computers. */
    fun send(projectId: String, includeCapture: Boolean = false): String? {
        val s = server ?: return "Start the transfer server first"
        return try {
            val file = File(outbox, "$projectId.sf3d")
            val manifest = ProjectPackager.pack(app.store, projectId, file, includeCapture)
            s.offer(file, manifest.projectId, manifest.projectName)
            refresh(); null
        } catch (e: Exception) {
            "Could not prepare the package: ${e.message}".also { setError(it) }
        }
    }

    fun cancel(offerId: String) { server?.cancelOffer(offerId); refresh() }

    private fun onEvent(e: TransferEvent) {
        when (e) {
            is TransferEvent.OfferCompleted -> {
                val offer = server?.offers()?.firstOrNull { it.id == e.offerId }
                if (offer != null) {
                    runCatching {
                        app.store.update(offer.projectId) { doc ->
                            doc.copy(transfers = doc.transfers + TransferRecord(e.offerId, offer.sha256, offer.receivedBy ?: "", if (e.verified) "verified" else "failed", app.store.now(), e.detail))
                        }
                    }
                    if (e.verified) _state.value = _state.value.copy(lastSuccess = "${offer.projectName} → ${offer.receivedBy} (${app.store.now()})")
                }
            }
            is TransferEvent.ServerError -> setError(e.message)
            else -> {}
        }
        refresh()
    }

    fun refresh() {
        val s = server
        _state.value = _state.value.copy(
            pendingPairings = s?.pendingPairings().orEmpty(),
            clients = registry.all(),
            offers = s?.offers().orEmpty().map(::toUi),
        )
    }

    private fun toUi(o: Offer) = OfferUi(o.id, o.projectId, o.projectName, o.bytes, o.status.name.lowercase(),
        if (o.chunkCount == 0) 0f else o.chunksServed.toFloat() / o.chunkCount, o.detail)

    private fun setError(msg: String) { _state.value = _state.value.copy(error = msg) }
}
