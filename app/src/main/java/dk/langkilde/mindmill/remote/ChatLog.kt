package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

data class ChatItem(val role: String, val text: String, val pending: Boolean = false)

/**
 * A session's messages as the phone has them, kept in step with `session.get` and `since`. When the Mac's count
 * goes down (the session was cleared or replaced) it starts over. Messages the user just sent show at once and
 * give way to the Mac's copy when it arrives.
 */
class ChatLog {
    private var confirmed: List<ChatItem> = emptyList()
    private var pending: List<String> = emptyList()

    val items: List<ChatItem> get() = confirmed + pending.map { ChatItem("user", it, pending = true) }

    var busy = false
        private set
    var title: String? = null
        private set
    var state: String = ""
        private set
    var scope: String? = null
        private set
    var space: String = ""
        private set
    /** What Claude was refused in its last turn (`state` blocked), when the Mac says. */
    var denials: List<String> = emptyList()
        private set
    /** What the session suggests adding to the knowledge base, waiting for the user. */
    var suggestions: List<Suggestion> = emptyList()
        private set

    /** What to pass as `since` next. */
    val since get() = confirmed.size

    /**
     * Applies a `session.get` answer asked with `since` = [askedSince]. True when the Mac has fewer messages than
     * that (the session was cleared): fetch again with `since` 0.
     */
    fun apply(result: JsonObject, askedSince: Int): Boolean {
        val total = result.int("count")
        result.obj("session")?.let { s ->
            busy = s.bool("busy") == true
            s.string("title")?.let { title = it }
            state = s.string("state").orEmpty()
            scope = s.string("scope")
            space = s.string("space").orEmpty()
        }
        denials = result.strings("denials")
        suggestions = Suggestion.list(result)
        if (askedSince > 0 && total != null && total < askedSince) return true
        val fresh = result.objects("items").map { ChatItem(it.string("role") ?: "status", it.string("text").orEmpty()) }
        confirmed = if (askedSince == 0) fresh else confirmed.take(askedSince) + fresh
        val echoed = fresh.filter { it.role == "user" }.map { it.text.trim() }.toMutableList()
        pending = pending.filter { p -> !echoed.remove(p.trim()) }
        return false
    }

    /** `session.changed` says how many there are: is a fetch needed? */
    fun isBehind(count: Int?): Boolean = count == null || count != confirmed.size

    fun addPending(text: String) {
        pending = pending + text
    }

    fun dropPending(text: String) {
        val i = pending.lastIndexOf(text)
        if (i >= 0) pending = pending.filterIndexed { j, _ -> j != i }
    }

    fun reset() {
        confirmed = emptyList()
        pending = emptyList()
        busy = false
        state = ""
        title = null
        scope = null
        space = ""
        denials = emptyList()
        suggestions = emptyList()
    }
}
