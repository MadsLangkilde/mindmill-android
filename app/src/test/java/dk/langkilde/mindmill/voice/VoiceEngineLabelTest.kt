package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.parseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEngineLabelTest {
    @Test fun saysWhichVoiceIsUsed() {
        val api = VoiceConfig.parse(parseJsonObject("""{"engine":"api","provider":"gemini","providerName":"Google Gemini Live"}"""))
        assertEquals("Google Gemini Live via the Mac", api.engineLabel())
        assertEquals("On this phone", api.engineLabel(keylessOnly = true))
        assertEquals("gemini via the Mac", VoiceConfig.parse(parseJsonObject("""{"engine":"api","provider":"gemini"}""")).engineLabel())
        assertEquals("On this phone", VoiceConfig.parse(parseJsonObject("""{"engine":"keyless","providerName":"Google Gemini Live"}""")).engineLabel())
    }
}
