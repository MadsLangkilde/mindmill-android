package dk.langkilde.mindmill.remote

import dk.langkilde.mindmill.store.MemoryKv
import dk.langkilde.mindmill.store.PairingStore
import dk.langkilde.mindmill.store.Pairings
import dk.langkilde.mindmill.store.StoredPairing
import dk.langkilde.mindmill.store.XorCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Two Mindmills on two computers, each with its own certificate and device token, and the phone's one connection. */
class TwoComputersTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val closing = mutableListOf<AutoCloseable>()
    private val store = PairingStore(MemoryKv(), XorCipher())

    private fun mac(name: String) = FakeMac(name).also { closing += it }

    @After
    fun tearDown() {
        scope.cancel()
        closing.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(main) { block() }

    private suspend fun RemoteConnection.connectedTo(mac: FakeMac): ConnectionState.Connected = withTimeout(10_000) {
        state.first { (it as? ConnectionState.Connected)?.hello?.name == mac.reportedName } as ConnectionState.Connected
    }

    @Test
    fun pairsWithBothAndSwitchesBetweenThem() = runBlocking<Unit> {
        val studio = mac("Studio")
        val laptop = mac("Laptop")
        val conn = RemoteConnection(store, scope)
        onMain { conn.pair(studio.invite(), "123456", "Pixel") }
        conn.connectedTo(studio)
        onMain { conn.pair(laptop.invite(), "123456", "Pixel") }
        conn.connectedTo(laptop)
        assertEquals(listOf("Studio", "Laptop"), conn.pairings.value.macs.map { it.macName })
        assertEquals(laptop.fingerprint, conn.active?.fingerprint)

        onMain { conn.switchTo(studio.fingerprint) }
        conn.connectedTo(studio)
        laptop.awaitNoPhone()
        onMain { conn.refreshState() }

        onMain { conn.switchTo(laptop.fingerprint) }
        conn.connectedTo(laptop)
        studio.awaitNoPhone()

        assertEquals(listOf(studio.token), studio.greetedWith.distinct())
        assertEquals(listOf(laptop.token), laptop.greetedWith.distinct())

        val again = store.loadPairings()
        assertEquals(laptop.fingerprint, again.active?.fingerprint)
        assertEquals(setOf(studio.token, laptop.token), again.macs.map { it.token }.toSet())
    }

    @Test
    fun switchesAwayFromAComputerThatDoesNotAnswer() = runBlocking<Unit> {
        val laptop = mac("Laptop")
        val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { closing += it }
        val off = StoredPairing("Studio", listOf("127.0.0.1"), silent.localPort, "cd".repeat(32), "studio-token")
        store.savePairings(Pairings().paired(laptop.stored()).paired(off))
        val conn = RemoteConnection(store, scope)
        onMain { conn.start() }
        delay(500)
        assertTrue(conn.state.value !is ConnectionState.Connected)

        onMain { conn.switchTo(laptop.fingerprint) }
        conn.connectedTo(laptop)
        delay(5_000)
        assertEquals("Laptop", (conn.state.value as ConnectionState.Connected).hello.name)
        withTimeout(5_000) { onMain { conn.refreshState() } }
    }

    @Test
    fun aComputerThatForgotThePhoneLeavesTheOtherUsable() = runBlocking<Unit> {
        val studio = mac("Studio")
        val laptop = mac("Laptop")
        store.savePairings(Pairings().paired(laptop.stored()).paired(studio.stored().copy(token = "revoked")))
        val conn = RemoteConnection(store, scope)
        onMain { conn.start() }
        withTimeout(10_000) { conn.state.first { it is ConnectionState.Unauthorized } }

        onMain { conn.switchTo(laptop.fingerprint) }
        conn.connectedTo(laptop)
    }

    @Test
    fun keepsTheNameTheComputerGoesByNow() = runBlocking<Unit> {
        val studio = mac("Studio")
        val conn = RemoteConnection(store, scope)
        onMain { conn.pair(studio.invite(), "123456", "Pixel") }
        conn.connectedTo(studio)
        studio.reportedName = "Studio Mac"
        onMain { conn.stop(); conn.start() }
        conn.connectedTo(studio)
        withTimeout(5_000) { conn.pairings.first { it.active?.macName == "Studio Mac" } }
        assertEquals("Studio Mac", store.loadPairings().active?.macName)
    }
}

/** A Mindmill on another computer, as far as the phone can tell: its own certificate, pairing code and device token. */
private class FakeMac(val name: String) : AutoCloseable {
    @Volatile var reportedName = name
    val token = "token-of-$name"
    val greetedWith = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val held = HeldCertificate.Builder().commonName("Mindmill on $name").addSubjectAlternativeName("127.0.0.1").build()
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(held.certificate.encoded).joinToString("") { "%02x".format(it) }
    private val server = MockWebServer()

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().webSocketUpgrade(Phone()).build()
        }
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    fun invite() = PairingInvite(listOf("127.0.0.1"), server.port, fingerprint, "123456", name)

    fun stored() = StoredPairing(name, listOf("127.0.0.1"), server.port, fingerprint, token)

    suspend fun awaitNoPhone() = withTimeout(5_000) { while (sockets.isNotEmpty()) delay(20) }

    override fun close() = server.close()

    private fun hello() = """{"name":"$reportedName","version":1,"brain":"Claude","scopes":[],"sections":[]}"""

    private inner class Phone : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val r = RemoteMessage.decode(text) as RemoteMessage.Request
            val answer = when (r.method) {
                "pair" -> if (r.params.string("code") == "123456") """{"token":"$token"}""" else null
                "hello" -> if (r.params.string("token") == token) hello().also { greetedWith += token } else null
                "state" -> hello()
                else -> null
            }
            webSocket.send(
                if (answer != null) """{"id":"${r.id}","result":$answer}"""
                else """{"id":"${r.id}","error":{"code":"unauthorized","message":"Pair again."}}""",
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            sockets -= webSocket
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets -= webSocket }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets -= webSocket }
    }
}
