package dk.langkilde.mindmill.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.mutableLongStateOf
import dk.langkilde.mindmill.remote.macName
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.MindmillApp
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.CancellationException

val LocalApp = staticCompositionLocalOf<MindmillApp> { error("No app") }

/**
 * A container colour and its text colour that read well in light and dark themes and with any wallpaper
 * colours: the primary container pair when it has enough contrast, else the secondary or tertiary one, else
 * the plain surface pair.
 */
@Composable
fun readableContainer(): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return Contrast.pick(
        listOf(
            cs.primaryContainer to cs.onPrimaryContainer,
            cs.secondaryContainer to cs.onSecondaryContainer,
            cs.tertiaryContainer to cs.onTertiaryContainer,
            cs.surfaceContainerHighest to cs.onSurface,
        ),
        argb = { it.toArgb() },
    )
}

@Composable
fun ProvideApp(app: MindmillApp, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalApp provides app, content = content)

/** A message in the layout, never over content. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    error: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = if (error) cs.errorContainer to cs.onErrorContainer else Contrast.pick(
        listOf(
            cs.secondaryContainer to cs.onSecondaryContainer,
            cs.tertiaryContainer to cs.onTertiaryContainer,
            cs.surfaceContainerHighest to cs.onSurface,
        ),
        argb = { it.toArgb() },
    )
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error) Icons.Default.ErrorOutline else Icons.Default.Info, contentDescription = null,
                modifier = Modifier.padding(end = 10.dp).size(20.dp),
            )
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 2.dp)) { Text(actionLabel, color = fg, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
            }
        }
    }
}

/** An empty list says so at the top, where the list would start. */
@Composable
fun ListEmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })

@Composable
fun InfoChip(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** What state something is in, with the same colours everywhere: needs you, running, done, failed, idle. */
enum class Status { NEEDS_YOU, RUNNING, DONE, FAILED, IDLE }

@Composable
fun statusColors(status: Status): StatusColors = when (status) {
    Status.NEEDS_YOU -> extras.needsYou
    Status.RUNNING -> extras.running
    Status.DONE -> extras.done
    Status.FAILED -> extras.failed
    Status.IDLE -> extras.idle
}

/** A small pill with a dot and the status in words; colour is never the only signal. */
@Composable
fun StatusChip(text: String, status: Status, modifier: Modifier = Modifier) {
    val c = statusColors(status)
    Surface(shape = CircleShape, color = c.container, contentColor = c.content, modifier = modifier) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(c.dot, CircleShape))
            Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** A section's heading: what follows belongs to it. [trailing] is a quiet action on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier.padding(vertical = 8.dp).semantics { heading() })
}

@Composable
fun Loading(modifier: Modifier = Modifier, text: String = "Loading…") {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp))
    }
}

/** A search box: clears with its ✕, and back clears it before it leaves the screen. */
@Composable
fun SearchField(query: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        query, onChange,
        placeholder = { Text(label) },
        singleLine = true,
        shape = CircleShape,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
    )
    ClearOnBack(query.isNotEmpty()) { onChange(""); focus.clearFocus() }
}

/** Back undoes [clear] first while [active], before it leaves the screen. */
@Composable
fun ClearOnBack(active: Boolean, clear: () -> Unit) {
    BackHandler(enabled = active, onBack = clear)
}

/** Advanced options stay folded away until asked for. */
@Composable
fun MoreOptions(label: String = "More options", content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (open) content()
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .let { if (onClick != null) it.clip(MaterialTheme.shapes.small).clickable(onClick = onClick) else it }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.padding(end = 14.dp)) { leading() }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
                if (below != null) Box(Modifier.padding(top = 6.dp)) { below() }
            }
            if (trailing != null) {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/** A tinted circle holding an icon, at the start of a row or tile. */
@Composable
fun IconBadge(icon: ImageVector, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Rows grouped on one rounded card, the way Settings groups them. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content = content)
    }
}

@Composable
fun ConfirmationDialog() {
    val app = LocalApp.current
    val q by app.confirmations.pending.collectAsState()
    val question = q ?: return
    AlertDialog(
        onDismissRequest = { app.confirmations.answer(false) },
        title = { Text("The Mac asks") },
        text = { Text(question.text) },
        confirmButton = { TextButton(onClick = { app.confirmations.answer(true) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { app.confirmations.answer(false) }) { Text("Cancel") } },
    )
}

/** The scope requests go to ([MindmillApp.currentScope]), as state: lists that depend on it load again when it changes. */
@Composable
fun rememberCurrentScope(): String? {
    val app = LocalApp.current
    val chosen by app.chosenScope.collectAsState()
    val hello by app.connection.lastHello.collectAsState()
    return dk.langkilde.mindmill.remote.Scopes.resolve(chosen, hello?.scope, hello?.scopes)
}

/** [waiting]: nothing loaded yet because the Mac isn't connected; not an error, the load runs once it is. */
class Loaded<T>(val value: T?, val loading: Boolean, val error: String?, val waiting: Boolean = false, val pulled: Boolean = false) {
    /** A reload the user asked for (pull to refresh, Retry) is running; background reloads stay quiet. */
    val refreshing get() = loading && pulled && value != null
}

/**
 * Loads from the Mac, again on `index.changed`, on reconnect, when [trigger] changes and when the returned refresh is
 * called. New [keys] start from empty; a [trigger] reload keeps showing the old value until the new one is in. While
 * the Mac isn't connected it waits instead of failing.
 */
@Composable
fun <T> rememberLoad(vararg keys: Any?, trigger: Any? = null, load: suspend () -> T): Pair<Loaded<T>, () -> Unit> {
    val app = LocalApp.current
    val refresh by app.refresh.collectAsState()
    val conn by app.connection.state.collectAsState()
    val connected = conn is ConnectionState.Connected
    var manual by remember { mutableIntStateOf(0) }
    var pulled by remember { mutableStateOf(false) }
    var state by remember(*keys) { mutableStateOf(Loaded<T>(null, true, null, waiting = !connected)) }
    LaunchedEffect(*keys, trigger, refresh, manual, connected) {
        if (!connected) {
            state = Loaded(state.value, false, null, waiting = state.value == null)
            return@LaunchedEffect
        }
        state = Loaded(state.value, true, null, pulled = pulled)
        state = try {
            Loaded(load(), false, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded(state.value, false, e.userMessage())
        }
        pulled = false
    }
    return state to {
        if (!connected) app.connection.retryNow()
        pulled = true
        manual++
    }
}

/** Loading, waiting for the Mac, or what went wrong, in the place the content goes. */
@Composable
fun LoadStatus(state: Loaded<*>, onRetry: () -> Unit, skeletonRows: Int = 4) {
    when {
        state.waiting -> WaitingForMac(onRetry)
        state.loading && state.value == null -> SkeletonRows(skeletonRows)
    }
    state.error?.let { ErrorState(it, onRetry, compact = state.value != null) }
}

/** Shown where a list goes while the Mac isn't connected yet: quiet while connecting, with Retry once it can't reach it. */
@Composable
fun WaitingForMac(onRetry: () -> Unit) {
    val app = LocalApp.current
    val conn by app.connection.state.collectAsState()
    val name = conn.macName ?: "your Mac"
    when (conn) {
        is ConnectionState.Unreachable, is ConnectionState.Reconnecting -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("This shows once Mindmill reaches $name.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Retry now") }
        }
        else -> {
            SkeletonRows(3)
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.errorContainer, contentColor = cs.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = if (compact) 8.dp else 12.dp, bottom = 4.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp).size(20.dp))
            Column(Modifier.weight(1f)) {
                if (!compact) Text("Couldn't load this", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("Retry", color = cs.onErrorContainer) }
                } else {
                    Box(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** An empty list says so at the top, with the next step when there is one. */
@Composable
fun EmptyState(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, detail: String? = null, icon: ImageVector? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            IconBadge(icon ?: Icons.Default.Inbox, size = 36.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                if (actionLabel != null && onAction != null) {
                    FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 10.dp)) { Text(actionLabel) }
                }
            }
        }
    }
}

/** Placeholder rows while a list loads, shaped like the rows that will come. */
@Composable
fun SkeletonRows(count: Int = 4) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    val tone = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = "Loading" }) {
        repeat(count) { i ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(tone, CircleShape))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.75f else 0.6f).height(14.dp).background(tone, RoundedCornerShape(7.dp)))
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.4f).height(10.dp).background(tone, RoundedCornerShape(5.dp)))
                }
            }
        }
    }
}

/** A list that refreshes when pulled down. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshList(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp), content = content)
    }
}

/** Asks before something that can't be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Seconds left until [atMillis], ticking. */
@Composable
fun secondsUntil(atMillis: Long): Int {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(atMillis) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= atMillis) break
            delay(250)
        }
    }
    return ((atMillis - now + 999) / 1000).toInt().coerceAtLeast(0)
}

/** A card with a title, used for groups on detail screens. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            }
            content()
        }
    }
}

/** A labelled switch row that wraps its text instead of squeezing. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Pick one of [options] (id to title) from a menu; the field shows the current title. */
@Composable
fun ChoiceField(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "—" }
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}
