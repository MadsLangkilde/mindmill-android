package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.ChatLog
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

data class LiveUi(
    val active: Boolean = false,
    val phase: LivePhase = LivePhase.IDLE,
    /** The session it talks in; null until a new one is made from the first thing said. */
    val session: String? = null,
    /** Started from the new-session screen, which opens the session once it exists. */
    val fromNew: Boolean = false,
    val partial: String = "",
    val notice: String? = null,
)

/**
 * Live chat in a session, on the phone: recognise speech, send it with `session.send` (or `session.new` for a new
 * session), say a short acknowledgement at once, speak Claude's reply as it arrives, then listen again. The turn-taking
 * is [LiveChatMachine]; this wires it to the phone's speech and the Mac.
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
    private var newSpace = "plan"
    private var newScope: String? = null
    private var newAssistant = false

    private val machine = LiveChatMachine(object : LiveEffects {
        override fun startListening() = device.startListening()
        override fun stopListening() = device.stopListening()
        override fun speak(text: String, utteranceId: String) = device.speak(text, utteranceId)
        override fun stopSpeaking() = device.stopSpeaking()
        override fun startBargeInWatch() = device.startBargeInWatch()
        override fun stopBargeInWatch() = device.stopBargeInWatch()
        override fun send(text: String) = sendNow(text)
    }) { p -> _ui.update { it.copy(phase = p, partial = if (p == LivePhase.LISTENING) it.partial else "") } }

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
            val config = runCatching { VoiceConfig.parse(call("voice.config", jsonOf())) }.getOrDefault(VoiceConfig())
            if (!active) return@launch
            machine.language = LiveLanguage.of(config.language)
            device.configure(config.rate, config.locale(), config.voice)
            device.begin()
            start.session?.let { fetch(it) }
            machine.start()
        }
    }

    fun end() {
        if (!_ui.value.active) return
        machine.end()
        device.finish()
        follower.reset()
        _ui.update { it.copy(active = false, phase = LivePhase.IDLE, partial = "") }
    }

    /** The new-session screen opened the session it made. */
    fun handedOver() = _ui.update { it.copy(fromNew = false) }

    /** The stop button while it speaks or waits: stop and listen. */
    fun stopSpeaking() = machine.bargeIn()

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    /** `session.changed` from the Mac. */
    fun onSessionChanged(id: String?) {
        val s = _ui.value.session ?: return
        if (active && id == s) scope.launch { fetch(s) }
    }

    private fun sendNow(text: String) {
        val id = _ui.value.session
        scope.launch {
            try {
                if (id == null) {
                    follower.begin(0)
                    val r = call("session.new", jsonOf(
                        "space" to newSpace, "scope" to newScope, "message" to text, "spoken" to true,
                        "assistant" to (if (newAssistant) true else null),
                    ))
                    val made = r.text("id") ?: throw IllegalStateException("The Mac didn't start a session.")
                    if (!active) return@launch
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
            }
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
            if (!follower.spokeAny && update.say.isEmpty()) machine.reply(LivePhrases.noAnswer(machine.language))
            machine.turnDone()
        }
    }

    override fun partial(text: String) = _ui.update { it.copy(partial = text) }

    override fun heard(text: String) {
        _ui.update { it.copy(partial = "") }
        machine.heard(text)
    }

    override fun utteranceDone(utteranceId: String) = machine.utteranceDone(utteranceId)

    override fun userStartedTalking() = machine.bargeIn()

    override fun deviceProblem(message: String) = _ui.update { it.copy(notice = message) }

    override fun focusLost() = machine.pause()

    override fun focusGained() = machine.resume()
}
