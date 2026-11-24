package dk.langkilde.mindmill.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** System back through the real Navigation 3 display, as a user presses it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavBackUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(navigator: Navigator, twoPane: Boolean = false) {
        rule.setContent {
            MindmillTheme {
                Column {
                    Row {
                        listOf(Screen.Home, Screen.Sessions("plan"), Screen.More).forEach { s ->
                            Button(onClick = { navigator.selectTab(s) }) { Text("tab " + s.title()) }
                        }
                    }
                    MindmillNavDisplay(navigator, twoPane, Modifier.weight(1f)) { nav, screen ->
                        Column {
                            Text("screen " + screen.title())
                            if (screen == Screen.More) Button(onClick = { nav.go(Screen.Tags) }) { Text("open Tags") }
                            if (screen is Screen.Sessions) Button(onClick = { nav.go(Screen.SessionChat("1", "One")) }) { Text("open One") }
                        }
                    }
                }
            }
        }
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun showing(title: String) {
        rule.onNodeWithText("screen $title").assertExists()
    }

    private fun gone(title: String) {
        rule.onAllNodesWithText("screen $title").assertCountEquals(0)
    }

    @Test
    fun moreThenItemThenBackReturnsToMoreThenTheStartSectionThenExits() {
        show(Navigator(NavState.initial(Mode.PLAN)))
        rule.onNodeWithText("tab More").performClick()
        rule.onNodeWithText("open Tags").performClick()
        showing("Tags")

        pressBack()
        showing("More")
        gone("Tags")
        assertFalse(rule.activity.isFinishing)

        pressBack()
        showing("Mindmill")
        gone("More")
        assertFalse(rule.activity.isFinishing)

        pressBack()
        assertTrue(rule.activity.isFinishing)
    }

    @Test
    fun onAWideWindowBackClosesTheDetailBesideTheListFirst() {
        show(Navigator(NavState.initial(Mode.PLAN)), twoPane = true)
        rule.onNodeWithText("tab Sessions").performClick()
        rule.onNodeWithText("open One").performClick()
        showing("Sessions")
        showing("Session")

        pressBack()
        showing("Sessions")
        rule.onAllNodesWithText("screen Session").assertCountEquals(0)
        assertFalse(rule.activity.isFinishing)

        pressBack()
        showing("Mindmill")
        pressBack()
        assertTrue(rule.activity.isFinishing)
    }
}
