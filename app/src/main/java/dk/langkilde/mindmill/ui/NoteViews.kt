package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.ActionOutcome
import dk.langkilde.mindmill.remote.NoteText
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.Scopes
import dk.langkilde.mindmill.remote.Sharing
import dk.langkilde.mindmill.remote.SharingDefault
import dk.langkilde.mindmill.remote.SharingDefaults
import dk.langkilde.mindmill.remote.Suggestion
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch

/**
 * What a session or an entry suggests adding to the knowledge base, as cards in the layout: where each would go, what
 * it is and why, with Add (`inbox.approve`) and Dismiss (`inbox.reject`), and Add all when there are several.
 * [onChanged] loads again after a decision.
 */
@Composable
fun SuggestionCards(
    suggestions: List<Suggestion>,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Suggested for your knowledge base",
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var gone by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    val shown = suggestions.filter { it.id !in gone }
    if (shown.isEmpty()) return

    fun decide(list: List<Suggestion>, add: Boolean) {
        val ids = list.map { it.id }
        busy = busy + ids
        scope.launch {
            try {
                app.call(if (add) "inbox.approve" else "inbox.reject", jsonOf("ids" to ids))
                gone = gone + ids
                val what = list.singleOrNull()?.let { "“${it.title}”" } ?: "${list.size} suggestions"
                app.done(if (add) "Added $what." else "Dismissed $what.")
            } catch (e: Exception) {
                app.done(e.userMessage(), error = true)
            }
            busy = busy - ids.toSet()
            onChanged()
        }
    }

    Column(modifier.fillMaxWidth()) {
        SectionTitle(title) {
            if (shown.size > 1) TextButton(enabled = shown.none { it.id in busy }, onClick = { decide(shown, true) }) { Text("Add all") }
        }
        shown.forEach { s ->
            SuggestionCard(s, busy = s.id in busy, onAdd = { decide(listOf(s), true) }, onDismiss = { decide(listOf(s), false) })
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, busy: Boolean, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            if (s.section.isNotBlank()) {
                Text("To ${sectionTitle(s.section)}", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            }
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            if (s.summary.isNotBlank()) Text(s.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (s.reason.isNotBlank()) {
                Text(s.reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            ChipFlow(Modifier.padding(top = 4.dp)) {
                FilledTonalButton(enabled = !busy, onClick = onAdd) { Text("Add") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Whether an entry is private or shared, with a lock or people icon and the Mac's words, and the switch to the other
 * when it can change (`entry.share`). Making a shared entry private asks first: it leaves the repository.
 */
@Composable
fun SharingRow(entryId: String, sharing: Sharing, onChanged: (Sharing) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    fun change(shared: Boolean) {
        busy = true
        scope.launch {
            try {
                val r = app.call("entry.share", jsonOf("id" to entryId, "shared" to shared))
                val now = Sharing.parse(r.obj("sharing") ?: r.takeIf { it.containsKey("state") }) ?: sharing.copy(shared = shared)
                onChanged(now)
                app.done(if (shared) "Shared: it goes into the project's next commit." else "Private now.")
            } catch (e: Exception) {
                app.done(e.userMessage(), error = true)
            }
            busy = false
        }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (sharing.shared) Icons.Default.Groups else Icons.Default.Lock, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
        )
        Text(sharing.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (sharing.canChange) {
            TextButton(enabled = !busy, onClick = { if (sharing.shared) confirming = true else change(true) }) {
                Text(if (sharing.shared) "Make private" else "Share")
            }
        }
    }
    if (confirming) {
        ConfirmDialog("Make it private?", sharing.privateQuestion(), "Make private", onConfirm = { change(false) }, onDismiss = { confirming = false })
    }
}

/**
 * A note's own buttons: talk it through out loud (`note.discuss`, then live voice in its session), have it sorted
 * (`note.sort`, then its session), and mark it filed or open again (`note.status`).
 */
@Composable
fun NoteActions(nav: Nav, id: String, title: String, status: String?, onChanged: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val startLive = rememberLiveStarter()
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun session(action: String, spoken: Boolean): String? {
        busy = action
        try {
            val o = app.runAction(action, jsonOf("id" to id, "spoken" to (if (spoken) true else null)))
            val session = o.data.text("session")
            when {
                o.status == ActionOutcome.Status.CANCELLED -> Unit
                o.ok && session != null -> return session
                else -> app.done(o.speech.ifBlank { "That didn't work." }, error = true)
            }
        } catch (e: Exception) {
            app.done(e.userMessage(), error = true)
        } finally {
            busy = null
        }
        return null
    }

    val filed = status == "filed"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ChipFlow {
            if (filed) StatusChip(NoteText.statusTitle(status), Status.DONE) else InfoChip(NoteText.statusTitle(status))
        }
        ChipFlow(Modifier.padding(top = 6.dp)) {
            Button(enabled = busy == null, onClick = {
                startLive {
                    scope.launch {
                        val s = session("note.discuss", spoken = true) ?: return@launch
                        nav.go(Screen.SessionChat(s, title))
                        app.startLive(s)
                    }
                }
            }) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(if (busy == "note.discuss") "Starting…" else "Talk it through")
            }
            OutlinedButton(enabled = busy == null, onClick = {
                scope.launch { session("note.sort", spoken = false)?.let { nav.go(Screen.SessionChat(it, title)) } }
            }) { Text(if (busy == "note.sort") "Sorting…" else "Sort it") }
            TextButton(enabled = busy == null, onClick = {
                busy = "note.status"
                scope.launch {
                    app.runAndReport("note.status", jsonOf("id" to id, "status" to if (filed) "open" else "filed"))
                    busy = null
                    onChanged()
                }
            }) { Text(if (filed) "Open it again" else "Mark filed") }
        }
    }
}

/**
 * Writing a note: just the text, a title if you like. `note.add` makes it; a Mac without that action gets
 * `entry.new` with a title from the first line.
 */
@Composable
fun NewNoteScreen(nav: Nav) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val dictation = rememberDictation { heard -> text = listOf(text.trimEnd(), heard).filter { it.isNotBlank() }.joinToString(if (text.isBlank()) "" else " ") }

    fun save() {
        busy = true
        problem = null
        scope.launch {
            try {
                val body = text.trim()
                val id = if (app.hasAction("note.add")) {
                    val o = app.runAction("note.add", jsonOf("text" to body, "title" to title.trim().ifBlank { null }, "scope" to app.currentScope))
                    when (o.status) {
                        ActionOutcome.Status.FAILED -> throw RemoteException("failed", o.speech.ifBlank { "The note wasn't saved." })
                        ActionOutcome.Status.CANCELLED, ActionOutcome.Status.NEEDS_CONFIRMATION -> return@launch
                        ActionOutcome.Status.DONE -> o.data.text("id")
                    }
                } else {
                    app.call(
                        "entry.new",
                        jsonOf("scope" to app.currentScope, "section" to "notes", "title" to title.trim().ifBlank { NoteText.titleFrom(body) }, "body" to body),
                    ).text("id")
                }
                app.done("Noted.")
                if (!id.isNullOrBlank()) nav.replace(Screen.Entry(id)) else nav.back()
            } catch (e: Exception) {
                problem = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            "Write it down as it comes. Later, talk it through or have it sorted into facts, decisions, ideas and follow-ups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            text, { text = it }, label = { Text("Note") }, minLines = 8,
            supportingText = dictation.partial.takeIf { it.isNotBlank() }?.let { p -> { Text(p) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        problem?.let { ErrorState(it, onRetry = null, compact = true) }
        ChipFlow(Modifier.padding(top = 12.dp)) {
            Button(enabled = !busy && text.isNotBlank(), onClick = ::save) { Text(if (busy) "Saving…" else "Save note") }
            OutlinedButton(onClick = { dictation.toggle() }) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(if (dictation.listening) "Stop dictating" else "Dictate")
            }
            TextButton(onClick = { nav.back() }) { Text("Cancel") }
        }
    }
}

/**
 * Whether each kind of entry starts private or shared in a scope (`sharing.defaults`), changed with
 * `sharing.setDefault`; each change asks whether the entries already there change too.
 */
@Composable
fun SharingDefaultsScreen(startScope: String?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val hello by app.connection.lastHello.collectAsState()
    val current = rememberCurrentScope()
    var scopeId by rememberSaveable { mutableStateOf(startScope ?: current) }
    val (state, retry) = rememberLoad(scopeId) { SharingDefaults.parse(app.call("sharing.defaults", jsonOf("scope" to scopeId))) }
    var changed by remember(state.value) { mutableStateOf(state.value) }
    var asking by remember { mutableStateOf<Pair<SharingDefault, Boolean>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun set(d: SharingDefault, shared: Boolean, applyExisting: Boolean) {
        busy = d.section
        scope.launch {
            try {
                val r = app.call("sharing.setDefault", jsonOf(
                    "scope" to scopeId, "section" to d.section, "visibility" to SharingDefaults.visibility(shared),
                    "applyExisting" to applyExisting,
                ))
                changed = changed?.with(d.section, shared)
                val n = r.int("changed") ?: 0
                app.done("New ${d.title.lowercase()} are ${if (shared) "shared" else "private"} from now on." +
                    if (n > 0) " $n already there changed too." else "")
            } catch (e: Exception) {
                app.done(e.userMessage(), error = true)
            }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            "Private entries stay in your knowledge base on the Mac. Shared ones also get a copy in the project's repository, " +
                "in its next commit and pull request.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        val scopes = hello?.scopes.orEmpty()
        if (scopes.size > 1) {
            ChipFlow(Modifier.padding(top = 4.dp)) {
                scopes.forEach { s -> Choice(Scopes.label(s), s.id == scopeId) { scopeId = s.id } }
            }
        }
        LoadStatus(state, retry)
        val d = changed ?: return@Column
        Text(
            d.repo?.let { "Repository: $it" } ?: "This scope has no repository, so everything stays private.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
        )
        if (d.defaults.isEmpty()) ListEmptyState("The Mac lists no kinds of entries here.")
        GroupCard(Modifier.padding(top = 8.dp)) {
            d.defaults.forEachIndexed { i, row ->
                ListRow(
                    row.title, divider = i < d.defaults.lastIndex,
                    below = {
                        ChipFlow {
                            val can = d.canShare && busy == null
                            FilterChipChoice("Private", !row.shared, can) { if (row.shared) asking = row to false }
                            FilterChipChoice("Shared", row.shared, can) { if (!row.shared) asking = row to true }
                        }
                    },
                )
            }
        }
    }

    asking?.let { (row, shared) ->
        val kind = row.title.lowercase()
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("New $kind ${if (shared) "shared" else "private"}") },
            text = {
                Text(
                    "Also make the $kind already there ${if (shared) "shared" else "private"}?" +
                        if (!shared) " That removes them from ${changed?.repo?.let { "the $it repository" } ?: "the repository"}." else "",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { asking = null; set(row, shared, applyExisting = true) }) { Text("Those too") }
                    TextButton(onClick = { asking = null; set(row, shared, applyExisting = false) }) { Text("Only new ones") }
                }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilterChipChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) =
    androidx.compose.material3.FilterChip(selected = selected, enabled = enabled, onClick = onClick, label = { Text(label) })
