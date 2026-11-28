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
import dk.langkilde.mindmill.remote.BuildFromPicks
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.EmptyJson
import dk.langkilde.mindmill.remote.ErrorCodes
import dk.langkilde.mindmill.remote.InstanceList
import dk.langkilde.mindmill.remote.InstanceSwitchAnswer
import dk.langkilde.mindmill.remote.RemoteConnection
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.RemoteMessage
import dk.langkilde.mindmill.remote.RpcChannel
import dk.langkilde.mindmill.remote.Scopes
import dk.langkilde.mindmill.remote.SkillInfo
import dk.langkilde.mindmill.remote.TestSources
import dk.langkilde.mindmill.remote.TestTarget
import dk.langkilde.mindmill.remote.deliverTest
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
import dk.langkilde.mindmill.voice.VoiceApi
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

private class RpcVoiceRemote(private val rpc: RpcChannel, private val loadConfig: suspend () -> VoiceConfig) : VoiceRemote {
    override suspend fun config() = loadConfig()
    override suspend fun start(scope: String?, forceKeyless: Boolean, session: String?): Pair<String, VoiceEngine?> {
        val r = rpc.call("voice.start", jsonOf("scope" to scope, "engine" to if (forceKeyless) "keyless" else null, "session" to session))
        val conv = r.text("conversation") ?: throw RemoteException("failed", "The Mac didn't start a conversation.")
        return conv to VoiceEngine.from(r.string("engine"))
    }
    override suspend fun say(conversation: String, text: String) =
        rpc.call("voice.say", jsonOf("conversation" to conversation, "text" to text)).text("turn")
    override suspend fun interrupt(conversation: String) { rpc.call("voice.interrupt", jsonOf("conversation" to conversation)) }
    override suspend fun end(conversation: String) { rpc.call("voice.end", jsonOf("conversation" to conversation)) }
    override fun sendFrame(frame: BinaryFrame) = rpc.sendFrame(frame)
}

/** A test build runs the project's tests and builds it on the Mac: allow for a slow one. */
const val TEST_BUILD_TIMEOUT_MS = 45 * 60_000L

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
        voice = VoiceConversation(RpcVoiceRemote(rpc) { fetchVoiceConfig() }, voiceDevice, AndroidApiAudio(this), scope)
        liveChat = LiveChat({ m, p -> call(m, p) }, AndroidKeylessDevice(this) { settings.value.routeToHeadset }, scope)
        meeting = LiveMeeting(this, rpc, scope) { on -> micForMeeting = on; updateMic() }
        voice.onSessionFallback = { id, why ->
            liveChat.startInSession(id)
            done(why, error = true)
        }
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
                    forgetSkills()
                    _instancesMissing.value = false
                    if (connection.active?.scope == null) s.hello.scope?.let { setScope(it) }
                    loadReviewCards()
                    scope.launch { runCatching { fetchVoiceConfig() } }
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

    /**
     * Talks live in session [id]: through the Mac's voice API (Gemini, OpenAI…) when it has one, so the voice model
     * talks and says the session's progress; otherwise with the phone's own speech.
     */
    fun startLive(id: String) {
        scope.launch {
            val config = runCatching { fetchVoiceConfig() }.getOrNull() ?: _voiceConfig.value
            if (config?.engine == VoiceEngine.API && !settings.value.keylessOnly) {
                voice.begin(currentScope, keylessOnly = false, session = id)
                if (voice.ui.value.active) return@launch
                voice.ui.value.notice?.let { done("The voice API didn't start, so the phone's own voice is used: $it", error = true) }
                voice.dismissNotice()
            }
            liveChat.startInSession(id)
        }
    }

    /** Ends live voice in a session, whichever kind runs. */
    fun endLive() {
        if (voice.ui.value.session != null) voice.end()
        liveChat.end()
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
        confirmations.answer(false)
        catalog = null
        _voiceConfig.value = null
        forgetSkills()
        _reviewCards.value = emptyList()
        _notice.value = null
        deliveries.clear()
        _buildsStarting.value = emptySet()
        buildFrom.clear()
        _instancesMissing.value = false
        _refresh.update { it + 1 }
    }

    private val _instancesMissing = MutableStateFlow(false)
    /** The Mac answered `unknown_method` for instances: it is older than them, and the phone shows nothing about them. */
    val instancesMissing: StateFlow<Boolean> = _instancesMissing

    /** `instances.list`, or null from a Mac older than instances. */
    suspend fun instances(): InstanceList? = try {
        InstanceList.parse(call("instances.list"))
    } catch (e: RemoteException) {
        if (!e.isUnknownMethod) throw e
        _instancesMissing.value = true
        null
    }

    /**
     * Opens the instance [id] on the Mac. Mindmill restarts there and the connection closes; until it is back the
     * phone says it is switching. False when it was open already.
     */
    suspend fun switchInstance(id: String, name: String): Boolean =
        restarting(id, name) { InstanceSwitchAnswer.parse(call("instance.switch", jsonOf("id" to id))) }.switching

    /** Makes an instance called [name] in the Mac folder [path] (`instance.create`); with [open], the Mac switches to it. */
    suspend fun createInstance(name: String, path: String, open: Boolean): InstanceSwitchAnswer =
        restarting(null, name.trim(), assumeOnDrop = open) { InstanceSwitchAnswer.parse(call("instance.create", InstanceList.createParams(name, path, open))) }

    /** The Mac can close the connection to restart before its answer arrives: that counts as switching. */
    private suspend fun restarting(
        id: String?, name: String, assumeOnDrop: Boolean = true, ask: suspend () -> InstanceSwitchAnswer,
    ): InstanceSwitchAnswer {
        val wasOpen = rpc.isOpen
        val answer = try {
            ask()
        } catch (e: RemoteException) {
            if (!(wasOpen && assumeOnDrop && e.code == ErrorCodes.OFFLINE)) throw e
            InstanceSwitchAnswer(id, switching = true)
        }
        if (answer.switching) connection.instanceSwitching(answer.id ?: id, name)
        return answer
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
            throw RemoteException(ErrorCodes.OFFLINE, "Can't reach $name right now. Check the connection and try again.")
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

    /** The Test screen's "Build from" per scope, until the app stops or another Mac is active. */
    val buildFrom = BuildFromPicks()

    /** `test.sources`, or [TestSources.MISSING] from a Mac older than it. Slow: the Mac asks git. */
    suspend fun testSources(scopeId: String?): TestSources = TestSources.load(scopeId) { m, p -> call(m, p, 60_000) }

    private val _buildsStarting = MutableStateFlow<Set<String>>(emptySet())
    /** Test targets the phone asked the Mac to build, until the Mac answers. */
    val buildsStarting: StateFlow<Set<String>> = _buildsStarting

    /**
     * Asks the Mac to build [target] and deliver it to test (`test.deliver`). A build takes minutes, so the answer is
     * awaited far longer than other requests; meanwhile the Test screen lists it as building.
     */
    fun requestTestBuild(target: TestTarget, scopeId: String?, session: String? = null) {
        if (target.id in _buildsStarting.value) return
        _buildsStarting.update { it + target.id }
        val mac = connection.pairedMacName ?: "the Mac"
        done("Building ${target.title} on $mac. It shows up on Test when it's ready.")
        scope.launch {
            kotlinx.coroutines.delay(1_500)
            bumpRefresh()
        }
        scope.launch {
            val runner = ActionRunner({ m, p -> call(m, p, TEST_BUILD_TIMEOUT_MS) }, confirmations::ask)
            try {
                val o = runner.deliverTest(target.id, scopeId, session)
                when (o.status) {
                    ActionOutcome.Status.DONE -> done(o.speech.ifBlank { "Delivered ${target.title}." })
                    ActionOutcome.Status.FAILED -> done(o.speech.ifBlank { "Building ${target.title} didn't work." }, error = true)
                    ActionOutcome.Status.CANCELLED -> done("Cancelled.")
                    ActionOutcome.Status.NEEDS_CONFIRMATION -> Unit
                }
            } catch (e: Exception) {
                done(e.userMessage(), error = true)
            } finally {
                _buildsStarting.update { it - target.id }
                bumpRefresh()
            }
        }
    }

    private val _voiceConfig = MutableStateFlow<VoiceConfig?>(null)
    /** The Mac's voice settings as last heard (`voice.config`, `voice.changed`): which engine the next conversation uses. */
    val voiceConfig: StateFlow<VoiceConfig?> = _voiceConfig

    /** `voice.config`, kept for showing which voice is used. */
    suspend fun fetchVoiceConfig(): VoiceConfig = VoiceConfig.parse(call("voice.config")).also { _voiceConfig.value = it }

    /** The Mac's voice API keys and which one is used, set from the phone; see [VoiceApi]. */
    val voiceApi = VoiceApi { m, p -> call(m, p) }

    private val skillsCache = java.util.concurrent.ConcurrentHashMap<String, List<SkillInfo>>()
    @Volatile private var skillsMissing = false
    private val _skillsVersion = MutableStateFlow(0)
    /** Bumps on `skills.changed`: lists of skills, plugins and tools load again. */
    val skillsVersion: StateFlow<Int> = _skillsVersion

    /**
     * `skills.list` for a session (or a scope), kept until `skills.changed`. Empty from a Mac that doesn't know it, which
     * isn't asked again on this connection.
     */
    suspend fun skillsFor(session: String?, scopeId: String?): List<SkillInfo> {
        if (skillsMissing) return emptyList()
        val key = session?.let { "s:$it" } ?: "c:${scopeId.orEmpty()}"
        skillsCache[key]?.let { return it }
        return try {
            SkillInfo.list(call("skills.list", jsonOf("session" to session, "scope" to if (session == null) scopeId else null)))
                .also { skillsCache[key] = it }
        } catch (e: RemoteException) {
            if (e.isUnknownMethod) skillsMissing = true
            emptyList()
        }
    }

    private fun forgetSkills() {
        skillsCache.clear()
        skillsMissing = false
        _skillsVersion.update { it + 1 }
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
            "voice.changed" -> VoiceConfig.parse(e.data).let { c ->
                _voiceConfig.value = c
                voice.configChanged(c)
            }
            "skills.changed" -> forgetSkills()
            else -> if (e.name.startsWith("voice.")) voice.onEvent(e.name, e.data)
        }
        _events.tryEmit(e)
    }
}
