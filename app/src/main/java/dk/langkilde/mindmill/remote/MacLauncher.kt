package dk.langkilde.mindmill.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** What asking the Mac's launcher to start Mindmill came to. */
enum class LaunchResult {
    /** Mindmill is starting on the Mac, in the background. */
    STARTED,

    /** Mindmill was running already. */
    RUNNING,

    /** The launcher said no: it doesn't know this phone any more, or it is turned off on the Mac. */
    REFUSED,

    /** Nothing answered on the launcher port: it is off, the Mac sleeps, or it can't be reached from here. */
    NO_ANSWER;

    val ok: Boolean get() = this == STARTED || this == RUNNING
}

/** What to say in the layout when asking [mac]'s launcher didn't start Mindmill, or that it did. */
fun LaunchResult.message(mac: String): String = when (this) {
    LaunchResult.STARTED -> "Starting Mindmill on $mac."
    LaunchResult.RUNNING -> "Mindmill is running on $mac already."
    LaunchResult.REFUSED -> "$mac said no: starting Mindmill from the phone is turned off there " +
        "(Settings → General → In the Background), or it doesn't know this phone any more."
    LaunchResult.NO_ANSWER -> "Nothing answered on $mac: starting Mindmill from the phone is off, $mac is asleep, " +
        "or it can't be reached from here."
}

/** The proof the launcher wants: the token itself never goes over the plain connection. */
object LauncherProof {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun tokenHash(token: String): String = sha256Hex(token)

    fun proof(token: String, nonce: String): String = sha256Hex(tokenHash(token) + nonce)
}

/**
 * Asks the small launcher on a paired Mac to start Mindmill (docs/remote-api.md, "Starting Mindmill from the phone"):
 * plain TCP to the Mac's hosts in order, one line each way. The first `OK` wins; a host that doesn't answer, or answers
 * something else, hands over to the next.
 */
class MacLauncher(private val connectTimeoutMs: Int = 3_000, private val readTimeoutMs: Int = 10_000) {
    suspend fun start(hosts: List<String>, port: Int, token: String): LaunchResult = withContext(Dispatchers.IO) {
        var refused = false
        for (host in hosts) {
            when (val r = ask(host, port, token)) {
                LaunchResult.STARTED, LaunchResult.RUNNING -> return@withContext r
                LaunchResult.REFUSED -> refused = true
                else -> Unit
            }
        }
        if (refused) LaunchResult.REFUSED else LaunchResult.NO_ANSWER
    }

    /** One host; null when nothing that speaks the launcher's protocol answered there. */
    fun ask(host: String, port: Int, token: String): LaunchResult? = try {
        val address = InetSocketAddress(host, port)
        if (address.isUnresolved) {
            null
        } else {
            Socket().use { s ->
                s.connect(address, connectTimeoutMs)
                s.soTimeout = readTimeoutMs
                val nonce = parseGreeting(readLine(s.getInputStream()))
                if (nonce == null) {
                    null
                } else {
                    s.getOutputStream().apply {
                        write("START ${LauncherProof.proof(token, nonce)}\n".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    parseAnswer(readLine(s.getInputStream()))
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private val GREETING = Regex("MINDMILL 1 ([0-9a-fA-F]{32})")

        /** The nonce from the Mac's first line, or null when the line isn't the launcher's. */
        fun parseGreeting(line: String?): String? = line?.trim()?.let { GREETING.matchEntire(it)?.groupValues?.get(1) }

        fun parseAnswer(line: String?): LaunchResult? {
            val l = line?.trim() ?: return null
            return when {
                l == "OK running" -> LaunchResult.RUNNING
                l == "OK" || l.startsWith("OK ") -> LaunchResult.STARTED
                l == "NO" || l.startsWith("NO ") -> LaunchResult.REFUSED
                else -> null
            }
        }

        /** One line up to `\n`, at most [max] bytes; null when the other side closed first or sent too much. */
        fun readLine(input: InputStream, max: Int = 512): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                if (buf.size() >= max) return null
                buf.write(b)
            }
            return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
        }
    }
}
