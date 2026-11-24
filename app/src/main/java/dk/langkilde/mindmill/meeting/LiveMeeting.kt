package dk.langkilde.mindmill.meeting

import android.content.Context
import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.RpcChannel
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import dk.langkilde.mindmill.voice.ContinuousRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class LiveMeetingUi(
    val meeting: String? = null,
    val title: String = "",
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val lines: List<String> = emptyList(),
    val partial: String = "",
    val notes: List<String> = emptyList(),
    val screens: Int = 0,
    val capturing: Boolean = false,
    val autoScreens: Boolean = false,
    val notice: String? = null,
    val finished: String? = null,
)

/**
 * A meeting recorded from the phone: the room's speech goes line by line to `meeting.line` (speaker Room), typed
 * notes to `meeting.note`, and screenshots as `screen` frames, on tap or every 20 seconds.
 */
class LiveMeeting(
    context: Context,
    private val rpc: RpcChannel,
    private val scope: CoroutineScope,
    private val micInUse: (Boolean) -> Unit,
) {
    private val _ui = MutableStateFlow(LiveMeetingUi())
    val ui: StateFlow<LiveMeetingUi> = _ui
    private var seq = 0L
    private var autoJob: Job? = null
    private var locale: Locale = Locale.getDefault()

    private val recognizer = ContinuousRecognizer(context, object : ContinuousRecognizer.Listener {
        override fun onPartial(text: String) = _ui.update { it.copy(partial = text) }
        override fun onFinal(text: String) = addLine(text)
        override fun onProblem(message: String) = _ui.update { it.copy(notice = message) }
    }).apply { continuous = true }

    val isLive get() = _ui.value.meeting != null

    suspend fun start(scopeId: String?, title: String, locale: Locale) {
        if (_ui.value.meeting != null || _ui.value.starting) return
        this.locale = locale
        _ui.value = LiveMeetingUi(starting = true, title = title)
        try {
            val r = rpc.call("meeting.start", jsonOf("scope" to scopeId, "title" to title.ifBlank { null }))
            val id = r.text("meeting") ?: throw IllegalStateException("The Mac didn't start a meeting.")
            seq = 0
            _ui.value = LiveMeetingUi(meeting = id, title = title)
            micInUse(true)
            recognizer.start(locale)
        } catch (e: Exception) {
            _ui.value = LiveMeetingUi(notice = e.userMessage())
        }
    }

    fun note(text: String) {
        val id = _ui.value.meeting ?: return
        if (text.isBlank()) return
        _ui.update { it.copy(notes = it.notes + text) }
        scope.launch {
            runCatching { rpc.call("meeting.note", jsonOf("meeting" to id, "text" to text)) }
                .onFailure { e -> _ui.update { it.copy(notice = e.userMessage()) } }
        }
    }

    fun captureScreen() {
        val id = _ui.value.meeting ?: return
        val service = ScreenCaptureService.instance ?: run {
            _ui.update { it.copy(notice = "Turn on screen capture first.") }
            return
        }
        service.capture { jpeg ->
            if (jpeg == null) return@capture
            if (rpc.sendFrame(BinaryFrame.screen(id, seq++, jpeg))) {
                _ui.update { it.copy(screens = it.screens + 1) }
            } else {
                _ui.update { it.copy(notice = "Couldn't send the screenshot: not connected to the Mac.") }
            }
        }
    }

    fun setAutoScreens(on: Boolean) {
        _ui.update { it.copy(autoScreens = on) }
        autoJob?.cancel()
        if (!on) return
        autoJob = scope.launch {
            while (isActive) {
                delay(20_000)
                if (_ui.value.capturing) captureScreen()
            }
        }
    }

    fun screenCaptureStarted() = _ui.update { it.copy(capturing = true) }

    fun screenCaptureEnded(problem: String?) = _ui.update { it.copy(capturing = false, notice = problem ?: it.notice) }

    /** Stops recording; the Mac writes the summary. Returns the meeting id to show. */
    suspend fun stop(context: Context): String? {
        val id = _ui.value.meeting ?: return null
        _ui.update { it.copy(stopping = true) }
        recognizer.stop()
        autoJob?.cancel()
        ScreenCaptureService.stop(context)
        micInUse(false)
        return try {
            rpc.call("meeting.stop", jsonOf("meeting" to id))
            _ui.value = LiveMeetingUi(finished = id)
            id
        } catch (e: Exception) {
            _ui.update { it.copy(stopping = false, notice = e.userMessage()) }
            null
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    private fun addLine(text: String) {
        val id = _ui.value.meeting ?: return
        _ui.update { it.copy(lines = it.lines + text, partial = "") }
        scope.launch {
            runCatching {
                rpc.call(
                    "meeting.line",
                    jsonOf("meeting" to id, "text" to text, "speaker" to "Room", "language" to locale.toLanguageTag()),
                )
            }.onFailure { e -> _ui.update { it.copy(notice = e.userMessage()) } }
        }
    }
}
