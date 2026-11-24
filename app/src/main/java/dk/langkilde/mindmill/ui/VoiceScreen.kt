package dk.langkilde.mindmill.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.userMessage
import dk.langkilde.mindmill.voice.VoiceActionCard
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

    Column(Modifier.fillMaxSize()) {
        ui.notice?.let { NoticeBar(it, Modifier.padding(vertical = 4.dp), onDismiss = { app.voice.dismissNotice() }) }
        if (!ui.active) {
            Button(onClick = ::start, enabled = !ui.starting, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.starting) "Starting…" else "Talk to $brain")
            }
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
            MoreOptions {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Always use the phone's own voice", modifier = Modifier.weight(1f))
                    Switch(settings.keylessOnly, { v -> app.updateSettings { it.copy(keylessOnly = v) } })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Use a Bluetooth headset or car when connected", modifier = Modifier.weight(1f))
                    Switch(settings.routeToHeadset, { v -> app.updateSettings { it.copy(routeToHeadset = v) } })
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
