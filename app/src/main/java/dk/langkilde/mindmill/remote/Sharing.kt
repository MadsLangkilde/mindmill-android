package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

/**
 * Something a session, note or review proposes for the knowledge base: an inbox entry waiting for the user.
 * [section] is where it goes when added (`inbox.approve`); dismissing it is `inbox.reject`.
 */
data class Suggestion(
    val id: String,
    val section: String,
    val title: String,
    val summary: String = "",
    val reason: String = "",
) {
    companion object {
        fun parse(o: JsonObject): Suggestion? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return Suggestion(
                id, o.string("section").orEmpty(), o.string("title")?.takeIf { it.isNotBlank() } ?: id,
                o.string("summary").orEmpty(), o.string("reason").orEmpty(),
            )
        }

        /** `suggestions[]` of `session.get` or `entry.get`; none from a Mac that doesn't send them. */
        fun list(o: JsonObject?): List<Suggestion> = o.objects("suggestions").mapNotNull(::parse).distinctBy { it.id }
    }
}

/**
 * Whether an entry is private (only in the knowledge base on the Mac) or shared (a copy in the project's
 * repository), as `entry.get` and `entry.share` say it. [title] says it in words.
 */
data class Sharing(
    val state: String,
    val title: String,
    val shared: Boolean,
    val canChange: Boolean,
    val repo: String? = null,
) {
    /** What to ask before making a shared entry private: it leaves the repository. */
    fun privateQuestion(): String =
        "Making it private removes it from " + (repo?.takeIf { it.isNotBlank() }?.let { "the $it repository" } ?: "the project's repository") +
            ". It stays in your knowledge base on the Mac."

    companion object {
        fun parse(o: JsonObject?): Sharing? {
            if (o == null) return null
            val state = o.string("state").orEmpty()
            val shared = o.bool("shared") ?: (state in setOf("uncommitted", "unpushed", "pushed"))
            return Sharing(
                state, o.string("title")?.takeIf { it.isNotBlank() } ?: if (shared) "Shared" else "Private",
                shared, o.bool("canChange") == true, o.string("repo")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Whether a kind of entry is private or shared when it is made (`sharing.defaults`). */
data class SharingDefault(val section: String, val title: String, val shared: Boolean)

data class SharingDefaults(val scope: String?, val repo: String?, val defaults: List<SharingDefault>) {
    /** Without a repository everything stays private and nothing can change. */
    val canShare: Boolean get() = repo != null

    fun with(section: String, shared: Boolean) =
        copy(defaults = defaults.map { if (it.section == section) it.copy(shared = shared) else it })

    companion object {
        fun parse(o: JsonObject) = SharingDefaults(
            o.string("scope"),
            o.string("repo")?.takeIf { it.isNotBlank() },
            o.objects("defaults").mapNotNull { d ->
                val section = d.string("section")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SharingDefault(section, d.string("title") ?: section.replaceFirstChar { it.uppercase() }, d.string("visibility") == "shared")
            },
        )

        fun visibility(shared: Boolean) = if (shared) "shared" else "private"
    }
}

/** What an entry id (`scope/section/2026-11-20-name`) says about the entry. */
object EntryIds {
    /** The section an id is in, or null for an id without one. */
    fun section(id: String): String? {
        val parts = id.split('/').filter { it.isNotBlank() }
        return if (parts.size >= 3) parts[parts.size - 2] else null
    }
}

/** A note is written as plain text; its title is optional. */
object NoteText {
    const val TITLE_LENGTH = 60

    /** A title for a note that has none: its first line, cut at a word near [TITLE_LENGTH]. */
    fun titleFrom(text: String): String {
        val line = text.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() } ?: return "Note"
        if (line.length <= TITLE_LENGTH) return line
        val cut = line.take(TITLE_LENGTH)
        val space = cut.lastIndexOf(' ')
        return (if (space >= TITLE_LENGTH / 2) cut.take(space) else cut).trimEnd(',', '.', ';', ':') + "…"
    }

    fun statusTitle(status: String?): String = when (status) {
        "filed" -> "Filed"
        else -> "Open"
    }
}
