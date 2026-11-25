package dk.langkilde.mindmill.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.ChatItem
import dk.langkilde.mindmill.remote.ChatLog
import dk.langkilde.mindmill.remote.ChatRow
import dk.langkilde.mindmill.remote.ChatRows
import dk.langkilde.mindmill.remote.ChoiceOption
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.ReplyPart
import dk.langkilde.mindmill.remote.ReplyParts
import dk.langkilde.mindmill.remote.ToolSteps
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import dk.langkilde.mindmill.voice.ContinuousRecognizer
import dk.langkilde.mindmill.voice.LiveChat
import dk.langkilde.mindmill.voice.LivePhase
import dk.langkilde.mindmill.voice.LiveUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Speech to text for a text field: one utterance at a time, on the phone. */
class Dictation {
    var listening by mutableStateOf(false)
        internal set
    var partial by mutableStateOf("")
        internal set
    internal var startFn: () -> Unit = {}
    internal var stopFn: () -> Unit = {}
    fun toggle() = if (listening) stopFn() else startFn()
}

@Composable
fun rememberDictation(onText: (String) -> Unit): Dictation {
    val app = LocalApp.current
    val context = LocalContext.current
    val d = remember { Dictation() }
    val recognizer = remember {
        ContinuousRecognizer(context, object : ContinuousRecognizer.Listener {
            override fun onPartial(text: String) { d.partial = text }
            override fun onFinal(text: String) {
                d.partial = ""
                if (text.isNotBlank()) onText(text)
                d.stopFn()
            }
            override fun onProblem(message: String) {
                d.listening = false
                d.partial = ""
                app.done(message, error = true)
            }
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { d.listening = true; recognizer.start(Locale.getDefault()) } else app.done("Mindmill needs the microphone for dictation.", error = true)
    }
    d.startFn = {
        when {
            app.voice.ui.value.active || app.liveChat.active -> app.done("End the conversation first: both need the microphone.", error = true)
            app.meeting.isLive -> app.done("A meeting is recording on the phone: it has the microphone.", error = true)
            context.hasPermission(Manifest.permission.RECORD_AUDIO) -> { d.listening = true; recognizer.start(Locale.getDefault()) }
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    d.stopFn = { d.listening = false; d.partial = ""; recognizer.stop() }
    DisposableEffect(Unit) { onDispose { recognizer.release() } }
    return d
}

/**
 * Starts live chat once the phone may use the microphone (and show the notification that keeps it going with the
 * screen off). Not while a voice conversation or a meeting on the phone has the microphone.
 */
@Composable
fun rememberLiveStarter(): (() -> Unit) -> Unit {
    val app = LocalApp.current
    val context = LocalContext.current
    val settings by app.settings.collectAsState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) pending?.invoke()
        else app.done("Mindmill needs the microphone to talk live.", error = true)
        pending = null
    }
    return { start ->
        when {
            app.meeting.isLive -> app.done("A meeting is recording on the phone. Stop it first: both need the microphone.", error = true)
            app.voice.ui.value.active -> app.done("End the voice conversation first: both need the microphone.", error = true)
            else -> {
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    if (Build.VERSION.SDK_INT >= 31 && settings.routeToHeadset) add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (wanted.all { context.hasPermission(it) }) start() else {
                    pending = start
                    permissions.launch(wanted.toTypedArray())
                }
            }
        }
    }
}

/**
 * The one message box of every session and of a new one. Empty, its button starts live chat (talking, with the reply
 * read aloud); with text, it sends. While live chat runs here the button ends it.
 */
@Composable
fun Composer(
    onSend: (String) -> Unit,
    liveHere: Boolean,
    onLive: () -> Unit,
    onEndLive: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    draftKey: String = "",
) {
    var draft by rememberSaveable(draftKey) { mutableStateOf("") }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            draft, { draft = it }, placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f), maxLines = 6, shape = RoundedCornerShape(20.dp),
        )
        Box(Modifier.padding(start = 6.dp)) {
            when {
                draft.isNotBlank() -> FilledIconButton(onClick = {
                    val text = draft.trim()
                    draft = ""
                    onSend(text)
                }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                liveHere -> FilledIconButton(
                    onClick = onEndLive,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Icon(Icons.Default.Stop, contentDescription = "End live chat") }
                else -> FilledTonalIconButton(onClick = onLive) { Icon(Icons.Default.GraphicEq, contentDescription = "Talk live") }
            }
        }
    }
}

/**
 * What live chat is doing, in the layout above the message box: what it heard so far (sent when the user pauses),
 * and the buttons that fit the moment.
 */
@Composable
fun LiveBar(live: LiveChat, ui: LiveUi) {
    val cs = MaterialTheme.colorScheme
    ui.notice?.let { NoticeBar(it, Modifier.padding(top = 4.dp), error = true, onDismiss = { live.dismissNotice() }) }
    Surface(
        color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    liveStatus(ui),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                TextButton(onClick = { live.end() }) { Text("End") }
            }
            val heard = listOf(ui.draft, ui.partial).filter { it.isNotBlank() }.joinToString(" ")
            if (heard.isNotEmpty()) {
                Text(heard, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp, end = 8.dp))
            }
            val speaking = ui.phase == LivePhase.SPEAKING
            if (speaking || ui.working || heard.isNotEmpty()) {
                ChipFlow {
                    if (heard.isNotEmpty()) {
                        TextButton(onClick = { live.sendHeard() }) { Text("Send now") }
                        if (ui.draft.isNotEmpty()) TextButton(onClick = { live.clearHeard() }) { Text("Clear") }
                    }
                    if (speaking) TextButton(onClick = { live.stopSpeaking() }) { Text("Stop talking") }
                    if (ui.working) TextButton(onClick = { live.stopWork() }) { Text("Stop Claude") }
                }
            }
        }
    }
}

private fun liveStatus(ui: LiveUi): String = when (ui.phase) {
    LivePhase.LISTENING -> if (ui.draft.isNotEmpty()) "Listening. Sends when you pause." else "Listening…"
    LivePhase.WAITING -> if (ui.working) "Working on it. Keep talking to add more, or say “stop”." else "Listening…"
    LivePhase.SPEAKING -> "Speaking. Talk to interrupt."
    LivePhase.PAUSED -> "Paused while another app has the audio"
    LivePhase.IDLE -> "Starting…"
}

/**
 * One session's chat: loads with `session.get`, follows `session.changed`, and sends with `session.send`. The steps
 * Claude takes fold into one row between messages; the last reply's options are buttons while the session waits;
 * a refused tool can be allowed here. [header] goes above the messages (inside the list, so it scrolls away).
 */
@Composable
fun ChatPane(
    id: String,
    emptyText: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    onTitle: (String) -> Unit = {},
    header: @Composable ColumnScope.(userMessages: Int) -> Unit = {},
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val conn by app.connection.state.collectAsState()
    val live by app.liveChat.ui.collectAsState()
    val startLive = rememberLiveStarter()
    val log = remember(id) { ChatLog() }
    var version by remember(id) { mutableIntStateOf(0) }
    var loaded by remember(id) { mutableStateOf(false) }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    val lock = remember(id) { Mutex() }
    val listState = rememberLazyListState()

    suspend fun fetch() = lock.withLock {
        try {
            val since = log.since
            val r = app.call("session.get", jsonOf("id" to id, "since" to since))
            if (log.apply(r, since)) log.apply(app.call("session.get", jsonOf("id" to id, "since" to 0)), 0)
            log.title?.let(onTitle)
            problem = null
        } catch (e: Exception) {
            problem = e.userMessage()
        }
        loaded = true
        version++
    }

    fun send(text: String) {
        log.addPending(text)
        version++
        scope.launch {
            try {
                app.call("session.send", jsonOf("id" to id, "text" to text))
                fetch()
            } catch (e: Exception) {
                log.dropPending(text)
                version++
                app.done("Not sent: ${e.userMessage()}", error = true)
            }
        }
    }

    LaunchedEffect(id, conn is ConnectionState.Connected) { if (conn is ConnectionState.Connected) fetch() }
    LaunchedEffect(id) {
        app.events.collect { e ->
            if (e.name == "session.changed" && e.data.text("id") == id) fetch()
        }
    }
    val items = remember(version) { log.items }
    val rows = remember(version) { ChatRows.group(items) }
    val active = remember(version) { ReplyParts.activeIndex(items, log.busy) }
    val lastIsSteps = rows.lastOrNull() is ChatRow.Steps
    // Opening a session lands at the end at once; only what arrives afterwards glides into view.
    var shownCount by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(items.size, log.busy) {
        if (items.isEmpty()) return@LaunchedEffect
        // The list may not have laid the new rows out yet; past the end lands on the last one.
        val last = maxOf(listState.layoutInfo.totalItemsCount - 1, rows.size + 8)
        if (shownCount == 0 || items.size - shownCount > 2) listState.scrollToItem(last)
        else listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        shownCount = items.size
    }
    val liveHere = live.active && live.session == id

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "head") { Column(Modifier.fillMaxWidth()) { header(items.count { it.role == "user" }) } }
            if (!loaded) item(key = "loading") { SkeletonRows(3) }
            else if (items.isEmpty()) item(key = "empty") { ListEmptyState(emptyText) }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.Steps -> StepsRow(row, running = log.busy && row == rows.last())
                    is ChatRow.Message -> when (row.item.role) {
                        "assistant" -> AssistantMessage(row.item.text, if (row.index == active) ::send else null)
                        "status" -> QuietLine(row.item.text)
                        else -> ChatBubble(row.item)
                    }
                }
            }
            if (log.busy && !lastIsSteps) {
                item(key = "working") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Working…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        problem?.let { ErrorState(it, onRetry = { scope.launch { fetch() } }, compact = true) }
        if (log.state == "blocked" && !log.busy) PermissionCard(id, log.denials)
        if (liveHere) {
            LiveBar(app.liveChat, live)
        }
        Composer(
            placeholder = placeholder, draftKey = id,
            onSend = ::send,
            liveHere = liveHere,
            onLive = { startLive { app.liveChat.startInSession(id) } },
            onEndLive = { app.liveChat.end() },
        )
    }
}

/** Claude was refused a tool and waits: what it wanted, and Allow for this session (the `session.allow` action). */
@Composable
private fun PermissionCard(session: String, denials: List<String>) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember(session, denials) { mutableStateOf(false) }
    val brain = app.connection.hello?.brain ?: "Claude"
    val colors = statusColors(Status.NEEDS_YOU)
    Surface(
        color = colors.container, contentColor = colors.content, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PanTool, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (denials.isEmpty()) "$brain needs your permission to go on." else "$brain wasn't allowed to run:",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp),
                )
            }
            denials.forEach { d ->
                Text(d, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            ChipFlow(Modifier.padding(top = 6.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        app.runAndReport("session.allow", jsonOf("id" to session))
                        busy = false
                    }
                }) { Text("Allow for this session") }
            }
        }
    }
}

/** The steps Claude took between two messages, in one line (the latest and how many); open it to see them all. */
@Composable
private fun StepsRow(steps: ChatRow.Steps, running: Boolean) {
    var open by rememberSaveable(steps.key) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val count = if (steps.count == 1) "1 step" else "${steps.count} steps"
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (open) "Hide the steps" else "Show the steps") { open = !open }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Build, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(if (running) "Working… · $count" else count, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    if (!open) Text(steps.latest, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant)
            }
            if (open) {
                Column(Modifier.padding(start = 38.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.items.forEach { item ->
                        val step = ToolSteps.step(item)
                        Text(step.title, style = MaterialTheme.typography.bodySmall)
                        step.code?.let { code ->
                            Surface(color = cs.surfaceContainerHighest, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                                SelectionContainer {
                                    Text(code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 8,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A note from the app in the transcript (stopped, cancelled), quietly. */
@Composable
private fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
}

/**
 * A reply: Markdown, with its ```choices blocks as buttons when [onChoose] is given (the latest reply, while the
 * session waits for the user); otherwise the options are listed as plain text.
 */
@Composable
private fun AssistantMessage(text: String, onChoose: ((String) -> Unit)?) {
    val parts = remember(text) { ReplyParts.parse(text) }
    val trailing = remember(text) { ReplyParts.trailing(text) }
    Box(Modifier.fillMaxWidth(), contentAlignment = BiasAlignment(-1f, 0f)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(end = 24.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEach { part ->
                    when (part) {
                        is ReplyPart.Text -> SelectionContainer { Text(markdownish(part.markdown), style = MaterialTheme.typography.bodyMedium) }
                        is ReplyPart.Choices -> if (onChoose != null && part == trailing) ChoiceButtons(part, onChoose) else ChoiceList(part)
                    }
                }
            }
        }
    }
}

/** Options to answer with, as buttons that wrap whole; the recommended one stands out. Several picks: tick, then send. */
@Composable
private fun ChoiceButtons(choices: ReplyPart.Choices, onChoose: (String) -> Unit) {
    val options = if (choices.strategies && choices.options.none { it.label.equals("No strategy", true) }) {
        choices.options + ChoiceOption("No strategy", false)
    } else choices.options
    var picked by remember(choices) { mutableStateOf(listOf<ChoiceOption>()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipFlow {
            options.forEach { o ->
                val on = o in picked
                val label: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (choices.multi) {
                            Icon(if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp).size(18.dp))
                        }
                        Column {
                            Text(o.label)
                            if (o.recommended) Text("Recommended", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                val click = {
                    if (choices.multi) picked = if (on) picked - o else picked + o
                    else onChoose(ReplyParts.answer(choices, listOf(o)))
                }
                if (o.recommended || on) Button(onClick = click) { label() } else OutlinedButton(onClick = click) { label() }
            }
        }
        if (choices.multi) {
            Button(enabled = picked.isNotEmpty(), onClick = { onChoose(ReplyParts.answer(choices, options.filter { it in picked })) }) {
                Text(if (picked.isEmpty()) "Pick one or more" else "Send ${picked.size} picked")
            }
        }
        Text("Or write your own answer below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Options of an earlier reply, or of one that no longer waits: as they were offered, not to tap. */
@Composable
private fun ChoiceList(choices: ReplyPart.Choices) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        choices.options.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp))
                Text(o.label + if (o.recommended) " (recommended)" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
fun ChatBubble(m: ChatItem) = ChatBubble(m.role, m.text, m.pending)

@Composable
fun ChatBubble(role: String, text: String, pending: Boolean = false) {
    val mine = role == "user"
    val quiet = role == "status" || role == "tool"
    val color = when {
        mine -> MaterialTheme.colorScheme.primaryContainer
        role == "error" -> MaterialTheme.colorScheme.errorContainer
        quiet -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) BiasAlignment(1f, 0f) else BiasAlignment(-1f, 0f)) {
        Surface(
            color = color, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(
                        if (quiet) AnnotatedString(text) else markdownish(text),
                        style = if (quiet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = if (pending) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                }
                if (pending) Text("Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Basic Markdown for chat: headings, **bold**, `code`, and bullets; the rest stays plain text. */
fun markdownish(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    var inCode = false
    lines.forEachIndexed { i, raw ->
        if (raw.trimStart().startsWith("```")) {
            inCode = !inCode
            return@forEachIndexed
        }
        if (inCode) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw) }
        } else {
            var line = raw
            val heading = Regex("^#{1,6}\\s+").find(line)
            if (heading != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line.removeRange(heading.range)) }
            } else {
                Regex("^(\\s*)[-*]\\s+").find(line)?.let { m -> line = m.groupValues[1] + "• " + line.substring(m.range.last + 1) }
                inline(line)
            }
        }
        if (i < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.inline(s: String) {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var at = 0
    for (m in pattern.findAll(s)) {
        append(s.substring(at, m.range.first))
        val bold = m.groups[1]?.value
        if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groups[2]?.value.orEmpty()) }
        at = m.range.last + 1
    }
    append(s.substring(at))
}
