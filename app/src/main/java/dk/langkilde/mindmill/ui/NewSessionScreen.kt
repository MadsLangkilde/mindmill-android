package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch

private val planExamples = listOf(
    "What needs me today?",
    "Start my next meeting on the Mac and transcribe it",
    "Make a factory for the bugs in this project",
    "What did we decide about login?",
)

private val buildExamples = listOf(
    "Find and fix the failing tests",
    "What changed in this project this week?",
    "Review my open pull request",
)

/**
 * Ask Mindmill: what it can do and some things to try. Nothing is made until the user writes or says something;
 * then `session.new` starts a session with it as the first message, in the mode's space and the phone's project, and
 * the session opens in place of this screen. In Plan it is an assistant session, which can do anything Mindmill can.
 * Talking live starts the session from the first thing said and keeps talking in it.
 */
@Composable
fun NewSessionScreen(nav: Nav, mode: Mode) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val live by app.liveChat.ui.collectAsState()
    val startLive = rememberLiveStarter()
    var starting by remember { mutableStateOf(false) }
    val brain = app.connection.hello?.brain ?: "Claude"
    val liveHere = live.active && live.fromNew

    LaunchedEffect(live.session, live.fromNew) {
        val id = live.session
        if (live.active && live.fromNew && id != null) {
            app.liveChat.handedOver()
            nav.replace(Screen.SessionChat(id, "New session"))
        }
    }

    fun start(text: String) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                val r = app.call("session.new", jsonOf(
                    "space" to mode.id, "scope" to app.currentScope, "message" to text,
                    "assistant" to (if (mode == Mode.PLAN) true else null),
                ))
                val id = r.text("id") ?: throw IllegalStateException("The Mac didn't start a session.")
                nav.replace(Screen.SessionChat(id, text.lineSequence().first().take(60)))
            } catch (e: Exception) {
                app.done("Not sent: ${e.userMessage()}", error = true)
            } finally {
                starting = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                if (mode == Mode.PLAN) {
                    "Ask for anything Mindmill can do: start a meeting, find a decision, change a factory, answer a question. " +
                        "It asks before anything risky; answer it in the session."
                } else {
                    "Describe the work: a bug to fix, a feature to build, a question about the code. $brain works in the " +
                        "project's code and asks before anything leaves the Mac."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            SectionTitle("Try")
            ChipFlow {
                (if (mode == Mode.PLAN) planExamples else buildExamples).forEach { e ->
                    SuggestionChip(onClick = { start(e) }, enabled = !starting, label = { Text(e) })
                }
            }
            if (starting) Loading(Modifier.padding(top = 12.dp), text = "Starting the session…")
        }
        if (liveHere) {
            LiveBar(live, onStop = { app.liveChat.stopSpeaking() }, onEnd = { app.liveChat.end() }, onDismissNotice = { app.liveChat.dismissNotice() })
        }
        Composer(
            placeholder = if (mode == Mode.PLAN) "Ask Mindmill…" else "What should it build or fix?",
            draftKey = "new-${mode.id}",
            onSend = ::start,
            liveHere = liveHere,
            onLive = { startLive { app.liveChat.startNew(mode.id, app.currentScope, assistant = mode == Mode.PLAN) } },
            onEndLive = { app.liveChat.end() },
        )
    }
}
