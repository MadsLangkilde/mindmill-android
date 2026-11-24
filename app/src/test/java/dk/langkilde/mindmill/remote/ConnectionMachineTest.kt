package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class ConnectionMachineTest {
    private val hello = HelloInfo("Studio", 1, "Claude", null, emptyList(), emptyList())
    private val timeout = ConnectionProblem(ConnectionProblem.Kind.TIMEOUT)

    @Test fun firstTryIsQuietConnecting() {
        val m = ConnectionMachine("Studio")
        val s = m.trying(null)
        assertEquals(ConnectionState.Connecting("Studio", null, 0, false), s)
    }

    @Test fun firstFailuresReconnectWithBackoffThenUnreachable() {
        val m = ConnectionMachine("Studio")
        val (s1, d1) = m.failed(timeout, 1_000)
        assertTrue(s1 is ConnectionState.Reconnecting)
        assertEquals(1_000, d1)
        assertEquals(2_000, (s1 as ConnectionState.Reconnecting).retryAtMillis)
        val (s2, d2) = m.failed(timeout, 0)
        assertTrue(s2 is ConnectionState.Reconnecting)
        assertEquals(2_000, d2)
        val (s3, d3) = m.failed(timeout, 0)
        assertTrue(s3 is ConnectionState.Reconnecting)
        assertEquals(4_000, d3)
        val (s4, d4) = m.failed(timeout, 0)
        assertTrue(s4 is ConnectionState.Unreachable)
        assertEquals(8_000, d4)
        assertEquals(4, (s4 as ConnectionState.Unreachable).attempt)
        assertEquals(4, (m.trying("10.0.0.2") as ConnectionState.Connecting).attempt)
    }

    @Test fun backoffCapsAtTheLongestStep() {
        val m = ConnectionMachine("Studio")
        var last = 0L
        repeat(20) { last = m.failed(timeout, 0).second }
        assertEquals(30_000, last)
    }

    @Test fun connectingResetsAndRemembersItWasConnected() {
        val m = ConnectionMachine("Studio")
        m.failed(timeout, 0)
        m.failed(timeout, 0)
        val s = m.connected("10.0.0.2", hello)
        assertEquals(ConnectionState.Connected("Studio", "10.0.0.2", hello), s)
        val (dropped, delay) = m.failed(ConnectionProblem(ConnectionProblem.Kind.DROPPED), 0)
        assertTrue(dropped is ConnectionState.Reconnecting)
        assertTrue((dropped as ConnectionState.Reconnecting).wasConnected)
        assertEquals(1_000, delay)
        assertTrue((m.trying(null) as ConnectionState.Connecting).wasConnected)
    }

    @Test fun resetStartsTheBackoffOver() {
        val m = ConnectionMachine("Studio")
        repeat(5) { m.failed(timeout, 0) }
        m.reset()
        assertEquals(0, m.failures)
        val (s, d) = m.failed(timeout, 0)
        assertTrue(s is ConnectionState.Reconnecting)
        assertEquals(1_000, d)
    }

    @Test fun offlineAndCertificateAreToldAtOnce() {
        val m = ConnectionMachine("Studio")
        assertTrue(m.failed(ConnectionProblem(ConnectionProblem.Kind.NO_NETWORK), 0).first is ConnectionState.Unreachable)
        m.reset()
        val (s, d) = m.failed(ConnectionProblem(ConnectionProblem.Kind.CERTIFICATE), 0)
        assertTrue(s is ConnectionState.Unreachable)
        assertEquals(30_000, d)
    }

    @Test fun problemsFromExceptions() {
        assertEquals(ConnectionProblem.Kind.REFUSED, ConnectionProblem.from(ConnectException("refused")).kind)
        assertEquals(ConnectionProblem.Kind.TIMEOUT, ConnectionProblem.from(SocketTimeoutException()).kind)
        assertEquals(ConnectionProblem.Kind.NOT_FOUND, ConnectionProblem.from(UnknownHostException()).kind)
        val pinned = SSLHandshakeException("bad").apply { initCause(CertificateException("pin")) }
        assertEquals(ConnectionProblem.Kind.CERTIFICATE, ConnectionProblem.from(pinned).kind)
    }

    @Test fun mostTellingProblemWins() {
        val p = ConnectionProblem.mostTelling(listOf(timeout, ConnectionProblem(ConnectionProblem.Kind.REFUSED), timeout))
        assertEquals(ConnectionProblem.Kind.REFUSED, p?.kind)
    }

    @Test fun tipsNameTheFixes() {
        val tips = timeout.tips("Studio").joinToString(" ")
        assertTrue("Wi-Fi" in tips)
        assertTrue("Tailscale" in tips)
        assertTrue("Settings → Remote" in tips)
        assertTrue("Mindmill is open" in tips)
        assertFalse(timeout.message("Studio").contains("null"))
    }
}
