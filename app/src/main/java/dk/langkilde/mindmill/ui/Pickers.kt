package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.Scopes
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.store.StoredPairing
import kotlinx.coroutines.launch

/** How a paired Mac is doing, in words and a status colour. Only the active Mac has a connection. */
fun macStatus(mac: StoredPairing, active: Boolean, conn: ConnectionState): Pair<String, Status> = when {
    !active -> (mac.lastHost?.let { "Paired · last reached at $it" } ?: "Paired") to Status.IDLE
    conn is ConnectionState.Connected -> "Connected at ${conn.host}" to Status.DONE
    conn is ConnectionState.Connecting -> "Connecting…" to Status.RUNNING
    conn is ConnectionState.Reconnecting -> "Reconnecting…" to Status.RUNNING
    conn is ConnectionState.Unreachable -> "Can't reach it right now" to Status.FAILED
    conn is ConnectionState.Unauthorized -> "Needs pairing again" to Status.NEEDS_YOU
    else -> "Not connected" to Status.IDLE
}

/** A row in the drawer that opens a menu: the current project or Mac. Its text wraps rather than being cut off. */
@Composable
private fun PickerRow(icon: ImageVector, heading: String, value: String, detail: String?, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.labelLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Selected(on: Boolean) {
    if (on) Icon(Icons.Default.Check, contentDescription = "Current") else Spacer(Modifier.size(24.dp))
}

/**
 * The Mac the phone talks to and how the connection is; with more than one paired, a menu to switch. With one, it
 * opens Connection.
 */
@Composable
fun MacPicker(go: (Screen) -> Unit) {
    val app = LocalApp.current
    val pairings by app.connection.pairings.collectAsState()
    val conn by app.connection.state.collectAsState()
    val active = pairings.active ?: return
    var open by remember { mutableStateOf(false) }
    val (status, _) = macStatus(active, true, conn)
    Box {
        PickerRow(Icons.Default.LaptopMac, "Mac", active.macName, status, "Mac: ${active.macName}, $status. Switch Mac") {
            if (pairings.macs.size < 2) go(Screen.Connection) else open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 320.dp)) {
            MenuHeading("Your Macs")
            pairings.macs.forEach { mac ->
                val isActive = pairings.isActive(mac)
                val (status, _) = macStatus(mac, isActive, conn)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(mac.macName, style = MaterialTheme.typography.bodyLarge)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Selected(isActive) },
                    onClick = { open = false; app.switchMac(mac.fingerprint) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Pair another Mac…") }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { open = false; go(Screen.PairMac) },
            )
            DropdownMenuItem(
                text = { Text("Manage Macs…") }, leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { open = false; go(Screen.Connection) },
            )
        }
    }
}

/**
 * The phone's own project and scope on this Mac, by project with the project-wide scope first. With "Switch the Mac
 * too" a pick also makes it the Mac's current one. Stays usable while the Mac is away, from what it said last.
 */
@Composable
fun ScopePicker(go: (Screen) -> Unit) {
    val app = LocalApp.current
    val hello by app.connection.lastHello.collectAsState()
    val settings by app.settings.collectAsState()
    val current = rememberCurrentScope()
    val ui = rememberCoroutineScope()
    val scopes = hello?.scopes.orEmpty()
    val instance = rememberOpenInstance()
    if (scopes.isEmpty() && current == null) return
    var open by remember { mutableStateOf(false) }
    val chosen = scopes.firstOrNull { it.id == current }
    val label = chosen?.let(Scopes::label) ?: current ?: "Pick a project"
    Box {
        PickerRow(Icons.Default.Folder, "Project", label, null, "Project: $label. Change project") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 320.dp)) {
            if (scopes.isEmpty()) {
                Text(
                    "The projects show once the Mac is connected.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Scopes.grouped(scopes).forEachIndexed { i, g ->
                if (i > 0) HorizontalDivider()
                g.title?.let { MenuHeading(it) }
                g.scopes.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                val notes = listOfNotNull(
                                    "Whole project".takeIf { s.wide && g.title != null },
                                    "Current on the Mac".takeIf { s.id == hello?.scope },
                                )
                                if (notes.isNotEmpty()) {
                                    Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        leadingIcon = { Selected(s.id == current) },
                        onClick = { open = false; app.setScope(s.id, alsoOnMac = settings.alsoSwitchMac) },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Switch the Mac too")
                        Text("Picking a project here also makes it the Mac's current one.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                leadingIcon = { Checkbox(checked = settings.alsoSwitchMac, onCheckedChange = null) },
                onClick = { app.updateSettings { it.copy(alsoSwitchMac = !it.alsoSwitchMac) } },
            )
            if (current != null && hello != null && current != hello?.scope) {
                DropdownMenuItem(
                    text = { Text("Make ${chosen?.title ?: "it"} current on the Mac") },
                    leadingIcon = { Icon(Icons.Default.LaptopMac, contentDescription = null) },
                    onClick = {
                        open = false
                        ui.launch {
                            app.runAndReport("project.select", jsonOf("scope" to current))
                            runCatching { app.connection.refreshState() }
                        }
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Manage projects…") }, leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { open = false; go(Screen.Projects) },
            )
            instance?.let { i ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Instances…")
                            Text("Open: ${i.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
                    onClick = { open = false; go(Screen.Instances) },
                )
            }
        }
    }
}
