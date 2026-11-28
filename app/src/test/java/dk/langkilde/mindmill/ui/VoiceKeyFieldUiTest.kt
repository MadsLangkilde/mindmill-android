package dk.langkilde.mindmill.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The voice API key field: out of autofill, hidden, and empty again as soon as the key is sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceKeyFieldUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val sent = mutableListOf<String>()

    private fun show() = rule.setContent { MindmillTheme { VoiceKeyField("Gemini key", enabled = true, onSend = { sent += it }) } }

    private fun field() = rule.onNode(hasSetTextAction(), useUnmergedTree = true)

    @Test
    fun theFieldIsAPasswordThatAutofillLeavesAlone() {
        show()
        field().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None))
    }

    @Test
    fun sendingHandsOverTheTrimmedKeyAndEmptiesTheField() {
        show()
        rule.onNodeWithText("Save on the Mac").assertIsNotEnabled()
        field().performTextInput("  AIza-secret-123 ")
        rule.onNodeWithText("Save on the Mac").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("AIza-secret-123"), sent)
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText("Save on the Mac").assertIsNotEnabled()
    }
}
