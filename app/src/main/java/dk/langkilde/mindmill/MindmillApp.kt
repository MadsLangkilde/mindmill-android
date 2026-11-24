package dk.langkilde.mindmill

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import dk.langkilde.mindmill.delivery.Deliveries
import dk.langkilde.mindmill.delivery.Delivery
import dk.langkilde.mindmill.meeting.LiveMeeting
import dk.langkilde.mindmill.remote.ActionInfo
import dk.langkilde.mindmill.remote.ActionOutcome
import dk.langkilde.mindmill.remote.ActionRunner
import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.EmptyJson
import dk.langkilde.mindmill.remote.RemoteConnection
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.RemoteMessage
import dk.langkilde.mindmill.remote.RpcChannel
import dk.langkilde.mindmill.remote.Scopes
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import dk.langkilde.mindmill.store.AppSettings
import dk.langkilde.mindmill.store.KeystoreCipher
import dk.langkilde.mindmill.store.PairingStore
import dk.langkilde.mindmill.store.PrefsKeyValueStore
import dk.langkilde.mindmill.voice.AndroidApiAudio
import dk.langkilde.mindmill.voice.AndroidKeylessDevice
import dk.langkilde.mindmill.voice.LiveChat
import dk.langkilde.mindmill.voice.VoiceConfig
import dk.langkilde.mindmill.voice.VoiceConversation
import dk.langkilde.mindmill.voice.VoiceEngine
import dk.langkilde.mindmill.voice.VoiceRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * A message for the user, in the layout. [transient] ones (a finished action) go away by themselves; [route] is
 * where [action] leads (a `ui.Screen`, or "test").
 */
data class Notice(
    val text: String,
    val action: String? = null,
    val route: Any? = null,
    val transient: Boolean = false,
    val error: Boolean = false,
    val id: Long = System.nanoTime(),
)

/** A yes-or-no the Mac asked before a risky action. */
class ConfirmationBroker {
    data class Question(val text: String, val answer: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<Question?>(null)
    val pending: StateFlow<Question?> = _pending

    suspend fun ask(text: String): Boolean {
        val q = Question(text, CompletableDeferred())
        _pending.value = q
        return try { q.answer.await() } finally { if (_pending.value === q) _pending.value = null }
    }

    fun answer(yes: Boolean) { _pending.value?.answer?.complete(yes) }
}

private class RpcVoiceRemote(private val rpc: RpcChannel) : VoiceRemote {
    override suspend fun config() = VoiceConfig.parse(rpc.call("voice.config"))
    override suspend fun start(scope: String?, forceKeyless: Boolean): Pair<String, VoiceEngine?> {
        val r = rpc.call("voice.start", jsonOf("scope" to scope, "engine" to if (forceKeyless) "keyless" else null))
        val conv = r.text("conversation") ?: throw RemoteException("failed", "The Mac didn't start a conversation.")
        return conv to VoiceEngine.from(r.string("engine"))
    }
    override suspend fun say(conversation: String, text: String) =
        rpc.call("voice.say", jsonOf("conversation" to conversation, "text" to text)).text("turn")
    override suspend fun interrupt(conversation: String) { rpc.call("voice.interrupt", jsonOf("conversation" to conversation)) }
    override suspend fun end(conversation: String) { rpc.call("voice.end", jsonOf("conversation" to conversation)) }
    override fun sendFrame(frame: BinaryFrame) = rpc.sendFrame(frame)
}

class MindmillApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: PairingStore
    lateinit var connection: RemoteConnection
    lateinit var voice: VoiceConversation
    lateinit var voiceDevice: AndroidKeylessDevice
    /** Talking live in a session (the message box's live button); see [LiveChat]. */
    lateinit var liveChat: LiveChat
    lateinit var meeting: LiveMeeting
    lateinit var deliveries: Deliveries
    val confirmations = ConfirmationBroker()
    lateinit var actions: ActionRunner

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    /** The active Mac's certificate fingerprint: what lists on screen belong to. Null before pairing. */
    lateinit var activeMac: StateFlow<String?>
        private set

    /** The scope the phone chose on the active Mac (kept per Mac), before checking the Mac still has it. */
    lateinit var chosenScope: StateFlow<String?>
        private set

    private val _refresh = MutableStateFlow(0)
    /** Bumps on `index.changed` and on reconnect: screens reload their lists. */
    val refresh: StateFlow<Int> = _refresh

    private val _events = MutableSharedFlow<RemoteMessage.Event>(extraBufferCapacity = 256)
    /** Every event from the Mac, on the main thread. */
    val events: SharedFlow<RemoteMessage.Event> = _events

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    private val _reviewCards = MutableStateFlow<List<JsonObject>>(emptyList())
    val reviewCards: StateFlow<List<JsonObject>> = _reviewCards

    private var micForVoice = false
    private var micForLive: Pair<Boolean, String?> = false to null
    private var micForMeeting = false

    val rpc: RpcChannel get() = connection.rpc

    override fun onCreate() {
        super.onCreate()
        store = PairingStore(PrefsKeyValueStore(this), KeystoreCipher())
        _settings.value = store.loadSettings()
        connection = RemoteConnection(store, scope)
        activeMac = connection.pairings.map { it.active?.fingerprint }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, connection.active?.fingerprint)
        chosenScope = connection.pairings.map { it.active?.scope }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, connection.active?.scope)
        actions = ActionRunner({ m, p -> call(m, p) }, confirmations::ask)
        voiceDevice = AndroidKeylessDevice(this) { settings.value.routeToHeadset }
        voice = VoiceConversation(RpcVoiceRemote(rpc), voiceDevice, AndroidApiAudio(this), scope)
        liveChat = LiveChat({ m, p -> call(m, p) }, AndroidKeylessDevice(this) { settings.value.routeToHeadset }, scope)
        meeting = LiveMeeting(this, rpc, scope) { on -> micForMeeting = on; updateMic() }
        deliveries = Deliveries(this, rpc, scope)

        rpc.addEventListener { e -> scope.launch { handle(e) } }
        rpc.addFrameListener { f -> if (f.kind == "voice") scope.launch { voice.onFrame(f) } }

        scope.launch {
            voice.ui.collect { ui ->
                val on = ui.active || ui.starting
                if (on != micForVoice) { micForVoice = on; updateMic() }
            }
        }
        scope.launch {
            liveChat.ui.collect { ui ->
                val now = ui.active to ui.session
                if (now != micForLive) { micForLive = now; updateMic() }
            }
        }
        scope.launch {
            var wasConnected = false
            connection.state.collect { s ->
                val connected = s is ConnectionState.Connected
                if (connected && !wasConnected) {
                    _refresh.update { it + 1 }
                    catalog = null
                    if (connection.active?.scope == null) s.hello.scope?.let { setScope(it) }
                    loadReviewCards()
                }
                if (!connected && wasConnected && voice.ui.value.active) voice.end()
                if (!connected && wasConnected) liveChat.end()
                wasConnected = connected
            }
        }
        getSystemService(ConnectivityManager::class.java)?.let { cm ->
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch { connection.onNetworkChanged(true) } }
                override fun onLost(network: Network) {
                    scope.launch { if (cm.activeNetwork == null) connection.onNetworkChanged(false) }
                }
            })
        }
        scope.launch { activeMac.drop(1).collect { forgetMacData() } }
        connection.start()
    }

    /** The scope requests go to: the phone's own choice on this Mac while the Mac still has it, else the Mac's current one. */
    val currentScope: String?
        get() {
            val hello = connection.lastHello.value
            return Scopes.resolve(connection.active?.scope, hello?.scope, hello?.scopes)
        }

    /** Makes [id] the phone's scope on this Mac; with [alsoOnMac], the Mac's current one too (`project.select`). */
    fun setScope(id: String, alsoOnMac: Boolean = false) {
        connection.setScope(id)
        if (alsoOnMac) {
            scope.launch {
                runAndReport("project.select", jsonOf("scope" to id))
                runCatching { connection.refreshState() }
            }
        }
    }

    /**
     * Talks to another paired Mac from now on. Not while this phone records a meeting for the current one: that
     * would cut the meeting off. Lists on screen start over for the new Mac (see [activeMac]).
     */
    fun switchMac(fingerprint: String): Boolean {
        if (connection.active?.isMac(fingerprint) == true) return true
        if (meeting.isLive) {
            done("Stop the meeting on this phone before switching Macs.", error = true)
            return false
        }
        connection.switchTo(fingerprint)
        return true
    }

    /** Forgets a paired Mac; when it is the active one, the phone moves on to another or back to pairing. */
    fun removeMac(fingerprint: String) {
        if (connection.active?.isMac(fingerprint) == true && voice.ui.value.active) voice.end()
        connection.remove(fingerprint)
    }

    /** What belonged to the Mac the phone just left: nothing of it may show for the next one. */
    private fun forgetMacData() {
        if (voice.ui.value.active) voice.end()
        liveChat.end()
        catalog = null
        _reviewCards.value = emptyList()
        _notice.value = null
        deliveries.clear()
        _refresh.update { it + 1 }
    }

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        _settings.value = s
        store.saveSettings(s)
    }

    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").ifBlank { "Android phone" }

    fun show(text: String, action: String? = null, route: Any? = null) { _notice.value = Notice(text, action, route) }
    fun dismissNotice() { _notice.value = null }

    private val _flash = MutableStateFlow<Notice?>(null)
    /** A short message about something that just finished; the layout shows it at the bottom and drops it. */
    val flash: StateFlow<Notice?> = _flash

    fun done(text: String, action: String? = null, route: Any? = null, error: Boolean = false) {
        _flash.value = Notice(text, action, route, transient = true, error = error)
    }

    /** Lists on screen load again, as after `index.changed`. */
    fun bumpRefresh() = _refresh.update { it + 1 }

    fun dismissFlash(id: Long) { if (_flash.value?.id == id) _flash.value = null }

    /**
     * A request to the Mac that waits a little for the connection when it is between tries, then fails with a
     * sentence the user can act on instead of hanging.
     */
    suspend fun call(method: String, params: JsonObject = EmptyJson, timeoutMs: Long = 30_000): JsonObject {
        if (!rpc.isOpen && !connection.awaitConnected(8_000)) {
            val name = connection.pairedMacName ?: "the Mac"
            throw RemoteException(dk.langkilde.mindmill.remote.ErrorCodes.OFFLINE, "Can't reach $name right now. Check the connection and try again.")
        }
        return rpc.call(method, params, timeoutMs)
    }

    suspend fun runAction(name: String, params: JsonObject = EmptyJson): ActionOutcome = actions.run(name, params)

    /** Runs an action and says how it went at the bottom of the screen. */
    suspend fun runAndReport(name: String, params: JsonObject = EmptyJson): ActionOutcome? = try {
        actions.run(name, params).also { o ->
            when (o.status) {
                ActionOutcome.Status.DONE -> done(o.speech.ifBlank { "Done." })
                ActionOutcome.Status.FAILED -> done(o.speech.ifBlank { "That didn't work." }, error = true)
                ActionOutcome.Status.CANCELLED -> done("Cancelled.")
                ActionOutcome.Status.NEEDS_CONFIRMATION -> Unit
            }
        }
    } catch (e: Exception) {
        done(e.userMessage(), error = true)
        null
    }

    @Volatile private var catalog: Pair<List<ActionInfo>, List<String>>? = null

    /** `actions.list`, kept until the connection changes. */
    suspend fun actionCatalog(force: Boolean = false): Pair<List<ActionInfo>, List<String>> {
        if (!force) catalog?.let { return it }
        val r = call("actions.list")
        return (ActionInfo.list(r) to ActionInfo.areaOrder(r)).also { catalog = it }
    }

    suspend fun loadReviewCards() {
        runCatching { call("review.list", jsonOf("scope" to currentScope)) }
            .onSuccess { r ->
                _reviewCards.value = (r["cards"] as? kotlinx.serialization.json.JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                    .filter(Delivery::isForAndroid)
            }
    }

    fun dropReviewCard(id: String) = _reviewCards.update { list -> list.filter { it.text("id") != id } }

    private fun updateMic() = MicService.update(this, micForVoice, micForMeeting, micForLive.first, micForLive.second)

    private fun handle(e: RemoteMessage.Event) {
        when (e.name) {
            "index.changed" -> _refresh.update { it + 1 }
            "projects.changed" -> scope.launch {
                runCatching { connection.refreshState() }
                _refresh.update { it + 1 }
            }
            "test.delivery" -> Delivery.parse(e.data)?.let { d ->
                deliveries.arrived(d)
                show("New ${if (d.kind == "apk") "build" else "preview"} to test: ${d.title}", "Open Test", "test")
            }
            "review.card" -> if (Delivery.isForAndroid(e.data)) {
                val id = e.data.text("id")
                _reviewCards.update { list -> listOf(e.data) + list.filter { it.text("id") != id } }
                show("Ready for review: ${e.data.string("title") ?: "a change"}", "Review", "test")
            }
            "session.changed" -> liveChat.onSessionChanged(e.data.text("id"))
            else -> if (e.name.startsWith("voice.")) voice.onEvent(e.name, e.data)
        }
        _events.tryEmit(e)
    }
}
