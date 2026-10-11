package app.scanforge.android.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * TLS identity of this phone: an EC P-256 key generated inside AndroidKeyStore (never exportable) with the
 * self-signed certificate AndroidKeyStore creates for it. Blender authenticates it via the pairing code
 * (fingerprint prefix) and afterwards by pinning the full fingerprint.
 */
object AndroidTls {
    private const val ALIAS = "scanforge-transfer-identity"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureIdentity(): X509Certificate {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=ScanForge 3D"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec); generateKeyPair() }
        }
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    /** Deletes the identity (e.g. "Reset pairing"); every paired computer must pair again. */
    fun resetIdentity() { keyStore().deleteEntry(ALIAS) }

    fun sslContext(): SSLContext {
        val cert = ensureIdentity()
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        // Explicit key manager: the private key stays an opaque AndroidKeyStore handle used only for signing.
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(km), null, null) }
    }
}
