package dk.langkilde.mindmill.ui

import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Plan or Build, as the switch at the top of the Mac's window. */
@kotlinx.serialization.Serializable
enum class Mode(val id: String, val title: String) {
    PLAN("plan", "Plan"),
    BUILD("build", "Build");

    companion object {
        fun of(id: String?): Mode = entries.firstOrNull { it.id == id } ?: PLAN
    }
}

/**
 * One row of a mode's sidebar on the Mac. [count] is how many there are (shown quietly); [needsYou] how many wait
 * for the user (shown as a badge), 0 when none.
 */
data class NavSection(
    val id: String,
    val title: String,
    val icon: String = "",
    val count: Int? = null,
    val needsYou: Int = 0,
    /** The sidebar heading it sits under on the Mac (Planning, Work, Sessions); null for the rows at the top. */
    val group: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): NavSection? {
            val id = o.string("id")?.takeIf { it.isNotBlank() } ?: return null
            val count = o.int("count")
            val needs = (o["needsYou"] as? JsonPrimitive)?.let { p ->
                p.intOrNull ?: if (p.booleanOrNull == true) (count?.takeIf { it > 0 } ?: 1) else 0
            } ?: 0
            return NavSection(id, o.string("title") ?: defaultTitle(id), o.string("icon").orEmpty(), count, needs, o.string("group"))
        }

        fun defaultTitle(id: String): String = when (id) {
            "home" -> "Home"
            "today" -> "Today"
            "pullRequests" -> "Pull requests"
            "followups" -> "Follow-ups"
            "overview" -> "Overview"
            "myWork" -> "My Work"
            "factories" -> "Factories"
            "testing" -> "Testing"
            else -> sectionTitle(id)
        }
    }
}

data class ModeStructure(val mode: Mode, val title: String, val sections: List<NavSection>)

/** What `app.structure` says the Mac's sidebars hold, per mode, in the user's order. */
data class AppStructure(val modes: List<ModeStructure>) {
    fun sections(mode: Mode): List<NavSection> = modes.firstOrNull { it.mode == mode }?.sections.orEmpty()

    fun needsYou(mode: Mode): Int = sections(mode).sumOf { it.needsYou }

    /**
     * The phone's own additions: Plan starts on Home (what needs you, quick actions); Build starts on its
     * overview. A mode the Mac left out, or left empty, takes the built-in one.
     */
    fun forPhone(kbSections: Collection<String> = emptyList()): AppStructure {
        val fallback = default(kbSections)
        return AppStructure(Mode.entries.map { m ->
            val given = modes.firstOrNull { it.mode == m }?.takeIf { it.sections.isNotEmpty() } ?: fallback.modes.first { it.mode == m }
            val sections = given.sections.distinctBy { it.id }
            ModeStructure(m, given.title, when (m) {
                Mode.PLAN -> if (sections.any { it.id == "home" }) sections else listOf(NavSection("home", "Home", "house")) + sections
                Mode.BUILD -> {
                    val merged = mergeBuildSessions(sections)
                    if (merged.any { it.id in BUILD_HOME }) merged else listOf(NavSection("overview", "Overview", "square.grid.2x2")) + merged
                }
            })
        })
    }

    companion object {
        private val BUILD_HOME = setOf("overview", "myWork")

        private fun isBuildSessions(s: NavSection) = s.id == "sessions" || s.id.startsWith("scope:") || s.id == "archived"

        /**
         * The Mac lists Build sessions as one row per scope (`scope:<id>`) and Archived; the phone shows them on one
         * Sessions screen, grouped the same way, so they become one Sessions section where the first of them was.
         * Build always has Sessions, so a new one can be started.
         */
        fun mergeBuildSessions(sections: List<NavSection>): List<NavSection> {
            val rows = sections.filter(::isBuildSessions)
            val live = rows.filter { it.id != "archived" }
            val merged = NavSection(
                "sessions", "Sessions", "bubble.left.and.text.bubble.right",
                count = live.sumOf { it.count ?: 0 }.takeIf { it > 0 },
                needsYou = live.sumOf { it.needsYou },
            )
            val at = sections.indexOfFirst(::isBuildSessions)
            val rest = sections.filterNot(::isBuildSessions)
            return if (at < 0) rest + merged else rest.take(at) + merged + rest.drop(at)
        }

        /** `app.structure`'s result, or null when it holds no mode. */
        fun parse(o: JsonObject): AppStructure? {
            val modes = o.objects("modes").mapNotNull { m ->
                val id = m.string("id") ?: return@mapNotNull null
                val mode = Mode.entries.firstOrNull { it.id == id } ?: return@mapNotNull null
                ModeStructure(mode, m.string("title") ?: mode.title, m.objects("sections").mapNotNull(NavSection::parse))
            }.distinctBy { it.mode }
            return if (modes.isEmpty()) null else AppStructure(modes)
        }

        val PLAN_DEFAULT = listOf(
            "today", "reminders", "goals", "sessions", "inbox", "decisions", "meetings", "discussions", "ideas",
            "research", "facts", "followups", "references", "backlog", "pullRequests", "architecture",
        )
        val BUILD_DEFAULT = listOf("overview", "factories", "testing", "sessions")
        private val NOT_KB = setOf("today", "reminders", "goals", "sessions", "pullRequests", "overview", "factories", "testing")

        /** The Mac's sidebars as they were before `app.structure`, for a Mac that doesn't send it. */
        fun default(kbSections: Collection<String> = emptyList()): AppStructure {
            val plan = PLAN_DEFAULT.filter { it in NOT_KB || kbSections.isEmpty() || it in kbSections }
            return AppStructure(listOf(
                ModeStructure(Mode.PLAN, "Plan", plan.map { NavSection(it, NavSection.defaultTitle(it)) }),
                ModeStructure(Mode.BUILD, "Build", BUILD_DEFAULT.map {
                    NavSection(it, if (it == "factories") "Software Factories" else NavSection.defaultTitle(it))
                }),
            ))
        }
    }
}

/** Where a section goes on the phone; a section without its own screen opens its area's actions. */
fun NavSection.screen(mode: Mode, kbSections: Collection<String> = emptyList()): Screen = when (id) {
    "home" -> Screen.Home
    "overview", "myWork" -> if (mode == Mode.BUILD) Screen.MyWork else Screen.Facts(section = "overview")
    "sessions" -> Screen.Sessions(mode.id)
    "backlog" -> Screen.Backlog
    "meetings" -> Screen.Meetings
    "discussions" -> Screen.Discussions
    "followups" -> Screen.Followups()
    "pullRequests", "prs" -> Screen.PullRequests
    "factories" -> Screen.Factories
    "test", "testing" -> Screen.Test
    "knowledge" -> Screen.Knowledge
    "tags" -> Screen.Tags
    "people" -> Screen.People
    "voice" -> Screen.Voice
    "assistant" -> Screen.NewSession(Mode.PLAN.id)
    "more" -> Screen.More
    "today" -> Screen.ActionView(title, "today.plan", "Today", listOf("today"), "Your plan for today.")
    "reminders" -> Screen.ActionView(title, "reminders.list", "Reminders and Goals", listOf("reminder", "list"), "Your open reminders.")
    "goals" -> Screen.ActionView(title, "goals.list", "Reminders and Goals", listOf("goal", "list"), "Your goals, and how they are going.")
    "strategies" -> Screen.ActionView(title, "strategies.list", "Strategies", listOf("strategy", "list"), "The strategies Build sessions and factories can use.")
    "project", "projects" -> Screen.Projects
    in KB_SECTIONS, in kbSections -> Screen.Facts(section = id)
    else -> Screen.AllActions(title)
}

private val KB_SECTIONS = setOf("inbox", "decisions", "facts", "architecture", "ideas", "research", "references")

/** Window widths as Material's size classes: phone, then tablet or unfolded or landscape, then large. */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun of(widthDp: Float): WidthClass = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** A session list split the way the Mac's Sessions page shows it. */
data class SessionRow(val session: SessionInfo, val indent: Int = 0)

data class SessionGroup(val id: String, val title: String, val rows: List<SessionRow>) {
    val needsYou: Int get() = rows.count { it.session.needsYou }
}

data class GroupedSessions(
    val groups: List<SessionGroup>,
    val archived: List<SessionInfo>,
    /** Plan sessions not touched for two weeks, folded as on the Mac. */
    val older: List<SessionInfo> = emptyList(),
) {
    val isEmpty get() = groups.isEmpty() && archived.isEmpty() && older.isEmpty()
}

object SessionGroups {
    val PLAN_KINDS = listOf(
        "assistant" to "Assistant", "workItems" to "Work items", "research" to "Research", "ideas" to "Ideas",
        "discussions" to "Discussions", "followups" to "Follow-ups", "meetings" to "Meetings", "goals" to "Goals",
        "entries" to "On entries", "questions" to "Questions",
    )

    /** A Plan session's kind; a Mac that doesn't send `kind` gets the assistant, or else a question. */
    fun kindOf(s: SessionInfo): String = s.kind?.takeIf { it.isNotBlank() } ?: if (s.role == "assistant") "assistant" else "questions"

    /** Sessions whose archived state was just changed here, before the Mac's list says so. */
    fun withOverrides(sessions: List<SessionInfo>, overrides: Map<String, Boolean>): List<SessionInfo> =
        sessions.map { s -> overrides[s.id]?.let { s.copy(archived = it) } ?: s }

    const val OLDER_AFTER_SECONDS = 14 * 24 * 3600.0

    /**
     * Every Plan session, however it was started, by kind in the Mac's order, unknown kinds after. Those not
     * touched for two weeks go in Older, unless they run or need the user; archived ones apart.
     */
    fun plan(all: List<SessionInfo>, nowSeconds: Double = System.currentTimeMillis() / 1000.0): GroupedSessions {
        val plan = all.filter { it.space != "build" }.sortedByDescending { it.updated }
        fun recent(s: SessionInfo) = s.needsYou || s.busy || s.state == "working" || s.updated <= 0 || nowSeconds - s.updated <= OLDER_AFTER_SECONDS
        val live = plan.filter { !it.archived && recent(it) }
        val byKind = live.groupBy(::kindOf)
        val known = PLAN_KINDS.map { it.first }.toSet()
        val groups = PLAN_KINDS.mapNotNull { (k, title) ->
            byKind[k]?.let { list -> SessionGroup(k, list.firstNotNullOfOrNull { it.kindTitle } ?: title, list.map { SessionRow(it) }) }
        } + live.map(::kindOf).distinct().filter { it !in known }.map { k ->
            val list = byKind.getValue(k)
            SessionGroup(k, list.firstNotNullOfOrNull { it.kindTitle } ?: dk.langkilde.mindmill.remote.humanize(k), list.map { SessionRow(it) })
        }
        return GroupedSessions(groups, plan.filter { it.archived }, plan.filter { !it.archived && !recent(it) })
    }

    /** "From Research" for a session started on `scope/research/…`; "From Goals" for `goals/…`. */
    fun originTitle(origin: String?): String? {
        val parts = origin?.split('/')?.filter { it.isNotBlank() }.orEmpty()
        return when {
            parts.isEmpty() -> null
            parts[0] == "goals" -> "From Goals"
            parts.size >= 3 -> "From ${sectionTitle(parts[1])}"
            else -> null
        }
    }

    /**
     * Build sessions by scope, in the order of the Mac's projects, then scopes it doesn't list, then those without
     * one. A helper goes under its lead when the Mac says which; the overseer stays out of the list.
     */
    fun build(all: List<SessionInfo>, scopes: List<Pair<String, String>>): GroupedSessions {
        val build = all.filter { it.space == "build" && it.role != "overseer" }.sortedByDescending { it.updated }
        val live = build.filter { !it.archived }
        val ids = live.map { it.id }.toSet()
        val top = live.filter { it.lead == null || it.lead !in ids }
        val members = live.filter { it.lead != null && it.lead in ids }.groupBy { it.lead }
        val order = scopes.map { it.first }
        val titles = scopes.toMap()
        val buckets = linkedMapOf<String?, MutableList<SessionInfo>>()
        (order + top.mapNotNull { it.scope?.takeIf(String::isNotBlank) }.filter { it !in order }.distinct()).forEach { buckets[it] = mutableListOf() }
        buckets[null] = mutableListOf()
        top.forEach { s -> buckets.getValue(s.scope?.takeIf(String::isNotBlank)).add(s) }
        val groups = buckets.filterValues { it.isNotEmpty() }.map { (scope, list) ->
            SessionGroup(
                scope ?: "-",
                scope?.let { titles[it] ?: it } ?: "No project",
                list.flatMap { s -> listOf(SessionRow(s)) + members[s.id].orEmpty().map { SessionRow(it, indent = 1) } },
            )
        }
        return GroupedSessions(groups, build.filter { it.archived })
    }

    fun stateTitle(state: String): String = when (state) {
        "working" -> "Working"
        "waiting" -> "Needs you"
        "blocked" -> "Needs permission"
        "idle" -> "Idle"
        "stopped" -> "Not running"
        else -> state.replaceFirstChar { it.uppercase() }
    }
}

/** WCAG contrast between two ARGB colours, from 1 to 21. */
object Contrast {
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The first container/on-container pair that reads well; the last one when none does. */
    fun <T> pick(pairs: List<Pair<T, T>>, argb: (T) -> Int, min: Double = 7.0): Pair<T, T> =
        pairs.firstOrNull { (bg, fg) -> ratio(argb(bg), argb(fg)) >= min } ?: pairs.last()
}
