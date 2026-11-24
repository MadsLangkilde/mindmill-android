package dk.langkilde.mindmill.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(nav: Nav, mode: Mode) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var showOlder by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { app.events.collect { if (it.name == "session.changed") tick++ } }
    val (state, retry) = rememberLoad(rememberCurrentScope(), trigger = tick) {
        app.call("sessions.list", jsonOf("archived" to true, "scope" to if (mode == Mode.PLAN) app.currentScope else null))
    }
    val fromMac = state.value?.objects("sessions")?.map(SessionInfo::parse).orEmpty()
    LaunchedEffect(fromMac) {
        fromMac.forEach { s -> if (SessionArchive.overrides[s.id] == s.archived) SessionArchive.overrides.remove(s.id) }
    }
    val all = SessionGroups.withOverrides(fromMac, SessionArchive.overrides)
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    val scopes = app.connection.hello?.scopes.orEmpty().map { it.id to it.title }
    val grouped = if (mode == Mode.BUILD) SessionGroups.build(all, scopes) else SessionGroups.plan(all)
    val toggle: (SessionInfo) -> Unit = { s -> SessionArchive.toggle(app, scope, s.id, s.title, !s.archived) }
    val open: (SessionInfo) -> Unit = { s -> openSession(app, scope, nav, s) }

    RefreshList(state.refreshing, retry) {
        item(key = "head") {
            ChipFlow(Modifier.padding(top = 4.dp)) {
                Button(onClick = { nav.go(Screen.NewSession(mode.id)) }) { Text("New session") }
            }
            if (fromMac.size > 8 || query.isNotBlank()) {
                SearchField(query, { query = it }, "Search sessions", Modifier.padding(top = 4.dp))
            }
            LoadStatus(state, retry)
            if (state.value != null && grouped.isEmpty) {
                if (query.isNotBlank()) EmptyState("No session matches “$query”.")
                else EmptyState(
                    "No ${mode.title} sessions yet.",
                    detail = if (mode == Mode.PLAN) "Sessions started here, from Research, Ideas or anywhere else in Plan, show here."
                    else "A Build session writes the code; factories start them too.",
                )
            }
        }
        grouped.groups.forEach { g ->
            item(key = "g-" + g.id) {
                SessionGroupHeader(g.title, g.rows.size, g.needsYou)
            }
            items(g.rows, key = { "s-" + it.session.id }) { r ->
                SessionRowView(r.session, r.indent, nav, onOpen = { open(r.session) }, onToggle = toggle)
            }
        }
        if (grouped.older.isNotEmpty()) {
            item(key = "older") { FoldedHeader("Older · ${grouped.older.size}", showOlder, "older sessions") { showOlder = !showOlder } }
            if (showOlder) {
                items(grouped.older, key = { "o-" + it.id }) { s -> SessionRowView(s, 0, nav, onOpen = { open(s) }, onToggle = toggle) }
            }
        }
        if (grouped.archived.isNotEmpty()) {
            item(key = "archived") { FoldedHeader("Archived · ${grouped.archived.size}", showArchived, "archived sessions") { showArchived = !showArchived } }
            if (showArchived) {
                items(grouped.archived, key = { "a-" + it.id }) { s -> SessionRowView(s, 0, nav, onOpen = { open(s) }, onToggle = toggle) }
            }
        }
    }
}

@Composable
private fun FoldedHeader(text: String, open: Boolean, what: String, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Hide $what" else "Show $what")
    }
    HorizontalDivider()
}

/**
 * Opens a session's chat; a conversation that lives elsewhere on the Mac (a meeting's chat, a goal's coach, the Ask
 * window) opens the phone's screen for it, or the Mac.
 */
internal fun openSession(app: dk.langkilde.mindmill.MindmillApp, scope: kotlinx.coroutines.CoroutineScope, nav: Nav, s: SessionInfo) {
    if (!s.isLink) { nav.go(Screen.SessionChat(s.id, s.title, s.archived)); return }
    when (s.linkScreen) {
        "meeting" -> s.linkId?.let { nav.go(Screen.MeetingDetail(it, s.title)) }
        "goals" -> nav.go(NavSection("goals", "Goals").screen(Mode.PLAN))
        else -> scope.launch { app.runAndReport("app.show", jsonOf("screen" to s.linkScreen, "id" to s.linkId)) }
    }
}

/** What a session was started from, opened on the phone. */
private fun originScreen(origin: String?): Screen? {
    val parts = origin?.split('/')?.filter { it.isNotBlank() }.orEmpty()
    return when {
        parts.isEmpty() -> null
        parts[0] == "goals" -> NavSection("goals", "Goals").screen(Mode.PLAN)
        parts.size >= 3 -> Screen.Entry(origin!!)
        else -> null
    }
}

@Composable
private fun SessionGroupHeader(title: String, count: Int, needs: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$title · $count", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
        )
        if (needs > 0) NeedsYouChip(if (needs == 1) "1 needs you" else "$needs need you")
    }
}

@Composable
private fun NeedsYouChip(text: String) = StatusChip(text, Status.NEEDS_YOU)

/** A session in the list: tap opens it; long-press or swipe archives it (or brings it back). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowView(s: SessionInfo, indent: Int, nav: Nav, onOpen: () -> Unit, onToggle: (SessionInfo) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue != SwipeToDismissBoxValue.Settled) {
            onToggle(s)
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    val action = if (s.archived) "Unarchive" else "Archive"
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End) {
                    Icon(if (s.archived) Icons.Default.Unarchive else Icons.Default.Archive, contentDescription = null)
                    Text(action, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
    ) {
        Surface {
            Box {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Session options")
                            .heightIn(min = 56.dp)
                            .padding(start = (4 + indent * 20).dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge)
                            val subtitle = listOf(
                                if (s.needsYou || s.state in setOf("working", "idle")) "" else SessionGroups.stateTitle(s.state).takeIf { s.state.isNotBlank() }.orEmpty(),
                                SessionGroups.originTitle(s.origin).orEmpty(),
                                s.updatedText,
                                if (s.count > 0) "${s.count} messages" else "",
                            ).filter { it.isNotBlank() }.joinToString(" · ")
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.needsYou) Box(Modifier.padding(top = 4.dp)) { NeedsYouChip(SessionGroups.stateTitle(s.state)) }
                        }
                        if (s.busy || s.state == "working") {
                            StatusChip("Working", Status.RUNNING, Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(action) }, onClick = { menu = false; onToggle(s) })
                    originScreen(s.origin)?.let { o ->
                        DropdownMenuItem(text = { Text("Open where it started") },
                            onClick = { menu = false; nav.go(o) })
                    }
                }
            }
        }
    }
}

@Composable
fun SessionChatScreen(nav: Nav, id: String, title: String) {
    var sessionTitle by remember(id) { mutableStateOf(title) }
    ChatPane(id, emptyText = "No messages yet.", onTitle = { sessionTitle = it }) { _ ->
        Column {
            Text(sessionTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
