package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.ErrorCodes
import dk.langkilde.mindmill.remote.FolderListing
import dk.langkilde.mindmill.remote.ProjectDraft
import dk.langkilde.mindmill.remote.ProjectInfo
import dk.langkilde.mindmill.remote.ProjectScope
import dk.langkilde.mindmill.remote.ProjectsConfig
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch

/** The Mac's projects with their locations and scopes (`projects.config.get`); each opens the editor. */
@Composable
fun ProjectsScreen(nav: Nav) {
    val app = LocalApp.current
    val (state, retry) = rememberLoad { ProjectsConfig.parse(app.call("projects.config.get")) }
    val instance = rememberOpenInstance()
    RefreshList(state.refreshing, retry) {
        item(key = "head") {
            Column {
                LoadStatus(state, retry)
                val cfg = state.value
                if (cfg != null) {
                    instance?.let {
                        Text("Instance: ${it.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        "A project has locations, the folders on the Mac its code is in, and scopes: one for the whole project " +
                            "and one per app or part." + (cfg.scopeTitle(cfg.current)?.let { " Current on the Mac: $it." } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    ChipFlow(Modifier.padding(vertical = 6.dp)) {
                        FilledTonalButton(onClick = { nav.go(Screen.ProjectEditor(null)) }) { Text("New project") }
                        OutlinedButton(onClick = { nav.go(Screen.SharingDefaults()) }) { Text("Private and shared") }
                        if (instance != null) OutlinedButton(onClick = { nav.go(Screen.Instances) }) { Text("Instances") }
                    }
                    if (cfg.projects.isEmpty()) ListEmptyState("No projects yet. Make one to give sessions their folders.")
                }
            }
        }
        items(state.value?.projects.orEmpty(), key = { it.id }) { p ->
            ProjectCard(p, current = state.value?.current) { nav.go(Screen.ProjectEditor(p.id)) }
        }
    }
}

@Composable
private fun ProjectCard(p: ProjectInfo, current: String?, onOpen: () -> Unit) {
    GroupCard(Modifier.padding(vertical = 6.dp)) {
        ListRow(
            p.title,
            subtitle = listOf(
                plural(p.locations.size, "location"), plural(p.scopes.size, "scope"),
            ).joinToString(" · "),
            onClick = onOpen,
            leading = { IconBadge(Icons.Default.Folder, size = 36.dp) },
            divider = false,
            below = {
                Column {
                    p.locations.forEach { l ->
                        Text(
                            "${l.name}: ${l.path}" + if (!l.exists) " (missing on the Mac)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (l.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    ChipFlow(Modifier.padding(top = 6.dp)) {
                        p.scopes.forEach { s ->
                            if (s.id == current) StatusChip(s.title + " · on the Mac", Status.DONE)
                            else InfoChip(if (s.wide) "${s.title} · whole project" else s.title)
                        }
                    }
                }
            },
        )
    }
}

internal fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/**
 * Makes or changes a project: its title, its locations (folders picked on the Mac with `folders.list`, each named),
 * and its scopes (which locations each works on, which scopes it also shows, which it reads knowledge from, and
 * which one is project-wide). Scopes taken out are deleted on save, after asking. The Mac's `bad_params` message
 * shows as it is.
 */
@Composable
fun ProjectEditorScreen(nav: Nav, id: String?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val (state, retry) = rememberLoad(id) { ProjectsConfig.parse(app.call("projects.config.get")) }
    var base by remember(id) { mutableStateOf<ProjectInfo?>(null) }
    var draft by remember(id) { mutableStateOf<ProjectDraft?>(null) }
    var changedOnMac by remember(id) { mutableStateOf<ProjectInfo?>(null) }
    LaunchedEffect(state.value) {
        val cfg = state.value ?: return@LaunchedEffect
        val loaded = cfg.project(id)
        val d = draft
        when {
            d == null -> {
                base = loaded
                draft = loaded?.let(ProjectDraft::from) ?: if (id == null) ProjectDraft.new() else null
            }
            id != null && loaded != null && loaded != base -> {
                if (d == base?.let(ProjectDraft::from)) {
                    base = loaded
                    draft = ProjectDraft.from(loaded)
                } else {
                    changedOnMac = loaded
                }
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var macProblem by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showLocal by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDeletes by remember { mutableStateOf<List<ProjectScope>>(emptyList()) }
    val (instancesState, _) = rememberLoad(id) {
        if (id != null) null else try { app.instances() } catch (_: RemoteException) { null }
    }
    val instances = instancesState.value?.takeIf { it.hasChoice }

    fun save(d: ProjectDraft) {
        saving = true
        saveError = null
        macProblem = null
        scope.launch {
            try {
                app.call("projects.config.save", d.forSave())
                val elsewhere = d.instance?.let { instances?.find(it)?.name ?: "the other instance" }
                app.done(
                    when {
                        elsewhere != null -> "Created ${d.title.trim()} in $elsewhere. It shows here once $elsewhere is open."
                        d.isNew -> "Created ${d.title.trim()}."
                        else -> "Saved ${d.title.trim()}."
                    },
                )
                runCatching { app.connection.refreshState() }
                app.bumpRefresh()
                nav.back()
            } catch (e: RemoteException) {
                if (e.code == ErrorCodes.BAD_PARAMS) macProblem = e.message?.takeIf { it.isNotBlank() } ?: "The Mac can't save the project like this."
                else saveError = e.userMessage()
            } catch (e: Exception) {
                saveError = e.userMessage()
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        LoadStatus(state, retry, skeletonRows = 6)
        val cfg = state.value ?: return@Column
        if (id != null && cfg.project(id) == null && draft == null) {
            ListEmptyState("This project isn't on the Mac any more.")
            return@Column
        }
        val d = draft ?: return@Column
        fun set(next: ProjectDraft) { draft = next }

        changedOnMac?.let { fresh ->
            NoticeBar(
                "This project changed on the Mac. Reload to see it; your changes here are dropped.",
                Modifier.padding(vertical = 6.dp),
                actionLabel = "Reload",
                onAction = { base = fresh; draft = ProjectDraft.from(fresh); changedOnMac = null },
                onDismiss = { changedOnMac = null },
            )
        }
        macProblem?.let { NoticeBar(it, Modifier.padding(vertical = 6.dp), error = true) }
        val problems = d.problems()
        if (showLocal && problems.isNotEmpty()) {
            NoticeBar(problems.joinToString("\n") { "• $it" }, Modifier.padding(vertical = 6.dp), error = true)
        }

        OutlinedTextField(
            d.title, { set(d.withTitle(it)) }, label = { Text("Title *") }, singleLine = true,
            isError = showLocal && d.title.isBlank(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (d.isNew) {
            OutlinedTextField(
                d.newId, { set(d.withNewId(it)) }, label = { Text("Id (optional)") }, singleLine = true,
                supportingText = { Text("The project's folder name. Left empty, it comes from the title. It can't change later.") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            Text("Id: ${d.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (d.isNew && instances != null) {
            ChoiceField(
                "Instance", d.instance ?: instances.current.orEmpty(),
                instances.instances.map { it.id to if (it.id == instances.current) "${it.name} (open)" else it.name },
                Modifier.padding(top = 4.dp),
            ) { set(d.withInstance(it, instances.current)) }
            if (d.elsewhere) {
                Text(
                    "It goes into ${instances.find(d.instance)?.name ?: "that instance"} and shows in Projects once that instance is open.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        SectionCard("Locations") {
            Text("The folders on the Mac the project's code is in, each with a name.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.locations.isEmpty()) ListEmptyState("No locations yet.")
            d.locations.forEach { l ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            l.name, { set(d.renameLocation(l.path, it)) }, label = { Text("Name") }, singleLine = true,
                            isError = showLocal && l.name.isBlank(), modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { set(d.removeLocation(l.path)) }) { Icon(Icons.Default.Delete, contentDescription = "Remove ${l.name}") }
                    }
                    Text(
                        l.path + listOfNotNull(
                            " · Git repository".takeIf { l.repository },
                            " · missing on the Mac".takeIf { !l.exists },
                        ).joinToString(""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (l.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Add a location") }
        }

        SectionCard("Scopes") {
            Text(
                "One scope shows everything in the project; add one per app or part, each working on some of the locations.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.wideScope == null) {
                Text(
                    "No scope is project-wide yet: saving adds one called Overview.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            d.scopes.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(top = 12.dp))
                ScopeEditor(d, s, cfg, showLocal) { set(it) }
            }
            TextButton(onClick = { set(d.addScope()) }, modifier = Modifier.padding(top = 6.dp)) { Text("Add a scope") }
        }

        saveError?.let { ErrorState(it, onRetry = null, compact = true) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !saving,
                onClick = {
                    showLocal = true
                    if (d.problems().isNotEmpty()) return@Button
                    val deleting = d.removedScopes(base)
                    if (deleting.isNotEmpty()) confirmDeletes = deleting else save(d)
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "Saving…" else if (d.isNew) "Create project" else "Save") }
            TextButton(onClick = { nav.back() }) { Text("Cancel") }
        }
    }

    if (picking) {
        FolderPickerDialog(
            onPick = { path, repository -> draft = draft?.addLocation(path, repository = repository); picking = false },
            onDismiss = { picking = false },
        )
    }
    if (confirmDeletes.isNotEmpty()) {
        val d = draft
        val names = confirmDeletes.joinToString(", ") { it.title }
        val entries = confirmDeletes.sumOf { it.entries ?: 0 }
        val into = d?.wideScope?.title ?: "the project-wide scope"
        ConfirmDialog(
            if (confirmDeletes.size == 1) "Delete the scope $names?" else "Delete the scopes $names?",
            (if (entries > 0) "Their $entries entries move to $into." else "Their entries move to $into.") +
                " Sessions and settings that use ${if (confirmDeletes.size == 1) "it" else "them"} lose it.",
            "Delete and save",
            onConfirm = { d?.let(::save) },
            onDismiss = { confirmDeletes = emptyList() },
        )
    }
}

@Composable
private fun ScopeEditor(d: ProjectDraft, s: ProjectScope, cfg: ProjectsConfig, showProblem: Boolean, set: (ProjectDraft) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                s.title, { set(d.renameScope(s.key, it)) }, label = { Text("Scope title") }, singleLine = true,
                isError = showProblem && s.title.isBlank(), modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { set(d.removeScope(s.key)) }, enabled = d.canRemove(s.key)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${s.title.ifBlank { "scope" }}")
            }
        }
        val facts = listOfNotNull(
            s.id?.let { "Id: $it" } ?: "New: its id comes from the title",
            s.entries?.let { n -> "$n ${if (n == 1) "entry" else "entries"}" },
        )
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        SwitchRow(
            "Project-wide", s.wide, { if (it) set(d.setWide(s.key)) },
            detail = if (s.wide) "Shows everything in the project. Make another scope project-wide to change this." else "Show everything in the project.",
            enabled = !s.wide,
        )

        Text("Works on", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        if (d.locations.isEmpty()) ListEmptyState("Add a location first.")
        ChipFlow(Modifier.padding(vertical = 4.dp)) {
            d.locations.forEach { l -> Choice(l.name.ifBlank { l.path }, l.path in s.paths) { set(d.togglePath(s.key, l.path)) } }
        }

        if (!s.wide) {
            Text("Also shows", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            val includable = d.includable(s.key)
            if (includable.isEmpty()) {
                Text("Other scopes of the project show here once they are saved.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChipFlow(Modifier.padding(vertical = 4.dp)) {
                includable.forEach { o -> o.id?.let { oid -> Choice(o.title, oid in s.includes) { set(d.toggleInclude(s.key, oid)) } } }
            }
        }

        Text("Reads knowledge from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        val readable = d.readable(s.key, cfg.allScopes)
        if (readable.isEmpty()) {
            Text(
                if (d.elsewhere) "The open instance's scopes can't be read from another instance. Set this up once it is open."
                else "No other saved scopes yet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChipFlow(Modifier.padding(vertical = 4.dp)) {
            readable.forEach { r ->
                val label = if (r.project == d.id || (d.isNew && r.project.isEmpty())) r.title else "${r.projectTitle} · ${r.title}"
                Choice(label, r.id in s.related) { set(d.toggleRelated(s.key, r.id)) }
            }
        }
    }
}

/**
 * Browses the Mac's folders under its home folder (`folders.list`) and picks one. "New folder" makes one in the folder
 * shown (`folders.create`) and goes into it; the Mac's `bad_params` message shows as it is.
 */
@Composable
internal fun FolderPickerDialog(
    onPick: (path: String, repository: Boolean) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose a folder",
) {
    val app = LocalApp.current
    val ui = rememberCoroutineScope()
    var path by remember { mutableStateOf<String?>(null) }
    var repository by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createProblem by remember { mutableStateOf<String?>(null) }
    val (state, retry) = rememberLoad(path) { FolderListing.parse(app.call("folders.list", jsonOf("path" to path))) }
    val listing = state.value

    fun enter(p: String?, repo: Boolean) {
        path = p
        repository = repo
        naming = null
        createProblem = null
    }

    fun create(parent: String, name: String) {
        creating = true
        createProblem = null
        ui.launch {
            try {
                val made = FolderListing.created(app.call("folders.create", FolderListing.createParams(parent, name)))
                if (made != null) enter(made.path, made.repository) else retry()
            } catch (e: RemoteException) {
                createProblem = if (e.code == ErrorCodes.BAD_PARAMS) {
                    e.message?.takeIf { it.isNotBlank() } ?: "The Mac can't make a folder with that name."
                } else {
                    e.userMessage()
                }
            } catch (e: Exception) {
                createProblem = e.userMessage()
            } finally {
                creating = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(listing?.path ?: path ?: "~", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LoadStatus(state, retry, skeletonRows = 3)
                if (listing != null) {
                    val name = naming
                    if (name == null) {
                        TextButton(onClick = { naming = ""; createProblem = null }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("New folder in ${listing.name}")
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            OutlinedTextField(
                                name, { naming = it; createProblem = null }, label = { Text("New folder's name") }, singleLine = true,
                                isError = createProblem != null, modifier = Modifier.fillMaxWidth(),
                            )
                            createProblem?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            ChipFlow(Modifier.padding(top = 4.dp)) {
                                FilledTonalButton(enabled = name.isNotBlank() && !creating, onClick = { create(listing.path, name) }) {
                                    Text(if (creating) "Making…" else "Make folder")
                                }
                                TextButton(onClick = { naming = null; createProblem = null }) { Text("Cancel") }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        listing.parent?.let { parent ->
                            item(key = "up") {
                                ListRow(
                                    "Up", subtitle = parent, onClick = { enter(parent, false) },
                                    leading = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                                )
                            }
                        }
                        if (listing.folders.isEmpty()) item(key = "empty") { ListEmptyState("No folders in here.") }
                        items(listing.folders, key = { it.path }) { f ->
                            ListRow(
                                f.name, subtitle = if (f.repository) "Git repository" else null,
                                onClick = { enter(f.path, f.repository) },
                                leading = { Icon(if (f.repository) Icons.Default.FolderSpecial else Icons.Default.Folder, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = listing != null, onClick = { listing?.let { onPick(it.path, repository) } }) {
                Text(listing?.let { "Use ${it.name}" } ?: "Use this folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
