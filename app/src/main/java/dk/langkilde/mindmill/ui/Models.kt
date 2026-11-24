package dk.langkilde.mindmill.ui

import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.double
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import dk.langkilde.mindmill.remote.text
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

data class EntrySummary(
    val id: String,
    val section: String,
    val title: String,
    val date: String,
    val summary: String,
    val tags: List<String>,
    val fields: Map<String, String>,
) {
    fun field(k: String) = fields[k]?.takeIf { it.isNotBlank() }

    companion object {
        fun parse(o: JsonObject) = EntrySummary(
            id = o.text("id").orEmpty(),
            section = o.string("section").orEmpty(),
            title = o.string("title") ?: o.text("id").orEmpty(),
            date = o.string("date").orEmpty(),
            summary = o.string("summary").orEmpty(),
            tags = o.strings("tags"),
            fields = o.obj("fields")?.keys?.associateWith { k -> o.obj("fields").text(k).orEmpty() }.orEmpty(),
        )

        fun list(o: JsonObject, key: String = "entries") = o.objects(key).map(::parse)
    }
}

data class WorkItem(
    val id: Int,
    val type: String,
    val title: String,
    val state: String,
    val stage: String,
    val sprint: String,
    val assignee: String,
    val mine: Boolean,
    val url: String,
    val tags: List<String>,
    val tasks: List<Task>,
    val raw: JsonObject,
) {
    data class Task(val id: Int, val title: String, val state: String, val assignee: String)

    val sprintName get() = sprint.substringAfterLast('\\')

    companion object {
        fun parse(o: JsonObject) = WorkItem(
            id = o.int("id") ?: 0, type = o.string("type").orEmpty(), title = o.string("title").orEmpty(),
            state = o.string("state").orEmpty(), stage = o.string("stage").orEmpty(), sprint = o.string("sprint").orEmpty(),
            assignee = o.string("assignee").orEmpty(), mine = o.bool("mine") == true, url = o.string("url").orEmpty(),
            tags = o.strings("tags"),
            tasks = o.objects("tasks").map {
                Task(it.int("id") ?: 0, it.string("title").orEmpty(), it.string("state").orEmpty(), it.string("assignee").orEmpty())
            },
            raw = o,
        )
    }
}

data class SessionInfo(
    val id: String,
    val title: String,
    val space: String,
    val role: String,
    val state: String,
    val busy: Boolean,
    val updated: Double,
    val count: Int,
    val scope: String? = null,
    /** What the session is for, as Plan's Sessions page groups it (`assistant`, `workItems`, `research`…). */
    val kind: String? = null,
    val kindTitle: String? = null,
    val archived: Boolean = false,
    /** Where it was started from (an idea, a research entry…), for the row's subtitle. */
    val origin: String? = null,
    /** The Build lead it works under, when the Mac says. */
    val lead: String? = null,
    /** What the Mac says; older Macs don't, and the state tells. */
    val needsYouFlag: Boolean? = null,
    /** A conversation that lives in its own place on the Mac (a meeting's chat, a goal's coach, Ask): where. */
    val linkScreen: String? = null,
    val linkId: String? = null,
) {
    val needsYou: Boolean get() = !archived && (needsYouFlag ?: (state == "waiting" || state == "blocked"))

    val isLink: Boolean get() = linkScreen != null || id.startsWith("link:")

    val updatedText: String
        get() = if (updated <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((updated * 1000).toLong()))

    companion object {
        fun parse(o: JsonObject) = SessionInfo(
            id = o.text("id").orEmpty(), title = o.string("title") ?: "Session", space = o.string("space") ?: "plan",
            role = o.string("role").orEmpty(), state = o.string("state").orEmpty(), busy = o.bool("busy") == true,
            updated = o.double("updated") ?: 0.0, count = o.int("count") ?: 0,
            scope = o.string("scope"), kind = o.string("kind"), kindTitle = o.string("kindTitle"),
            archived = o.bool("archived") == true,
            origin = o.string("origin") ?: o.obj("origin")?.let { it.string("title") ?: it.text("id") },
            lead = o.string("lead"),
            needsYouFlag = o.bool("needsYou"),
            linkScreen = o.obj("link")?.string("screen"),
            linkId = o.obj("link")?.text("id"),
        )
    }
}

data class Factory(
    val id: String,
    val name: String,
    val status: String,
    val progress: Double?,
    val maxParallel: Int,
    val tasks: List<FactoryTask>,
    val questions: List<FactoryQuestion>,
) {
    data class FactoryTask(val workItem: Int?, val title: String, val session: String?, val milestone: String?)
    data class FactoryQuestion(val id: String, val text: String, val options: List<String>, val workItem: Int?)

    companion object {
        fun parse(o: JsonObject) = Factory(
            id = o.text("id").orEmpty(), name = o.string("name") ?: "Factory", status = o.string("status") ?: "never",
            progress = o.double("progress"), maxParallel = o.int("maxParallel") ?: 1,
            tasks = o.objects("tasks").map {
                FactoryTask(it.int("workItem"), it.string("title").orEmpty(), it.string("session"), it.string("milestone"))
            },
            questions = o.objects("questions").map {
                FactoryQuestion(it.text("id").orEmpty(), it.string("text").orEmpty(), it.strings("options"), it.int("workItem"))
            },
        )
    }
}

fun sectionTitle(s: String) = when (s) {
    "followups" -> "Follow-ups"
    else -> s.replaceFirstChar { it.uppercase() }
}

/** One event of `calendar.upcoming`. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val scope: String,
    val date: String,
    val start: String,
    val end: String,
    val attendees: List<String>,
    val online: Boolean,
    val status: String,
    val open: Boolean,
) {
    val whenText: String
        get() {
            val time = listOf(clock(start), clock(end)).filter { it.isNotBlank() }.joinToString("–")
            return listOf(dayTitle(date), time, if (online) "online" else "", statusTitle).filter { it.isNotBlank() }.joinToString(" · ")
        }

    val statusTitle: String
        get() = when (status) {
            "live" -> "live now"
            "prepared" -> "prepared"
            "review" -> "to review"
            "filed" -> "filed"
            "cancelled" -> "cancelled"
            else -> ""
        }

    val canStart get() = status == "scheduled" || status == "prepared" || status.isBlank()

    companion object {
        /** "Today", "Tomorrow", "Yesterday", or the date as the Mac wrote it. */
        fun dayTitle(date: String): String {
            val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return date
            val today = java.time.LocalDate.now()
            return when (d) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                today.minusDays(1) -> "Yesterday"
                else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
            }
        }

        /** "2026-11-23T09:30:00+01:00" or "09:30" → "09:30". */
        fun clock(s: String): String = Regex("(\\d{1,2}:\\d{2})").find(s)?.value ?: s

        fun parse(o: JsonObject) = CalendarEvent(
            id = o.text("id").orEmpty(), title = o.string("title") ?: "Meeting", scope = o.string("scope").orEmpty(),
            date = o.string("date").orEmpty(), start = o.text("start").orEmpty(), end = o.text("end").orEmpty(),
            attendees = o.strings("attendees").ifEmpty { o.objects("attendees").mapNotNull { it.string("name") } },
            online = o.bool("online") == true || !o.string("online").isNullOrBlank(),
            status = o.string("status").orEmpty(), open = o.bool("open") == true,
        )
    }
}
