package dk.langkilde.mindmill.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dk.langkilde.mindmill.delivery.Delivery
import dk.langkilde.mindmill.delivery.InstallStep
import dk.langkilde.mindmill.delivery.PreviewWebViewClient
import dk.langkilde.mindmill.remote.BuildChoice
import dk.langkilde.mindmill.remote.BuildLog
import dk.langkilde.mindmill.remote.BuildSource
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.canStartMindmill
import dk.langkilde.mindmill.remote.TestSources
import dk.langkilde.mindmill.remote.TestTarget
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import dk.langkilde.mindmill.store.StoredPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Composable
fun TestScreen(nav: Nav) {
    val app = LocalApp.current
    val deliveries by app.deliveries.list.collectAsState()
    val steps by app.deliveries.steps.collectAsState()
    val cards by app.reviewCards.collectAsState()
    val starting by app.buildsStarting.collectAsState()
    val scopeId = rememberCurrentScope()
    var tick by remember { mutableIntStateOf(0) }
    val (state, retry) = rememberLoad(scopeId, trigger = tick) {
        app.call("test.status", jsonOf("scope" to app.currentScope)).also { r ->
            app.deliveries.merge(r.objects("deliveries").mapNotNull(Delivery::parse))
            app.loadReviewCards()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        steps.filterValues { it == InstallStep.NeedsPermission }.keys.forEach(app.deliveries::permissionGranted)
    }
    val checking = state.value?.bool("checking") == true
    val building = starting.isNotEmpty() || deliveries.any { it.isBuilding }
    LaunchedEffect(building, checking) {
        while (building || checking) {
            delay(if (checking) 3_000 else 5_000)
            tick++
        }
    }
    val mac = app.connection.pairedMacName ?: "the Mac"
    val targets = state.value?.objects("targets").orEmpty().filter { TestTarget.forAndroid(it.string("type")) }
    val buildable = TestTarget.list(state.value).filter { TestTarget.forAndroid(it.type) }
    val picks by app.buildFrom.picks.collectAsState()
    val picked = picks[scopeId.orEmpty()]
    val (sources, retrySources) = rememberLoad(scopeId) { app.testSources(scopeId).also { app.buildFrom.reconcile(scopeId, it) } }
    var detecting by remember { mutableStateOf(false) }
    var rechecking by remember { mutableStateOf(false) }
    val ui = rememberCoroutineScope()
    RefreshList(state.refreshing, retry) {
        item {
            LoadStatus(state, retry)
            if (cards.isNotEmpty()) SectionTitle("Ready for review")
        }
        items(cards, key = { it.text("id").orEmpty() }) { c -> ReviewCard(c) }
        if (state.value != null) item {
            SectionTitle("Send a new version to this phone")
            if (checking) {
                Text("Checking what $mac can build…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                buildable.isNotEmpty() -> {
                    BuildFromPicker(sources, picked, retrySources) { app.buildFrom.pick(scopeId, it) }
                    buildable.forEach { t ->
                        BuildRow(t, BuildChoice.of(t, deliveries, starting), checking) {
                            app.requestTestBuild(t, scopeId, TestSources.sessionToBuild(picked, sources.value))
                        }
                    }
                    if (!checking && buildable.any { BuildChoice.of(it, deliveries, starting) is BuildChoice.Blocked }) {
                        OutlinedButton(enabled = !rechecking, onClick = {
                            rechecking = true
                            ui.launch {
                                runCatching { app.call("action.run", jsonOf("name" to "test.check", "params" to jsonOf("scope" to scopeId)), 5 * 60_000L) }
                                    .onFailure { app.done(it.userMessage(), error = true) }
                                rechecking = false
                                retry()
                            }
                        }) { Text(if (rechecking) "Checking $mac again…" else "Check $mac again") }
                    }
                }
                else -> {
                    Text(
                        state.value?.string("note") ?: "Mindmill hasn't found anything to build in this project yet.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp),
                    )
                    OutlinedButton(enabled = !detecting, onClick = {
                        detecting = true
                        ui.launch {
                            app.runAndReport("test.detect", jsonOf("scope" to scopeId))
                            detecting = false
                            retry()
                        }
                    }) { Text(if (detecting) "Looking…" else "Look for what can be tested") }
                }
            }
        }
        item {
            SectionTitle("Builds and previews")
            if (deliveries.isEmpty()) ListEmptyState("Nothing sent to this phone yet.")
        }
        items(deliveries, key = { it.id }) { d ->
            val from = d.session?.let { id -> sources.value?.find(id)?.let { "From ${it.label}" } ?: "From a session's version" }
            DeliveryRow(d, steps[d.id], nav, from)
        }
        if (targets.isNotEmpty()) {
            item { SectionTitle("Set-up (change it on the Mac)") }
            items(targets) { t -> TargetCard(t) }
        }
        state.value?.obj("compatibility")?.let { c ->
            val findings = c.objects("findings")
            if (findings.isNotEmpty()) {
                item { SectionTitle("Compatibility") }
                items(findings) { f ->
                    ListRow(f.string("title").orEmpty(),
                        subtitle = listOfNotNull(f.string("detail"), f.string("canStill")?.let { "Can still: $it" }, f.string("cannot")?.let { "Can't: $it" })
                            .joinToString("\n"))
                }
            }
        }
    }
}

/**
 * Which version Build builds: this Mac's own copy, or the one a Build session works on in its own copy (`test.sources`).
 * Labels are shortened to keep the menu narrow; the whole title and branch show under the pick and in the menu. A Mac
 * without sessions' versions shows nothing here.
 */
@Composable
private fun BuildFromPicker(state: Loaded<TestSources>, picked: BuildSource?, onRetry: () -> Unit, onPick: (BuildSource?) -> Unit) {
    val sources = state.value
    if (sources?.supported == false) return
    var open by remember { mutableStateOf(false) }
    val current = if (sources == null) picked else picked?.let { sources.find(it.id) }
    val label = current?.label ?: sources?.localLabel ?: TestSources.LOCAL
    val full = when {
        current != null -> current.fullLabel.takeIf { current.isShortened }
        else -> sources?.localFullLabel?.takeIf { sources.isLocalShortened }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Build from", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        val detail = if (state.loading) "Looking for sessions' versions…" else full
                        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (state.loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 320.dp)) {
                SourceItem(sources?.localLabel ?: TestSources.LOCAL, sources?.localFullLabel?.takeIf { sources.isLocalShortened }, current == null) {
                    open = false
                    onPick(null)
                }
                sources?.sessions?.forEach { s ->
                    SourceItem(s.label, s.fullLabel.takeIf { s.isShortened }, current?.id == s.id) {
                        open = false
                        onPick(s)
                    }
                }
                when {
                    sources == null -> DropdownMenuItem(
                        text = { Text("Looking for sessions' versions…") }, onClick = {}, enabled = false,
                        leadingIcon = { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) },
                    )
                    sources.sessions.isEmpty() -> DropdownMenuItem(
                        text = { Text("No session has its own version of this project.") }, onClick = {}, enabled = false,
                        leadingIcon = { Spacer(Modifier.size(24.dp)) },
                    )
                }
            }
        }
        Text(
            "Sessions can work on their own version of the code. Pick whose version to build and test.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        state.error?.let {
            NoticeBar("Couldn't list the sessions' versions. $it", Modifier.padding(top = 4.dp), actionLabel = "Try again", onAction = onRetry, error = true)
        }
    }
}

@Composable
private fun SourceItem(label: String, full: String?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                full?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        leadingIcon = { if (selected) Icon(Icons.Default.Check, contentDescription = "Picked") else Spacer(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}

/** One test target: build it now, or what's happening or missing. */
@Composable
private fun BuildRow(t: TestTarget, choice: BuildChoice, checking: Boolean, onBuild: () -> Unit) {
    val app = LocalApp.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(t.title, style = MaterialTheme.typography.bodyLarge)
        when (choice) {
            BuildChoice.Build -> Button(onClick = onBuild, modifier = Modifier.padding(top = 4.dp)) { Text(t.buildLabel) }
            BuildChoice.Building -> {
                Text("Building on ${app.connection.pairedMacName ?: "the Mac"}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is BuildChoice.Blocked -> if (!checking) Text(
                if (t.unavailable != null) choice.why else "${choice.why}. Finish its set-up on the Mac.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeliveryRow(d: Delivery, step: InstallStep?, nav: Nav, from: String?) {
    val app = LocalApp.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(d.title, style = MaterialTheme.typography.bodyLarge)
        Text(listOfNotNull(
            when (d.kind) { "apk" -> "Android build"; "preview" -> "Preview"; else -> "Link" },
            d.target, d.size?.takeIf { it > 1024 * 1024 }?.let { "${it / 1024 / 1024} MB" }, from,
        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (d.isBuilding) {
            Text("Building on ${app.connection.pairedMacName ?: "the Mac"}…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        } else if (d.hasFailed) {
            NoticeBar(d.message ?: "This build failed.", error = true)
        } else when (d.kind) {
            "apk" -> when (step) {
                InstallStep.NeedsPermission -> InstallPermissionGuide(d)
                is InstallStep.Downloading -> {
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                    val of = step.of
                    if (of != null && of > 0) LinearProgressIndicator(progress = { (step.bytes.toFloat() / of).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                InstallStep.Installing -> Text("Installing… confirm on the screen that opens.", style = MaterialTheme.typography.bodySmall)
                InstallStep.Installed -> Text("Installed.", style = MaterialTheme.typography.bodySmall)
                is InstallStep.Failed -> {
                    NoticeBar(step.message, error = true)
                    TextButton(onClick = { app.deliveries.install(d) }) { Text("Try again") }
                }
                null -> Button(onClick = { app.deliveries.install(d) }) { Text("Install") }
            }
            "preview" -> Button(onClick = { nav.go(Screen.Web(d.title, PreviewWebViewClient.url(d.id), preview = true)) }) { Text("Open preview") }
            else -> d.url?.let { url ->
                ChipFlow {
                    Button(onClick = { nav.go(Screen.Web(d.title, url, preview = false)) }) { Text("Open") }
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) { Text("Open in browser") }
                }
            }
        }
        if (d.hasLog && !d.isBuilding) {
            TextButton(onClick = { nav.go(Screen.BuildLog(d.id, d.title)) }) { Text("Show log") }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun InstallPermissionGuide(d: Delivery) {
    val app = LocalApp.current
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Let Mindmill install test builds", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. Tap Open settings.\n2. Turn on “Allow from this source” for Mindmill.\n3. Come back here and tap Check again.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp),
            )
            ChipFlow {
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
                        )
                    }
                }) { Text("Open settings") }
                OutlinedButton(onClick = {
                    if (app.deliveries.canInstall()) app.deliveries.install(d) else app.done("Not allowed yet. Turn on “Allow from this source” for Mindmill.", error = true)
                }) { Text("Check again") }
            }
        }
    }
}

@Composable
private fun TargetCard(t: JsonObject) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(t.string("title") ?: t.text("id").orEmpty(), style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(t.string("methodTitle") ?: t.string("method"), if (t.bool("ready") == true) "Ready" else "Not ready").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
            t.objects("checklist").forEach { c ->
                val mark = when (c.string("status")) { "ok" -> "✓"; "missing" -> "✗"; else -> "?" }
                Text("$mark ${c.string("title").orEmpty()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (c.string("status") != "ok") c.string("help")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun ReviewCard(card: JsonObject) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val id = card.text("id").orEmpty()
    var changing by rememberSaveable(id) { mutableStateOf(false) }
    var change by rememberSaveable(id) { mutableStateOf("") }
    var showLines by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun respond(action: String, text: String? = null) {
        busy = true
        scope.launch {
            try {
                val r = app.call("review.respond", jsonOf("card" to id, "action" to action, "text" to text))
                app.done(r.string("speech") ?: "Sent.")
                if (action == "approve" || action == "change") app.dropReviewCard(id)
                if (action == "install") card.obj("delivery")?.let(Delivery::parse)?.let(app.deliveries::arrived)
            } catch (e: Exception) {
                app.done(e.userMessage(), error = true)
            } finally {
                busy = false
            }
        }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(card.string("title").orEmpty(), style = MaterialTheme.typography.titleMedium)
            card.string("summary")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            val origin = listOfNotNull(card.text("workItem")?.let { "#$it" }, card.string("factory"), card.string("session")?.let { "session" })
            if (origin.isNotEmpty()) Text(origin.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            card.obj("tests")?.let { t ->
                val passed = t.int("passed") ?: 0
                val failed = t.int("failed") ?: 0
                TextButton(onClick = { showLines = !showLines }) {
                    Text("Tests: $passed passed" + if (failed > 0) ", $failed failed" else "")
                }
                if (showLines) t.strings("lines").forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            card.objects("images").forEach { img -> ReviewImage(id, img) }
            val actions = card.strings("actions").ifEmpty { listOf("approve", "pause", "change") }
            ChipFlow(Modifier.padding(top = 8.dp)) {
                if ("approve" in actions) Button(enabled = !busy, onClick = { respond("approve") }) { Text("Approve") }
                if ("change" in actions) OutlinedButton(enabled = !busy, onClick = { changing = !changing }) { Text("Ask for a change") }
                if ("install" in actions) OutlinedButton(enabled = !busy, onClick = { respond("install") }) { Text("Install") }
                if ("pause" in actions) TextButton(enabled = !busy, onClick = { respond("pause") }) { Text("Pause") }
            }
            if (changing) {
                OutlinedTextField(change, { change = it }, label = { Text("What should change?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(enabled = change.isNotBlank() && !busy, onClick = { respond("change", change.trim()); change = ""; changing = false }) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ReviewImage(cardId: String, img: JsonObject) {
    val title = img.string("title").orEmpty()
    val after = img.string("path")
    val before = img.string("before")
    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    if (before == null) {
        after?.let { PreviewImage(cardId, it, Modifier.fillMaxWidth()) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp) {
            Column {
                Text("Before", style = MaterialTheme.typography.labelSmall)
                PreviewImage(cardId, before, Modifier.fillMaxWidth())
                Text("After", style = MaterialTheme.typography.labelSmall)
                after?.let { PreviewImage(cardId, it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text("Before", style = MaterialTheme.typography.labelSmall)
                    PreviewImage(cardId, before, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("After", style = MaterialTheme.typography.labelSmall)
                    after?.let { PreviewImage(cardId, it, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun PreviewImage(delivery: String, path: String, modifier: Modifier) {
    val app = LocalApp.current
    val conn by app.connection.state.collectAsState()
    val image by produceState<ImageBitmap?>(null, delivery, path, conn is ConnectionState.Connected) {
        value = runCatching {
            val (_, bytes) = app.deliveries.previewFile(delivery, path)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }
    image?.let { Image(it, contentDescription = path, contentScale = ContentScale.FillWidth, modifier = modifier.padding(vertical = 4.dp)) }
        ?: Text("Loading image…", style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

/**
 * What building and testing a delivery printed (`test.log`): the first error on top, then the log, monospaced and
 * selectable, starting at its end. Long lines scroll sideways inside the log, never the screen.
 */
@Composable
fun BuildLogScreen(delivery: String, title: String) {
    val app = LocalApp.current
    val (state, retry) = rememberLoad(delivery) { BuildLog.parse(app.call("test.log", jsonOf("delivery" to delivery), 60_000)) }
    val log = state.value
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
        when {
            state.waiting -> WaitingForMac(retry)
            state.loading && log == null -> {
                NoticeBar("Getting the build log from ${app.connection.pairedMacName ?: "the Mac"}…", Modifier.padding(vertical = 4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.error?.let { NoticeBar(it, Modifier.padding(vertical = 4.dp), actionLabel = "Try again", onAction = retry, error = true) }
        log?.firstError?.let { first ->
            val cs = MaterialTheme.colorScheme
            Surface(color = cs.errorContainer, contentColor = cs.onErrorContainer, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("First error", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(first, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
                }
            }
        }
        when {
            log == null -> Unit
            log.log.isBlank() -> ListEmptyState("The build printed nothing.")
            else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                SelectionContainer {
                    Text(
                        log.log, style = mono, softWrap = false,
                        modifier = Modifier.verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(title: String, url: String, preview: Boolean) {
    val app = LocalApp.current
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = if (preview) PreviewWebViewClient(app.deliveries) else WebViewClient()
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
fun ConnectionScreen(nav: Nav) {
    val app = LocalApp.current
    val conn by app.connection.state.collectAsState()
    val settings by app.settings.collectAsState()
    val pairings by app.connection.pairings.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MacsCard(nav)
            SectionCard("Your Mac") {
                val c = conn
                Text(
                    when (c) {
                        is ConnectionState.Connected -> "Connected to ${c.hello.name} at ${c.host}. ${c.hello.brain} is the brain." +
                            (c.hello.instance?.let { " Instance: ${it.name}." } ?: "")
                        is ConnectionState.Connecting -> "Connecting to ${c.macName}" + (c.host?.let { " at $it" } ?: "") + "…"
                        is ConnectionState.Reconnecting -> c.problem.message(c.macName) + " Trying again shortly."
                        is ConnectionState.Unreachable -> c.problem.message(c.macName)
                        else -> "Not paired."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (c is ConnectionState.Unreachable) {
                    c.problem.tips(c.macName).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (c !is ConnectionState.Connected) {
                    val canStart = canStartMindmill(pairings.active, c)
                    ChipFlow(Modifier.padding(top = 8.dp)) {
                        if (canStart) {
                            StartMindmillButton(c, outlined = false)
                            OutlinedButton(onClick = { app.connection.retryNow() }) { Text("Retry now") }
                        } else {
                            Button(onClick = { app.connection.retryNow() }) { Text("Retry now") }
                        }
                    }
                } else {
                    TextButton(onClick = { nav.go(Screen.RunAction("app.show", mapOf("screen" to "settings", "id" to "remote"))) }) {
                        Text("Show Remote settings on the Mac")
                    }
                }
            }
            SectionCard("Voice") {
                VoiceEngineLine(Modifier.padding(bottom = 4.dp))
                SwitchRow("Always use the phone's own voice", settings.keylessOnly, { v -> app.updateSettings { it.copy(keylessOnly = v) } },
                    detail = "Off: the Mac decides, and may use its real-time voice.")
                SwitchRow("Bluetooth headset or car", settings.routeToHeadset, { v -> app.updateSettings { it.copy(routeToHeadset = v) } },
                    detail = "Talk through it when one is connected.")
                VoiceApiSection(Modifier.padding(top = 6.dp))
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SectionCard("Look") {
                    SwitchRow("Wallpaper colours", settings.wallpaperColours, { v -> app.updateSettings { it.copy(wallpaperColours = v) } },
                        detail = "Off: Mindmill's own navy and cream. On: colours from your wallpaper.")
                }
            }
        }
    }
}

/**
 * Every Mac the phone is paired with: which one it talks to and how that connection is, switching to another,
 * pairing one more, and removing one (asked first).
 */
@Composable
private fun MacsCard(nav: Nav) {
    val app = LocalApp.current
    val conn by app.connection.state.collectAsState()
    val pairings by app.connection.pairings.collectAsState()
    var removing by remember { mutableStateOf<StoredPairing?>(null) }
    SectionCard(if (pairings.macs.size > 1) "Your Macs" else "Your Mac") {
        pairings.macs.forEachIndexed { i, mac ->
            val active = pairings.isActive(mac)
            val (status, tone) = macStatus(mac, active, conn)
            ListRow(
                mac.macName,
                subtitle = status,
                leading = { IconBadge(Icons.Default.LaptopMac, size = 36.dp) },
                divider = i < pairings.macs.lastIndex,
                below = {
                    Column {
                        if (active) StatusChip("In use", tone)
                        ChipFlow(Modifier.padding(top = 4.dp)) {
                            if (!active) OutlinedButton(onClick = { app.switchMac(mac.fingerprint) }) { Text("Switch to this Mac") }
                            TextButton(onClick = { removing = mac }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                        if (active) {
                            MoreOptions("Details") {
                                Text("Addresses: ${mac.orderedHosts.joinToString(", ")}, port ${mac.port}", style = MaterialTheme.typography.bodySmall)
                                Text("Certificate: ${mac.fingerprint.chunked(4).joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
            )
        }
        Button(onClick = { nav.go(Screen.PairMac) }, modifier = Modifier.padding(top = 8.dp)) { Text("Pair another Mac") }
    }
    removing?.let { mac ->
        val next = pairings.removed(mac.fingerprint).active?.takeIf { pairings.isActive(mac) }
        ConfirmDialog(
            "Remove ${mac.macName}?",
            "The phone forgets ${mac.macName} and its pairing. To use it again you need a new pairing code from that Mac." +
                (next?.let { " The phone switches to ${it.macName}." } ?: ""),
            "Remove",
            onConfirm = { app.removeMac(mac.fingerprint) },
            onDismiss = { removing = null },
        )
    }
}
