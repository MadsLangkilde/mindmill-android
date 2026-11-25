package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.ChatLog
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.Locale

data class LiveUi(
    val active: Boolean = false,
    val phase: LivePhase = LivePhase.IDLE,
    /** The session it talks in; null until a new one is made from the first thing said. */
    val session: String? = null,
    /** Started from the new-session screen, which opens the session once it exists. */
    val fromNew: Boolean = false,
    val partial: String = "",
    /** Heard and not sent yet: sent after a pause, or with the send button. */
    val draft: String = "",
    /** Claude works on something said in this live chat, so it can be stopped. */
    val working: Boolean = false,
    val notice: String? = null,
)

/**
 * Live chat in a session, on the phone: recognise speech until the user pauses, send it with `session.send` (or
 * `session.new` for a new session), say a short acknowledgement at once, keep listening while Claude works (more is
 * added, "stop" stops it), and speak Claude's reply as it arrives. The turn-taking is [LiveChatMachine]; this wires
 * it to the phone's speech and the Mac.
 */
class LiveChat(
    private val call: suspend (String, JsonObject) -> JsonObject,
    private val device: KeylessDevice,
    private val scope: CoroutineScope,
) : DeviceEvents {
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui

    private val log = ChatLog()
    private val follower = ReplyFollower()
    private val lock = Mutex()
    private val sendLock = Mutex()
    private var newSpace = "plan"
    private var newScope: String? = null
    private var newAssistant = false
    private var config = VoiceConfig()
    private var sendTimer: Job? = null

    private val machine: LiveChatMachine = LiveChatMachine(object : LiveEffects {
        override fun startListening() = device.startDictating()
        override fun stopListening() = device.stopListening()
        override fun speak(text: String, utteranceId: String) = device.speak(text, utteranceId)
        override fun stopSpeaking() = device.stopSpeaking()
        override fun startBargeInWatch() = device.startBargeInWatch()
        override fun stopBargeInWatch() = device.stopBargeInWatch()
        override fun send(text: String) = sendNow(text)
        override fun stopSession() = stopSessionNow()
        override fun startSendTimer(ms: Long) {
            sendTimer?.cancel()
            sendTimer = scope.launch {
                delay(ms)
                sendTimer = null
                machine.pauseOver()
                sync()
            }
        }
        override fun cancelSendTimer() {
            sendTimer?.cancel()
            sendTimer = null
        }
    }) { p ->
        val keepsPartial = p == LivePhase.LISTENING || p == LivePhase.WAITING
        _ui.update { it.copy(phase = p, partial = if (keepsPartial) it.partial else "") }
    }

    init {
        device.events = this
    }

    val active: Boolean get() = _ui.value.active

    /** Talks live in the session [id]. */
    fun startInSession(id: String) = begin(LiveUi(active = true, session = id))

    /** Listens for the first thing to say, which starts a new session in [space] and [scopeId]. */
    fun startNew(space: String, scopeId: String?, assistant: Boolean) {
        newSpace = space
        newScope = scopeId
        newAssistant = assistant
        begin(LiveUi(active = true, fromNew = true))
    }

    private fun begin(start: LiveUi) {
        if (active) end()
        log.reset()
        follower.reset()
        _ui.value = start.copy(phase = LivePhase.LISTENING)
        scope.launch {
            config = runCatching { VoiceConfig.parse(call("voice.config", jsonOf())) }.getOrDefault(VoiceConfig())
            if (!active) return@launch
            machine.language = LiveLanguage.of(config.language)
            device.configure(config.rate, config.locale(), config.voice)
            (device as? AndroidKeylessDevice)?.detectLanguages = if (config.followsPhone) DETECTED else emptyList()
            device.begin()
            start.session?.let { fetch(it) }
            machine.start()
            sync()
        }
    }

    fun end() {
        if (!_ui.value.active) return
        machine.end()
        device.finish()
        follower.reset()
        _ui.update { it.copy(active = false, phase = LivePhase.IDLE, partial = "", draft = "", working = false) }
    }

    /** The new-session screen opened the session it made. */
    fun handedOver() = _ui.update { it.copy(fromNew = false) }

    /** The stop button while it speaks: stop talking and listen. */
    fun stopSpeaking() { machine.bargeIn(); sync() }

    /** Stop Claude's work on what was said. */
    fun stopWork() { machine.stopWork(); sync() }

    /** Send what was heard now instead of waiting for the pause. */
    fun sendHeard() {
        val partial = _ui.value.partial
        _ui.update { it.copy(partial = "") }
        machine.sendNow(partial)
        sync()
    }

    /** Throw away what was heard and not sent. */
    fun clearHeard() {
        if (_ui.value.draft.isEmpty()) return
        machine.discardDraft()
        sync()
    }

    private fun sync() = _ui.update { it.copy(draft = machine.draft, working = machine.working) }

    private fun stopSessionNow() {
        val id = _ui.value.session ?: return
        scope.launch {
            try {
                call("session.stop", jsonOf("id" to id))
            } catch (_: Exception) {
                // An older Mac has session.stop only as an action, which asks first; saying "stop" was the answer.
                runCatching {
                    val r = call("action.run", jsonOf("name" to "session.stop", "params" to mapOf("id" to id), "voice" to true))
                    r.text("token")?.let { call("action.confirm", jsonOf("token" to it)) }
                }.onFailure { e -> _ui.update { it.copy(notice = "Couldn't stop the session: ${(e as? Exception)?.userMessage() ?: e.message}") } }
            }
            fetch(id)
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    /** `session.changed` from the Mac. */
    fun onSessionChanged(id: String?) {
        val s = _ui.value.session ?: return
        if (active && id == s) scope.launch { fetch(s) }
    }

    private fun sendNow(text: String) {
        scope.launch { sendLock.withLock { sendInTurn(text) } }
    }

    /** One at a time, so more said while the session is still being made goes to that session. */
    private suspend fun sendInTurn(text: String) {
        if (!active) return
        val id = _ui.value.session
        try {
            if (id == null) {
                follower.begin(0)
                val r = call("session.new", jsonOf(
                    "space" to newSpace, "scope" to newScope, "message" to text, "spoken" to true,
                    "assistant" to (if (newAssistant) true else null),
                ))
                val made = r.text("id") ?: throw IllegalStateException("The Mac didn't start a session.")
                if (!active) return
                _ui.update { it.copy(session = made) }
                follower.sent()
                fetch(made)
            } else {
                follower.begin(log.since)
                call("session.send", jsonOf("id" to id, "text" to text, "spoken" to true))
                follower.sent()
                fetch(id)
            }
        } catch (e: Exception) {
            follower.reset()
            _ui.update { it.copy(notice = "Not sent: ${e.userMessage()}") }
            machine.failed(LivePhrases.notSent(machine.language))
            sync()
        }
    }

    private suspend fun fetch(id: String) = lock.withLock {
        try {
            val since = log.since
            val r = call("session.get", jsonOf("id" to id, "since" to since))
            if (log.apply(r, since)) log.apply(call("session.get", jsonOf("id" to id, "since" to 0)), 0)
        } catch (_: Exception) {
            return@withLock
        }
        if (!active || _ui.value.session != id || !follower.following) return@withLock
        val update = follower.follow(log.items, log.busy)
        update.say.forEach { item ->
            val said = if (item.role == "error") item.text.lineSequence().firstOrNull().orEmpty().ifBlank { LivePhrases.problem(machine.language) }
            else SpokenText.of(item.text, machine.language)
            machine.reply(said)
        }
        if (update.done) {
            if (!follower.spokeAny && update.say.isEmpty() && machine.working) machine.reply(LivePhrases.noAnswer(machine.language))
            machine.turnDone()
        }
        sync()
    }

    override fun partial(text: String) {
        _ui.update { it.copy(partial = text) }
        machine.partial(text)
    }

    override fun heard(text: String) {
        _ui.update { it.copy(partial = "") }
        machine.heard(text)
        sync()
    }

    override fun heardNothing() {
        _ui.update { it.copy(partial = "") }
        machine.heardNothing()
        sync()
    }

    /** The user speaks another of the two languages than before: answer and acknowledge in that one. */
    override fun heardLanguage(tag: String) {
        if (!config.followsPhone) return
        val language = LiveLanguage.of(tag)
        if (language == machine.language) return
        machine.language = language
        device.configure(config.rate, Locale.forLanguageTag(if (language == LiveLanguage.DA) "da-DK" else "en-US"), config.voice)
    }

    override fun utteranceDone(utteranceId: String) { machine.utteranceDone(utteranceId); sync() }

    override fun userStartedTalking() { machine.bargeIn(); sync() }

    override fun deviceProblem(message: String) = _ui.update { it.copy(notice = message) }

    override fun focusLost() { machine.pause(); sync() }

    override fun focusGained() = machine.resume()

    private companion object {
        /** What live chat tells apart when the language follows the phone. */
        val DETECTED = listOf("da-DK", "en-US")
    }
}
