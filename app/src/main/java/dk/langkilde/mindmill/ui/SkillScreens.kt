package dk.langkilde.mindmill.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dk.langkilde.mindmill.remote.McpServer
import dk.langkilde.mindmill.remote.McpServers
import dk.langkilde.mindmill.remote.Plugins
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.SkillGroups
import dk.langkilde.mindmill.remote.SkillInfo
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The AI writes or changes a skill: allow it time. */
private const val DRAFT_TIMEOUT_MS = 180_000L
private const val PLUGIN_TIMEOUT_MS = 120_000L

private fun JsonObject.merged(other: JsonObject) = JsonObject(toMap() + other)

/** `plugins.list`, or why there are none: the brain has no plugins (`unavailable`). */
private data class PluginsLoad(val plugins: Plugins?, val unavailable: String?)

/**
 * What the session's AI can use, as on the Mac: its skills (the user's own open in an editor), its plugins, and its
 * tools (MCP servers). Everything loads again on `skills.changed`.
 */
@Composable
fun SkillsToolsScreen(nav: Nav, startTab: String) {
    val app = LocalApp.current
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val version by app.skillsVersion.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.trim() != searched) delay(400)
        searched = query.trim()
    }
    val (plugins, retryPlugins) = rememberLoad(trigger = searched to version) {
        try {
            PluginsLoad(Plugins.parse(app.call("plugins.list", jsonOf("available" to true, "query" to searched.ifBlank { null }), PLUGIN_TIMEOUT_MS)), null)
        } catch (e: RemoteException) {
            if (e.isUnavailable) PluginsLoad(null, e.message?.takeIf { it.isNotBlank() } ?: "${app.connection.hello?.brain ?: "This AI"} has no plugins.")
            else throw e
        }
    }
    val noPlugins = plugins.value?.unavailable
    val shownTab = if (tab == "plugins" && noPlugins != null) "skills" else tab

    Column(Modifier.fillMaxSize()) {
        ChipFlow(Modifier.padding(top = 4.dp)) {
            Choice("Skills", shownTab == "skills") { tab = "skills" }
            if (noPlugins == null) Choice("Plugins", shownTab == "plugins") { tab = "plugins" }
            Choice("Tools", shownTab == "tools") { tab = "tools" }
        }
        noPlugins?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (shownTab) {
                "plugins" -> PluginsPart(plugins, retryPlugins, query) { query = it }
                "tools" -> ToolsPart(version)
                else -> SkillsPart(nav, version)
            }
        }
    }
}

@Composable
private fun SkillsPart(nav: Nav, version: Int) {
    val app = LocalApp.current
    val scopeId = rememberCurrentScope()
    val (state, retry) = rememberLoad(scopeId, trigger = version) {
        SkillInfo.list(app.call("skills.list", jsonOf("scope" to app.currentScope)))
    }
    val groups = state.value?.let { SkillGroups.of(it) }.orEmpty()
    RefreshList(state.refreshing, retry) {
        item(key = "head") {
            Column {
                Text("Type / in a session's message box to use one.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                FilledTonalButton(onClick = { nav.go(Screen.SkillEditor(null, "user", app.currentScope)) }, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("Add skill")
                }
                LoadStatus(state, retry)
                if (state.value != null && groups.isEmpty()) ListEmptyState("No skills yet.")
            }
        }
        groups.forEach { g ->
            item(key = "g-${g.id}") { SectionTitle("${g.title} · ${g.skills.size}") }
            items(g.skills, key = { "${g.id}-${it.command}" }) { s ->
                SkillRow(s, onClick = if (s.isOwn && (s.editable || s.location != null)) {
                    { nav.go(Screen.SkillEditor(s.name, s.location ?: s.source, s.scope)) }
                } else null)
            }
        }
    }
}

@Composable
private fun SkillRow(s: SkillInfo, onClick: (() -> Unit)?) {
    ListRow(
        s.command, subtitle = null, onClick = onClick,
        below = {
            Column {
                if (s.description.isNotBlank()) {
                    Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(s.sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp))
            }
        },
    )
}

@Composable
private fun PluginsPart(state: Loaded<PluginsLoad>, retry: () -> Unit, query: String, onQuery: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(setOf<String>()) }
    var uninstalling by remember { mutableStateOf<String?>(null) }
    var enabled by remember(state.value) { mutableStateOf(mapOf<String, Boolean>()) }

    fun run(id: String, method: String, params: Map<String, Any?>, said: String) {
        busy = busy + id
        scope.launch {
            try {
                app.call(method, jsonOf(*params.toList().toTypedArray()), PLUGIN_TIMEOUT_MS)
                app.done(said)
            } catch (e: Exception) {
                app.done(e.userMessage(), error = true)
                enabled = enabled - id
            }
            busy = busy - id
            retry()
        }
    }

    val p = state.value?.plugins
    RefreshList(state.refreshing, retry) {
        item(key = "head") {
            Column {
                Text("New plugins work in sessions started after they're installed.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                LoadStatus(state, retry)
                if (p != null) {
                    SectionTitle("Installed · ${p.installed.size}")
                    if (p.installed.isEmpty()) ListEmptyState("No plugins installed.")
                }
            }
        }
        items(p?.installed.orEmpty(), key = { "i-" + it.id }) { pl ->
            val on = enabled[pl.id] ?: pl.enabled
            ListRow(
                pl.name,
                subtitle = listOf(pl.marketplace, pl.version.takeIf { it.isNotBlank() }?.let { "v$it" }.orEmpty(), pl.scope)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                below = {
                    Column {
                        if (pl.description.isNotBlank()) Text(pl.description, style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = pl.id !in busy, onClick = { uninstalling = pl.id }) {
                            Text("Uninstall", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                trailing = {
                    Switch(on, enabled = pl.id !in busy, onCheckedChange = { v ->
                        enabled = enabled + (pl.id to v)
                        run(pl.id, "plugin.set", mapOf("id" to pl.id, "enabled" to v), if (v) "${pl.name} is on." else "${pl.name} is off.")
                    })
                },
            )
        }
        item(key = "available-head") {
            Column {
                SectionTitle("Available")
                SearchField(query, onQuery, "Search plugins")
                if (p != null && p.available.isEmpty()) {
                    ListEmptyState(if (query.isNotBlank()) "No plugin matches “${query.trim()}”." else "The marketplaces offer nothing more.")
                }
            }
        }
        items(p?.available.orEmpty(), key = { "a-" + it.id }) { a ->
            ListRow(
                a.name,
                subtitle = listOfNotNull(a.marketplace.takeIf { it.isNotBlank() }, a.installs?.let { "$it installs" }).joinToString(" · "),
                below = {
                    Column {
                        if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = a.id !in busy, onClick = {
                            run(a.id, "plugin.install", mapOf("id" to a.id), "Installed ${a.name}. It works in sessions started from now on.")
                        }, modifier = Modifier.padding(top = 4.dp)) { Text(if (a.id in busy) "Installing…" else "Install") }
                    }
                },
            )
        }
    }
    uninstalling?.let { id ->
        val name = p?.installed?.firstOrNull { it.id == id }?.name ?: id
        ConfirmDialog(
            "Uninstall $name?", "Its skills and tools go away for new sessions.", "Uninstall",
            onConfirm = { run(id, "plugin.uninstall", mapOf("id" to id), "Uninstalled $name.") },
            onDismiss = { uninstalling = null },
        )
    }
}

@Composable
private fun ToolsPart(version: Int) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (state, retry) = rememberLoad(trigger = version) { McpServers.parse(app.call("mcp.list")) }
    var removing by remember { mutableStateOf<McpServer?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var transport by rememberSaveable { mutableStateOf("http") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun add() {
        problem = McpServers.problem(name, url)
        if (problem != null) return
        busy = true
        scope.launch {
            try {
                app.call("mcp.add", jsonOf("name" to name.trim(), "url" to url.trim(), "transport" to transport), PLUGIN_TIMEOUT_MS)
                app.done("Added ${name.trim()}.")
                name = ""
                url = ""
                adding = false
            } catch (e: Exception) {
                problem = e.userMessage()
            }
            busy = false
            retry()
        }
    }

    val servers = state.value
    RefreshList(state.refreshing, retry) {
        item(key = "head") {
            Column {
                Text("Tools are MCP servers the AI can call.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                ChipFlow(Modifier.padding(vertical = 6.dp)) {
                    if (!adding) FilledTonalButton(onClick = { adding = true }) { Text("Add a server") }
                    servers?.connectorsURL?.let { link ->
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) } }) {
                            Text("Manage connectors")
                        }
                    }
                }
                if (adding) {
                    SectionCard("Add a server") {
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(url, { url = it }, label = { Text("Address (https://…)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        ChipFlow(Modifier.padding(top = 6.dp)) {
                            Choice("HTTP", transport == "http") { transport = "http" }
                            Choice("SSE", transport == "sse") { transport = "sse" }
                        }
                        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        ChipFlow(Modifier.padding(top = 6.dp)) {
                            Button(enabled = !busy, onClick = ::add) { Text(if (busy) "Adding…" else "Add") }
                            TextButton(onClick = { adding = false; problem = null }) { Text("Cancel") }
                        }
                    }
                }
                LoadStatus(state, retry)
                if (servers != null && servers.servers.isEmpty()) ListEmptyState("No tools yet.")
            }
        }
        items(servers?.servers.orEmpty(), key = { it.name }) { s ->
            val status = when (s.status) {
                "connected" -> Status.DONE
                "needsAuth" -> Status.NEEDS_YOU
                "failed" -> Status.FAILED
                else -> Status.IDLE
            }
            ListRow(
                s.name, subtitle = s.target.takeIf { it.isNotBlank() },
                leading = { Box(Modifier.size(10.dp).background(statusColors(status).dot, CircleShape)) },
                below = {
                    ChipFlow {
                        StatusChip(s.statusTitle, status)
                        if (s.originTitle.isNotBlank()) InfoChip(s.originTitle)
                        if (s.removable) TextButton(onClick = { removing = s }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
    }
    removing?.let { s ->
        ConfirmDialog(
            "Remove ${s.name}?", "The AI can't use its tools any more.", "Remove",
            onConfirm = {
                scope.launch {
                    try {
                        app.call("mcp.remove", jsonOf("name" to s.name))
                        app.done("Removed ${s.name}.")
                    } catch (e: Exception) {
                        app.done(e.userMessage(), error = true)
                    }
                    retry()
                }
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * One of the user's own skills: its SKILL.md to edit and save, delete, or have the AI change as described. A new one
 * starts from what it should do: the AI writes it (`skill.draft`), the user reviews and saves it.
 */
@Composable
fun SkillEditorScreen(nav: Nav, name: String?, startLocation: String, startScope: String?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val hello by app.connection.lastHello.collectAsState()
    val current = rememberCurrentScope()
    val isNew = name == null
    var location by rememberSaveable { mutableStateOf(startLocation) }
    val scopeId = startScope ?: current
    val scopeTitle = hello?.scopes?.firstOrNull { it.id == scopeId }?.title ?: scopeId
    var skillName by rememberSaveable { mutableStateOf(name.orEmpty()) }
    var content by rememberSaveable { mutableStateOf("") }
    var filled by rememberSaveable { mutableStateOf(isNew) }
    var wanted by rememberSaveable { mutableStateOf("") }
    var changing by rememberSaveable { mutableStateOf(false) }
    var proposal by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }

    val (state, retry) = rememberLoad(name, location) {
        if (name == null) null
        else app.call("skill.get", jsonOf("name" to name, "location" to location, "scope" to if (location == "project") scopeId else null))
    }
    LaunchedEffect(state.value) {
        val r = state.value ?: return@LaunchedEffect
        if (!filled) {
            content = r.string("content").orEmpty()
            filled = true
        }
    }
    fun where() = jsonOf("location" to location, "scope" to if (location == "project") scopeId else null)

    fun draft() {
        busy = "draft"
        problem = null
        scope.launch {
            try {
                val r = app.call("skill.draft", jsonOf(
                    "description" to wanted.trim(), "name" to skillName.trim().ifBlank { null },
                    "content" to if (isNew) null else content,
                ).merged(where()), DRAFT_TIMEOUT_MS)
                val text = r.string("content").orEmpty()
                if (isNew) {
                    content = text
                    if (skillName.isBlank()) skillName = r.string("name").orEmpty()
                } else proposal = text
                wanted = ""
                changing = false
            } catch (e: Exception) {
                problem = e.userMessage()
            }
            busy = null
        }
    }

    fun save(text: String) {
        busy = "save"
        problem = null
        scope.launch {
            try {
                app.call("skill.save", jsonOf("name" to skillName.trim(), "content" to text).merged(where()))
                app.done("Saved /${skillName.trim()}.")
                nav.back()
            } catch (e: Exception) {
                problem = e.userMessage()
            }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (!isNew) LoadStatus(state, retry, skeletonRows = 6)
        if (isNew) {
            OutlinedTextField(skillName, { skillName = it.replace(' ', '-') }, label = { Text("Name (optional)") }, singleLine = true,
                supportingText = { Text("Left empty, the AI names it.") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("Where", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            ChipFlow {
                Choice("For me in every project", location == "user") { location = "user" }
                if (scopeId != null) Choice("Only in $scopeTitle", location == "project") { location = "project" }
            }
            OutlinedTextField(wanted, { wanted = it }, label = { Text("What should it do?") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            ChipFlow(Modifier.padding(top = 8.dp)) {
                Button(enabled = busy == null && wanted.isNotBlank(), onClick = ::draft) {
                    Text(if (busy == "draft") "Writing it…" else if (content.isBlank()) "Write it" else "Write it again")
                }
            }
        } else {
            Text(
                if (location == "project") "Only in $scopeTitle" else "For you, in every project",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        problem?.let { ErrorState(it, onRetry = null, compact = true) }
        if (filled && (!isNew || content.isNotBlank())) {
            OutlinedTextField(
                content, { content = it }, label = { Text("SKILL.md") }, minLines = 10,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            ChipFlow(Modifier.padding(top = 8.dp)) {
                Button(enabled = busy == null && skillName.isNotBlank() && content.isNotBlank(), onClick = { save(content) }) {
                    Text(if (busy == "save") "Saving…" else "Save")
                }
                if (!isNew) {
                    OutlinedButton(enabled = busy == null, onClick = { changing = !changing }) { Text("Change it…") }
                    TextButton(enabled = busy == null, onClick = { deleting = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { nav.back() }) { Text("Cancel") }
            }
        }
        if (changing && !isNew) {
            SectionCard("Change it") {
                OutlinedTextField(wanted, { wanted = it }, label = { Text("What should change?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                ChipFlow(Modifier.padding(top = 6.dp)) {
                    Button(enabled = busy == null && wanted.isNotBlank(), onClick = ::draft) { Text(if (busy == "draft") "Writing it…" else "Write the change") }
                    TextButton(onClick = { changing = false }) { Text("Cancel") }
                }
            }
        }
        proposal?.let { p ->
            SectionCard("The changed version") {
                Text("Check it, then save it or keep the one you have.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    p, { proposal = it }, minLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                ChipFlow(Modifier.padding(top = 6.dp)) {
                    Button(enabled = busy == null, onClick = { content = p; proposal = null; save(p) }) { Text("Save this version") }
                    TextButton(onClick = { proposal = null }) { Text("Keep mine") }
                }
            }
        }
    }
    if (deleting) {
        ConfirmDialog(
            "Delete /${name.orEmpty()}?",
            "It's removed " + (if (location == "project") "from $scopeTitle's repository" else "from every project") + ". Sessions can't use it any more.",
            "Delete",
            onConfirm = {
                scope.launch {
                    try {
                        app.call("skill.delete", jsonOf("name" to name).merged(where()))
                        app.done("Deleted /${name.orEmpty()}.")
                        nav.back()
                    } catch (e: Exception) {
                        problem = e.userMessage()
                    }
                }
            },
            onDismiss = { deleting = false },
        )
    }
}
