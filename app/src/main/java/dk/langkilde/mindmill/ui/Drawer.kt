package dk.langkilde.mindmill.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.objects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** What the navigation drawer holds for a mode, worked out without UI. */
object DrawerModel {
    /** Shown elsewhere in the drawer: its own sessions part, and the Project picker. */
    private val OWN_PARTS = setOf("sessions", "project", "projects", "more")

    /** The mode's sections from `app.structure`, in the user's order, then More. */
    fun destinations(sections: List<NavSection>): List<NavSection> =
        sections.filter { it.id !in OWN_PARTS } + NavSection("more", "More", "ellipsis")

    /**
     * The mode's sessions for the drawer: every one that needs the user first (newest first), then the [recent] most
     * recently touched others. Archived ones stay out, and so does Build's overview session unless it needs the user.
     */
    fun sessions(all: List<SessionInfo>, mode: Mode, recent: Int = 8): List<SessionInfo> {
        val mine = all.filter { s ->
            !s.archived && (if (mode == Mode.BUILD) s.space == "build" && (s.role != "overseer" || s.needsYou) else s.space != "build")
        }.sortedByDescending { it.updated }
        val needs = mine.filter { it.needsYou }
        return needs + mine.filter { !it.needsYou }.take(recent)
    }

    /** How many of the mode's live sessions the drawer leaves out (the Sessions screen has them all). */
    fun hidden(all: List<SessionInfo>, mode: Mode, recent: Int = 8): Int =
        all.count { !it.archived && (if (mode == Mode.BUILD) it.space == "build" && (it.role != "overseer" || it.needsYou) else it.space != "build") } -
            sessions(all, mode, recent).size
}

/** Opening from the drawer: a session or a new one takes the place of the one on screen instead of piling up. */
private class DrawerNav(private val navigator: Navigator) : Nav(navigator) {
    override fun go(s: Screen) {
        val top = navigator.state.top
        if (top is Screen.SessionChat || top is Screen.NewSession) navigator.replace(s) else navigator.go(s)
    }
}

/**
 * The app's navigation: Plan | Build, the mode's places as the Mac's sidebar has them, New session, the mode's
 * sessions (those that need you first), then the project and the Mac.
 */
@Composable
fun DrawerContent(
    navigator: Navigator,
    structure: AppStructure,
    kb: List<String>,
    visible: Boolean,
    close: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalApp.current
    val ui = rememberCoroutineScope()
    val mode = navigator.state.mode
    val sections = structure.sections(mode)
    val nav = remember(navigator) { DrawerNav(navigator) }
    val state = navigator.state
    val selected = state.tab.root

    var tick by remember { mutableIntStateOf(0) }
    val shown by rememberUpdatedState(visible)
    LaunchedEffect(Unit) {
        app.events.collectLatest { e ->
            if (e.name == "session.changed" && shown) {
                delay(1_000)
                tick++
            }
        }
    }
    LaunchedEffect(visible) { if (visible) tick++ }
    val scopeId = rememberCurrentScope()
    val (loaded, _) = rememberLoad(scopeId, mode, trigger = tick) {
        app.call("sessions.list", jsonOf("scope" to scopeId)).objects("sessions").map(SessionInfo::parse)
    }
    val all = SessionGroups.withOverrides(loaded.value.orEmpty(), SessionArchive.overrides)
    val sessions = DrawerModel.sessions(all, mode)
    val more = DrawerModel.hidden(all, mode)

    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(Modifier.padding(start = 12.dp, bottom = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.size(28.dp))
            Text("Mindmill", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp))
        }
        DrawerModeSwitch(mode, Mode.entries.associateWith { structure.needsYou(it) }) { navigator.switchMode(it) }

        var heading: String? = null
        DrawerModel.destinations(sections).forEach { s ->
            if (s.group != null && s.group != heading) DrawerHeading(s.group)
            heading = s.group
            val screen = s.screen(mode, kb)
            DrawerRow(
                s.title, sectionIcon(s.id, s.icon), selected = selected == screen && !state.canGoUp,
                needs = s.needsYou, count = s.count,
            ) { navigator.selectTab(screen); close() }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DrawerRow("New session", Icons.Default.Add, selected = state.top is Screen.NewSession) {
            nav.go(Screen.NewSession(mode.id)); close()
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${mode.title} sessions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { navigator.selectTab(Screen.Sessions(mode.id)); close() }) {
                Text(if (more > 0) "All · ${sessions.size + more}" else "All")
            }
        }
        when {
            loaded.value == null && loaded.loading -> Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading sessions…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
            loaded.value == null && loaded.error != null -> Text(loaded.error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            loaded.value != null && sessions.isEmpty() -> Text("No ${mode.title} sessions yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        }
        sessions.forEach { s ->
            val open = (state.top as? Screen.SessionChat)?.id == s.id
            DrawerSessionRow(s, open) { openSession(app, ui, nav, s); close() }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        ScopePicker { navigator.go(it); close() }
        MacPicker { navigator.go(it); close() }
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedContainerColor = Color.Transparent,
)

@Composable
private fun DrawerRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, needs: Int = 0, count: Int? = null, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(title, style = MaterialTheme.typography.labelLarge) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        badge = {
            when {
                needs > 0 -> Badge(containerColor = extras.needsYou.dot, contentColor = Color.White) { Text("$needs") }
                (count ?: 0) > 0 -> Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = drawerItemColors(),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/** A session in the drawer: its title (two lines at most, then the rest on its screen) and what it is doing. */
@Composable
private fun DrawerSessionRow(s: SessionInfo, open: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = if (s.needsYou) FontWeight.SemiBold else null)
                val status = when {
                    s.needsYou -> SessionGroups.stateTitle(s.state.ifBlank { "waiting" })
                    s.busy || s.state == "working" -> "Working"
                    else -> ""
                }
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.labelSmall,
                        color = if (s.needsYou) extras.needsYou.dot else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        selected = open,
        onClick = onClick,
        icon = {
            when {
                s.needsYou -> Surface(shape = CircleShape, color = extras.needsYou.dot, modifier = Modifier.size(10.dp)) {}
                s.busy || s.state == "working" -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        colors = drawerItemColors(),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = s.title + if (s.needsYou) ", needs you" else "" },
    )
}

/** Plan | Build at the top of the drawer, as at the top of the Mac's window, each with how many wait for you. */
@Composable
private fun DrawerModeSwitch(mode: Mode, needs: Map<Mode, Int>, onPick: (Mode) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Mode.entries.forEach { m ->
                val on = m == mode
                val bg by animateColorAsState(if (on) cs.primary else Color.Transparent, label = "modeBg")
                val fg by animateColorAsState(if (on) cs.onPrimary else cs.onSurfaceVariant, label = "modeFg")
                val n = if (on) 0 else needs[m] ?: 0
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(bg)
                        .selectable(on, role = Role.Tab) { onPick(m) }
                        .semantics { contentDescription = m.title + if (n > 0) ", $n need you" else "" }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(if (m == Mode.PLAN) Icons.Default.Map else Icons.Default.Code, contentDescription = null, tint = fg,
                        modifier = Modifier.size(18.dp))
                    Text(m.title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp))
                    if (n > 0) Badge(Modifier.padding(start = 6.dp), containerColor = extras.needsYou.dot, contentColor = Color.White) { Text("$n") }
                }
            }
        }
    }
}
