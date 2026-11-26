package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.ChatItem
import dk.langkilde.mindmill.remote.ReplyPart
import dk.langkilde.mindmill.remote.ReplyParts
import java.util.Locale
import kotlin.random.Random

/** The languages live chat speaks: Danish, else English. */
enum class LiveLanguage {
    EN, DA;

    companion object {
        /** From `voice.config`'s `language` (`auto`, `da-DK`, `en-US`); `auto` follows the phone. */
        fun of(configLanguage: String?, device: Locale = Locale.getDefault()): LiveLanguage {
            val tag = configLanguage?.takeIf { it.isNotBlank() && it != "auto" } ?: device.toLanguageTag()
            return if (tag.lowercase().startsWith("da")) DA else EN
        }
    }
}

/**
 * The short "I heard you" said the moment what the user said is on its way, before the real answer. Never the same
 * twice in a row.
 */
class Acknowledgements(private val random: Random = Random.Default) {
    private var last: String? = null

    fun next(language: LiveLanguage): String {
        val all = PHRASES.getValue(language)
        val choices = all.filter { it != last }.ifEmpty { all }
        return choices[random.nextInt(choices.size)].also { last = it }
    }

    /** Said when more is added to a message Claude already works on. */
    fun added(language: LiveLanguage): String {
        val all = ADDED.getValue(language)
        val choices = all.filter { it != last }.ifEmpty { all }
        return choices[random.nextInt(choices.size)].also { last = it }
    }

    companion object {
        val PHRASES = mapOf(
            LiveLanguage.EN to listOf(
                "Got it. I'll get back to you shortly.",
                "Okay, give me a moment.",
                "Heard you. Working on it.",
                "Sure, one moment.",
                "Got it. I'll be right back with an answer.",
                "On it. Back shortly.",
            ),
            LiveLanguage.DA to listOf(
                "Forstået. Jeg vender tilbage om lidt.",
                "Okay, giv mig et øjeblik.",
                "Jeg hørte dig. Jeg går i gang.",
                "Fint, et øjeblik.",
                "Modtaget. Jeg vender tilbage med et svar.",
                "Jeg er i gang. Tilbage om lidt.",
            ),
        )
        val ADDED = mapOf(
            LiveLanguage.EN to listOf("Added that.", "Got it, I'll take that along.", "Noted, adding it.", "Okay, I'll include that."),
            LiveLanguage.DA to listOf("Det tager jeg med.", "Noteret, jeg tilføjer det.", "Okay, det kommer med.", "Fint, det får jeg med."),
        )
    }
}

/** What live chat says besides acknowledgements and replies. */
object LivePhrases {
    fun noAnswer(l: LiveLanguage) = if (l == LiveLanguage.DA) "Der kom intet svar." else "No answer came back."
    fun notSent(l: LiveLanguage) = if (l == LiveLanguage.DA) "Det blev ikke sendt." else "That wasn't sent."
    fun options(l: LiveLanguage) = if (l == LiveLanguage.DA) "Muligheder" else "Options"
    fun or(l: LiveLanguage) = if (l == LiveLanguage.DA) "eller" else "or"
    fun restOnScreen(l: LiveLanguage) = if (l == LiveLanguage.DA) "Resten står i chatten." else "The rest is in the chat."
    fun problem(l: LiveLanguage) = if (l == LiveLanguage.DA) "Der gik noget galt." else "Something went wrong."
    fun stopped(l: LiveLanguage) = if (l == LiveLanguage.DA) "Jeg stoppede. Hvad vil du nu?" else "Stopped. What now?"
}

/** A Claude reply made fit to say out loud: no Markdown, no code or tables, options read out briefly. */
object SpokenText {
    const val LIMIT = 700

    fun of(markdown: String, language: LiveLanguage, limit: Int = LIMIT): String {
        val said = mutableListOf<String>()
        ReplyParts.parse(markdown).forEach { part ->
            when (part) {
                is ReplyPart.Text -> said += prose(part.markdown)
                is ReplyPart.Choices -> said += options(part.options.map { it.label }, language)
            }
        }
        val text = said.filter { it.isNotBlank() }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return shorten(text, limit, language)
    }

    fun options(labels: List<String>, language: LiveLanguage): String {
        val clean = labels.map { inline(it).trimEnd('.') }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return ""
        val list = if (clean.size == 1) clean[0] else clean.dropLast(1).joinToString(", ") + " ${LivePhrases.or(language)} " + clean.last()
        return "${LivePhrases.options(language)}: $list."
    }

    private fun prose(markdown: String): String {
        val out = mutableListOf<String>()
        var inCode = false
        for (raw in markdown.lines()) {
            val t = raw.trim()
            if (t.startsWith("```")) { inCode = !inCode; continue }
            if (inCode || t.isEmpty() || t.startsWith("|") || t == "---" || t == "***" || t == "___") continue
            var line = t.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^>\\s?"), "")
            line = line.replace(Regex("^([-*+•]|\\d{1,3}[.)])\\s+(\\[[ xX]]\\s+)?"), "")
            line = inline(line)
            if (line.isBlank()) continue
            out += if (line.last() in ".!?:;") line else "$line."
        }
        return out.joinToString(" ")
    }

    private fun inline(s: String): String = s
        .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\*\\*(.+?)\\*\\*|__(.+?)__"), "$1$2")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun shorten(text: String, limit: Int, language: LiveLanguage): String {
        if (text.length <= limit) return text
        val cut = text.take(limit)
        val end = Regex("[.!?](\\s|$)").findAll(cut).lastOrNull()?.range?.first
        val kept = if (end != null && end > limit / 3) cut.substring(0, end + 1) else cut.substringBeforeLast(' ') + "…"
        return "$kept ${LivePhrases.restOnScreen(language)}"
    }
}

/**
 * Follows one live turn in a session's transcript: what Claude says after the user's message, and when the turn is
 * over (the session stopped working after the message went).
 */
class ReplyFollower {
    private var next = -1
    private var sent = false
    var spokeAny = false
        private set

    val following: Boolean get() = next >= 0

    /** The message is about to go; the transcript has [count] items. */
    fun begin(count: Int) {
        next = count
        sent = false
        spokeAny = false
    }

    /** `session.send` (or `session.new`) answered: from now on an idle session means the turn is over. */
    fun sent() { sent = true }

    fun reset() {
        next = -1
        sent = false
        spokeAny = false
    }

    data class Update(val say: List<ChatItem>, val done: Boolean)

    /** [items] is the whole transcript the phone has, [busy] whether the session works. */
    fun follow(items: List<ChatItem>, busy: Boolean): Update {
        if (next < 0) return Update(emptyList(), false)
        val fresh = items.drop(next).filter { !it.pending && (it.role == "assistant" || it.role == "error") }
        next = maxOf(next, items.count { !it.pending })
        if (fresh.isNotEmpty()) spokeAny = true
        val done = sent && !busy
        if (done) next = -1
        return Update(fresh, done)
    }
}

/** What live chat asks of the phone and the Mac. */
interface LiveEffects {
    /** Keeps recognising speech, utterance after utterance, until [stopListening]. */
    fun startListening()
    fun stopListening()
    fun speak(text: String, utteranceId: String)
    fun stopSpeaking()
    fun startBargeInWatch()
    fun stopBargeInWatch()
    /** Sends what the user said to the session (or starts the session with it). */
    fun send(text: String)
    /** Stops what the session is doing. */
    fun stopSession()
    /** Calls [LiveChatMachine.pauseOver] after [ms], replacing any timer already running. */
    fun startSendTimer(ms: Long)
    fun cancelSendTimer()
}

/**
 * LISTENING: nothing is being worked on; what the user says becomes the next message. WAITING: Claude works on it
 * and the microphone stays open, so the user can add to it or say "stop". SPEAKING: a reply is read out; talking
 * over it stops it.
 */
enum class LivePhase { IDLE, LISTENING, WAITING, SPEAKING, PAUSED }

/** A whole utterance that means "stop what you're doing", in English or Danish. */
object StopCommand {
    private val PHRASES = setOf(
        "stop", "stop it", "stop that", "stop now", "stop working", "please stop", "stop please", "cancel",
        "cancel that", "cancel it", "abort", "halt", "hold on stop",
        "stop det", "stop nu", "hold op", "hold så op", "annuller", "annullér", "afbryd", "afbryd det", "stop lige",
    )

    fun matches(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("mindmill ").removePrefix("okay ").removePrefix("ok ").trim()
        return t in PHRASES
    }
}

/**
 * Talking live in a session. What the user says is collected until they pause ([PAUSE_MS] after the recogniser's
 * last result) and then sent, with a short acknowledgement. The microphone stays open while Claude works: more
 * speech is added to the message, and "stop" stops the session. A reply is read out as it comes, with the
 * recogniser off (it would hear the phone) and a voice detector watching for the user talking over it.
 * Everything runs on one thread.
 */
class LiveChatMachine(
    private val fx: LiveEffects,
    private val acknowledgements: Acknowledgements = Acknowledgements(),
    private val onPhase: (LivePhase) -> Unit = {},
) {
    var phase = LivePhase.IDLE
        private set(value) {
            if (field != value) { field = value; onPhase(value) }
        }

    var language = LiveLanguage.EN

    /** What has been heard and not sent yet. */
    var draft = ""
        private set

    /** Claude works on something sent in this live chat. */
    var working = false
        private set

    private var hearing = false
    private var muted = false
    private var watching = false
    private var listening = false
    private var n = 0
    private val outstanding = LinkedHashSet<String>()
    private val held = mutableListOf<String>()

    val queued: Int get() = outstanding.size

    fun start() {
        reset()
        phase = LivePhase.LISTENING
        listen()
    }

    /** The recogniser has words for the utterance in progress. */
    fun partial(text: String) {
        if (!collecting() || text.isBlank()) return
        hearing = true
        fx.cancelSendTimer()
    }

    /** One utterance recognised. */
    fun heard(text: String) {
        if (!collecting()) return
        hearing = false
        val t = text.trim()
        if (t.isEmpty()) { heardNothing(); return }
        if (draft.isEmpty() && StopCommand.matches(t)) {
            if (working) stopWork()
            return
        }
        draft = if (draft.isEmpty()) t else "$draft $t"
        fx.startSendTimer(PAUSE_MS)
    }

    /** The recogniser stopped without words (silence, or nothing it could make out). */
    fun heardNothing() {
        if (!collecting()) return
        hearing = false
        if (draft.isNotEmpty()) fx.startSendTimer(PAUSE_MS) else speakHeld()
    }

    /** The pause after the last utterance is long enough: send what was heard. */
    fun pauseOver() {
        if (hearing) return
        sendDraft()
    }

    /** The send button: send what was heard now, with [extra] (the words still being recognised). */
    fun sendNow(extra: String = "") {
        if (!collecting()) return
        val e = extra.trim()
        if (e.isNotEmpty()) draft = if (draft.isEmpty()) e else "$draft $e"
        hearing = false
        sendDraft()
    }

    /** The user threw away what was heard. */
    fun discardDraft() {
        fx.cancelSendTimer()
        draft = ""
        speakHeld()
    }

    /** Something Claude said in this turn, ready to speak. */
    fun reply(text: String) {
        if (muted || text.isBlank()) return
        if (phase != LivePhase.WAITING && phase != LivePhase.SPEAKING) return
        if (hearing || draft.isNotEmpty()) { held += text; return }
        say("reply", text)
    }

    /** The session stopped working. */
    fun turnDone() {
        if (!working) return
        working = false
        if (phase == LivePhase.WAITING) phase = LivePhase.LISTENING
    }

    /** Sending failed: say why, then listen again. */
    fun failed(message: String) {
        working = false
        held.clear()
        if (phase == LivePhase.WAITING || phase == LivePhase.SPEAKING) say("note", message)
    }

    fun utteranceDone(id: String) {
        if (!outstanding.remove(id) || outstanding.isNotEmpty()) return
        stopWatch()
        if (phase == LivePhase.SPEAKING) {
            phase = if (working) LivePhase.WAITING else LivePhase.LISTENING
            listen()
        }
    }

    /**
     * The user talked over the phone, or tapped stop while it spoke: stop speaking and listen. Interrupting a reply
     * skips the rest of that reply; interrupting the acknowledgement or a note doesn't, so the reply is still read.
     */
    fun bargeIn() {
        if (phase != LivePhase.SPEAKING) return
        val wasReply = outstanding.any { it.startsWith("reply:") }
        fx.stopSpeaking()
        outstanding.clear()
        stopWatch()
        if (wasReply) {
            held.clear()
            muted = true
        }
        phase = if (working) LivePhase.WAITING else LivePhase.LISTENING
        listen()
    }

    /** "Stop", said or tapped, while Claude works: stop the session and say so. */
    fun stopWork() {
        if (!working || phase == LivePhase.IDLE || phase == LivePhase.PAUSED) return
        fx.stopSession()
        working = false
        muted = true
        held.clear()
        fx.cancelSendTimer()
        draft = ""
        if (outstanding.isNotEmpty()) {
            fx.stopSpeaking()
            outstanding.clear()
        }
        phase = LivePhase.WAITING
        say("note", LivePhrases.stopped(language))
    }

    fun pause() {
        if (phase == LivePhase.IDLE || phase == LivePhase.PAUSED) return
        fx.stopSpeaking()
        silence()
        stopWatch()
        fx.cancelSendTimer()
        outstanding.clear()
        held.clear()
        hearing = false
        muted = true
        phase = LivePhase.PAUSED
    }

    fun resume() {
        if (phase != LivePhase.PAUSED) return
        phase = if (working) LivePhase.WAITING else LivePhase.LISTENING
        listen()
    }

    fun end() {
        if (phase == LivePhase.IDLE) return
        fx.stopSpeaking()
        silence()
        stopWatch()
        fx.cancelSendTimer()
        reset()
        phase = LivePhase.IDLE
    }

    private fun collecting() = phase == LivePhase.LISTENING || phase == LivePhase.WAITING

    private fun sendDraft() {
        fx.cancelSendTimer()
        val text = draft.trim()
        draft = ""
        if (text.isEmpty()) { speakHeld(); return }
        val adding = working
        working = true
        muted = false
        fx.send(text)
        phase = LivePhase.WAITING
        say("ack", if (adding) acknowledgements.added(language) else acknowledgements.next(language))
        speakHeld()
    }

    private fun speakHeld() {
        if (held.isEmpty()) return
        val all = held.toList()
        held.clear()
        all.forEach { say("reply", it) }
    }

    private fun say(kind: String, text: String) {
        val id = "$kind:${n++}"
        if (outstanding.isEmpty()) {
            silence()
            if (!watching) { watching = true; fx.startBargeInWatch() }
        }
        outstanding += id
        phase = LivePhase.SPEAKING
        fx.speak(text, id)
    }

    private fun stopWatch() {
        if (watching) { watching = false; fx.stopBargeInWatch() }
    }

    private fun listen() {
        if (listening) return
        listening = true
        fx.startListening()
    }

    private fun silence() {
        if (!listening) return
        listening = false
        hearing = false
        fx.stopListening()
    }

    private fun reset() {
        working = false
        muted = false
        hearing = false
        listening = false
        draft = ""
        outstanding.clear()
        held.clear()
    }

    companion object {
        /** How long a pause after the last utterance sends the message (the recogniser has waited ~1.5 s already). */
        const val PAUSE_MS = 1_600L
    }
}
