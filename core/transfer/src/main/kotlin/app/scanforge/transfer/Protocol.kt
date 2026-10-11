package app.scanforge.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * ScanForge transfer protocol v1. Normative description: docs/protocol/transfer-protocol-v1.md.
 * The phone is the TLS server; Blender is the client.
 */
object Protocol {
    const val VERSION = 1
    const val SERVICE = "scanforge"
    const val DEFAULT_PORT = 47821
    const val DISCOVERY_PORT = 47820
    const val DISCOVERY_REQUEST = "SF3D_DISCOVER_V1"
    const val DEFAULT_CHUNK_SIZE = 1 shl 20
    const val MAX_JSON_BODY = 64 * 1024
    const val PAIRING_CODE_CHARS = 16
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class InfoResponse(
    val service: String = Protocol.SERVICE,
    @SerialName("protocol_version") val protocolVersion: Int = Protocol.VERSION,
    @SerialName("app_name") val appName: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("pairing_open") val pairingOpen: Boolean,
)

@Serializable
data class PairRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("client_app") val clientApp: String = "",
)

@Serializable
data class PairStatusResponse(
    @SerialName("request_id") val requestId: String,
    /** pending | approved | denied | expired */
    val status: String,
    /** Bearer token, returned exactly once when status becomes approved. */
    val token: String? = null,
)

@Serializable
data class OfferSummary(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("file_name") val fileName: String,
    val format: String,
    val bytes: Long,
    val sha256: String,
    @SerialName("offered_at") val offeredAt: String,
    val status: String,
)

@Serializable
data class OffersResponse(val offers: List<OfferSummary>)

@Serializable
data class OfferManifest(
    val offer: OfferSummary,
    @SerialName("chunk_size") val chunkSize: Int,
    @SerialName("chunk_sha256") val chunkSha256: List<String>,
)

@Serializable
data class ReceiptRequest(
    /** verified | failed */
    val status: String,
    val sha256: String,
    val bytes: Long,
    val detail: String? = null,
)

@Serializable
data class ErrorResponse(val error: String, val message: String)

/**
 * Pairing code: the first 80 bits of SHA-256(server certificate DER) in RFC 4648 base32, shown as
 * `XXXX-XXXX-XXXX-XXXX`. The client checks it against the certificate the TLS handshake presented,
 * which authenticates the server without a CA. 80 bits makes finding a colliding certificate infeasible.
 */
object PairingCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun fingerprint(certDer: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(certDer)
    fun fingerprintHex(certDer: ByteArray) = fingerprint(certDer).joinToString("") { "%02x".format(it) }

    fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun fromCertificate(certDer: ByteArray): String = format(base32(fingerprint(certDer)).substring(0, Protocol.PAIRING_CODE_CHARS))

    fun format(raw: String) = raw.chunked(4).joinToString("-")

    /** Normalizes user input: case, separators and common look-alikes (0→O, 1→I, 8→B). */
    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }
        .map { when (it) { '0' -> 'O'; '1' -> 'I'; '8' -> 'B'; else -> it } }.joinToString("")

    fun matches(input: String, certDer: ByteArray) =
        normalize(input) == normalize(fromCertificate(certDer)) && normalize(input).length == Protocol.PAIRING_CODE_CHARS
}

object Tokens {
    private val random = SecureRandom()
    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun newId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also(random::nextBytes))
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Constant-time comparison. */
    fun equalsConstantTime(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
