package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun FactsScreen(nav: Nav, startProposed: Boolean, startSection: String = "facts") {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var proposed by rememberSaveable { mutableStateOf(startProposed) }
    var section by rememberSaveable { mutableStateOf(startSection) }
    var query by rememberSaveable { mutableStateOf("") }
    val (state, retry) = rememberLoad(rememberCurrentScope(), proposed, section, query) {
        if (proposed) app.call("facts.proposed", jsonOf("scope" to app.currentScope))
        else app.call(
            "entries.list",
            jsonOf("scope" to app.currentScope, "section" to section, "query" to query.trim().ifBlank { null }, "limit" to 200),
        )
    }
    val entries = state.value?.let { EntrySummary.list(it) }.orEmpty()
    var deciding by remember { mutableStateOf(setOf<String>()) }

    fun decide(e: EntrySummary, approve: Boolean) {
        deciding = deciding + e.id
        scope.launch {
            try {
                app.call(if (approve) "inbox.approve" else "inbox.reject", jsonOf("ids" to listOf(e.id)))
                app.done(if (approve) "Approved “${e.title}”." else "Declined “${e.title}”.")
            } catch (ex: Exception) {
                app.done(ex.userMessage(), error = true)
            }
            deciding = deciding - e.id
            retry()
        }
    }

    RefreshList(state.refreshing, retry) {
        item {
            ChipFlow(Modifier.padding(top = 4.dp)) {
                Choice(sectionTitle(section), !proposed) { proposed = false }
                if (section == "facts") Choice("Proposed", proposed) { proposed = true }
            }
            if (!proposed) {
                SearchField(query, { query = it }, "Search ${sectionTitle(section).lowercase()}", Modifier.padding(top = 4.dp))
                ChipFlow(Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { nav.go(Screen.NewEntry(section)) }) { Text("New") }
                    TextButton(onClick = { nav.go(Screen.Knowledge) }) { Text("Other sections") }
                }
            }
            LoadStatus(state, retry)
            if (state.value != null && entries.isEmpty()) {
                if (proposed) EmptyState("No proposed facts are waiting.", detail = "Sessions and meetings propose facts; they show here for your yes or no.")
                else if (query.isNotBlank()) EmptyState("Nothing matches “$query”.")
                else EmptyState("Nothing here yet.", "Add one", { nav.go(Screen.NewEntry(section)) })
            }
        }
        items(entries, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth()) {
                ListRow(e.title, subtitle = listOf(e.summary, e.date).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = { nav.go(Screen.Entry(e.id)) })
                if (proposed) {
                    e.field("reason")?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall) }
                    ChipFlow(Modifier.padding(bottom = 8.dp)) {
                        val busy = e.id in deciding
                        Button(enabled = !busy, onClick = { decide(e, true) }) { Text("Approve") }
                        OutlinedButton(enabled = !busy, onClick = { decide(e, false) }) { Text("Decline") }
                    }
                }
            }
        }
    }
}

private fun JsonObject.fm(key: String): String = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")
    is JsonPrimitive -> if (v is JsonNull) "" else v.content
    else -> ""
}

@Composable
fun EntryScreen(nav: Nav, id: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val (state, retry) = rememberLoad(id) { app.call("entry.get", jsonOf("id" to id)) }
    val doc = state.value
    val fm = doc?.obj("frontmatter") ?: JsonObject(emptyMap())
    var title by rememberSaveable(doc) { mutableStateOf(fm.fm("title")) }
    var summary by rememberSaveable(doc) { mutableStateOf(fm.fm("summary")) }
    var tags by rememberSaveable(doc) { mutableStateOf(fm.fm("tags")) }
    var body by rememberSaveable(doc) { mutableStateOf(doc?.string("body").orEmpty()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LoadStatus(state, retry, skeletonRows = 6)
        if (doc == null) return@Column
        if (!editing) {
            Text(fm.fm("title").ifBlank { id }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 6.dp))
            if (fm.fm("summary").isNotBlank()) Text(fm.fm("summary"), style = MaterialTheme.typography.bodyLarge)
            ChipFlow(Modifier.padding(vertical = 8.dp)) {
                listOf("status", "kind", "owner", "due", "waiting_on", "date").mapNotNull { k ->
                    fm.fm(k).takeIf { it.isNotBlank() }?.let { "${k.replace('_', ' ')}: $it" }
                }.forEach { InfoChip(it) }
                fm.strings("tags").forEach { InfoChip("#$it") }
            }
            OutlinedButton(onClick = { editing = true }) { Text("Edit") }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(markdownish(doc.string("body").orEmpty()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            val linked = doc.obj("extra")?.let { EntrySummary.list(it, "linked") }.orEmpty()
            if (linked.isNotEmpty()) {
                SectionTitle("Linked facts")
                linked.forEach { l -> ListRow(l.title, subtitle = l.summary, onClick = { nav.go(Screen.Entry(l.id)) }) }
            }
        } else {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(summary, { summary = it }, label = { Text("Summary") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags, separated by commas") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(body, { body = it }, label = { Text("Text") }, minLines = 8, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            ChipFlow(Modifier.padding(vertical = 8.dp)) {
                Button(enabled = !saving, onClick = {
                    val changed = mutableMapOf<String, Any?>()
                    if (title != fm.fm("title")) changed["title"] = title.trim()
                    if (summary != fm.fm("summary")) changed["summary"] = summary.trim()
                    if (tags != fm.fm("tags")) changed["tags"] = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val newBody = if (body != doc.string("body").orEmpty()) body else null
                    saving = true
                    scope.launch {
                        try {
                            app.call("entry.save", jsonOf("id" to id, "frontmatter" to changed, "body" to newBody))
                            editing = false
                            retry()
                            app.done("Saved.")
                        } catch (e: Exception) {
                            app.done("Not saved: ${e.userMessage()}", error = true)
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun FollowupsScreen(startFilter: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(startFilter) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val (state, retry) = rememberLoad(rememberCurrentScope(), filter) {
        app.runAction("followups.list", jsonOf("filter" to filter, "scope" to app.currentScope))
    }
    val entries = state.value?.let { EntrySummary.list(it.data) }.orEmpty()

    fun update(params: JsonObject) {
        scope.launch {
            app.runAndReport("followup.update", params)
            retry()
        }
    }

    RefreshList(state.refreshing, retry) {
        item {
            ChipFlow(Modifier.padding(top = 4.dp)) {
                Choice("On me", filter == "mine") { filter = "mine" }
                Choice("Waiting on others", filter == "waiting") { filter = "waiting" }
                Choice("All open", filter == "all") { filter = "all" }
            }
            if (creating) {
                NewFollowup(onDone = { creating = false; retry() }, onCancel = { creating = false })
            } else {
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.padding(top = 4.dp)) { Text("New follow-up") }
            }
            LoadStatus(state, retry)
            if (state.value != null && entries.isEmpty()) {
                EmptyState(if (filter == "waiting") "Nothing is waiting on others." else "No open follow-ups.",
                    if (!creating) "New follow-up" else null, { creating = true })
            }
        }
        items(entries, key = { it.id }) { e -> FollowupRow(e, ::update) }
    }
}

@Composable
private fun FollowupRow(e: EntrySummary, update: (JsonObject) -> Unit) {
    var noting by remember { mutableStateOf(false) }
    var note by rememberSaveable(e.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(e.title, style = MaterialTheme.typography.bodyLarge)
        val meta = listOfNotNull(
            e.field("owner")?.let { "Owner: $it" }, e.field("waiting_on")?.let { "Waiting on: $it" },
            e.field("due")?.let { "Due $it" }, e.field("kind"),
        )
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        ChipFlow(Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { update(jsonOf("id" to e.id, "status" to "done")) }) { Text("Done") }
            OutlinedButton(onClick = { update(jsonOf("id" to e.id, "status" to "dropped")) }) { Text("Drop") }
            TextButton(onClick = { noting = !noting }) { Text("Note") }
        }
        if (noting) {
            OutlinedTextField(note, { note = it }, label = { Text("A line under Updates") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { update(jsonOf("id" to e.id, "note" to note.trim())); note = ""; noting = false }, enabled = note.isNotBlank()) {
                Text("Add note")
            }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun NewFollowup(onDone: () -> Unit, onCancel: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("action") }
    var owner by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("What needs doing or answering") }, modifier = Modifier.fillMaxWidth())
        ChipFlow(Modifier.padding(top = 4.dp)) {
            Choice("Action", kind == "action") { kind = "action" }
            Choice("Question", kind == "question") { kind = "question" }
        }
        MoreOptions {
            OutlinedTextField(owner, { owner = it }, label = { Text("Owner (leave empty for you)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(due, { due = it }, label = { Text("Due (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        ChipFlow {
            Button(enabled = title.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val o = app.runAndReport(
                        "followup.create",
                        jsonOf("title" to title.trim(), "kind" to kind, "owner" to owner.trim().ifBlank { null },
                            "due" to due.trim().ifBlank { null }, "scope" to app.currentScope),
                    )
                    busy = false
                    if (o?.ok == true) onDone()
                }
            }) { Text("Add") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private data class MoreRow(val title: String, val subtitle: String, val icon: String, val screen: Screen)

/** The phone's More page: what's everywhere, whichever mode, each group on a card, then every area of the Mac. */
@Composable
fun MoreScreen(nav: Nav) {
    val ctx = LocalNavContext.current
    val shown = DrawerModel.destinations(ctx.sections).map { it.screen(ctx.mode, ctx.kbSections) }.toSet()
    val groups = listOf(
        "Ask and do" to listOf(
            MoreRow("Ask Mindmill", "Ask for anything in your own words, typed or said", "assistant", Screen.NewSession(ctx.mode.id)),
            MoreRow("Voice", "Talk to Mindmill, hands free", "voice", Screen.Voice),
            MoreRow("All actions", "Everything the Mac can do, by area, with a form for each", "allActions", Screen.AllActions()),
            MoreRow("Strategies", "How Build sessions work", "strategies",
                Screen.ActionView("Strategies", "strategies.list", "Strategies", listOf("strategy", "list"), "The strategies Build sessions and factories can use.")),
        ),
        "Knowledge" to listOf(
            MoreRow("Search knowledge", "Every section, and search across them", "knowledge", Screen.Knowledge),
            MoreRow("Proposed facts", "New facts to approve", "facts", Screen.Facts(proposed = true)),
            MoreRow("Tags", "Everything with a tag", "tags", Screen.Tags),
            MoreRow("People", "Colleagues and people added by name", "people", Screen.People),
        ),
        "Set-up" to listOf(
            MoreRow("Projects", "The Mac's projects: their folders and scopes", "projects", Screen.Projects),
            MoreRow("Connection", "Your Macs, switching between them, voice options, colours", "connection", Screen.Connection),
        ),
    ).map { (t, rows) -> t to rows.filter { it.screen !in shown } }.filter { it.second.isNotEmpty() }
    val app = LocalApp.current
    val (catalog, _) = rememberLoad { app.actionCatalog() }
    val areas = catalog.value?.let { (actions, order) -> dk.langkilde.mindmill.remote.ActionInfo.grouped(actions, order) }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        groups.forEach { (title, rows) ->
            item(key = "group-$title") {
                SectionTitle(title)
                GroupCard {
                    rows.forEachIndexed { i, r ->
                        ListRow(
                            r.title, subtitle = r.subtitle, onClick = { nav.go(r.screen) },
                            leading = { IconBadge(sectionIcon(r.icon), size = 36.dp) },
                            divider = i < rows.lastIndex,
                        )
                    }
                }
            }
        }
        if (areas.isNotEmpty()) {
            item(key = "group-areas") {
                SectionTitle("Every area of the Mac")
                GroupCard {
                    areas.forEachIndexed { i, (area, list) ->
                        ListRow(
                            dk.langkilde.mindmill.remote.ActionInfo.areaTitle(area),
                            subtitle = list.take(4).joinToString(", ") { it.title.lowercase() } + if (list.size > 4) "…" else "",
                            onClick = { nav.go(Screen.AllActions(area)) },
                            divider = i < areas.lastIndex,
                            trailing = { CountBadge(list.size) },
                        )
                    }
                }
            }
        }
    }
}

fun JsonObject.idText() = text("id").orEmpty()
