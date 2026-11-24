package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.EmptyJson
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** The Mac side of a conversation: `voice.*` methods. */
interface VoiceRemote {
    suspend fun config(): VoiceConfig
    /** Returns the conversation id and the engine the Mac picked (null when it didn't say). */
    suspend fun start(scope: String?, forceKeyless: Boolean): Pair<String, VoiceEngine?>
    suspend fun say(conversation: String, text: String): String?
    suspend fun interrupt(conversation: String)
    suspend fun end(conversation: String)
    fun sendFrame(frame: BinaryFrame): Boolean
}

/** Callbacks from the phone's microphone, recogniser and speech. */
interface DeviceEvents {
    fun partial(text: String)
    fun heard(text: String)
    fun utteranceDone(utteranceId: String)
    fun userStartedTalking()
    fun deviceProblem(message: String)
    fun focusLost()
    fun focusGained()
}

/** The phone's speech hardware for the keyless engine. */
interface KeylessDevice {
    var events: DeviceEvents?
    fun configure(rate: Float, locale: Locale, voiceHint: String?)
    fun startListening()
    fun stopListening()
    fun speak(text: String, utteranceId: String)
    fun stopSpeaking()
    fun startBargeInWatch()
    fun stopBargeInWatch()
    fun begin()
    fun finish()
}

/** The API engine's audio: microphone frames out, the Mac's audio in. */
interface ApiAudio {
    fun startStreaming(onFrame: (ByteArray) -> Unit)
    fun stopStreaming()
    fun play(pcm24k: ByteArray)
    fun clear()
}

data class Bubble(val role: String, val text: String, val final: Boolean = true)

data class VoiceActionCard(
    val name: String,
    val status: String,
    val speech: String,
    val token: String?,
    val data: kotlinx.serialization.json.JsonElement? = null,
)

data class VoiceUi(
    val active: Boolean = false,
    val starting: Boolean = false,
    val engine: VoiceEngine = VoiceEngine.KEYLESS,
    val engineLabel: String = "On this phone",
    val phase: String = "Not talking",
    val partial: String = "",
    val bubbles: List<Bubble> = emptyList(),
    val cards: List<VoiceActionCard> = emptyList(),
    val notice: String? = null,
    val conversation: String? = null,
    val paused: Boolean = false,
)

/**
 * One voice conversation with the Mac. Picks the engine (`api` streams audio through the Mac's relay, `keyless`
 * listens and speaks on the phone), routes `voice.*` events, and on `voice.fallback` carries on keyless in the
 * same conversation. Call everything on one thread.
 */
class VoiceConversation(
    private val remote: VoiceRemote,
    private val device: KeylessDevice,
    private val apiAudio: ApiAudio,
    private val scope: CoroutineScope,
) : DeviceEvents {
    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui

    var conversation: String? = null
        private set
    var engine: VoiceEngine = VoiceEngine.KEYLESS
        private set
    var config: VoiceConfig = VoiceConfig()
        private set

    private var seq = 0L
    private val replies = mutableMapOf<String, StringBuilder>()

    val machine = KeylessVoiceMachine(object : KeylessEffects {
        override fun startListening() = device.startListening()
        override fun stopListening() = device.stopListening()
        override fun speak(text: String, utteranceId: String) = device.speak(text, utteranceId)
        override fun stopSpeaking() = device.stopSpeaking()
        override fun startBargeInWatch() = device.startBargeInWatch()
        override fun stopBargeInWatch() = device.stopBargeInWatch()
        override fun say(text: String) = sendSay(text)
        override fun interrupt() = sendInterrupt()
    }) { s -> if (engine == VoiceEngine.KEYLESS) _ui.update { it.copy(phase = phaseTitle(s), paused = s == KeylessState.PAUSED) } }

    init {
        device.events = this
    }

    suspend fun begin(scopeId: String?, keylessOnly: Boolean) {
        if (_ui.value.active || _ui.value.starting) return
        _ui.value = VoiceUi(starting = true, phase = "Starting…")
        try {
            config = remote.config()
            val (conv, picked) = remote.start(scopeId, forceKeyless = keylessOnly)
            conversation = conv
            engine = if (keylessOnly) VoiceEngine.KEYLESS else picked ?: config.engine
            device.configure(config.rate, config.locale(), config.voice)
            device.begin()
            _ui.value = VoiceUi(active = true, engine = engine, engineLabel = engineLabel(), conversation = conv)
            when (engine) {
                VoiceEngine.API -> startApi(conv)
                VoiceEngine.KEYLESS -> machine.start()
            }
        } catch (e: Exception) {
            conversation = null
            _ui.value = VoiceUi(notice = e.userMessage())
        }
    }

    fun end() {
        val conv = conversation ?: run { _ui.update { it.copy(starting = false) }; return }
        conversation = null
        machine.end()
        apiAudio.stopStreaming()
        apiAudio.clear()
        device.finish()
        _ui.update { it.copy(active = false, starting = false, phase = "Not talking", partial = "") }
        scope.launch { runCatching { remote.end(conv) } }
    }

    /** The stop button: barge in without saying anything. */
    fun stopSpeaking() {
        when (engine) {
            VoiceEngine.KEYLESS -> machine.bargeIn()
            VoiceEngine.API -> { apiAudio.clear(); sendInterrupt() }
        }
    }

    fun resume() = machine.resume()

    fun onEvent(name: String, data: JsonObject) {
        if (!name.startsWith("voice.")) return
        val conv = conversation ?: return
        if (data.string("conversation").let { it != null && it != conv }) return
        when (name) {
            "voice.sentence" -> {
                val turn = data.text("turn") ?: return
                val text = data.string("text") ?: return
                replies.getOrPut(turn) { StringBuilder() }.apply { if (isNotEmpty()) append(' '); append(text) }
                showReply(turn, replies[turn].toString(), final = false)
                if (engine == VoiceEngine.KEYLESS) machine.sentence(turn, data.int("index") ?: 0, text)
            }
            "voice.done" -> {
                val turn = data.text("turn") ?: return
                val text = data.string("text") ?: replies[turn]?.toString().orEmpty()
                replies.remove(turn)
                if (text.isNotBlank()) showReply(turn, text, final = true)
                if (engine == VoiceEngine.KEYLESS) machine.done(turn, text)
            }
            "voice.action" -> {
                val d = data.obj("data") ?: EmptyJson
                val card = VoiceActionCard(
                    name = data.string("name") ?: "action",
                    status = data.string("status") ?: "done",
                    speech = data.string("speech").orEmpty(),
                    token = data.string("token") ?: d.string("token"),
                    data = data["data"]?.takeIf { it !is kotlinx.serialization.json.JsonNull },
                )
                _ui.update { it.copy(cards = it.cards + card) }
            }
            "voice.error" -> {
                val message = data.string("message") ?: "Something went wrong."
                if (engine == VoiceEngine.KEYLESS) machine.announce(message) else _ui.update { it.copy(notice = message) }
            }
            "voice.transcript" -> {
                val role = data.string("role") ?: "assistant"
                val text = data.string("text") ?: return
                val final = data.bool("final") ?: true
                _ui.update { u ->
                    val last = u.bubbles.lastOrNull()
                    val list = if (last != null && last.role == role && !last.final) u.bubbles.dropLast(1) else u.bubbles
                    u.copy(bubbles = list + Bubble(role, text, final), partial = "")
                }
            }
            "voice.state" -> if (engine == VoiceEngine.API) {
                _ui.update { it.copy(phase = (data.string("state") ?: "").replaceFirstChar { c -> c.uppercase() }) }
            }
            "voice.clear" -> if (engine == VoiceEngine.API) apiAudio.clear()
            "voice.fallback" -> fallBack(data.string("speech").orEmpty(), data.string("reason"))
        }
    }

    fun onFrame(frame: BinaryFrame) {
        if (engine != VoiceEngine.API || frame.kind != "voice" || frame.format != "pcm24k") return
        val conv = conversation ?: return
        if (frame.header.string("conversation").let { it != null && it != conv }) return
        apiAudio.play(frame.payload)
    }

    override fun partial(text: String) = _ui.update { it.copy(partial = text) }

    override fun heard(text: String) {
        if (text.isNotBlank()) _ui.update { it.copy(bubbles = it.bubbles + Bubble("user", text), partial = "") }
        machine.heard(text)
    }

    override fun utteranceDone(utteranceId: String) = machine.utteranceDone(utteranceId)

    override fun userStartedTalking() = machine.bargeIn()

    override fun deviceProblem(message: String) = _ui.update { it.copy(notice = message) }

    override fun focusLost() {
        when (engine) {
            VoiceEngine.KEYLESS -> machine.pause()
            VoiceEngine.API -> { apiAudio.clear(); apiAudio.stopStreaming(); _ui.update { it.copy(paused = true, phase = "Paused") } }
        }
    }

    override fun focusGained() {
        val conv = conversation ?: return
        when (engine) {
            VoiceEngine.KEYLESS -> machine.resume()
            VoiceEngine.API -> if (_ui.value.paused) { _ui.update { it.copy(paused = false) }; startApi(conv) }
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun dropCard(card: VoiceActionCard) = _ui.update { it.copy(cards = it.cards - card) }

    private fun fallBack(speech: String, reason: String?) {
        if (conversation == null) return
        if (engine == VoiceEngine.API) {
            apiAudio.stopStreaming()
            apiAudio.clear()
        }
        engine = VoiceEngine.KEYLESS
        _ui.update { it.copy(engine = engine, engineLabel = engineLabel(), notice = reason?.let { r -> "Switched to on-phone voice: $r" }) }
        if (machine.state == KeylessState.IDLE || machine.state == KeylessState.PAUSED) machine.start()
        if (speech.isNotBlank()) machine.announce(speech)
    }

    private fun startApi(conv: String) {
        _ui.update { it.copy(phase = "Listening") }
        apiAudio.startStreaming { pcm ->
            remote.sendFrame(BinaryFrame.voice(conv, seq++, pcm))
        }
    }

    private fun sendSay(text: String) {
        val conv = conversation ?: return
        scope.launch {
            try {
                remote.say(conv, text)?.let { if (conversation == conv) machine.turnStarted(it) }
            } catch (e: Exception) {
                if (conversation == conv) machine.announce(e.userMessage())
            }
        }
    }

    private fun sendInterrupt() {
        val conv = conversation ?: return
        scope.launch { runCatching { remote.interrupt(conv) } }
    }

    private fun showReply(turn: String, text: String, final: Boolean) {
        _ui.update { u ->
            val i = u.bubbles.indexOfLast { it.role == "assistant:$turn" }
            val b = Bubble("assistant:$turn", text, final)
            u.copy(bubbles = if (i >= 0) u.bubbles.toMutableList().also { it[i] = b } else u.bubbles + b)
        }
    }

    private fun engineLabel() = when (engine) {
        VoiceEngine.KEYLESS -> "On this phone"
        VoiceEngine.API -> "${config.providerName ?: config.provider ?: "Voice API"} via the Mac"
    }

    private fun phaseTitle(s: KeylessState) = when (s) {
        KeylessState.IDLE -> "Not talking"
        KeylessState.LISTENING -> "Listening"
        KeylessState.SENDING -> "Thinking"
        KeylessState.SPEAKING -> "Speaking"
        KeylessState.PAUSED -> "Paused"
    }
}
