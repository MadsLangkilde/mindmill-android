package dk.langkilde.mindmill.remote

import dk.langkilde.mindmill.store.StoredPairing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class LauncherProofTest {
    @Test
    fun matchesTheContractsTestVector() {
        assertEquals("19b6b086eebb807f54e6327309dec0ff347a6c3c30bf3bb396f167513eba3475", LauncherProof.tokenHash("test-token-123"))
        assertEquals(
            "9b89935c64db57ff6439992d10224d8b92300607ba4067092741cc82d3a84e28",
            LauncherProof.proof("test-token-123", "00112233445566778899aabbccddeeff"),
        )
    }

    @Test
    fun readsTheGreetingAndTheAnswer() {
        assertEquals("00112233445566778899aabbccddeeff", MacLauncher.parseGreeting("MINDMILL 1 00112233445566778899aabbccddeeff"))
        assertNull(MacLauncher.parseGreeting("MINDMILL 2 00112233445566778899aabbccddeeff"))
        assertNull(MacLauncher.parseGreeting("MINDMILL 1 0011"))
        assertNull(MacLauncher.parseGreeting("HTTP/1.1 400 Bad Request"))
        assertNull(MacLauncher.parseGreeting(null))
        assertEquals(LaunchResult.STARTED, MacLauncher.parseAnswer("OK started"))
        assertEquals(LaunchResult.RUNNING, MacLauncher.parseAnswer("OK running\r"))
        assertEquals(LaunchResult.REFUSED, MacLauncher.parseAnswer("NO"))
        assertNull(MacLauncher.parseAnswer("maybe"))
        assertNull(MacLauncher.parseAnswer(null))
    }

    @Test
    fun aLineEndsAtNewlineAndAFloodIsNotALine() {
        assertEquals("NO", MacLauncher.readLine(ByteArrayInputStream("NO\r\nrest".toByteArray())))
        assertNull(MacLauncher.readLine(ByteArrayInputStream("no newline".toByteArray())))
        assertNull(MacLauncher.readLine(ByteArrayInputStream(ByteArray(2_000) { 'a'.code.toByte() }), max = 512))
    }

    @Test
    fun startIsOfferedOnlyWhenItCanHelp() {
        val mac = StoredPairing("Studio", listOf("192.168.1.5"), 7878, "aa".repeat(32), "tok", launcherPort = 7879)
        val refused = ConnectionState.Unreachable("Studio", ConnectionProblem(ConnectionProblem.Kind.REFUSED), 4, 0)
        assertTrue(canStartMindmill(mac, refused))
        assertTrue(canStartMindmill(mac, ConnectionState.Reconnecting("Studio", ConnectionProblem(ConnectionProblem.Kind.TIMEOUT), 1, 0, true)))
        assertFalse(canStartMindmill(mac.copy(launcherPort = null), refused))
        assertFalse(canStartMindmill(mac, ConnectionState.Unreachable("Studio", ConnectionProblem(ConnectionProblem.Kind.NO_NETWORK), 1, 0)))
        assertFalse(canStartMindmill(mac, ConnectionState.Unreachable("Studio", ConnectionProblem(ConnectionProblem.Kind.CERTIFICATE), 1, 0)))
        assertFalse(canStartMindmill(mac, ConnectionState.Connecting("Studio", null, 0, false)))
        assertFalse(canStartMindmill(null, refused))
    }

    @Test
    fun saysWhyInWordsForTheLayout() {
        assertTrue(LaunchResult.NO_ANSWER.message("Studio").contains("asleep"))
        assertTrue(LaunchResult.REFUSED.message("Studio").startsWith("Studio said no"))
    }
}

/** The launcher client against a local socket that plays the Mac's side. */
class MacLauncherTest {
    private val nonce = "00112233445566778899aabbccddeeff"
    private val token = "test-token-123"
    private val proof = "9b89935c64db57ff6439992d10224d8b92300607ba4067092741cc82d3a84e28"
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val servers = mutableListOf<ServerSocket>()
    private val heard = CopyOnWriteArrayList<String?>()
    private val launcher = MacLauncher(connectTimeoutMs = 1_000, readTimeoutMs = 500)

    @After
    fun close() = servers.forEach { runCatching { it.close() } }

    /** A Mac that greets with [greeting] (none when null), hears the phone's line, and answers [answer] (none when null). */
    private fun mac(greeting: String? = "MINDMILL 1 $nonce", answer: (String?) -> String?): Int {
        val server = ServerSocket(0, 5, loopback).also { servers += it }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s: Socket = runCatching { server.accept() }.getOrNull() ?: break
                s.use {
                    val out = it.getOutputStream()
                    if (greeting != null) {
                        out.write("$greeting\n".toByteArray())
                        out.flush()
                    }
                    val line = runCatching { MacLauncher.readLine(it.getInputStream()) }.getOrNull()
                    heard += line
                    answer(line)?.let { a ->
                        out.write("$a\n".toByteArray())
                        out.flush()
                    } ?: Thread.sleep(1_500)
                }
            }
        }
        return server.localPort
    }

    @Test
    fun startsMindmillWithTheProofAndNeverTheToken() = runBlocking {
        val port = mac { line -> if (line == "START $proof") "OK started" else "NO" }
        assertEquals(LaunchResult.STARTED, launcher.start(listOf("127.0.0.1"), port, token))
        assertEquals(listOf("START $proof"), heard)
        assertTrue(heard.none { it?.contains(token) == true })
    }

    @Test
    fun saysWhenMindmillRanAlready() = runBlocking {
        val port = mac { "OK running" }
        assertEquals(LaunchResult.RUNNING, launcher.start(listOf("127.0.0.1"), port, token))
    }

    @Test
    fun aNoIsRefused() = runBlocking {
        val port = mac { "NO" }
        assertEquals(LaunchResult.REFUSED, launcher.start(listOf("127.0.0.1"), port, token))
    }

    @Test
    fun somethingElseOnThePortIsNoAnswer() = runBlocking {
        val port = mac(greeting = "SSH-2.0-OpenSSH_9.0") { "OK started" }
        assertEquals(LaunchResult.NO_ANSWER, launcher.start(listOf("127.0.0.1"), port, token))
        assertTrue(heard.none { it?.startsWith("START") == true })
    }

    @Test
    fun aMacThatGoesQuietTimesOut() = runBlocking {
        val silent = mac(greeting = null) { null }
        assertEquals(LaunchResult.NO_ANSWER, launcher.start(listOf("127.0.0.1"), silent, token))
        val noAnswer = mac { null }
        assertEquals(LaunchResult.NO_ANSWER, launcher.start(listOf("127.0.0.1"), noAnswer, token))
        assertEquals(listOf("START $proof"), heard.filterNotNull())
    }

    @Test
    fun nothingListeningIsNoAnswer() = runBlocking {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        assertEquals(LaunchResult.NO_ANSWER, launcher.start(listOf("127.0.0.1"), port, token))
        assertEquals(LaunchResult.NO_ANSWER, launcher.start(emptyList(), port, token))
    }

    @Test
    fun triesTheHostsInOrderUntilOneSaysOk() = runBlocking {
        val port = mac { "OK started" }
        assertEquals(LaunchResult.STARTED, launcher.start(listOf("::1", "127.0.0.1"), port, token))
    }
}
