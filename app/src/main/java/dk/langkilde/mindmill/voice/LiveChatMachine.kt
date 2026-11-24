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
    fun startListening()
    fun stopListening()
    fun speak(text: String, utteranceId: String)
    fun stopSpeaking()
    fun startBargeInWatch()
    fun stopBargeInWatch()
    /** Sends what the user said to the session (or starts the session with it). */
    fun send(text: String)
}

enum class LivePhase { IDLE, LISTENING, WAITING, SPEAKING, PAUSED }

/**
 * Talking live in a session: listen, send what was heard, say a short acknowledgement at once, speak the reply as
 * it comes, then listen again. Talking over the reply stops it and listens. Everything runs on one thread.
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

    private var turnOver = false
    private var muted = false
    private var watching = false
    private var n = 0
    private val outstanding = LinkedHashSet<String>()

    val queued: Int get() = outstanding.size

    fun start() {
        clear()
        listen()
    }

    fun heard(text: String) {
        if (phase != LivePhase.LISTENING) return
        if (text.isBlank()) { fx.startListening(); return }
        clear()
        phase = LivePhase.WAITING
        fx.send(text.trim())
        say("ack", acknowledgements.next(language))
    }

    /** Something Claude said in this turn, ready to speak. */
    fun reply(text: String) {
        if (muted || text.isBlank()) return
        if (phase != LivePhase.WAITING && phase != LivePhase.SPEAKING) return
        say("reply", text)
        phase = LivePhase.SPEAKING
        if (!watching) { watching = true; fx.startBargeInWatch() }
    }

    /** The session stopped working on it; listen again once everything is said. */
    fun turnDone() {
        if (phase != LivePhase.WAITING && phase != LivePhase.SPEAKING) return
        turnOver = true
        finishIfQuiet()
    }

    /** Sending failed: say why, then listen again. */
    fun failed(message: String) {
        if (phase != LivePhase.WAITING && phase != LivePhase.SPEAKING) return
        say("note", message)
        turnOver = true
        finishIfQuiet()
    }

    fun utteranceDone(id: String) {
        if (outstanding.remove(id)) finishIfQuiet()
    }

    /** The user talked over the reply, or tapped stop: drop the rest of this turn and listen. */
    fun bargeIn() {
        if (phase != LivePhase.SPEAKING && phase != LivePhase.WAITING) return
        fx.stopSpeaking()
        outstanding.clear()
        stopWatch()
        muted = true
        listen()
    }

    fun pause() {
        if (phase == LivePhase.IDLE || phase == LivePhase.PAUSED) return
        fx.stopSpeaking()
        fx.stopListening()
        stopWatch()
        outstanding.clear()
        muted = true
        phase = LivePhase.PAUSED
    }

    fun resume() {
        if (phase == LivePhase.PAUSED) listen()
    }

    fun end() {
        if (phase == LivePhase.IDLE) return
        fx.stopSpeaking()
        fx.stopListening()
        stopWatch()
        clear()
        phase = LivePhase.IDLE
    }

    private fun say(kind: String, text: String) {
        val id = "$kind:${n++}"
        outstanding += id
        fx.speak(text, id)
    }

    private fun finishIfQuiet() {
        if (!turnOver || outstanding.isNotEmpty()) return
        stopWatch()
        listen()
    }

    private fun stopWatch() {
        if (watching) { watching = false; fx.stopBargeInWatch() }
    }

    private fun listen() {
        turnOver = false
        phase = LivePhase.LISTENING
        fx.startListening()
    }

    private fun clear() {
        turnOver = false
        muted = false
        outstanding.clear()
    }
}
