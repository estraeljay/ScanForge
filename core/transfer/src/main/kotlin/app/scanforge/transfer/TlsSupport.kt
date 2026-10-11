package app.scanforge.transfer

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

object TlsSupport {
    /** Protocols in preference order; TLS 1.2 is kept for Android 8–9 which lack TLS 1.3 server support. */
    val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    fun sslContext(keyStore: KeyStore, password: CharArray?): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun loadPkcs12(file: File, password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }

    fun certificate(keyStore: KeyStore, alias: String? = null): X509Certificate {
        val a = alias ?: keyStore.aliases().toList().first { keyStore.isKeyEntry(it) }
        return keyStore.getCertificate(a) as X509Certificate
    }

    fun serverSocketFactory(ctx: SSLContext): (Int, InetAddress?) -> ServerSocket = { port, bind ->
        (ctx.serverSocketFactory.createServerSocket(port, 16, bind) as SSLServerSocket).apply {
            val supported = supportedProtocols.toSet()
            enabledProtocols = PROTOCOLS.filter { it in supported }.toTypedArray()
            needClientAuth = false
        }
    }

    /**
     * Creates a self-signed EC P-256 key pair with the JDK `keytool` (desktop/CLI and tests only;
     * Android generates its key in AndroidKeyStore instead).
     */
    fun generateWithKeytool(file: File, password: String, commonName: String) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        file.parentFile?.mkdirs()
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "scanforge", "-keyalg", "EC", "-groupname", "secp256r1",
            "-sigalg", "SHA256withECDSA", "-validity", "3650", "-dname", "CN=$commonName",
            "-storetype", "PKCS12", "-keystore", file.path, "-storepass", password, "-keypass", password, "-noprompt",
        ).redirectErrorStream(true).start()
        val output = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "keytool failed: $output" }
    }
}
