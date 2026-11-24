package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.ActionForm
import dk.langkilde.mindmill.remote.ActionInfo
import dk.langkilde.mindmill.remote.ActionOutcome
import dk.langkilde.mindmill.remote.ActionParamSpec
import dk.langkilde.mindmill.remote.DataBlock
import dk.langkilde.mindmill.remote.DataRow
import dk.langkilde.mindmill.remote.DataView
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** Every action the Mac offers, grouped by area and searchable; each opens a form made from its parameters. */
@Composable
fun AllActionsScreen(nav: Nav, area: String?) {
    val app = LocalApp.current
    var query by rememberSaveable { mutableStateOf("") }
    val (state, retry) = rememberLoad { app.actionCatalog(force = false) }
    val (actions, order) = state.value ?: (emptyList<ActionInfo>() to emptyList())
    val shown = actions.filter { (area == null || it.area.equals(area, ignoreCase = true) || it.areaTitle.equals(area, true)) && it.matches(query) }
    val groups = ActionInfo.grouped(shown, order)

    RefreshList(state.refreshing, { retry() }) {
        item {
            Text(
                if (area == null) "Everything Mindmill on the Mac can do. Pick one to fill in what it needs and run it."
                else "What Mindmill can do here. Pick one to fill in what it needs and run it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            SearchField(query, { query = it }, "Search actions", Modifier.padding(vertical = 4.dp))
            LoadStatus(state, retry)
            if (state.value != null && shown.isEmpty()) {
                EmptyState(
                    if (query.isNotBlank()) "No action matches “$query”." else "The Mac offers no actions here yet.",
                    actionLabel = if (area != null) "Show all actions" else null,
                    onAction = { nav.replace(Screen.AllActions()) },
                    detail = "Or ask Mindmill in your own words.",
                )
            }
        }
        groups.forEach { (g, list) ->
            if (area == null) item(key = "area-$g") { SectionTitle(ActionInfo.areaTitle(g)) }
            items(list, key = { it.name }) { a ->
                ListRow(
                    a.title,
                    subtitle = listOfNotNull(a.about.ifBlank { null }, riskLabel(a.risk)).joinToString(" · "),
                    onClick = { nav.go(Screen.RunAction(a.name)) },
                )
            }
        }
    }
}

private fun riskLabel(risk: String) = when (risk) {
    "destructive" -> "asks first"
    "external" -> "changes an outside tool; asks first"
    else -> null
}

/** A form for one action, generated from its JSON schema; runs it and shows what it did. */
@Composable
fun RunActionScreen(nav: Nav, name: String, defaults: Map<String, String>) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val hello = app.connection.hello
    val (state, retry) = rememberLoad(name) { app.actionCatalog().first.firstOrNull { it.name == name } }
    val action = state.value
    var values by remember(action) {
        mutableStateOf(action?.let { ActionForm.initial(it.params, mapOf("scope" to (app.currentScope ?: "")) + defaults) } ?: emptyMap())
    }
    var showProblems by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var outcome by remember(name) { mutableStateOf<ActionOutcome?>(null) }
    var failure by remember(name) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        LoadStatus(state, retry)
        if (state.value == null && !state.loading && !state.waiting && state.error == null) {
            EmptyState("The Mac doesn't offer $name.", "All actions", { nav.replace(Screen.AllActions()) })
        }
        val a = action ?: return@Column
        Text(a.about, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
        if (a.isRisky) {
            Text("The Mac asks before it does this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val problems = ActionForm.problems(a.params, values)
        a.params.forEach { p ->
            ParamField(
                p, values[p.name].orEmpty(), if (showProblems) problems[p.name] else null,
                scopes = hello?.scopes.orEmpty().map { it.id to it.title },
            ) { v -> values = values + (p.name to v) }
        }
        if (a.params.isEmpty()) Text("Nothing to fill in.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        Button(
            enabled = !running,
            onClick = {
                showProblems = true
                if (problems.isNotEmpty()) return@Button
                running = true
                failure = null
                scope.launch {
                    try {
                        val o = app.runAction(a.name, ActionForm.toParams(a.params, values))
                        outcome = o
                        if (o.status == ActionOutcome.Status.DONE) app.done(o.speech.ifBlank { "Done." })
                    } catch (e: Exception) {
                        failure = e.userMessage()
                    } finally {
                        running = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text(if (running) "Running…" else "Run") }
        failure?.let { ErrorState(it, onRetry = null, compact = true) }
        outcome?.let { OutcomeView(it, nav) }
    }
}

@Composable
private fun ParamField(p: ActionParamSpec, value: String, problem: String?, scopes: List<Pair<String, String>>, onChange: (String) -> Unit) {
    val label = p.label + if (p.required) " *" else ""
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when {
            p.type == ActionParamSpec.Type.BOOLEAN -> SwitchRow(label, value == "true", { onChange(if (it) "true" else "false") }, detail = p.about.ifBlank { null })
            p.choices.isNotEmpty() -> {
                val options = (if (p.required) emptyList() else listOf("" to "Not set")) + p.choices.map { it to humanizeChoice(it) }
                ChoiceField(label, value, options, onPick = onChange)
                if (p.about.isNotBlank()) Help(p.about)
            }
            p.name == "scope" && scopes.isNotEmpty() -> {
                ChoiceField(label, value, listOf("" to "The current project") + scopes, onPick = onChange)
            }
            else -> {
                OutlinedTextField(
                    value, onChange, label = { Text(label) },
                    supportingText = (problem ?: p.about.ifBlank { null }?.let { about ->
                        if (p.type == ActionParamSpec.Type.STRINGS) "$about (one per line)" else about
                    })?.let { t -> { Text(t) } },
                    isError = problem != null,
                    singleLine = p.type != ActionParamSpec.Type.STRING && p.type != ActionParamSpec.Type.STRINGS,
                    minLines = if (p.type == ActionParamSpec.Type.STRINGS) 2 else 1,
                    keyboardOptions = when (p.type) {
                        ActionParamSpec.Type.INTEGER -> KeyboardOptions(keyboardType = KeyboardType.Number)
                        ActionParamSpec.Type.NUMBER -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        else -> KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (problem != null && (p.choices.isNotEmpty() || p.type == ActionParamSpec.Type.BOOLEAN)) {
            Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Help(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))

private fun humanizeChoice(c: String) = dk.langkilde.mindmill.remote.humanize(c)

/** What an action did: its sentence, and its data as fields, rows and text. */
@Composable
fun OutcomeView(o: ActionOutcome, nav: Nav?) {
    val color = when (o.status) {
        ActionOutcome.Status.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                when (o.status) {
                    ActionOutcome.Status.DONE -> "Done"
                    ActionOutcome.Status.FAILED -> "Didn't work"
                    ActionOutcome.Status.CANCELLED -> "Cancelled"
                    ActionOutcome.Status.NEEDS_CONFIRMATION -> "Waiting for your answer"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            if (o.speech.isNotBlank()) Text(o.speech, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        }
    }
    DataBlocksView(o.dataElement, nav)
}

/** Any JSON from the Mac, readable: fields as label and value, lists of objects as rows that open what they are. */
@Composable
fun DataBlocksView(data: JsonElement?, nav: Nav?, maxRows: Int = 50) {
    val blocks = remember(data) { DataView.blocks(data) }
    blocks.forEach { b ->
        when (b) {
            is DataBlock.Fields -> SectionCard(b.label) {
                b.fields.forEach { (k, v) -> FieldLine(k, v) }
            }
            is DataBlock.Text -> SectionCard(b.label) {
                SelectionContainer { Text(markdownish(b.text), style = MaterialTheme.typography.bodyMedium) }
            }
            is DataBlock.Rows -> {
                b.label?.let { SectionTitle("$it (${b.rows.size})") }
                b.rows.take(maxRows).forEach { r -> DataRowView(r, nav) }
                if (b.rows.size > maxRows) Text("…and ${b.rows.size - maxRows} more.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FieldLine(k: String, v: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DataRowView(r: DataRow, nav: Nav?) {
    var open by remember { mutableStateOf(false) }
    val target: Screen? = when (val l = r.link) {
        is DataRow.Link.Entry -> Screen.Entry(l.id)
        is DataRow.Link.Session -> Screen.SessionChat(l.id, l.title)
        is DataRow.Link.WorkItem -> Screen.WorkItemDetail(r.raw)
        null -> null
    }
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            r.title, subtitle = r.subtitle.ifBlank { null },
            onClick = { if (target != null && nav != null) nav.go(target) else open = !open },
            trailing = if (r.details.isNotEmpty() && target != null) {
                { TextButton(onClick = { open = !open }) { Text(if (open) "Less" else "More") } }
            } else null,
        )
        if (open) {
            Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                r.details.forEach { (k, v) ->
                    Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
}
