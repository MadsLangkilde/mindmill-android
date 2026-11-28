package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import dk.langkilde.mindmill.remote.ErrorCodes
import dk.langkilde.mindmill.remote.InstanceInfo
import dk.langkilde.mindmill.remote.InstanceList
import dk.langkilde.mindmill.remote.InstanceRef
import dk.langkilde.mindmill.remote.InstanceSwitchAnswer
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch

/** The instance open on the active Mac, as `hello` named it; null for a Mac older than instances. */
@Composable
fun rememberOpenInstance(): InstanceRef? {
    val app = LocalApp.current
    val hello by app.connection.lastHello.collectAsState()
    val missing by app.instancesMissing.collectAsState()
    return hello?.instance?.takeIf { !missing }
}

/**
 * The Mac's Mindmill instances (`instances.list`): which one is open, switching to another (Mindmill restarts on the
 * Mac, so it asks first), and making a new one in a folder picked on the Mac.
 */
@Composable
fun InstancesScreen() {
    val app = LocalApp.current
    val ui = rememberCoroutineScope()
    val (state, retry) = rememberLoad { app.instances() }
    val switching by app.connection.switching.collectAsState()
    val mac = app.connection.pairedMacName ?: "the Mac"
    var confirm by remember { mutableStateOf<InstanceInfo?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val list = state.value

    RefreshList(state.refreshing, retry) {
        item(key = "head") {
            Column {
                LoadStatus(state, retry)
                if (list == null && !state.loading && !state.waiting && state.error == null) {
                    ListEmptyState("Mindmill on $mac doesn't have instances yet. Update Mindmill on the Mac to use them.")
                }
                if (list != null) {
                    Text(
                        "Each instance is a knowledge base of its own, with its own projects, sessions and factories. " +
                            "One is open on $mac at a time.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    problem?.let { NoticeBar(it, Modifier.padding(vertical = 6.dp), error = true, onDismiss = { problem = null }) }
                    if (creating) {
                        NewInstanceForm(
                            mac,
                            onCreated = { answer, name ->
                                creating = false
                                if (!answer.switching) {
                                    app.done("Created $name.")
                                    retry()
                                }
                            },
                            onCancel = { creating = false },
                        )
                    } else {
                        ChipFlow(Modifier.padding(vertical = 6.dp)) {
                            FilledTonalButton(enabled = switching == null, onClick = { creating = true }) { Text("New instance") }
                        }
                    }
                    if (list.instances.isEmpty()) ListEmptyState("$mac lists no instances.")
                }
            }
        }
        items(list?.instances.orEmpty(), key = { it.id }) { i ->
            InstanceCard(
                i, mac, open = i.id == list?.current, opening = switching?.id == i.id, canSwitch = switching == null,
            ) { confirm = i }
        }
    }

    confirm?.let { i ->
        ConfirmDialog(
            "Open ${i.name}?",
            "Mindmill restarts on $mac with ${i.name} open, which takes a few seconds. The phone reconnects by itself.",
            "Switch",
            destructive = false,
            onConfirm = {
                problem = null
                ui.launch {
                    try {
                        if (!app.switchInstance(i.id, i.name)) {
                            app.done("${i.name} is open already.")
                            retry()
                        }
                    } catch (e: Exception) {
                        problem = e.userMessage()
                    }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun InstanceCard(i: InstanceInfo, mac: String, open: Boolean, opening: Boolean, canSwitch: Boolean, onSwitch: () -> Unit) {
    val switchable = !open && i.exists && canSwitch
    GroupCard(Modifier.padding(vertical = 6.dp)) {
        ListRow(
            i.name,
            subtitle = listOfNotNull(i.path.takeIf { it.isNotBlank() }, i.projects?.let { plural(it, "project") }).joinToString(" · "),
            onClick = if (switchable) onSwitch else null,
            leading = { IconBadge(Icons.Default.Layers, size = 36.dp) },
            divider = false,
            below = {
                Column {
                    if (!i.exists) {
                        Text(
                            "Its folder is gone from $mac. Find it again or remove it on the Mac.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ChipFlow(Modifier.padding(top = 2.dp)) {
                        when {
                            open -> StatusChip("Open on $mac", Status.DONE)
                            opening -> StatusChip("Opening…", Status.RUNNING)
                            switchable -> OutlinedButton(onClick = onSwitch) { Text("Switch to it") }
                        }
                    }
                }
            },
        )
    }
}

/**
 * A new instance: its name, its folder on the Mac (picked with `folders.list`, or made there), and whether to open it
 * now. The Mac's `bad_params` message shows as it is.
 */
@Composable
private fun NewInstanceForm(mac: String, onCreated: (InstanceSwitchAnswer, String) -> Unit, onCancel: () -> Unit) {
    val app = LocalApp.current
    val ui = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var open by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var macProblem by remember { mutableStateOf<String?>(null) }

    SectionCard("New instance") {
        Text(
            "Its knowledge base goes in a folder on $mac: a new, empty one, or one that holds a knowledge base already.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            name, { name = it }, label = { Text("Name *") }, singleLine = true, isError = tried && name.isBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Text("Folder", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        Text(
            path ?: "None chosen yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (tried && path == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (path == null) "Choose a folder" else "Choose another folder")
        }
        SwitchRow("Open it now", open, { open = it }, detail = "Mindmill restarts on $mac with the new instance open.")
        val local = if (tried) InstanceList.createProblem(name, path) else null
        (macProblem ?: local)?.let { NoticeBar(it, Modifier.padding(vertical = 6.dp), error = true) }
        ChipFlow(Modifier.padding(top = 6.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    tried = true
                    macProblem = null
                    val folder = path
                    if (InstanceList.createProblem(name, folder) != null || folder == null) return@Button
                    busy = true
                    ui.launch {
                        try {
                            onCreated(app.createInstance(name, folder, open), name.trim())
                        } catch (e: RemoteException) {
                            macProblem = if (e.code == ErrorCodes.BAD_PARAMS) {
                                e.message?.takeIf { it.isNotBlank() } ?: "The Mac can't make an instance like this."
                            } else {
                                e.userMessage()
                            }
                        } catch (e: Exception) {
                            macProblem = e.userMessage()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Creating…" else "Create instance") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    if (picking) {
        FolderPickerDialog(
            title = "Choose the instance's folder",
            onPick = { p, _ -> path = p; picking = false },
            onDismiss = { picking = false },
        )
    }
}
