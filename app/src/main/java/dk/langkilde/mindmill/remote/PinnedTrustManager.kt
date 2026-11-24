package dk.langkilde.mindmill.remote

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts exactly one certificate: the Mac's self-signed one, whose SHA-256 (of the DER bytes, lower-case hex) came in
 * the pairing invite. No certificate authority and no host name check: the Mac is reached by IP addresses and names
 * that a self-signed certificate can't vouch for, so the pin is the whole of the trust.
 */
class PinnedTrustManager(fingerprint: String) : X509ExtendedTrustManager() {
    private val pin = PairingInvite.normalizeFingerprint(fingerprint)
        ?: throw IllegalArgumentException("Not a SHA-256 fingerprint")

    fun accepts(leafDer: ByteArray): Boolean = MessageDigest.isEqual(
        sha256Hex(leafDer).toByteArray(), pin.toByteArray(),
    )

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The Mac sent no certificate")
        if (!accepts(leaf.encoded)) throw CertificateException("This isn't the Mac you paired with (certificate changed)")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates aren't used")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrustManager), null) }.socketFactory

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
