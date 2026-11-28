package dk.langkilde.mindmill.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.userMessage
import dk.langkilde.mindmill.voice.KeyCheck
import dk.langkilde.mindmill.voice.VoiceActionCard
import dk.langkilde.mindmill.voice.VoiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun VoiceScreen(nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by app.voice.ui.collectAsState()
    val settings by app.settings.collectAsState()
    val brain = app.connection.hello?.brain ?: "your Mac"

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            scope.launch { app.voice.begin(app.currentScope, settings.keylessOnly) }
        } else {
            app.done("Mindmill needs the microphone to talk.", error = true)
        }
    }

    fun start() {
        if (app.meeting.isLive) {
            app.done("A meeting is recording on the phone. Stop it first: both need the microphone.", error = true)
            return
        }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31 && settings.routeToHeadset) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (wanted.all { context.hasPermission(it) }) {
            scope.launch { app.voice.begin(app.currentScope, settings.keylessOnly) }
        } else {
            permissions.launch(wanted.toTypedArray())
        }
    }

    val listState = rememberLazyListState()
    val rows = ui.bubbles.size + ui.cards.size
    LaunchedEffect(rows, ui.partial) { if (rows > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val optionsMax = maxHeight * 0.6f
        Column(Modifier.fillMaxSize()) {
            ui.notice?.let { NoticeBar(it, Modifier.padding(vertical = 4.dp), onDismiss = { app.voice.dismissNotice() }) }
            if (!ui.active) {
                Button(onClick = ::start, enabled = !ui.starting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ui.starting) "Starting…" else "Talk to $brain")
                }
                VoiceEngineLine(Modifier.padding(top = 6.dp))
                Text(
                    "Talk as you would to a colleague: it can do anything Mindmill on the Mac can. Start speaking while it " +
                        "answers to interrupt. It keeps going with the screen off.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
                )
                ChipFlow {
                    OutlinedButton(onClick = { nav.go(Screen.NewSession(Mode.PLAN.id)) }) { Text("Type instead") }
                    TextButton(onClick = { nav.go(Screen.AllActions()) }) { Text("All actions") }
                }
            } else {
                Text("${ui.phase} · ${ui.engineLabel}" + (app.voiceDevice.routedTo?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall)
                ChipFlow(Modifier.padding(vertical = 6.dp)) {
                    Button(onClick = { app.voice.end() }) { Text("End") }
                    TextButton(onClick = { nav.go(Screen.NewSession(Mode.PLAN.id)) }) { Text("Type instead") }
                    if (ui.phase == "Speaking" || ui.phase == "Thinking") OutlinedButton(onClick = { app.voice.stopSpeaking() }) { Text("Stop") }
                    if (ui.paused) OutlinedButton(onClick = { app.voice.resume() }) { Text("Resume") }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.bubbles.isEmpty() && ui.partial.isBlank()) {
                    item { ListEmptyState(if (ui.active) "Listening. Say something." else "No conversation yet.") }
                }
                items(ui.bubbles) { b -> ChatBubble(if (b.role == "user") "user" else "assistant", b.text) }
                if (ui.partial.isNotBlank()) item { ChatBubble("status", ui.partial) }
                items(ui.cards) { card -> VoiceCard(card, nav) }
            }
            if (!ui.active) {
                Column(Modifier.fillMaxWidth().heightIn(max = optionsMax).verticalScroll(rememberScrollState())) {
                    MoreOptions {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Always use the phone's own voice", modifier = Modifier.weight(1f))
                            Switch(settings.keylessOnly, { v -> app.updateSettings { it.copy(keylessOnly = v) } })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Use a Bluetooth headset or car when connected", modifier = Modifier.weight(1f))
                            Switch(settings.routeToHeadset, { v -> app.updateSettings { it.copy(routeToHeadset = v) } })
                        }
                        VoiceApiSection(Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(card: VoiceActionCard, nav: Nav) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(dk.langkilde.mindmill.remote.humanize(card.name.replace('.', ' ')), style = MaterialTheme.typography.labelMedium,
                color = if (card.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(card.speech, style = MaterialTheme.typography.bodyMedium)
            if (card.data != null && card.status != "needs_confirmation") {
                var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Show details") }
                if (open) DataBlocksView(card.data, nav, maxRows = 20)
            }
            if (card.status == "needs_confirmation" && card.token != null) {
                Text("Answer out loud, or here:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                ChipFlow {
                    Button(onClick = {
                        scope.launch {
                            val said = runCatching { app.call("action.confirm", jsonOf("token" to card.token)) }
                                .fold({ it.string("speech") ?: "Done." }, { it.userMessage() })
                            app.voice.dropCard(card)
                            app.done(said)
                        }
                    }) { Text("Yes") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { app.call("action.cancel", jsonOf("token" to card.token)) }
                            app.voice.dropCard(card)
                        }
                    }) { Text("No") }
                }
            }
        }
    }
}

/** Which voice the next conversation uses: the Mac's voice API, or the phone's own (from `voice.config` / `voice.changed`). */
@Composable
fun VoiceEngineLine(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val config by app.voiceConfig.collectAsState()
    val settings by app.settings.collectAsState()
    LaunchedEffect(Unit) { if (app.voiceConfig.value == null) runCatching { app.fetchVoiceConfig() } }
    val c = config ?: return
    Text("Voice: ${c.engineLabel(settings.keylessOnly)}", style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

/** Test voice key: asks the Mac to check the voice API key it holds (`voice.test`); the key never comes to the phone. */
@Composable
fun VoiceKeyTest(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    if (missing) return
    Column(modifier.fillMaxWidth()) {
        OutlinedButton(enabled = !testing, onClick = {
            testing = true
            result = null
            scope.launch {
                try {
                    val r = app.call("voice.test")
                    val ok = r.bool("ok") == true
                    result = ok to (r.string("message") ?: if (ok) "The voice key works." else "The voice key doesn't work.")
                } catch (e: RemoteException) {
                    if (e.isMissingOnMac) missing = true else result = false to e.userMessage()
                } catch (e: Exception) {
                    result = false to e.userMessage()
                }
                testing = false
            }
        }) { Text(if (testing) "Testing…" else "Test voice key") }
        result?.let { (ok, message) ->
            val c = statusColors(if (ok) Status.DONE else Status.FAILED)
            Surface(color = c.container, contentColor = c.content, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * The Mac's voice API (`voice.providers`): which providers it holds a key for and which one voice goes through, with
 * a key typed here sent once (`voice.key.set`) and never kept. A Mac older than that gets one line and the key test.
 */
@Composable
fun VoiceApiSection(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val config by app.voiceConfig.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val (state, retry) = rememberLoad(trigger = version to config) { app.voiceApi.providers() }
    var busy by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, KeyCheck>>(emptyMap()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showLater by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<VoiceProvider?>(null) }
    val providers = state.value

    fun change(provider: String?, work: suspend () -> KeyCheck?) {
        busy = provider.orEmpty()
        problem = null
        if (provider != null) results = results - provider
        scope.launch {
            try {
                work()?.let { r -> if (provider != null) results = results + (provider to r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (provider != null) results = results + (provider to KeyCheck(false, e.userMessage())) else problem = e.userMessage()
            }
            runCatching { app.fetchVoiceConfig() }
            version++
            busy = null
        }
    }

    if (providers?.supported == false) {
        Column(modifier.fillMaxWidth()) {
            Text("Update Mindmill on the Mac to add and pick its voice API keys from here.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            VoiceKeyTest(Modifier.padding(top = 6.dp))
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        Text("Voice API on the Mac", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(
            "The Mac keeps the keys. A key you type here goes to the Mac once; the phone never keeps it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state.loading && providers == null -> Loading(text = "Asking the Mac about its voice APIs…")
            state.error != null -> NoticeBar(state.error, Modifier.padding(vertical = 4.dp), actionLabel = "Try again", onAction = retry, error = true)
        }
        problem?.let { NoticeBar(it, Modifier.padding(vertical = 4.dp), error = true, onDismiss = { problem = null }) }
        if (providers != null) {
            if (providers.providers.isEmpty()) ListEmptyState("The Mac lists no voice APIs.")
            val panel: @Composable (VoiceProvider) -> Unit = { p ->
                ProviderPanel(
                    p, inUse = providers.isInUse(p), enabled = busy == null, working = busy == p.id, result = results[p.id],
                    onSave = { key -> change(p.id) { app.voiceApi.saveKey(p.id, key) } },
                    onUse = { change(p.id) { app.voiceApi.use(p.id); null } },
                    onTest = { change(p.id) { app.voiceApi.test(p.id) } },
                    onRemove = { removing = p },
                    onModel = { m -> change(p.id) { app.voiceApi.use(p.id, model = m); null } },
                    onVoice = { v -> change(p.id) { app.voiceApi.use(p.id, voice = v); null } },
                )
            }
            providers.available.forEach { panel(it) }
            if (providers.later.isNotEmpty()) {
                TextButton(onClick = { showLater = !showLater }) { Text(if (showLater) "Fewer providers" else "More providers") }
                if (showLater) {
                    Text("Listed for later: Mindmill can't talk through these yet.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    providers.later.forEach { panel(it) }
                }
            }
            if (providers.inUse != null) {
                OutlinedButton(enabled = busy == null, onClick = { change(null) { app.voiceApi.use(null); null } },
                    modifier = Modifier.padding(top = 8.dp)) { Text("Use the phone's own voice") }
            }
        }
    }
    removing?.let { p ->
        ConfirmDialog(
            "Remove the ${p.name} key?",
            "The Mac deletes the ${p.name} key it keeps. If voice goes through ${p.name} now, it goes back to the phone's own voice.",
            "Remove",
            onConfirm = { change(p.id) { app.voiceApi.removeKey(p.id); KeyCheck(true, "The Mac removed its ${p.name} key.") } },
            onDismiss = { removing = null },
        )
    }
}

/** One voice API: whether the Mac holds a key and uses it; opened, its key, model and voice, and what to do with them. */
@Composable
private fun ProviderPanel(
    p: VoiceProvider,
    inUse: Boolean,
    enabled: Boolean,
    working: Boolean,
    result: KeyCheck?,
    onSave: (String) -> Unit,
    onUse: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onModel: (String) -> Unit,
    onVoice: (String) -> Unit,
) {
    val context = LocalContext.current
    var open by remember(p.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                ChipFlow(Modifier.padding(top = 4.dp)) {
                    when {
                        inUse -> StatusChip("In use", Status.DONE)
                        p.hasKey -> StatusChip("Key on the Mac", Status.IDLE)
                        else -> StatusChip("No key", Status.IDLE)
                    }
                }
                if (inUse) {
                    listOfNotNull(p.model, p.voice).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Close ${p.name}" else "Open ${p.name}")
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                if (inUse && p.models.isNotEmpty()) {
                    ChoiceField("Model", p.model.orEmpty(), p.models.map { it to it }) { if (it != p.model && enabled) onModel(it) }
                }
                if (inUse && p.voices.isNotEmpty()) {
                    ChoiceField("Voice", p.voice.orEmpty(), p.voices.map { it to it }) { if (it != p.voice && enabled) onVoice(it) }
                }
                VoiceKeyField(if (p.hasKey) "New ${p.name} key" else "${p.name} key", enabled, onSave) {
                    p.keyPage?.let { page ->
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, page.toUri())) } }) { Text("Get a key") }
                    }
                }
                if (p.hasKey) {
                    ChipFlow(Modifier.padding(top = 4.dp)) {
                        if (p.available && !inUse) Button(enabled = enabled, onClick = onUse) { Text("Use") }
                        OutlinedButton(enabled = enabled, onClick = onTest) { Text("Test key") }
                        TextButton(enabled = enabled, onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                result?.let { r ->
                    val c = statusColors(if (r.ok) Status.DONE else Status.FAILED)
                    Surface(color = c.container, contentColor = c.content, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(r.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/**
 * A voice API key to send to the Mac. Hidden as it's typed, on a password keyboard that suggests and learns nothing,
 * kept out of autofill, and held only in memory until [onSend] takes it: the field empties at once, with no undo back
 * to it. [actions] sit beside "Save on the Mac".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceKeyField(label: String, enabled: Boolean, onSend: (String) -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val state = remember { TextFieldState() }
    val focus = LocalFocusManager.current
    fun forget() {
        state.clearText()
        state.undoState.clearHistory()
    }
    fun send() {
        val key = state.text.toString().trim()
        forget()
        if (key.isEmpty()) return
        focus.clearFocus()
        onSend(key)
    }
    DisposableEffect(state) { onDispose { forget() } }
    Column(modifier.fillMaxWidth()) {
        OutlinedSecureTextField(
            state = state,
            enabled = enabled,
            label = { Text(label) },
            textObfuscationMode = TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            onKeyboardAction = { if (enabled) send() },
            modifier = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None },
        )
        ChipFlow(Modifier.padding(top = 6.dp)) {
            Button(enabled = enabled && state.text.isNotBlank(), onClick = { send() }) { Text("Save on the Mac") }
            actions()
        }
    }
}
