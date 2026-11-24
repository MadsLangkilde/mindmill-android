package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.FactoryConfig
import dk.langkilde.mindmill.remote.FactoryDraft
import dk.langkilde.mindmill.remote.FactoryOption
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Creates, edits or duplicates a factory with `factory.config.get/save`: what it takes, how it builds, when it asks,
 * how it delivers and how long it runs. Problems from the Mac show at the top.
 */
@Composable
fun FactoryEditorScreen(nav: Nav, id: String?, duplicate: Boolean) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val (state, retry) = rememberLoad(id, duplicate) {
        FactoryConfig.parse(app.call("factory.config.get", jsonOf("id" to id)))
    }
    var draft by remember(id, duplicate) { mutableStateOf<FactoryDraft?>(null) }
    LaunchedEffect(state.value) {
        val d = state.value?.draft ?: return@LaunchedEffect
        if (draft == null) {
            draft = when {
                duplicate -> d.duplicate("${d.name} (copy)")
                d.id == null && d.scopes.isEmpty() -> d.withScopes(listOfNotNull(app.currentScope))
                else -> d
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var macProblems by remember(state.value) { mutableStateOf(state.value?.problems.orEmpty()) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showLocal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        LoadStatus(state, retry, skeletonRows = 6)
        (state.error)?.takeIf { it.contains("Update Mindmill") }?.let {
            Text("Until then, use All actions → Factories for start, pause and settings.", style = MaterialTheme.typography.bodySmall)
        }
        val cfg = state.value ?: return@Column
        val d = draft ?: return@Column
        fun set(next: FactoryDraft) { draft = next }
        val options = cfg.options

        if (cfg.status != null && !duplicate) {
            Text(
                "Status: " + factoryStatusTitle(cfg.status) + if (cfg.running) ". Changes apply to new work items." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (macProblems.isNotEmpty()) {
            NoticeBar(
                "Before it can start:\n" + macProblems.joinToString("\n") { "• $it" },
                Modifier.padding(vertical = 6.dp), error = true,
            )
        }
        val local = d.localProblems(options.checkpoints)
        if (showLocal && local.isNotEmpty()) {
            NoticeBar(local.joinToString("\n") { "• $it" }, Modifier.padding(vertical = 6.dp), error = true)
        }

        OutlinedTextField(
            d.name, { set(d.withName(it)) }, label = { Text("Name *") }, singleLine = true,
            isError = showLocal && d.name.isBlank(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        SectionCard("Work it takes") {
            val scopes = options.scopes.map { it.id to it.title }.ifEmpty { app.connection.hello?.scopes.orEmpty().map { it.id to it.title } }
            Text("Projects", style = MaterialTheme.typography.labelLarge)
            ChipFlow(Modifier.padding(vertical = 4.dp)) {
                scopes.forEach { (sid, title) ->
                    Choice(title, sid in d.scopes) { set(d.withScopes(if (sid in d.scopes) d.scopes - sid else d.scopes + sid)) }
                }
            }
            SwitchRow("Assigned to me", d.assignedToMe, { set(d.setBool("assignedToMe", it)) })
            SwitchRow("Unassigned too", d.unassigned, { set(d.setBool("unassigned", it)) }, detail = "It assigns them to you first.")
            SwitchRow("PBIs", d.pbis, { set(d.setBool("pbis", it)) })
            SwitchRow("Bugs", d.bugs, { set(d.setBool("bugs", it)) })
            ChoiceField("Sprint", d.sprint, options.sprint.pairs()) { set(d.setChoice("sprint", it)) }
            SwitchRow("Also my started items no session works on", d.resumeStarted, { set(d.setBool("resumeStarted", it)) })
            ChoiceField("New work items it finds", d.newWorkItems, options.newWorkItems.pairs()) { set(d.setChoice("newWorkItems", it)) }
        }

        SectionCard("How it builds") {
            val strategies = listOf("" to "One session, no strategy") + options.strategies.map { it.id to it.title }
            ChoiceField("Default strategy", d.defaultStrategy, strategies) { set(d.setChoice("defaultStrategy", it)) }
            if (options.strategies.isEmpty()) {
                Text("The Mac sent no strategies; the default runs one session.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Exceptions (first match wins)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            val rules = d.strategyRules
            if (rules.isEmpty()) Text("None: every item gets the default.", style = MaterialTheme.typography.bodySmall)
            rules.forEachIndexed { i, r ->
                StrategyRuleRow(
                    r, i, rules.size, options.strategyMatch, strategies,
                    onChange = { nr -> set(d.withStrategyRules(rules.toMutableList().also { it[i] = nr })) },
                    onMove = { by -> set(d.withStrategyRules(rules.toMutableList().also { val x = it.removeAt(i); it.add(i + by, x) })) },
                    onRemove = { set(d.withStrategyRules(rules.filterIndexed { j, _ -> j != i })) },
                    showProblem = showLocal,
                )
            }
            TextButton(onClick = { set(d.withStrategyRules(rules + FactoryDraft.StrategyRule.new())) }) { Text("Add an exception") }
            ChoiceField("Work items at once", d.maxParallel.toString(), options.maxParallel.map { it.toString() to it.toString() }) {
                set(d.withMaxParallel(it.toInt()))
            }
        }

        SectionCard("When it asks you") {
            ChoiceField("Questions", d.questions, options.questions.pairs()) { set(d.setChoice("questions", it)) }
            val asking = d.questions != "never"
            if (d.questions == "whenUnsure") AskWording("unsure", "When it's unsure", d) { set(it) }
            d.askableCheckpoints(options.checkpoints).forEach { cp ->
                SwitchRow(cp.title, cp.id in d.checkpoints, { set(d.withCheckpoint(cp.id, it)) }, detail = cp.detail.ifBlank { null }, enabled = asking)
                if (asking && cp.id in d.checkpoints) AskWording(cp.id, cp.title, d) { set(it) }
            }
            Text("Your own moments to ask", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            val asks = d.customAsks
            asks.forEachIndexed { i, a ->
                CustomAskRow(
                    a, enabled = asking, showProblem = showLocal,
                    onChange = { na -> set(d.withCustomAsks(asks.toMutableList().also { it[i] = na })) },
                    onRemove = { set(d.withCustomAsks(asks.filterIndexed { j, _ -> j != i })) },
                )
            }
            TextButton(enabled = asking, onClick = { set(d.withCustomAsks(asks + FactoryDraft.CustomAsk.new(""))) }) { Text("Add a moment to ask") }
        }

        SectionCard("Delivery") {
            SwitchRow("Open pull requests", d.openPRs, { set(d.setBool("openPRs", it)) })
            SwitchRow("Also complete (merge) them", d.completePRs, { set(d.setBool("completePRs", it)) }, enabled = d.openPRs)
        }

        SectionCard("How long it runs") {
            ChoiceField("Runs", d.duration, options.duration.pairs()) { set(d.setChoice("duration", it)) }
            when (d.duration) {
                "hours" -> {
                    var text by remember { mutableStateOf(formatHours(d.hours)) }
                    OutlinedTextField(
                        text, { v -> text = v; v.replace(',', '.').toDoubleOrNull()?.let { set(d.withHours(it)) } },
                        label = { Text("Hours") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = text.replace(',', '.').toDoubleOrNull()?.let { it <= 0 } ?: true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "until" -> UntilField(d.until) { set(d.withUntil(it)) }
            }
        }

        saveError?.let { ErrorState(it, onRetry = null, compact = true) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !saving,
                onClick = {
                    showLocal = true
                    if (d.localProblems(options.checkpoints).isNotEmpty()) return@Button
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            val r = app.call("factory.config.save", d.forSave())
                            val newId = r.text("id") ?: d.id
                            app.done(if (d.id == null) "Created ${d.name}." else "Saved ${d.name}.")
                            app.bumpRefresh()
                            if (newId != null && d.id == null) nav.replace(Screen.FactoryEditor(newId)) else nav.back()
                        } catch (e: RemoteException) {
                            if (e.code == dk.langkilde.mindmill.remote.ErrorCodes.BAD_PARAMS) {
                                macProblems = e.message.orEmpty().split(Regex("(?<=[.!?])\\s+|\\n")).map { it.trim() }.filter { it.isNotBlank() }
                                saveError = "The Mac can't save it like this. Fix what's listed at the top."
                            } else saveError = e.userMessage()
                        } catch (e: Exception) {
                            saveError = e.userMessage()
                        } finally {
                            saving = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "Saving…" else if (d.id == null) "Create factory" else "Save") }
            TextButton(onClick = { nav.back() }) { Text("Cancel") }
        }
        if (d.id != null && !duplicate) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ChipFlow {
                OutlinedButton(onClick = { nav.replace(Screen.FactoryEditor(d.id, duplicate = true)) }) { Text("Duplicate") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    val d = draft
    if (confirmDelete && d?.id != null) {
        ConfirmDialog(
            "Delete ${d.name}?",
            "The factory and its settings go away. If it is running, it stops first; sessions it started keep their work.",
            "Delete",
            onConfirm = { scope.launch { if (deleteFactory(app, d.id, d.name)) nav.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

suspend fun deleteFactory(app: dk.langkilde.mindmill.MindmillApp, id: String, name: String): Boolean = try {
    app.call("factory.config.delete", jsonOf("id" to id))
    app.done("Deleted $name.")
    app.bumpRefresh()
    true
} catch (e: Exception) {
    app.done("Couldn't delete $name: ${e.userMessage()}", error = true)
    false
}

fun factoryStatusTitle(status: String) = when (status) {
    "never" -> "Hasn't run"
    "windingDown" -> "Finishing up"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun List<FactoryOption>.pairs() = map { it.id to it.title }

private fun formatHours(h: Double) = if (h == Math.floor(h)) h.toLong().toString() else h.toString()

@Composable
private fun StrategyRuleRow(
    r: FactoryDraft.StrategyRule,
    index: Int,
    count: Int,
    matches: List<FactoryOption>,
    strategies: List<Pair<String, String>>,
    onChange: (FactoryDraft.StrategyRule) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    showProblem: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Exception ${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            IconButton(enabled = index > 0, onClick = { onMove(-1) }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
            IconButton(enabled = index < count - 1, onClick = { onMove(1) }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove exception") }
        }
        ChoiceField("When", r.match, matches.map { it.id to it.title }) { onChange(r.with(match = it)) }
        val needsValue = matches.firstOrNull { it.id == r.match }?.needsValue ?: r.needsValue
        if (needsValue) {
            OutlinedTextField(
                r.value, { onChange(r.with(value = it)) },
                label = { Text(if (r.match == "tag") "Tag" else "Words in the title") }, singleLine = true,
                isError = showProblem && r.value.isBlank(), modifier = Modifier.fillMaxWidth(),
            )
        }
        ChoiceField("Use", r.strategy, strategies) { onChange(r.with(strategy = it)) }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AskWording(id: String, title: String, d: FactoryDraft, onChange: (FactoryDraft) -> Unit) {
    var open by remember(id) { mutableStateOf(false) }
    val current = d.askTexts[id].orEmpty()
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide wording" else if (current.isBlank()) "Change what it's told" else "Wording changed: edit") }
    if (open) {
        OutlinedTextField(
            current, { onChange(d.withAskText(id, it)) }, label = { Text("What the session is told ($title)") },
            supportingText = { Text("Leave empty for the default.") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CustomAskRow(
    a: FactoryDraft.CustomAsk,
    enabled: Boolean,
    showProblem: Boolean,
    onChange: (FactoryDraft.CustomAsk) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                a.title, { onChange(a.with(title = it)) }, label = { Text("When to ask") }, singleLine = true, enabled = enabled,
                isError = showProblem && a.title.isBlank(), modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
        OutlinedTextField(
            a.instruction, { onChange(a.with(instruction = it)) }, label = { Text("More for the session (optional)") },
            enabled = enabled, minLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        SwitchRow("On", a.on, { onChange(a.with(on = it)) }, enabled = enabled)
    }
}

@Composable
private fun UntilField(until: Instant?, onChange: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val local = until?.atZone(zone)?.toLocalDateTime()
    var text by remember { mutableStateOf(local?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "06:00") }
    LaunchedEffect(Unit) { if (until == null) nextOccurrence(text, zone)?.let(onChange) }
    OutlinedTextField(
        text, { v -> text = v; nextOccurrence(v, zone)?.let(onChange) },
        label = { Text("Until (HH:mm)") }, singleLine = true, isError = nextOccurrence(text, zone) == null,
        supportingText = {
            Text(until?.atZone(zone)?.format(DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm"))?.let { "Stops $it" } ?: "The next time it is this time")
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The next moment the clock shows [hhmm], today or tomorrow. */
private fun nextOccurrence(hhmm: String, zone: ZoneId): Instant? {
    val t = runCatching { LocalTime.parse(hhmm.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull() ?: return null
    val now = LocalDateTime.now(zone)
    var at = now.toLocalDate().atTime(t)
    if (!at.isAfter(now)) at = at.plusDays(1)
    return at.atZone(zone).toInstant().truncatedTo(ChronoUnit.SECONDS)
}
