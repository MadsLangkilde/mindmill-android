package dk.langkilde.mindmill.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import dk.langkilde.mindmill.remote.ActionOutcome
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.objects
import kotlinx.coroutines.delay
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.meeting.ScreenCaptureService
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import dk.langkilde.mindmill.remote.text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun MeetingsScreen(nav: Nav) {
    val app = LocalApp.current
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(day)
    val (state, retry) = rememberLoad(rememberCurrentScope(), day) {
        app.runAction("meetings.list", jsonOf("day" to day, "scope" to app.currentScope))
    }
    val entries = state.value?.let { EntrySummary.list(it.data) }.orEmpty()
    RefreshList(state.refreshing, retry) {
        item {
            SectionCard("Start a meeting") {
                Text(
                    "On the Mac, it records the Mac's microphone and the call's audio and writes the notes there. " +
                        "On this phone, the phone listens to the room.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipFlow(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { nav.go(Screen.MacMeeting) }) { Text("On the Mac") }
                    OutlinedButton(onClick = { nav.go(Screen.LiveMeeting) }) {
                        Text(if (app.meeting.isLive) "Back to the phone meeting" else "On this phone")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = date.minusDays(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Day before")
                }
                Text(
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { day = date.plusDays(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Day after")
                }
            }
            ChipFlow {
                val today = LocalDate.now()
                Choice("Yesterday", date == today.minusDays(1)) { day = today.minusDays(1).toString() }
                Choice("Today", date == today) { day = today.toString() }
                Choice("Tomorrow", date == today.plusDays(1)) { day = today.plusDays(1).toString() }
            }
            LoadStatus(state, retry)
            if (state.value != null && entries.isEmpty()) EmptyState("No meetings on this day.")
        }
        items(entries, key = { it.id }) { e ->
            val time = listOfNotNull(e.field("start")?.let(CalendarEvent::clock), e.field("end")?.let(CalendarEvent::clock)).joinToString("–")
            ListRow(e.title, subtitle = listOf(time, e.field("status").orEmpty(), e.summary).filter { it.isNotBlank() }.joinToString(" · "),
                onClick = { nav.go(Screen.MeetingDetail(e.id, e.title)) })
        }
    }
}

/**
 * A meeting the Mac runs itself: start one from the calendar or now, then follow it live, add notes, pause the
 * transcription, stop it and file it, all through the `meeting.mac.*` actions.
 */
@Composable
fun MacMeetingScreen(nav: Nav) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { app.events.collect { if (it.name == "meeting.changed" || it.name == "index.changed") tick++ } }
    val (status, retryStatus) = rememberLoad(trigger = tick) { app.runAction("meeting.mac.status") }
    val live = status.value?.takeIf { it.ok }?.data?.takeIf { !it.text("meeting").isNullOrBlank() }
    LaunchedEffect(live?.text("meeting"), live?.string("stage")) {
        while (live != null && live.string("stage") in setOf("live", "summarizing", "prepare")) {
            delay(5_000)
            tick++
        }
    }
    val (upcoming, retryUpcoming) = rememberLoad { app.runAction("calendar.upcoming", jsonOf("days" to 2)) }
    var title by rememberSaveable { mutableStateOf("") }
    var transcribe by rememberSaveable { mutableStateOf(true) }
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun act(name: String, params: JsonObject, after: () -> Unit = {}) {
        busy = true
        scope.launch {
            val o = app.runAndReport(name, params)
            if (o?.ok == true) after()
            busy = false
            tick++
        }
    }

    RefreshList(status.refreshing || upcoming.refreshing, { retryStatus(); retryUpcoming() }) {
        item {
            LoadStatus(status, retryStatus, skeletonRows = 2)
            status.value?.takeIf { it.ok && it.data.text("meeting").isNullOrBlank() }?.let {
                Text(it.speech.ifBlank { "No meeting is open on the Mac." }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            }
            status.value?.takeIf { it.status == ActionOutcome.Status.FAILED }?.let {
                ErrorState(
                    if (it.speech.contains("can't do", true)) "The Mac's Mindmill can't run meetings from the phone yet. Update it on the Mac." else it.speech,
                    retryStatus, compact = true,
                )
            }
        }
        if (live != null) {
            item {
                val meeting = live.text("meeting").orEmpty()
                val stage = live.string("stage").orEmpty()
                SectionCard(live.string("title") ?: "Meeting") {
                    Text(
                        listOfNotNull(
                            macStageTitle(stage),
                            if (live.bool("transcribing") == true) "transcribing" else if (stage == "live") "not transcribing" else null,
                            live.int("lines")?.let { "$it lines" },
                            live.string("started")?.let { started ->
                                runCatching { java.time.Instant.parse(started).atZone(java.time.ZoneId.systemDefault()).toLocalTime() }
                                    .getOrNull()?.let { "started ${it.toString().take(5)}" }
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    if (stage == "live") {
                        OutlinedTextField(note, { note = it }, label = { Text("Add a note") }, minLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        ChipFlow(Modifier.padding(top = 6.dp)) {
                            Button(enabled = !busy && note.isNotBlank(), onClick = {
                                act("meeting.mac.note", jsonOf("text" to note.trim(), "meeting" to meeting)) { note = "" }
                            }) { Text("Add note") }
                            val on = live.bool("transcribing") == true
                            OutlinedButton(enabled = !busy, onClick = {
                                act("meeting.mac.transcribe", jsonOf("on" to !on, "meeting" to meeting))
                            }) { Text(if (on) "Pause transcription" else "Transcribe") }
                            OutlinedButton(enabled = !busy, onClick = { act("meeting.mac.stop", jsonOf("meeting" to meeting)) }) {
                                Text("Stop", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (stage == "review") {
                        Text("The summary and proposals are ready to review. File it to save the ticked proposals and the summary.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        ChipFlow(Modifier.padding(top = 6.dp)) {
                            Button(enabled = !busy, onClick = { act("meeting.mac.file", jsonOf("meeting" to meeting)) }) { Text("File it") }
                        }
                    }
                    ChipFlow(Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { nav.go(Screen.MeetingDetail(meeting, live.string("title") ?: "Meeting")) }) { Text("Notes and transcript") }
                        TextButton(onClick = { act("app.show", jsonOf("screen" to "meeting", "id" to meeting)) }) { Text("Show on the Mac") }
                    }
                    val transcript = live.objects("transcript").takeLast(8)
                    if (transcript.isNotEmpty()) {
                        SectionTitle("Latest")
                        transcript.forEach { l ->
                            Text(listOfNotNull(l.string("speaker")?.takeIf { it.isNotBlank() }, l.string("text")).joinToString(": "),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    live.string("notes")?.takeIf { it.isNotBlank() }?.let { notes ->
                        SectionTitle("Notes")
                        Text(markdownish(notes.trim().takeLast(1500)), style = MaterialTheme.typography.bodySmall)
                    }
                    val proposals = live["proposals"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    if (proposals != null) {
                        SectionTitle("Proposals")
                        DataBlocksView(proposals, nav, maxRows = 20)
                    }
                }
            }
        }
        item {
            if (live?.string("stage") != "live") SectionCard("Start one now on the Mac") {
                OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow("Transcribe", transcribe, { transcribe = it })
                Button(
                    enabled = !busy,
                    onClick = {
                        act("meeting.mac.start", jsonOf("title" to title.trim().ifBlank { null }, "scope" to app.currentScope, "transcribe" to transcribe)) { title = "" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start on Mac") }
            }
            SectionTitle("Today and tomorrow")
            LoadStatus(upcoming, retryUpcoming, skeletonRows = 3)
            upcoming.value?.takeIf { it.status == ActionOutcome.Status.FAILED }?.let { ErrorState(it.speech, retryUpcoming, compact = true) }
        }
        val events = upcoming.value?.takeIf { it.ok }?.data?.objects("events")?.map(CalendarEvent::parse).orEmpty()
        if (upcoming.value?.ok == true && events.isEmpty()) item { EmptyState("No meetings in the calendar today or tomorrow.") }
        items(events, key = { it.id }) { ev ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(ev.title, style = MaterialTheme.typography.bodyLarge)
                Text(ev.whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ev.attendees.isNotEmpty()) {
                    Text(ev.attendees.take(6).joinToString(", ") + if (ev.attendees.size > 6) " +${ev.attendees.size - 6}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChipFlow(Modifier.padding(top = 4.dp)) {
                    if (ev.canStart) {
                        OutlinedButton(enabled = !busy, onClick = { act("meeting.mac.start", jsonOf("event" to ev.id, "transcribe" to transcribe)) }) {
                            Text("Start on Mac")
                        }
                    }
                    TextButton(onClick = { nav.go(Screen.MeetingDetail(ev.id, ev.title)) }) { Text("Notes") }
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun macStageTitle(stage: String) = when (stage) {
    "prepare" -> "Getting ready"
    "live" -> "Live"
    "summarizing" -> "Writing the summary"
    "review" -> "Ready to review"
    "filed" -> "Filed"
    else -> stage
}

@Composable
fun MeetingDetailScreen(id: String, title: String) {
    val app = LocalApp.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(id) {
        app.events.collect { if (it.name == "meeting.changed" && it.data.text("meeting") == id) tick++ }
    }
    val (state, retry) = rememberLoad(id, trigger = tick) {
        try {
            app.rpc.call("meeting.get", jsonOf("meeting" to id)) to false
        } catch (e: RemoteException) {
            if (!e.isUnknownMethod && e.code != "not_found") throw e
            app.rpc.call("entry.get", jsonOf("id" to id)) to true
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val pair = state.value
        val o: JsonObject? = pair?.first
        Text(o?.string("title") ?: title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 6.dp))
        LoadStatus(state, retry)
        if (o == null) return@Column
        if (pair.second) {
            Text(o.string("body").orEmpty(), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        when (o.string("state")) {
            "live" -> NoticeBar("This meeting is live.")
            "summarising" -> NoticeBar("The Mac is writing the summary. It shows here when it's ready.")
            else -> Unit
        }
        o.string("summary")?.takeIf { it.isNotBlank() }?.let {
            SectionTitle("Summary")
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        o.string("notes")?.takeIf { it.isNotBlank() }?.let {
            SectionTitle("Notes")
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        val transcript = o.strings("transcript").ifEmpty {
            (o["transcript"] as? kotlinx.serialization.json.JsonArray)?.filterIsInstance<JsonObject>()?.map {
                listOfNotNull(it.string("speaker"), it.string("text")).joinToString(": ")
            }.orEmpty()
        }
        if (transcript.isNotEmpty()) {
            SectionTitle("Transcript")
            transcript.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
fun LiveMeetingScreen(nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by app.meeting.ui.collectAsState()
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val settings by app.settings.collectAsState()

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            scope.launch { app.meeting.start(app.currentScope, title.trim(), java.util.Locale.getDefault()) }
        }
    }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) ScreenCaptureService.start(context, result.resultCode, data)
    }
    LaunchedEffect(ui.finished) {
        ui.finished?.let { nav.replace(Screen.MeetingDetail(it, title.ifBlank { "Meeting" })) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(ui.lines.size) { if (ui.lines.isNotEmpty()) listState.animateScrollToItem(ui.lines.size) }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            ui.notice?.let { NoticeBar(it, Modifier.padding(vertical = 4.dp), error = true, onDismiss = { app.meeting.dismissNotice() }) }
            if (ui.meeting == null) {
                Text(
                    "The phone listens to the room and sends each line to the Mac, which writes the notes and a summary.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(
                    enabled = !ui.starting,
                    onClick = {
                        val perms = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (app.voice.ui.value.active) app.show("End the voice conversation first: both need the microphone.")
                        else mic.launch(perms.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (ui.starting) "Starting…" else "Start recording") }
            } else {
                Text(
                    "Recording ${ui.title.ifBlank { "the meeting" }}. ${ui.lines.size} lines, ${ui.screens} screenshots.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = !ui.stopping,
                    onClick = { scope.launch { app.meeting.stop(context) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (ui.stopping) "Stopping…" else "Stop and summarise") }
                OutlinedTextField(note, { note = it }, label = { Text("Type a note") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { app.meeting.note(note.trim()); note = "" }, enabled = note.isNotBlank()) { Text("Add note") }
                MoreOptions("Screen capture") {
                    if (!ui.capturing) {
                        OutlinedButton(onClick = {
                            val mpm = context.getSystemService(MediaProjectionManager::class.java)
                            projection.launch(mpm.createScreenCaptureIntent())
                        }) { Text("Turn on screen capture") }
                        Text(
                            "Then capture from here or from the notification while another app is on screen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        ChipFlow {
                            Button(onClick = { app.meeting.captureScreen() }) { Text("Capture now") }
                            OutlinedButton(onClick = { ScreenCaptureService.stop(context) }) { Text("Turn off") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("Every 20 seconds", modifier = Modifier.weight(1f))
                            Switch(checked = ui.autoScreens, onCheckedChange = {
                                app.meeting.setAutoScreens(it)
                                app.updateSettings { s -> s.copy(meetingAutoScreens = it) }
                            })
                        }
                    }
                }
                SectionTitle("Transcript")
                if (ui.lines.isEmpty() && ui.partial.isBlank()) ListEmptyState("Listening… lines appear as people speak.")
            }
        }
        items(ui.lines) { line -> Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
        if (ui.partial.isNotBlank()) {
            item { Text(ui.partial, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (ui.notes.isNotEmpty()) {
            item { SectionTitle("Your notes") }
            items(ui.notes) { n -> Text("• $n", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    LaunchedEffect(ui.meeting, ui.capturing) {
        if (ui.meeting != null && ui.capturing && settings.meetingAutoScreens && !ui.autoScreens) app.meeting.setAutoScreens(true)
    }
}
