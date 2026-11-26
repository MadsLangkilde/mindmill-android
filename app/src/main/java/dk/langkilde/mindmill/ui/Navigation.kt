package dk.langkilde.mindmill.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface Screen {
    @Serializable data object Home : Screen
    @Serializable data object MyWork : Screen
    @Serializable data object Backlog : Screen
    @Serializable data class Sessions(val space: String = "plan") : Screen
    @Serializable data object Voice : Screen
    @Serializable data object More : Screen
    /** Ask Mindmill: the description and suggestions; a session is made from the first thing written or said. */
    @Serializable data class NewSession(val space: String = "plan") : Screen
    @Serializable data class Facts(val proposed: Boolean = false, val section: String = "facts") : Screen
    @Serializable data class Followups(val filter: String = "mine") : Screen
    @Serializable data object Meetings : Screen
    @Serializable data object MacMeeting : Screen
    @Serializable data object Factories : Screen
    @Serializable data class FactoryEditor(val id: String?, val duplicate: Boolean = false) : Screen
    @Serializable data object Test : Screen
    @Serializable data object Connection : Screen
    @Serializable data object PairMac : Screen
    @Serializable data object Projects : Screen
    @Serializable data class ProjectEditor(val id: String?) : Screen
    @Serializable data class AllActions(val area: String? = null) : Screen
    @Serializable data class RunAction(val name: String, val defaults: Map<String, String> = emptyMap()) : Screen
    @Serializable data object Knowledge : Screen
    @Serializable data class NewEntry(val section: String) : Screen
    @Serializable data object PullRequests : Screen
    @Serializable data class PullRequest(val id: String, val title: String) : Screen
    @Serializable data object Discussions : Screen
    @Serializable data object People : Screen
    @Serializable data object Tags : Screen
    @Serializable data class Command(val title: String, val args: List<String>, val about: String) : Screen
    @Serializable data class ActionView(val title: String, val action: String, val area: String, val fallbackArgs: List<String>, val about: String) : Screen
    @Serializable data class WorkItemDetail(val item: JsonObject) : Screen
    @Serializable data class Entry(val id: String) : Screen
    @Serializable data class SessionChat(val id: String, val title: String, val archived: Boolean = false) : Screen
    @Serializable data class MeetingDetail(val id: String, val title: String) : Screen
    @Serializable data object LiveMeeting : Screen
    @Serializable data class Web(val title: String, val url: String, val preview: Boolean) : Screen
    /** What the session's AI can use: skills, plugins and tools (MCP servers). */
    @Serializable data class SkillsTools(val tab: String = "skills") : Screen
    /** One of the user's own skills; [name] null writes a new one. */
    @Serializable data class SkillEditor(val name: String?, val location: String = "user", val scope: String? = null) : Screen
    /** Whether each kind of entry is private or shared by default, in [scope] (the current one when null). */
    @Serializable data class SharingDefaults(val scope: String? = null) : Screen
}

/** One screen on a back stack; [id] tells two visits of the same screen apart and keys its saved state. */
@Serializable
data class NavEntryKey(val id: Long, val screen: Screen) {
    val contentKey: String get() = "e$id"
}

/** A top-level section's own back stack; its first entry is the section itself. */
@Serializable
data class TabStack(val entries: List<NavEntryKey>) {
    val root: Screen get() = entries.first().screen
    val top: NavEntryKey get() = entries.last()
}

/** One mode's sections that have been visited. The first is the mode's start section and is never removed. */
@Serializable
data class ModeHistory(val tabs: List<TabStack>, val current: Int = 0) {
    val tab: TabStack get() = tabs[current]
    val start: TabStack get() = tabs.first()

    /** What back walks through: this section's stack, then the start section's. */
    val history: List<NavEntryKey> get() = if (current == 0) start.entries else start.entries + tab.entries

    fun withTab(t: TabStack, index: Int = current) = copy(tabs = tabs.toMutableList().also { it[index] = t })
}

/**
 * Where the user is, in both modes. Pure: every change returns a new state, so the rules are tested without UI.
 *
 * Back rules, in order:
 * 1. Pop the current section's stack (detail → list → the section it was opened from, e.g. More).
 * 2. At a section's root that isn't the mode's start section, go to the start section (as it was left).
 * 3. At the start section's root, right after a Plan/Build switch, go back to the mode switched from, once.
 * 4. Otherwise there is nothing to go back to and the system closes the app.
 */
@Serializable
data class NavState(
    val mode: Mode,
    val plan: ModeHistory,
    val build: ModeHistory,
    /** The mode switched away from, which back returns to once this mode is back at its start. */
    val previousMode: Mode? = null,
    val nextId: Long,
) {
    fun of(m: Mode): ModeHistory = if (m == Mode.PLAN) plan else build
    val current: ModeHistory get() = of(mode)
    val tab: TabStack get() = current.tab
    val top: Screen get() = tab.top.screen

    /** The back stack as the screen shows it: back drops the last entry. */
    val history: List<NavEntryKey> get() = (previousMode?.let { of(it).history }.orEmpty()) + current.history

    /** Whether the current section has something of its own to go back to (the top bar's back arrow). */
    val canGoUp: Boolean get() = tab.entries.size > 1

    val allIds: Set<Long> get() = (plan.tabs + build.tabs).flatMap { t -> t.entries.map { it.id } }.toSet()

    /** The section an entry belongs to, as "plan/2"; entries of different sections never share a pane. */
    fun sectionOf(id: Long): String? = Mode.entries.firstNotNullOfOrNull { m ->
        of(m).tabs.indexOfFirst { t -> t.entries.any { it.id == id } }.takeIf { it >= 0 }?.let { "${m.id}/$it" }
    }

    private fun withMode(m: Mode, h: ModeHistory) = if (m == Mode.PLAN) copy(plan = h) else copy(build = h)
    private fun withCurrent(h: ModeHistory) = withMode(mode, h)

    /** Opens [screen] on top of the current section; opening what's already on top does nothing. */
    fun go(screen: Screen): NavState {
        if (top == screen) return this
        return withCurrent(current.withTab(TabStack(tab.entries + NavEntryKey(nextId, screen)))).copy(nextId = nextId + 1)
    }

    /** Swaps the top screen for [screen] (after creating something); a section's root is never replaced. */
    fun replace(screen: Screen): NavState {
        if (tab.entries.size == 1) return go(screen)
        if (top == screen) return this
        val kept = tab.entries.dropLast(1)
        return withCurrent(current.withTab(TabStack(kept + NavEntryKey(nextId, screen)))).copy(nextId = nextId + 1)
    }

    /** Pops back to the entry [id] in the current section; nothing happens if it isn't there. */
    fun popTo(id: Long): NavState {
        val at = tab.entries.indexOfFirst { it.id == id }
        if (at < 0 || at == tab.entries.lastIndex) return this
        return withCurrent(current.withTab(TabStack(tab.entries.take(at + 1))))
    }

    /** A list beside its detail opens [screen] in place of the detail shown now. */
    fun openFromList(listId: Long, screen: Screen): NavState = popTo(listId).go(screen)

    fun popToRoot(): NavState = popTo(tab.entries.first().id)

    /**
     * Picks a top-level section (from the drawer). Each keeps its own stack; picking the one you're in takes it back
     * to its root.
     */
    fun selectTab(root: Screen): NavState {
        if (tab.root == root) return popToRoot()
        val at = current.tabs.indexOfFirst { it.root == root }
        if (at >= 0) return withCurrent(current.copy(current = at))
        val tabs = current.tabs + TabStack(listOf(NavEntryKey(nextId, root)))
        return withCurrent(ModeHistory(tabs, tabs.lastIndex)).copy(nextId = nextId + 1)
    }

    /** Plan | Build: each mode keeps its stacks; back returns to the mode switched from, once, from the start section. */
    fun switchMode(to: Mode): NavState = if (to == mode) this else copy(mode = to, previousMode = mode)

    /** One step back, or null when there's nothing left and the app should close. */
    fun back(): NavState? = when {
        tab.entries.size > 1 -> withCurrent(current.withTab(TabStack(tab.entries.dropLast(1))))
        current.current != 0 -> withCurrent(current.copy(current = 0))
        previousMode != null -> copy(mode = previousMode, previousMode = null)
        else -> null
    }

    /** Another Mac is active: nothing on the stacks belongs to it, so both modes start over in the mode the user is in. */
    fun restarted(): NavState = initial(mode).copy(nextId = nextId)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun startOf(m: Mode): Screen = if (m == Mode.PLAN) Screen.Home else Screen.MyWork

        fun initial(mode: Mode) = NavState(
            mode = mode,
            plan = ModeHistory(listOf(TabStack(listOf(NavEntryKey(1, startOf(Mode.PLAN)))))),
            build = ModeHistory(listOf(TabStack(listOf(NavEntryKey(2, startOf(Mode.BUILD)))))),
            nextId = 3,
        )

        fun decode(s: String): NavState? = runCatching { json.decodeFromString(serializer(), s) }.getOrNull()
    }
}

/** Holds the navigation state for the UI; survives rotation and process death through [saver]. */
class Navigator(initial: NavState) {
    var state by mutableStateOf(initial)
        private set

    fun go(s: Screen) { state = state.go(s) }
    fun replace(s: Screen) { state = state.replace(s) }
    fun selectTab(root: Screen) { state = state.selectTab(root) }
    fun switchMode(to: Mode) { state = state.switchMode(to) }
    fun openFromList(listId: Long, s: Screen) { state = state.openFromList(listId, s) }
    fun restart() { state = state.restarted() }

    /** False when there's nothing to go back to. */
    fun back(): Boolean {
        val b = state.back() ?: return false
        state = b
        return true
    }

    companion object {
        fun saver(fallback: () -> NavState) = Saver<Navigator, String>(
            save = { it.state.encode() },
            restore = { Navigator(NavState.decode(it) ?: fallback()) },
        )
    }
}

/** What screens use to move around. A list shown beside its detail opens items in place of that detail. */
open class Nav(private val navigator: Navigator) {
    val current: Screen get() = navigator.state.top
    open fun go(s: Screen) = navigator.go(s)
    fun replace(s: Screen) = navigator.replace(s)
    fun back(): Boolean = navigator.back()
}

class ListPaneNav(private val navigator: Navigator, private val listId: Long) : Nav(navigator) {
    override fun go(s: Screen) = navigator.openFromList(listId, s)
}
