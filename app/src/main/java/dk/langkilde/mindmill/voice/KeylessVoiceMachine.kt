package dk.langkilde.mindmill.voice

import java.util.TreeMap

/** What the keyless conversation asks of the phone and the Mac. */
interface KeylessEffects {
    fun startListening()
    fun stopListening()
    fun speak(text: String, utteranceId: String)
    /** Stops speech at once and drops everything queued. */
    fun stopSpeaking()
    fun startBargeInWatch()
    fun stopBargeInWatch()
    fun say(text: String)
    fun interrupt()
}

enum class KeylessState { IDLE, LISTENING, SENDING, SPEAKING, PAUSED }

/**
 * The keyless engine's turn-taking: listen, send what was heard with `voice.say`, speak each `voice.sentence` as it
 * arrives, and listen again once the last one is spoken. The user talking over it (barge-in) stops speech at once,
 * sends `voice.interrupt` and listens. Everything runs on one thread.
 */
class KeylessVoiceMachine(
    private val fx: KeylessEffects,
    private val onState: (KeylessState) -> Unit = {},
) {
    var state = KeylessState.IDLE
        private set(value) {
            if (field != value) { field = value; onState(value) }
        }

    private var turn: String? = null
    private var turnDone = false
    private var spokeAny = false
    private var holdUnknownTurns = false
    private var extraUtterances = 0
    private val interrupted = mutableSetOf<String>()
    private val nextIndex = mutableMapOf<String, Int>()
    private val buffered = mutableMapOf<String, TreeMap<Int, String>>()
    private val outstanding = LinkedHashSet<String>()
    private val pendingDone = mutableMapOf<String, String>()

    val currentTurn: String? get() = turn
    val queuedUtterances: Int get() = outstanding.size

    fun start() {
        clearTurn()
        listen()
    }

    fun heard(text: String) {
        if (state != KeylessState.LISTENING) return
        if (text.isBlank()) { fx.startListening(); return }
        clearTurn()
        state = KeylessState.SENDING
        fx.say(text)
    }

    /** The `voice.say` answer named the turn this reply belongs to. */
    fun turnStarted(turnId: String) {
        if (state != KeylessState.SENDING && state != KeylessState.SPEAKING) return
        if (turnId in interrupted) return
        holdUnknownTurns = false
        val adopted = turn
        if (adopted != null && adopted != turnId) {
            interrupted += adopted
            fx.stopSpeaking()
            outstanding.clear()
            spokeAny = false
            if (state == KeylessState.SPEAKING) { fx.stopBargeInWatch(); state = KeylessState.SENDING }
        }
        buffered.keys.filter { it != turnId }.forEach { interrupted += it; buffered.remove(it); nextIndex.remove(it) }
        pendingDone.keys.filter { it != turnId }.forEach { pendingDone.remove(it) }
        turn = turnId
        drain(turnId)
        pendingDone.remove(turnId)?.let { done(turnId, it) }
    }

    fun sentence(turnId: String, index: Int, text: String) {
        if (turnId in interrupted) return
        if (state != KeylessState.SENDING && state != KeylessState.SPEAKING) return
        if (turn == null && !holdUnknownTurns) turn = turnId
        val buf = buffered.getOrPut(turnId) { TreeMap() }
        buf[index] = text
        if (!nextIndex.containsKey(turnId)) nextIndex[turnId] = index
        if (turnId == turn) drain(turnId)
    }

    fun done(turnId: String, text: String) {
        if (turnId in interrupted) return
        if (state != KeylessState.SENDING && state != KeylessState.SPEAKING) return
        if (turn == null && !holdUnknownTurns) turn = turnId
        if (turnId != turn) { pendingDone[turnId] = text; return }
        buffered.remove(turnId)?.forEach { (i, t) -> speakNow("$turnId:$i", t) }
        turnDone = true
        if (!spokeAny && text.isNotBlank()) speakNow("$turnId:all", text)
        maybeFinish()
    }

    /** Says something that isn't part of a reply (an error, the fallback notice), then listens again. */
    fun announce(text: String) {
        if (state == KeylessState.IDLE || state == KeylessState.PAUSED) return
        if (text.isBlank()) return
        if (state == KeylessState.LISTENING) fx.stopListening()
        turnDone = true
        speakNow("note:${extraUtterances++}", text)
    }

    fun utteranceDone(utteranceId: String) {
        if (outstanding.remove(utteranceId)) maybeFinish()
    }

    /** The user started talking (or tapped stop) while the reply was coming. */
    fun bargeIn() {
        if (state != KeylessState.SPEAKING && state != KeylessState.SENDING) return
        val wasSpeaking = state == KeylessState.SPEAKING
        fx.stopSpeaking()
        outstanding.clear()
        buffered.keys.forEach { interrupted += it }
        buffered.clear()
        val t = turn
        if (t != null) interrupted += t else holdUnknownTurns = true
        if (wasSpeaking) fx.stopBargeInWatch()
        fx.interrupt()
        clearTurn()
        listen()
    }

    fun pause() {
        if (state == KeylessState.IDLE) return
        fx.stopSpeaking()
        fx.stopListening()
        if (state == KeylessState.SPEAKING) fx.stopBargeInWatch()
        outstanding.clear()
        turn?.let { interrupted += it }
        clearTurn()
        state = KeylessState.PAUSED
    }

    fun resume() {
        if (state == KeylessState.PAUSED) listen()
    }

    fun end() {
        if (state == KeylessState.IDLE) return
        fx.stopSpeaking()
        fx.stopListening()
        fx.stopBargeInWatch()
        outstanding.clear()
        clearTurn()
        state = KeylessState.IDLE
    }

    private fun drain(turnId: String) {
        val buf = buffered[turnId] ?: return
        var next = nextIndex[turnId] ?: return
        while (true) {
            val text = buf.remove(next) ?: break
            speakNow("$turnId:$next", text)
            next++
        }
        nextIndex[turnId] = next
    }

    private fun speakNow(id: String, text: String) {
        if (state != KeylessState.SPEAKING) {
            state = KeylessState.SPEAKING
            fx.startBargeInWatch()
        }
        outstanding += id
        spokeAny = true
        fx.speak(text, id)
    }

    private fun maybeFinish() {
        if (!turnDone || outstanding.isNotEmpty()) return
        if (state == KeylessState.SPEAKING) fx.stopBargeInWatch()
        if (state == KeylessState.SPEAKING || state == KeylessState.SENDING) {
            clearTurn()
            listen()
        }
    }

    private fun listen() {
        state = KeylessState.LISTENING
        fx.startListening()
    }

    private fun clearTurn() {
        pendingDone.clear()
        turn = null
        turnDone = false
        spokeAny = false
        outstanding.clear()
    }
}
