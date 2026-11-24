package dk.langkilde.mindmill

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import dk.langkilde.mindmill.ui.MindmillRoot
import dk.langkilde.mindmill.ui.MindmillTheme
import dk.langkilde.mindmill.ui.ProvideApp
import dk.langkilde.mindmill.ui.Screen

class MainActivity : ComponentActivity() {
    /** A screen a notification asked for; it opens on top of where the user is, so back returns there. */
    private val open = mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) open.value = screenFor(intent)
        val app = application as MindmillApp
        setContent {
            ProvideApp(app) {
                val settings by app.settings.collectAsState()
                MindmillTheme(wallpaperColours = settings.wallpaperColours) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        MindmillRoot(open = open.value, onOpened = { open.value = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screenFor(intent)?.let { open.value = it }
    }

    override fun onStart() {
        super.onStart()
        (application as MindmillApp).connection.onForeground()
    }

    companion object {
        const val EXTRA_OPEN = "dk.langkilde.mindmill.OPEN"
        const val OPEN_VOICE = "voice"
        const val OPEN_MEETING = "meeting"
        const val OPEN_LIVE = "live"
        const val EXTRA_SESSION = "dk.langkilde.mindmill.SESSION"

        fun screenFor(intent: Intent?): Screen? = when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_VOICE -> Screen.Voice
            OPEN_MEETING -> Screen.LiveMeeting
            OPEN_LIVE -> intent.getStringExtra(EXTRA_SESSION)?.let { Screen.SessionChat(it, "Session") }
            else -> null
        }
    }
}
