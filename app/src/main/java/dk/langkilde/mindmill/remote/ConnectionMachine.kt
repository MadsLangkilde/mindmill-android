package dk.langkilde.mindmill.remote

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Why the phone can't talk to the Mac, in words the user can act on. */
data class ConnectionProblem(val kind: Kind, val detail: String? = null) {
    enum class Kind { NO_NETWORK, REFUSED, TIMEOUT, NOT_FOUND, CERTIFICATE, DROPPED, OTHER }

    fun message(mac: String): String = when (kind) {
        Kind.NO_NETWORK -> "This phone is offline."
        Kind.REFUSED -> "$mac answered, but Mindmill isn't listening there."
        Kind.TIMEOUT -> "$mac didn't answer."
        Kind.NOT_FOUND -> "Can't find $mac on this network."
        Kind.CERTIFICATE -> "This isn't the Mac you paired with: its certificate changed."
        Kind.DROPPED -> "The connection to $mac dropped."
        Kind.OTHER -> detail?.takeIf { it.isNotBlank() }?.let { "Couldn't connect to $mac: $it" } ?: "Couldn't connect to $mac."
    }

    fun tips(mac: String): List<String> {
        val network = "Put the phone on the same Wi-Fi as $mac, or turn on Tailscale on both."
        val running = "Check that Mindmill is open on $mac."
        val remote = "On $mac, turn on Mindmill → Settings → Remote."
        return when (kind) {
            Kind.NO_NETWORK -> listOf("Turn on Wi-Fi or mobile data.", "Away from home, connect to Tailscale on the phone and on $mac.")
            Kind.REFUSED -> listOf(running, remote)
            Kind.CERTIFICATE -> listOf("If Mindmill was reinstalled on $mac, remove it here (More → Connection) and pair again.")
            else -> listOf(network, running, remote)
        }
    }

    /** No point retrying fast: the user has to change something first. */
    val needsUser get() = kind == Kind.CERTIFICATE

    companion object {
        fun from(t: Throwable?): ConnectionProblem {
            var c: Throwable? = t
            while (c != null) {
                if (c is CertificateException) return ConnectionProblem(Kind.CERTIFICATE)
                c = c.cause
            }
            return when (t) {
                null -> ConnectionProblem(Kind.TIMEOUT)
                is ConnectException -> ConnectionProblem(Kind.REFUSED)
                is SocketTimeoutException -> ConnectionProblem(Kind.TIMEOUT)
                is NoRouteToHostException -> ConnectionProblem(Kind.TIMEOUT)
                is UnknownHostException -> ConnectionProblem(Kind.NOT_FOUND)
                is SSLException -> ConnectionProblem(Kind.OTHER, "the secure connection failed")
                is java.io.EOFException -> ConnectionProblem(Kind.DROPPED)
                else -> ConnectionProblem(Kind.OTHER, t.message)
            }
        }

        /** Of the problems seen trying every host, the one that says most about what to do. */
        fun mostTelling(problems: List<ConnectionProblem>): ConnectionProblem? {
            val order = listOf(Kind.CERTIFICATE, Kind.REFUSED, Kind.OTHER, Kind.DROPPED, Kind.TIMEOUT, Kind.NOT_FOUND, Kind.NO_NETWORK)
            return problems.minByOrNull { order.indexOf(it.kind) }
        }
    }
}

sealed interface ConnectionState {
    data object NotPaired : ConnectionState

    /** Trying now. [attempt] 0 is the first try since the app started, the network changed or it connected. */
    data class Connecting(val macName: String, val host: String?, val attempt: Int, val wasConnected: Boolean) : ConnectionState

    data class Connected(val macName: String, val host: String, val hello: HelloInfo) : ConnectionState

    /** A try failed; the next is at [retryAtMillis]. */
    data class Reconnecting(
        val macName: String, val problem: ConnectionProblem, val attempt: Int, val retryAtMillis: Long, val wasConnected: Boolean,
    ) : ConnectionState

    /** Several tries failed: say why and how to fix it. Still retries at [retryAtMillis]. */
    data class Unreachable(val macName: String, val problem: ConnectionProblem, val attempt: Int, val retryAtMillis: Long) : ConnectionState

    data class Unauthorized(val message: String) : ConnectionState
}

val ConnectionState.isConnected get() = this is ConnectionState.Connected

val ConnectionState.macName: String?
    get() = when (this) {
        is ConnectionState.Connecting -> macName
        is ConnectionState.Connected -> macName
        is ConnectionState.Reconnecting -> macName
        is ConnectionState.Unreachable -> macName
        else -> null
    }

class Backoff(private val steps: LongArray = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)) {
    /** The wait after the [failures]th failure in a row (1 is the first). */
    fun delayMs(failures: Int): Long = steps[(failures - 1).coerceIn(0, steps.lastIndex)]
    val longest get() = steps.last()
}

/**
 * What the connection shows and how long it waits, from what happened. No I/O, so it can be tested: the connection
 * loop reports each try and acts on the delay it gets back.
 */
class ConnectionMachine(
    val macName: String,
    private val backoff: Backoff = Backoff(),
    private val unreachableAfter: Int = 4,
) {
    var failures = 0
        private set
    var everConnected = false
        private set

    fun trying(host: String?): ConnectionState = ConnectionState.Connecting(macName, host, failures, everConnected)

    fun connected(host: String, hello: HelloInfo): ConnectionState {
        failures = 0
        everConnected = true
        return ConnectionState.Connected(macName, host, hello)
    }

    /** A try failed or the socket dropped: the state to show and how long to wait before the next try. */
    fun failed(problem: ConnectionProblem, now: Long): Pair<ConnectionState, Long> {
        failures++
        val delay = if (problem.needsUser) backoff.longest else backoff.delayMs(failures)
        val at = now + delay
        val telling = failures >= unreachableAfter || problem.kind == ConnectionProblem.Kind.NO_NETWORK || problem.needsUser
        val state = if (telling) {
            ConnectionState.Unreachable(macName, problem, failures, at)
        } else {
            ConnectionState.Reconnecting(macName, problem, failures, at, everConnected)
        }
        return state to delay
    }

    /** The network changed or the app came back: start the backoff over. */
    fun reset() {
        failures = 0
    }
}
