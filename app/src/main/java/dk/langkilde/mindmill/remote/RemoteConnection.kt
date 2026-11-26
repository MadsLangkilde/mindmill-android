package dk.langkilde.mindmill.remote

import dk.langkilde.mindmill.store.PairingStore
import dk.langkilde.mindmill.store.Pairings
import dk.langkilde.mindmill.store.StoredPairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class HelloInfo(
    val name: String,
    val version: Int,
    val brain: String,
    val scope: String?,
    val scopes: List<Scope>,
    val sections: List<String>,
) {
    companion object {
        fun parse(o: JsonObject) = HelloInfo(
            name = o.string("name") ?: "Mac",
            version = o.int("version") ?: 1,
            brain = o.string("brain") ?: "Claude",
            scope = o.string("scope"),
            scopes = o.objects("scopes").mapNotNull(Scope::parse),
            sections = o.strings("sections"),
        )
    }
}

/**
 * The one WebSocket to the active Mac: tries its hosts in order, says hello with the device token, and reconnects
 * with backoff when the socket drops. [ConnectionMachine] decides what to show and how long to wait. The phone can be
 * paired with several Macs ([pairings]) and talks to one at a time; [switchTo] moves to another.
 */
class RemoteConnection(private val store: PairingStore, private val scope: CoroutineScope) {
    val rpc = RpcChannel()

    private val _pairings = MutableStateFlow(store.loadPairings())
    /** Every paired Mac and which one is active, as stored. */
    val pairings: StateFlow<Pairings> = _pairings

    /** The Mac the phone talks to. */
    val active: StoredPairing? get() = pairings.value.active

    private val _state = MutableStateFlow<ConnectionState>(
        active?.let { ConnectionState.Connecting(it.macName, null, 0, false) } ?: ConnectionState.NotPaired,
    )
    val state: StateFlow<ConnectionState> = _state

    private val _lastHello = MutableStateFlow<HelloInfo?>(null)
    /** What the active Mac said last, kept while the connection is down so its scopes stay pickable. */
    val lastHello: StateFlow<HelloInfo?> = _lastHello

    private var loop: Job? = null
    @Volatile private var link: Link? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var cachedClient: Pair<String, OkHttpClient>? = null
    private var machine: ConnectionMachine? = null

    /** False while Android says there is no network at all; set by the app's network callback. */
    @Volatile var networkAvailable = true
        private set

    val hello: HelloInfo? get() = (state.value as? ConnectionState.Connected)?.hello

    val pairedMacName: String? get() = active?.macName

    private fun save(p: Pairings) {
        store.savePairings(p)
        _pairings.value = p
    }

    fun start() {
        if (loop?.isActive == true) return
        val pairing = active ?: run { _state.value = ConnectionState.NotPaired; return }
        loop = scope.launch { runLoop(pairing) }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        closeLink()
    }

    /** Try again now instead of waiting out the backoff. Does nothing while connected. */
    fun retryNow() {
        when (state.value) {
            is ConnectionState.Unauthorized, is ConnectionState.Connected -> return
            else -> if (loop?.isActive != true) start() else wake.trySend(Unit)
        }
    }

    /** The app came to the foreground: start the backoff over and try now. */
    fun onForeground() {
        machine?.reset()
        retryNow()
        if (state.value is ConnectionState.Connected) scope.launch { checkAlive() }
    }

    fun onNetworkChanged(available: Boolean) {
        networkAvailable = available
        if (!available) return
        machine?.reset()
        if (state.value is ConnectionState.Connected) scope.launch { checkAlive() } else retryNow()
    }

    /** After a network change the socket may be dead without knowing it: ask, and drop it if the Mac doesn't answer. */
    private suspend fun checkAlive() {
        val l = link ?: return
        try {
            rpc.call("state", timeoutMs = 5_000)
        } catch (e: RemoteException) {
            if (link === l && e.code != ErrorCodes.UNKNOWN_METHOD) closeLink()
        }
    }

    /** Waits up to [timeoutMs] for a connection; false when there is none by then. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        if (state.value is ConnectionState.Connected && rpc.isOpen) return true
        retryNow()
        return withTimeoutOrNull(timeoutMs) { state.first { it is ConnectionState.Connected || it is ConnectionState.NotPaired } }
            ?.let { it is ConnectionState.Connected } ?: false
    }

    /** Disconnects from the active Mac and connects to [fingerprint], a paired one. */
    fun switchTo(fingerprint: String) {
        val target = pairings.value.find(fingerprint) ?: return
        if (active?.isMac(fingerprint) == true && loop?.isActive == true) return
        stop()
        save(pairings.value.activate(target.fingerprint))
        restartForActive()
    }

    /** Forgets a paired Mac. When it was the active one, the phone moves on to another, or back to pairing. */
    fun remove(fingerprint: String) {
        val wasActive = active?.isMac(fingerprint) == true
        if (wasActive) stop()
        save(pairings.value.removed(fingerprint))
        if (wasActive) restartForActive()
    }

    /** The phone's own scope on the active Mac. */
    fun setScope(scopeId: String?) {
        val a = active ?: return
        if (a.scope == scopeId) return
        save(pairings.value.updated(a.fingerprint) { it.copy(scope = scopeId) })
    }

    private fun restartForActive() {
        machine = null
        _lastHello.value = null
        val next = active
        _state.value = next?.let { ConnectionState.Connecting(it.macName, null, 0, false) } ?: ConnectionState.NotPaired
        if (next != null) start()
    }

    suspend fun refreshState() {
        val current = state.value as? ConnectionState.Connected ?: return
        val info = HelloInfo.parse(rpc.call("state"))
        if (state.value !== current) return
        _lastHello.value = info
        _state.value = current.copy(hello = info)
    }

    /**
     * Pairs with the code shown on the Mac, stores what's needed to come back, and connects to it. Other paired Macs
     * stay; pairing one of them again replaces its entry. When pairing fails, the phone goes back to the Mac it had.
     */
    suspend fun pair(invite: PairingInvite, code: String, deviceName: String): StoredPairing {
        val shut = state.value as? ConnectionState.Unauthorized
        stop()
        if (active != null && shut == null) _state.value = ConnectionState.Connecting(invite.name, null, 0, false)
        var paired = false
        try {
            val problems = mutableListOf<ConnectionProblem>()
            val opened = openFirst(invite.hosts, invite.port, invite.fingerprint, problems, report = null)
                ?: throw PairingFailed(ConnectionProblem.mostTelling(problems) ?: ConnectionProblem(ConnectionProblem.Kind.TIMEOUT), invite.name)
            val r = rpc.call("pair", jsonOf("code" to code.trim(), "name" to deviceName, "platform" to PLATFORM), 15_000)
            val token = r.string("token")?.takeIf { it.isNotEmpty() }
                ?: throw RemoteException(ErrorCodes.FAILED, "The Mac didn't send a device token.")
            val p = StoredPairing(invite.name, invite.hosts, invite.port, invite.fingerprint, token, opened.second)
            save(pairings.value.paired(p))
            paired = true
            return p
        } finally {
            closeLink()
            when {
                paired -> restartForActive()
                shut != null -> _state.value = shut
                active != null -> {
                    machine = null
                    start()
                }
            }
        }
    }

    private suspend fun runLoop(initial: StoredPairing) {
        val m = machine?.takeIf { it.macName == initial.macName } ?: ConnectionMachine(initial.macName).also { machine = it }
        while (coroutineContext.isActive) {
            val pairing = pairings.value.find(initial.fingerprint) ?: initial
            val problem: ConnectionProblem
            if (!networkAvailable) {
                problem = ConnectionProblem(ConnectionProblem.Kind.NO_NETWORK)
            } else {
                _state.value = m.trying(pairing.lastHost)
                val problems = mutableListOf<ConnectionProblem>()
                val opened = openFirst(pairing.orderedHosts, pairing.port, pairing.fingerprint, problems) { host -> _state.value = m.trying(host) }
                if (opened != null) {
                    val (l, host) = opened
                    try {
                        val hello = rpc.call("hello", jsonOf("token" to pairing.token, "platform" to PLATFORM), 10_000)
                        val info = HelloInfo.parse(hello)
                        val known = pairings.value.find(pairing.fingerprint)
                        val name = hello.string("name")?.takeIf { it.isNotBlank() } ?: known?.macName
                        if (known != null && (host != known.lastHost || name != known.macName)) {
                            save(pairings.value.updated(pairing.fingerprint) { it.copy(lastHost = host, macName = name ?: it.macName) })
                        }
                        _lastHello.value = info
                        _state.value = m.connected(host, info)
                        l.closed.await()
                        problems += ConnectionProblem(ConnectionProblem.Kind.DROPPED)
                    } catch (e: RemoteException) {
                        if (e.isUnauthorized) {
                            closeLink()
                            _state.value = ConnectionState.Unauthorized(
                                "${pairing.macName} doesn't know this phone any more. Pair again to connect.",
                            )
                            return
                        }
                        problems += ConnectionProblem(ConnectionProblem.Kind.OTHER, e.userMessage())
                    }
                }
                problem = if (opened != null) problems.last() else ConnectionProblem.mostTelling(problems)
                    ?: ConnectionProblem(ConnectionProblem.Kind.TIMEOUT)
            }
            closeLink()
            val (st, delay) = m.failed(problem, System.currentTimeMillis())
            _state.value = st
            withTimeoutOrNull(delay) { wake.receive() }
        }
    }

    private suspend fun openFirst(
        hosts: List<String>, port: Int, fingerprint: String, problems: MutableList<ConnectionProblem>,
        report: ((String) -> Unit)?,
    ): Pair<Link, String>? {
        val client = clientFor(fingerprint) ?: run {
            problems += ConnectionProblem(ConnectionProblem.Kind.CERTIFICATE)
            return null
        }
        for (host in hosts) {
            report?.invoke(host)
            val l = Link()
            link = l
            val url = "wss://${if (':' in host) "[$host]" else host}:$port/"
            l.socket = client.newWebSocket(Request.Builder().url(url).build(), l)
            val ok = withTimeoutOrNull(4_000) { runCatching { l.opened.await() }.isSuccess } ?: false
            if (ok) {
                rpc.sender = { text -> l.socket?.send(text) ?: false }
                rpc.binarySender = { bytes -> l.socket?.send(bytes.toByteString()) ?: false }
                return l to host
            }
            problems += l.problem ?: ConnectionProblem(ConnectionProblem.Kind.TIMEOUT)
            if (link === l) link = null
            l.socket?.cancel()
        }
        return null
    }

    private fun clientFor(fingerprint: String): OkHttpClient? {
        cachedClient?.let { (fp, c) -> if (fp == fingerprint) return c }
        val tm = runCatching { PinnedTrustManager(fingerprint) }.getOrNull() ?: return null
        val c = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .sslSocketFactory(tm.socketFactory(), tm)
            .hostnameVerifier { _, _ -> true }
            .build()
        cachedClient = fingerprint to c
        return c
    }

    private fun closeLink() {
        val l = link ?: return
        link = null
        rpc.failAll()
        l.socket?.close(1000, null)
        l.closed.complete(Unit)
    }

    private inner class Link : WebSocketListener() {
        var socket: WebSocket? = null
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        @Volatile var problem: ConnectionProblem? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (link === this) rpc.onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (link === this) rpc.onBinary(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            problem = ConnectionProblem.from(t)
            opened.completeExceptionally(t)
            ended()
        }

        private fun ended() {
            if (link === this) rpc.failAll()
            closed.complete(Unit)
        }
    }
}

/** Pairing couldn't reach the Mac at all. */
class PairingFailed(val problem: ConnectionProblem, val macName: String) : Exception(problem.message(macName))

/** Sent with `pair` and `hello`, so the Mac sends this phone only builds it can install. */
const val PLATFORM = "android"
