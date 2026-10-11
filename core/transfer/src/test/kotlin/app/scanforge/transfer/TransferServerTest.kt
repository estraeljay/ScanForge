package app.scanforge.transfer

import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the server over real TLS sockets. The client trusts nothing up front: it captures the presented
 * certificate and checks it against the pairing code, exactly like the Blender add-on.
 */
class TransferServerTest {
    private lateinit var dir: File
    private lateinit var server: TransferServer
    private lateinit var certDer: ByteArray

    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) { val text get() = body.decodeToString() }

    /** Minimal pinned-TLS HTTP client. */
    inner class Client {
        var peerCert: ByteArray? = null
        private val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>, a: String?) { peerCert = c[0].encoded }
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
        }

        fun request(method: String, path: String, body: String? = null, token: String? = null): Resp {
            (ctx.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { s ->
                s.startHandshake()
                val b = body?.toByteArray() ?: ByteArray(0)
                val head = buildString {
                    append("$method $path HTTP/1.1\r\nHost: test\r\nConnection: close\r\nContent-Length: ${b.size}\r\n")
                    if (token != null) append("Authorization: Bearer $token\r\n")
                    append("\r\n")
                }
                s.outputStream.write(head.toByteArray() + b); s.outputStream.flush()
                val input = BufferedInputStream(s.inputStream)
                val lines = mutableListOf<String>()
                val sb = StringBuilder()
                while (true) {
                    val c = input.read(); if (c < 0) break
                    if (c == '\n'.code) { val l = sb.toString().trimEnd('\r'); sb.clear(); if (l.isEmpty()) break; lines += l } else sb.append(c.toChar())
                }
                val status = lines[0].split(' ')[1].toInt()
                val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                val len = headers["content-length"]!!.toInt()
                val data = ByteArray(len); var r = 0
                while (r < len) { val n = input.read(data, r, len - r); if (n < 0) break; r += n }
                return Resp(status, headers, data)
            }
        }
    }

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("xfer").toFile()
        val ks = File(dir, "server.p12")
        TlsSupport.generateWithKeytool(ks, "changeit", "ScanForge Test")
        val store = TlsSupport.loadPkcs12(ks, "changeit".toCharArray())
        certDer = TlsSupport.certificate(store).encoded
        val ctx = TlsSupport.sslContext(store, "changeit".toCharArray())
        server = TransferServer(
            ServerConfig(deviceName = "Test Phone", deviceId = "dev-1", port = 0, bindAddress = InetAddress.getLoopbackAddress(), chunkSize = 64 * 1024, maxAuthFailuresPerAddress = 3),
            ClientRegistry(File(dir, "clients.json")),
            TlsSupport.serverSocketFactory(ctx),
        )
        server.start()
    }

    @AfterTest fun tearDown() { server.close() }

    private fun pair(c: Client): String {
        server.setPairingOpen(true)
        val start = c.request("POST", "/v1/pair", """{"client_id":"blender-abcdef","client_name":"Blender Workstation"}""")
        assertEquals(202, start.status, start.text)
        val id = ProtocolJson.decodeFromString(PairStatusResponse.serializer(), start.text).requestId
        assertEquals("pending", ProtocolJson.decodeFromString(PairStatusResponse.serializer(), c.request("GET", "/v1/pair/$id").text).status)
        server.approvePairing(server.pendingPairings().single().requestId)
        val approved = ProtocolJson.decodeFromString(PairStatusResponse.serializer(), c.request("GET", "/v1/pair/$id").text)
        assertEquals("approved", approved.status)
        // Token is delivered exactly once.
        assertEquals(404, c.request("GET", "/v1/pair/$id").status)
        return assertNotNull(approved.token)
    }

    @Test fun pairingCodeAuthenticatesTheServerCertificate() {
        val c = Client()
        val info = c.request("GET", "/v1/info")
        assertEquals(200, info.status)
        assertTrue(info.text.contains("\"protocol_version\":1"))
        val seen = assertNotNull(c.peerCert)
        val code = PairingCode.fromCertificate(certDer)
        assertTrue(Regex("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}").matches(code), code)
        assertTrue(PairingCode.matches(code.lowercase().replace("-", " "), seen))
        assertFalse(PairingCode.matches("AAAA-AAAA-AAAA-AAAA", seen))
        assertEquals("MFRGG", PairingCode.base32("abc".toByteArray()))
    }

    @Test fun pairingRequiresOpenWindowAndApproval() {
        val c = Client()
        assertEquals(403, c.request("POST", "/v1/pair", """{"client_id":"blender-abcdef","client_name":"x"}""").status)
        server.setPairingOpen(true)
        val r = c.request("POST", "/v1/pair", """{"client_id":"blender-abcdef","client_name":"x"}""")
        val id = ProtocolJson.decodeFromString(PairStatusResponse.serializer(), r.text).requestId
        server.denyPairing(id)
        val denied = ProtocolJson.decodeFromString(PairStatusResponse.serializer(), c.request("GET", "/v1/pair/$id").text)
        assertEquals("denied", denied.status)
        assertEquals(null, denied.token)
        assertEquals(400, c.request("POST", "/v1/pair", "not json").status)
    }

    @Test fun authenticatedChunkedTransferWithVerifiedReceipt() {
        val c = Client()
        val token = pair(c)
        assertEquals(401, c.request("GET", "/v1/offers").status)
        val pkg = File(dir, "scan.sf3d").apply { writeBytes(Random(7).nextBytes(200_000)) }
        val offer = server.offer(pkg, "proj-1", "Mug")
        val list = ProtocolJson.decodeFromString(OffersResponse.serializer(), c.request("GET", "/v1/offers", token = token).text)
        assertEquals(listOf("Mug"), list.offers.map { it.projectName })
        val manifest = ProtocolJson.decodeFromString(OfferManifest.serializer(), c.request("GET", "/v1/offers/${offer.id}", token = token).text)
        assertEquals(4, manifest.chunkSha256.size) // 200 000 B in 64 KiB chunks
        val received = java.io.ByteArrayOutputStream()
        manifest.chunkSha256.forEachIndexed { i, expected ->
            val r = c.request("GET", "/v1/offers/${offer.id}/chunks/$i", token = token)
            assertEquals(200, r.status)
            val h = MessageDigest.getInstance("SHA-256").digest(r.body).joinToString("") { "%02x".format(it) }
            assertEquals(expected, h); assertEquals(expected, r.headers["x-chunk-sha256"])
            received.write(r.body)
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(received.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.offer.sha256, sha)
        assertEquals(OfferStatus.IN_PROGRESS, offer.status)
        // A receipt with the wrong hash must not be accepted as success.
        val bad = c.request("POST", "/v1/offers/${offer.id}/receipt", """{"status":"verified","sha256":"00","bytes":200000}""", token)
        assertEquals(409, bad.status); assertEquals(OfferStatus.FAILED, offer.status)
        val ok = c.request("POST", "/v1/offers/${offer.id}/receipt", """{"status":"verified","sha256":"$sha","bytes":200000}""", token)
        assertEquals(200, ok.status); assertEquals(OfferStatus.VERIFIED, offer.status)
        // Cancelled offers disappear and further chunk requests fail with 410.
        val o2 = server.offer(File(dir, "b.sf3d").apply { writeBytes(ByteArray(10)) }, "p2", "B")
        server.cancelOffer(o2.id)
        assertEquals(410, c.request("GET", "/v1/offers/${o2.id}/chunks/0", token = token).status)
        // Revocation.
        assertEquals(204, c.request("DELETE", "/v1/pairing", token = token).status)
        assertEquals(401, c.request("GET", "/v1/offers", token = token).status)
    }

    @Test fun duplicateOffersAreReplacedAndRegistryPersists() {
        val c = Client()
        pair(c)
        val f = File(dir, "a.sf3d").apply { writeBytes(ByteArray(1000) { 1 }) }
        server.offer(f, "p", "A"); server.offer(f, "p", "A")
        assertEquals(1, server.offers().size)
        assertEquals(1, ClientRegistry(File(dir, "clients.json")).all().size)
        assertTrue(!File(dir, "clients.json").readText().contains("token\""), "only token hashes are persisted")
    }

    @Test fun repeatedAuthFailuresLockOut() {
        val c = Client()
        repeat(3) { assertEquals(401, c.request("GET", "/v1/offers", token = "wrong").status) }
        assertEquals(429, c.request("GET", "/v1/offers", token = "wrong").status)
    }

    @Test fun malformedHttpIsRejected() {
        val c = Client()
        assertEquals(404, c.request("GET", "/nope").status)
        val big = "x".repeat(Protocol.MAX_JSON_BODY + 1)
        server.setPairingOpen(true)
        assertEquals(413, c.request("POST", "/v1/pair", big).status)
    }

    @Test fun discoveryAnswersBroadcastProbe() {
        DiscoveryResponder(0) { DiscoveryReply(deviceName = "Test Phone", deviceId = "dev-1", port = 47821, pairingOpen = false) }.use { d ->
            d.start()
            java.net.DatagramSocket().use { s ->
                s.soTimeout = 3000
                val req = Protocol.DISCOVERY_REQUEST.toByteArray()
                s.send(java.net.DatagramPacket(req, req.size, InetAddress.getLoopbackAddress(), d.localPort))
                val buf = ByteArray(512); val p = java.net.DatagramPacket(buf, buf.size)
                s.receive(p)
                val reply = ProtocolJson.decodeFromString(DiscoveryReply.serializer(), String(p.data, 0, p.length))
                assertEquals("Test Phone", reply.deviceName)
            }
        }
    }
}
