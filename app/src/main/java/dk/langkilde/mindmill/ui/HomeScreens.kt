package dk.langkilde.mindmill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.ActionOutcome
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One count on the dashboard: what it is, how many, where it leads, and how urgent it is. */
private data class Stat(val label: String, val count: Int, val icon: ImageVector, val status: Status, val screen: Screen)

/**
 * Plan's dashboard: a greeting with the day in a sentence, Ask Mindmill, quick actions, what needs you as tiles,
 * today's meetings, then sessions that wait or work.
 */
@Composable
fun HomeScreen(nav: Nav) {
    val app = LocalApp.current
    val cards by app.reviewCards.collectAsState()
    val (state, retry) = rememberLoad(rememberCurrentScope()) { app.runAction("overview", jsonOf("scope" to app.currentScope)) }
    val (meetings, retryMeetings) = rememberLoad(rememberCurrentScope()) {
        runCatching { app.runAction("calendar.upcoming", jsonOf("days" to 1)) }.getOrNull()?.takeIf { it.ok }
    }
    val (sessions, retrySessions) = rememberSessions()
    val outcome = state.value
    val d = outcome?.data
    fun count(k: String) = d?.int(k) ?: 0
    val stats = buildList {
        if (count("proposedFacts") > 0) add(Stat("Proposed facts", count("proposedFacts"), Icons.Default.Lightbulb, Status.NEEDS_YOU, Screen.Facts(proposed = true)))
        if (count("followupsMine") > 0) add(Stat("Follow-ups on you", count("followupsMine"), Icons.Default.Checklist, Status.NEEDS_YOU, Screen.Followups("mine")))
        if (count("workItems") > 0) add(Stat("Your work items", count("workItems"), Icons.AutoMirrored.Filled.List, Status.NEEDS_YOU, Screen.Backlog))
        if (count("factoryQuestions") > 0) add(Stat("Factory questions", count("factoryQuestions"), Icons.Default.QuestionAnswer, Status.NEEDS_YOU, Screen.Factories))
        if (cards.isNotEmpty()) add(Stat("Ready for review", cards.size, Icons.Default.Science, Status.NEEDS_YOU, Screen.Test))
        if (count("followupsWaiting") > 0) add(Stat("Waiting on others", count("followupsWaiting"), Icons.Default.HourglassTop, Status.IDLE, Screen.Followups("waiting")))
        if (count("factoriesRunning") > 0) add(Stat("Factories running", count("factoriesRunning"), Icons.Default.PrecisionManufacturing, Status.RUNNING, Screen.Factories))
    }
    val waiting = d?.objects("sessionsWaiting")?.map(SessionInfo::parse).orEmpty()
    val running = sessions.value.orEmpty().filter { !it.archived && (it.busy || it.state == "working") }

    RefreshList(state.refreshing || sessions.refreshing, { retry(); retryMeetings(); retrySessions() }) {
        item(key = "hero") {
            Greeting(
                outcome?.takeIf { it.status != ActionOutcome.Status.FAILED && stats.isEmpty() }?.speech,
                waiting.size + stats.filter { it.status == Status.NEEDS_YOU }.sumOf { it.count },
            )
        }
        item(key = "needs") {
            LoadStatus(state, retry, skeletonRows = 2)
            if (outcome?.status == ActionOutcome.Status.FAILED) ErrorState(outcome.speech, retry, compact = true)
            if (outcome != null && outcome.ok && stats.isEmpty() && waiting.isEmpty()) {
                EmptyState("Nothing waits for you right now.", icon = Icons.Default.AutoAwesome)
            }
            if (stats.isNotEmpty()) StatGrid(stats) { nav.go(it) }
        }
        item(key = "ask") { AskCard(nav) }
        item(key = "quick") {
            QuickActions(
                listOf(
                    QuickAction("Talk", Icons.Default.Mic) { nav.go(Screen.Voice) },
                    QuickAction("Meeting on the Mac", Icons.Default.Groups) { nav.go(Screen.MacMeeting) },
                    QuickAction(if (app.meeting.isLive) "Back to the phone meeting" else "Record on this phone", Icons.Default.RecordVoiceOver) { nav.go(Screen.LiveMeeting) },
                    QuickAction("New session", Icons.Default.Add) { nav.go(Screen.NewSession(Mode.PLAN.id)) },
                    QuickAction("All actions", Icons.Default.Bolt) { nav.go(Screen.AllActions()) },
                ),
            )
        }
        meetings.value?.let { m -> meetingsToday(m.data, nav) }
        if (waiting.isNotEmpty()) {
            item(key = "waiting-title") { SectionTitle("Sessions waiting for you") }
            items(waiting, key = { "w-" + it.id }) { s -> SessionLine(s, Status.NEEDS_YOU, nav) }
        }
        if (running.isNotEmpty()) {
            item(key = "running-title") { SectionTitle("Working now") }
            items(running.take(5), key = { "r-" + it.id }) { s -> SessionLine(s, Status.RUNNING, nav) }
        }
    }
}

/** Build's overview, like My Work on the Mac: sessions waiting for you, what's working, your work items. */
@Composable
fun MyWorkScreen(nav: Nav) {
    val app = LocalApp.current
    val (sessions, retrySessions) = rememberSessions()
    val (work, retryWork) = rememberLoad(rememberCurrentScope()) {
        app.call("tracker.items", jsonOf("scope" to app.currentScope, "mine" to true)).objects("items").map(WorkItem::parse)
    }
    val (reviews, retryReviews) = rememberLoad { (app.readAction("prs.reviews") { null } as? kotlinx.serialization.json.JsonObject)?.objects("reviews").orEmpty() }
    val build = sessions.value.orEmpty().filter { it.space == "build" && !it.archived }
    val waiting = build.filter { it.needsYou }
    val running = build.filter { it.busy || it.state == "working" }
    RefreshList(sessions.refreshing || work.refreshing, { retrySessions(); retryWork(); retryReviews() }) {
        item(key = "hero") {
            val loaded = sessions.value != null
            Greeting(
                if (!loaded) null else listOf(
                    when (waiting.size) { 0 -> "No Build session waits for you"; 1 -> "1 session waits for you"; else -> "${waiting.size} sessions wait for you" },
                    when (running.size) { 0 -> "none is working"; 1 -> "1 is working"; else -> "${running.size} are working" },
                ).joinToString(", ") + ".",
                waiting.size,
            )
        }
        item(key = "quick") {
            QuickActions(
                listOf(
                    QuickAction("New session", Icons.Default.Add) { nav.go(Screen.NewSession(Mode.BUILD.id)) },
                    QuickAction("Factories", Icons.Default.PrecisionManufacturing) { nav.go(Screen.Factories) },
                    QuickAction("Test", Icons.Default.Science) { nav.go(Screen.Test) },
                    QuickAction("Pull requests", Icons.AutoMirrored.Filled.MergeType) { nav.go(Screen.PullRequests) },
                ),
            )
        }
        item(key = "stats") {
            if (sessions.value != null) {
                StatGrid(
                    listOf(
                        Stat("Need you", waiting.size, Icons.AutoMirrored.Filled.Chat, if (waiting.isEmpty()) Status.IDLE else Status.NEEDS_YOU, Screen.Sessions(Mode.BUILD.id)),
                        Stat("Working now", running.size, Icons.Default.PrecisionManufacturing, if (running.isEmpty()) Status.IDLE else Status.RUNNING, Screen.Sessions(Mode.BUILD.id)),
                        Stat("Your work items", work.value?.size ?: 0, Icons.AutoMirrored.Filled.List, Status.IDLE, Screen.Backlog),
                        Stat("To review", reviews.value?.size ?: 0, Icons.AutoMirrored.Filled.MergeType,
                            if (reviews.value.isNullOrEmpty()) Status.IDLE else Status.NEEDS_YOU, Screen.PullRequests),
                    ),
                    Modifier.padding(top = 12.dp),
                ) { nav.go(it) }
            }
        }
        item(key = "waiting-title") {
            SectionTitle("Sessions waiting for you")
            LoadStatus(sessions, retrySessions, skeletonRows = 2)
            if (sessions.value != null && waiting.isEmpty()) ListEmptyState("No Build session waits for you.")
        }
        items(waiting, key = { "w-" + it.id }) { s -> SessionLine(s, Status.NEEDS_YOU, nav) }
        if (running.isNotEmpty()) {
            item(key = "running-title") { SectionTitle("Working now") }
            items(running, key = { "r-" + it.id }) { s -> SessionLine(s, Status.RUNNING, nav) }
        }
        item(key = "work-title") {
            SectionTitle("Your work items")
            LoadStatus(work, retryWork, skeletonRows = 3)
            if (work.value?.isEmpty() == true) ListEmptyState("Nothing is assigned to you.")
        }
        items(work.value.orEmpty(), key = { "i-" + it.id }) { w ->
            ListRow(
                "#${w.id} ${w.title}",
                subtitle = listOf(w.type, w.state, w.sprintName).filter { it.isNotBlank() }.joinToString(" · "),
                onClick = { nav.go(Screen.WorkItemDetail(w.raw)) },
            )
        }
    }
}

/** Every session, reloaded when one changes. */
@Composable
private fun rememberSessions(): Pair<Loaded<List<SessionInfo>>, () -> Unit> {
    val app = LocalApp.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { app.events.collect { if (it.name == "session.changed") tick++ } }
    return rememberLoad(trigger = tick) { app.call("sessions.list").objects("sessions").map(SessionInfo::parse) }
}

@Composable
private fun SessionLine(s: SessionInfo, status: Status, nav: Nav) {
    ListRow(
        s.title,
        subtitle = listOf(s.space.replaceFirstChar { it.uppercase() }, s.updatedText).filter { it.isNotBlank() }.joinToString(" · "),
        onClick = { nav.go(Screen.SessionChat(s.id, s.title)) },
        leading = {
            val c = statusColors(status)
            IconBadge(Icons.AutoMirrored.Filled.Chat, c.container, c.content, size = 36.dp)
        },
        below = { StatusChip(if (status == Status.RUNNING) "Working" else SessionGroups.stateTitle(s.state), status) },
    )
}

/** The day at a glance: a greeting in the brand's serif, the date, and the Mac's summary in a sentence. */
@Composable
private fun Greeting(summary: String?, needsYou: Int) {
    val hour = LocalTime.now().hour
    val hello = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        Text(
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
        Text(hello, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 2.dp).semantics { heading() })
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (needsYou > 0) {
            StatusChip(if (needsYou == 1) "1 thing needs you" else "$needsYou things need you", Status.NEEDS_YOU, Modifier.padding(top = 10.dp))
        }
    }
}

/** Ask Mindmill: the brand's navy card with the windmill; white text on it reads at 7:1 or better. */
@Composable
fun AskCard(nav: Nav) {
    val x = extras
    Surface(
        shape = MaterialTheme.shapes.large, color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Ask Mindmill" },
        onClick = { nav.go(Screen.NewSession(Mode.PLAN.id)) },
    ) {
        Box(Modifier.background(x.brand)) {
            Row(Modifier.padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = x.onBrand, modifier = Modifier.size(20.dp))
                        Text("Ask Mindmill", style = MaterialTheme.typography.titleLarge, color = x.onBrand, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "Anything the Mac can do: start a meeting, find a decision, set up a factory. Type or dictate.",
                        style = MaterialTheme.typography.bodyMedium, color = x.onBrand, modifier = Modifier.padding(top = 6.dp),
                    )
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.18f), contentColor = x.onBrand, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Ask anything…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Box(Modifier.padding(start = 12.dp).size(64.dp).background(Color.White.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    BrandMark(Modifier.size(44.dp), mono = true, tint = x.onBrand)
                }
            }
        }
    }
}

private data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Big tonal buttons that wrap whole onto the next line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(actions: List<QuickAction>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { a ->
            FilledTonalButton(
                onClick = a.onClick,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(a.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(a.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Counts as tiles, as many per row as fit the space they get. */
@Composable
private fun StatGrid(stats: List<Stat>, modifier: Modifier = Modifier, open: (Screen) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / (150.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { s -> StatTile(s, Modifier.weight(1f).fillMaxHeight()) { open(s.screen) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StatTile(s: Stat, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = if (s.status == Status.NEEDS_YOU) StatusColors(cs.primaryContainer, cs.onPrimaryContainer, extras.needsYou.dot) else statusColors(s.status)
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.large, color = c.container, contentColor = c.content,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "${s.label}: ${s.count}" },
    ) {
        Column(Modifier.padding(14.dp).heightIn(min = 72.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(s.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).background(c.dot, CircleShape))
            }
            Text("${s.count}", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp))
            Text(s.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Today's meetings on one card, the next one with Start on Mac. */
private fun LazyListScope.meetingsToday(data: JsonObject, nav: Nav) {
    val now = LocalTime.now()
    val events = data.objects("events").map(CalendarEvent::parse)
        .filter { it.status != "cancelled" && it.status != "filed" }
        .filter { ev ->
            ev.status == "live" || ev.date != LocalDate.now().toString() ||
                runCatching { LocalTime.parse(CalendarEvent.clock(ev.end).padStart(5, '0')) }.getOrNull()?.isAfter(now) != false
        }
        .distinctBy { it.title to it.start }
    val nextId = data.obj("next")?.text("id") ?: data.text("next")
    if (events.isEmpty()) return
    item(key = "meetings") {
        val app = LocalApp.current
        val scope = rememberCoroutineScope()
        SectionTitle("Coming up today", trailing = { TextButton(onClick = { nav.go(Screen.MacMeeting) }) { Text("All") } })
        GroupCard {
            events.take(4).forEachIndexed { i, ev ->
                val isNext = ev.id == nextId || ev.status == "live" || (nextId == null && i == 0)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.widthIn(min = 56.dp).padding(end = 12.dp)) {
                        Text(CalendarEvent.clock(ev.start), style = MaterialTheme.typography.titleMedium,
                            color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(CalendarEvent.clock(ev.end), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(ev.title, style = MaterialTheme.typography.bodyLarge)
                        val sub = listOf(if (ev.online) "Online" else "", ev.statusTitle.replaceFirstChar { it.uppercase() }).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ev.status == "live") {
                            Button(onClick = { nav.go(Screen.MacMeeting) }, modifier = Modifier.padding(top = 6.dp)) { Text("Open the live meeting") }
                        } else if (isNext && ev.canStart) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val o = app.runAndReport("meeting.mac.start", jsonOf("event" to ev.id))
                                        if (o?.ok == true) nav.go(Screen.MacMeeting)
                                    }
                                },
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Start on Mac", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (events.size > 4) {
                TextButton(onClick = { nav.go(Screen.MacMeeting) }) { Text("${events.size - 4} more") }
            }
        }
    }
}

@Composable
fun CountBadge(n: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = CircleShape) {
        Text("$n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

