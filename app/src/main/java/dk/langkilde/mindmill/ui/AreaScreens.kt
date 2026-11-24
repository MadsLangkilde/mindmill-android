package dk.langkilde.mindmill.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dk.langkilde.mindmill.MindmillApp
import dk.langkilde.mindmill.remote.ActionInfo
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.RemoteJson
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Runs the `kb` command on the Mac (`kb.run`): its output. */
suspend fun MindmillApp.kb(vararg args: String, timeoutMs: Long = 90_000): String =
    call("kb.run", jsonOf("args" to args.toList()), timeoutMs).string("output").orEmpty()

/** `kb … --json`, parsed. */
suspend fun MindmillApp.kbJson(vararg args: String): JsonElement {
    val out = kb(*args)
    return runCatching { RemoteJson.parseToJsonElement(out) }.getOrElse {
        throw RemoteException("failed", out.lines().firstOrNull { it.isNotBlank() } ?: "The Mac sent no data.")
    }
}

/** Whether the Mac offers [name] in `actions.list`. */
suspend fun MindmillApp.hasAction(name: String): Boolean = runCatching { actionCatalog().first.any { it.name == name } }.getOrDefault(false)

/**
 * The `data` of a read action, or what [fallback] gets when the Mac doesn't offer that action (an older Mindmill).
 * A failed action throws its sentence.
 */
suspend fun MindmillApp.readAction(name: String, params: JsonObject = jsonOf(), fallback: (suspend () -> JsonElement?)? = null): JsonElement? {
    if (fallback != null && !hasAction(name)) return fallback()
    val o = runAction(name, params)
    if (o.status == dk.langkilde.mindmill.remote.ActionOutcome.Status.FAILED) throw RemoteException("failed", o.speech)
    return o.dataElement ?: JsonObject(emptyMap())
}

/** Actions of the Mac whose name starts with [prefix], for buttons on a dedicated screen. */
@Composable
fun RelatedActions(nav: Nav, prefix: String, defaults: Map<String, String> = emptyMap(), title: String = "More you can do") {
    val app = LocalApp.current
    val (state, _) = rememberLoad(prefix) { app.actionCatalog().first.filter { it.name.startsWith(prefix) && !it.name.endsWith(".get") && !it.name.endsWith(".list") } }
    val list = state.value.orEmpty()
    if (list.isEmpty()) return
    SectionTitle(title)
    ChipFlow {
        list.forEach { a: ActionInfo ->
            val keys = a.params.map { it.name }.toSet()
            OutlinedButton(onClick = { nav.go(Screen.RunAction(a.name, defaults.filterKeys { it in keys })) }) { Text(a.title) }
        }
    }
}

private val knowledgeSections = listOf(
    "facts" to "What's true about the project, confirmed",
    "decisions" to "What was decided, and why",
    "architecture" to "How it is built",
    "ideas" to "Ideas being explored",
    "research" to "Research and findings",
    "references" to "Links and documents",
    "discussions" to "Open email threads and chats",
    "meetings" to "Meeting notes",
    "followups" to "Actions and questions",
)

@Composable
fun KnowledgeScreen(nav: Nav) {
    val app = LocalApp.current
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    val (found, retry) = rememberLoad(searched, rememberCurrentScope()) {
        if (searched.isBlank()) null else EntrySummary.list(app.call("entries.search", jsonOf("query" to searched, "scope" to app.currentScope)))
    }
    val sections = app.connection.hello?.sections.orEmpty().ifEmpty { knowledgeSections.map { it.first } }
    RefreshList(found.refreshing, retry) {
        item {
            OutlinedTextField(
                query, { query = it }, label = { Text("Search everything") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                trailingIcon = { TextButton(enabled = query.isNotBlank(), onClick = { searched = query.trim() }) { Text("Search") } },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { searched = query.trim() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            )
            ClearOnBack(query.isNotEmpty() || searched.isNotEmpty()) { query = ""; searched = "" }
        }
        if (searched.isNotBlank()) {
            item {
                SectionTitle("Found for “$searched”")
                LoadStatus(found, retry)
                if (found.value?.isEmpty() == true) EmptyState("Nothing found.", detail = "Try other words, or ask Mindmill.")
            }
            items(found.value.orEmpty(), key = { "f-" + it.id }) { e ->
                ListRow(e.title, subtitle = listOf(sectionTitle(e.section), e.summary).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = { nav.go(Screen.Entry(e.id)) })
            }
        }
        item { SectionTitle("Sections") }
        items(knowledgeSections.filter { it.first in sections }, key = { it.first }) { (s, about) ->
            ListRow(sectionTitle(s), subtitle = about, onClick = {
                when (s) {
                    "discussions" -> nav.go(Screen.Discussions)
                    "meetings" -> nav.go(Screen.Meetings)
                    "followups" -> nav.go(Screen.Followups())
                    else -> nav.go(Screen.Facts(section = s))
                }
            })
        }
        item {
            ListRow("Tags", subtitle = "Everything with a tag", onClick = { nav.go(Screen.Tags) })
            ListRow("People", subtitle = "Colleagues and people added by name", onClick = { nav.go(Screen.People) })
        }
    }
}

@Composable
fun NewEntryScreen(nav: Nav, section: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text("In ${sectionTitle(section)}, in the current project.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Title *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(summary, { summary = it }, label = { Text("Summary") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(tags, { tags = it }, label = { Text("Tags, separated by commas") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(body, { body = it }, label = { Text("Text") }, minLines = 6, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        problem?.let { ErrorState(it, onRetry = null, compact = true) }
        ChipFlow(Modifier.padding(top = 12.dp)) {
            Button(enabled = !busy && title.isNotBlank(), onClick = {
                busy = true
                problem = null
                scope.launch {
                    try {
                        val r = app.call(
                            "entry.new",
                            jsonOf(
                                "scope" to app.currentScope, "section" to section, "title" to title.trim(),
                                "summary" to summary.trim().ifBlank { null },
                                "tags" to tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
                                "body" to body.ifBlank { null },
                            ),
                        )
                        app.done("Added “${title.trim()}”.")
                        val id = r.text("id")
                        if (!id.isNullOrBlank()) nav.replace(Screen.Entry(id)) else nav.back()
                    } catch (e: Exception) {
                        problem = e.userMessage()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Adding…" else "Add") }
            TextButton(onClick = { nav.back() }) { Text("Cancel") }
        }
    }
}

@Composable
fun PullRequestsScreen(nav: Nav) {
    val app = LocalApp.current
    val (state, retry) = rememberLoad { app.readAction("prs.list") { app.kbJson("pr", "list", "--json") } as? JsonObject }
    val prs = state.value?.objects("prs").orEmpty()
    val closed = state.value?.objects("closed").orEmpty()
    // An older Mac has no prs.reviews: the section is left out.
    val (reviewState, reloadReviews) = rememberLoad { app.readAction("prs.reviews") { null } as? JsonObject }
    val reviews = reviewState.value?.objects("reviews").orEmpty()
    val scope = rememberCoroutineScope()
    var starting by remember { mutableStateOf<String?>(null) }
    RefreshList(state.refreshing || reviewState.refreshing, { retry(); reloadReviews() }) {
        if (reviews.isNotEmpty()) {
            item { SectionTitle("To review (${reviews.size})") }
            items(reviews, key = { "rv-" + it.text("id") }) { r ->
                val id = r.text("id").orEmpty()
                val session = r.string("session")
                ListRow(
                    "!$id ${r.string("title").orEmpty()}",
                    subtitle = listOfNotNull(
                        r.string("author")?.takeIf { it.isNotBlank() }?.let { "by $it" }, r.string("repo"),
                        if (r.bool("draft") == true) "draft" else null,
                        if (r.bool("required") == true) "you're a required reviewer" else null,
                        when {
                            starting == id -> "starting a review…"
                            session != null -> "review session started · tap to open"
                            else -> "tap to review it with Mindmill"
                        },
                    ).joinToString(" · "),
                    onClick = {
                        if (session != null) {
                            nav.go(Screen.SessionChat(session, "Review !$id ${r.string("title").orEmpty()}"))
                        } else if (starting == null) {
                            starting = id
                            scope.launch {
                                val o = app.runAndReport("pr.review", jsonOf("id" to (id.toIntOrNull() ?: id)))
                                starting = null
                                o?.data?.string("session")?.let { nav.go(Screen.SessionChat(it, "Review !$id ${r.string("title").orEmpty()}")) }
                            }
                        }
                    },
                )
            }
            item { SectionTitle("Yours") }
        }
        item {
            Text("Your pull requests in Azure DevOps, with what blocks completing them.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            LoadStatus(state, retry)
            if (state.value != null && prs.isEmpty()) EmptyState("You have no active pull requests.")
        }
        items(prs, key = { "pr-" + it.text("id") }) { pr ->
            val blockers = pr.strings("blockers")
            ListRow(
                "!${pr.text("id")} ${pr.string("title").orEmpty()}",
                subtitle = listOfNotNull(
                    pr.string("repo"),
                    if (pr.bool("draft") == true) "draft" else null,
                    (pr.int("open_threads") ?: pr.int("openThreads"))?.takeIf { it > 0 }?.let { "$it open comments" },
                    if (blockers.isEmpty()) "ready to complete" else blockers.joinToString("; "),
                ).joinToString(" · "),
                onClick = { nav.go(Screen.PullRequest(pr.text("id").orEmpty(), pr.string("title").orEmpty())) },
            )
        }
        if (closed.isNotEmpty()) {
            item { SectionTitle("Recently closed") }
            items(closed, key = { "c-" + it.text("id") }) { pr ->
                ListRow("!${pr.text("id")} ${pr.string("title").orEmpty()}",
                    subtitle = listOfNotNull(pr.string("repo"), pr.string("status")).joinToString(" · "))
            }
        }
        item { RelatedActions(nav, "pr.") }
    }
}

@Composable
fun PullRequestScreen(nav: Nav, id: String, title: String) {
    val app = LocalApp.current
    val context = LocalContext.current
    val (state, retry) = rememberLoad(id) {
        app.readAction("pr.get", jsonOf("id" to (id.toIntOrNull() ?: id))) { app.kbJson("pr", "show", id, "--json") }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 6.dp))
        LoadStatus(state, retry, skeletonRows = 5)
        val pr = state.value
        val url = (pr as? JsonObject)?.string("url") ?: ((pr as? JsonObject)?.get("pr") as? JsonObject)?.string("url")
        ChipFlow {
            if (url != null) OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) { Text("Open in browser") }
        }
        RelatedActions(nav, "pr.", mapOf("id" to id, "pr" to id))
        val o = pr as? JsonObject
        if (o != null) {
            val blockers = o.strings("blockers")
            SectionCard("Status") {
                Text(
                    listOfNotNull(
                        o.string("repo"), o.string("source")?.let { s -> "$s → ${o.string("target").orEmpty()}" },
                        if (o.bool("draft") == true) "draft" else null,
                        if (blockers.isEmpty()) "ready to complete" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                blockers.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                o.objects("reviewers").takeIf { it.isNotEmpty() }?.let { rs ->
                    Text("Reviewers: " + rs.joinToString(", ") { r -> "${r.string("name").orEmpty()} (${r.string("vote_name") ?: "no vote"})" },
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            o.string("description")?.takeIf { it.isNotBlank() }?.let { d ->
                SectionCard("Description") { Text(markdownish(d), style = MaterialTheme.typography.bodyMedium) }
            }
            val threads = o.objects("threads")
            if (threads.isNotEmpty()) SectionTitle("Comments (${threads.size})")
            threads.forEach { t ->
                SectionCard(listOfNotNull(t.string("file")?.takeIf { it.isNotBlank() }?.let { f -> f + (t.text("line")?.let { ":$it" } ?: "") },
                    t.string("status")).joinToString(" · ").ifBlank { null }) {
                    t.objects("comments").forEach { c ->
                        Text(c.string("author").orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(markdownish(c.string("content").orEmpty()), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
            if (threads.isEmpty() && o.string("description") == null) DataBlocksView(o, nav)
        } else if (pr != null) DataBlocksView(pr, nav)
    }
}

@Composable
fun DiscussionsScreen(nav: Nav) {
    val app = LocalApp.current
    var all by rememberSaveable { mutableStateOf(false) }
    val (state, retry) = rememberLoad(rememberCurrentScope(), all) {
        val args = buildList {
            add("discussion"); add("list")
            app.currentScope?.let { add("--project"); add(it) }
            if (all) add("--all")
            add("--json")
        }
        (app.kbJson(*args.toTypedArray()) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    }
    RefreshList(state.refreshing, retry) {
        item {
            ChipFlow(Modifier.padding(top = 4.dp)) {
                Choice("Open", !all) { all = false }
                Choice("All", all) { all = true }
            }
            LoadStatus(state, retry)
            if (state.value?.isEmpty() == true) EmptyState(if (all) "No discussions." else "No open discussions.", detail = "Email threads and chats you follow show here.")
        }
        items(state.value.orEmpty(), key = { it.text("id").orEmpty() }) { d ->
            ListRow(
                d.string("title").orEmpty(),
                subtitle = listOfNotNull(
                    d.string("status"), d.string("waiting_on")?.takeIf { it.isNotBlank() }?.let { "waiting on $it" },
                    d.string("updated"), d.string("channel")?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                onClick = { nav.go(Screen.Entry(d.text("id").orEmpty())) },
            )
        }
        item { RelatedActions(nav, "discussion.") }
    }
}

@Composable
fun PeopleScreen() {
    val app = LocalApp.current
    var query by rememberSaveable { mutableStateOf("") }
    val (state, retry) = rememberLoad {
        when (val d = app.readAction("people.list") { app.kbJson("people", "list", "--json") }) {
            is JsonArray -> d.filterIsInstance<JsonObject>()
            is JsonObject -> d.objects("people")
            else -> emptyList()
        }
    }
    val shown = state.value.orEmpty().filter { p ->
        query.isBlank() || listOf(p.string("name"), p.string("email"), p.string("initials")).any { it?.contains(query.trim(), true) == true }
    }
    RefreshList(state.refreshing, retry) {
        item {
            SearchField(query, { query = it }, "Find someone", Modifier.padding(top = 4.dp))
            LoadStatus(state, retry)
            if (state.value != null && shown.isEmpty()) EmptyState(if (query.isBlank()) "No people yet." else "No one matches “$query”.")
        }
        items(shown) { p ->
            ListRow(
                p.string("name").orEmpty(),
                subtitle = listOfNotNull(p.string("initials"), p.string("email")?.takeIf { it.isNotBlank() },
                    if (p.bool("known") == true) "added by you" else null).joinToString(" · "),
            )
        }
    }
}

@Composable
fun TagsScreen(nav: Nav) {
    val app = LocalApp.current
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val (state, retry) = rememberLoad(rememberCurrentScope()) {
        val sections = listOf("facts", "decisions", "architecture", "ideas", "research", "references", "discussions", "meetings", "followups")
            .filter { it in app.connection.hello?.sections.orEmpty().ifEmpty { listOf(it) } }
        sections.flatMap { s ->
            runCatching { EntrySummary.list(app.call("entries.list", jsonOf("scope" to app.currentScope, "section" to s, "limit" to 500))) }
                .getOrDefault(emptyList())
        }
    }
    val entries = state.value.orEmpty()
    val tags = entries.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key }))
    RefreshList(state.refreshing, retry) {
        item {
            LoadStatus(state, retry)
            if (state.value != null && tags.isEmpty()) EmptyState("Nothing in this project has a tag yet.")
            ChipFlow(Modifier.padding(vertical = 4.dp)) {
                tags.forEach { (t, n) -> Choice("#$t ($n)", picked == t) { picked = if (picked == t) null else t } }
            }
            picked?.let { SectionTitle("Tagged #$it") }
        }
        val p = picked
        if (p != null) {
            items(entries.filter { p in it.tags }, key = { it.id }) { e ->
                ListRow(e.title, subtitle = listOf(sectionTitle(e.section), e.summary).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = { nav.go(Screen.Entry(e.id)) })
            }
        }
    }
}

/**
 * What a read action of the Mac says (reminders, goals, today's plan…), shown as rows that open what they are, with
 * the area's other actions below. On a Mac without the action, the `kb` command's output.
 */
@Composable
fun ActionViewScreen(nav: Nav, action: String, area: String, fallbackArgs: List<String>, about: String) {
    val app = LocalApp.current
    val (state, retry) = rememberLoad(action, rememberCurrentScope()) {
        if (app.hasAction(action)) app.runAction(action, jsonOf()) to null
        else null to app.kb(*fallbackArgs.toTypedArray())
    }
    RefreshList(state.refreshing, retry) {
        item {
            Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
            LoadStatus(state, retry)
            val (outcome, text) = state.value ?: (null to null)
            if (outcome != null) {
                if (outcome.status == dk.langkilde.mindmill.remote.ActionOutcome.Status.FAILED) ErrorState(outcome.speech, retry, compact = true)
                else {
                    if (outcome.speech.isNotBlank()) Text(outcome.speech, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                    DataBlocksView(outcome.dataElement, nav)
                }
            }
            if (text != null) {
                if (text.isBlank()) EmptyState("Nothing here.")
                else SectionCard { SelectionContainer { Text(text.trimEnd(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
            }
            ChipFlow(Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = { nav.go(Screen.AllActions(area)) }) { Text("More in $area") }
            }
        }
    }
}

/** The output of a `kb` command on the Mac, for areas that have no screen of their own yet. */
@Composable
fun CommandScreen(args: List<String>, about: String) {
    val app = LocalApp.current
    val (state, retry) = rememberLoad(args) { app.kb(*args.toTypedArray()) }
    RefreshList(state.refreshing, retry) {
        item {
            Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
            LoadStatus(state, retry)
            state.value?.let { out ->
                if (out.isBlank()) EmptyState("Nothing here.")
                else SectionCard {
                    SelectionContainer { Text(out.trimEnd(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
