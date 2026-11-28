package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.parseJsonObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceProvidersTest {
    private val answer = parseJsonObject(
        """
        {"providers": [
           {"id": "gemini", "name": "Google Gemini Live", "available": true, "hasKey": true, "keyPage": "https://aistudio.google.com/apikey",
            "models": ["gemini-live-2.5-flash", "gemini-2.5-pro"], "model": "gemini-live-2.5-flash", "voices": ["Puck", "Kore"], "voice": "Puck",
            "key": "sk-should-never-be-kept", "apiKey": "sk-nor-this"},
           {"id": "openai", "name": "OpenAI Realtime", "available": true, "hasKey": false, "keyPage": "javascript:alert(1)",
            "models": [], "model": "", "voices": [], "voice": ""},
           {"id": "later", "name": "Some Later Voice", "available": false, "hasKey": false},
           {"name": "no id"}
         ],
         "active": "gemini", "engine": "api"}
        """.trimIndent(),
    )!!

    @Test
    fun parsesProvidersAndWhichOneIsUsed() {
        val ps = VoiceProviders.parse(answer)
        assertTrue(ps.supported)
        assertEquals(listOf("gemini", "openai", "later"), ps.providers.map { it.id })
        assertEquals(listOf("gemini", "openai"), ps.available.map { it.id })
        assertEquals(listOf("later"), ps.later.map { it.id })
        val gemini = ps.providers[0]
        assertEquals("gemini", ps.inUse?.id)
        assertTrue(ps.isInUse(gemini))
        assertTrue(gemini.hasKey)
        assertEquals("https://aistudio.google.com/apikey", gemini.keyPage)
        assertEquals(listOf("gemini-live-2.5-flash", "gemini-2.5-pro"), gemini.models)
        assertEquals("Puck", gemini.voice)
        val openai = ps.providers[1]
        assertFalse(openai.hasKey)
        assertNull(openai.keyPage)
        assertNull(openai.model)
        assertNull(openai.voice)
        assertFalse(ps.isInUse(openai))
    }

    @Test
    fun noKeyTheMacSendsIsKept() {
        val ps = VoiceProviders.parse(answer)
        assertFalse(ps.toString().contains("sk-"))
        val fields = VoiceProvider::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.equals("key", ignoreCase = true) || it.contains("apiKey", ignoreCase = true) || it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun nothingIsInUseWhileTheMacUsesThePhonesOwnVoice() {
        val ps = VoiceProviders.parse(parseJsonObject("""{"providers": [{"id": "gemini", "available": true, "hasKey": true}], "active": "gemini", "engine": "keyless"}""")!!)
        assertNull(ps.inUse)
        assertEquals("gemini", ps.providers.single().name)
        assertNull(VoiceProviders.parse(parseJsonObject("""{"providers": [], "active": null, "engine": "api"}""")!!).inUse)
    }

    private class FakeMac(val answers: (String) -> JsonObject) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        suspend fun call(method: String, params: JsonObject): JsonObject { calls += method to params; return answers(method) }
    }

    @Test
    fun anOlderMacHasNoProvidersToShowAndOtherFailuresStillFail() = runTest {
        for (code in listOf("unknown_method", "unavailable")) {
            val r = VoiceApi { _, _ -> throw RemoteException(code, "nope") }.providers()
            assertFalse(r.supported)
            assertTrue(r.providers.isEmpty())
        }
        try {
            VoiceApi { _, _ -> throw RemoteException("failed", "broken") }.providers()
            fail("should fail")
        } catch (e: RemoteException) {
            assertEquals("failed", e.code)
        }
    }

    @Test
    fun theKeyGoesToTheMacOnceTrimmedAndTheMacsAnswerIsShown() = runTest {
        val mac = FakeMac { jsonOf("saved" to true, "ok" to false, "message" to "Google Gemini Live refused the key.") }
        val r = VoiceApi(mac::call).saveKey("gemini", "  AIza-secret-123\n")
        assertEquals(KeyCheck(false, "Google Gemini Live refused the key."), r)
        assertEquals("voice.key.set" to jsonOf("provider" to "gemini", "key" to "AIza-secret-123", "use" to true), mac.calls.single())
        assertEquals(KeyCheck(true, "The Mac keeps the key, and it works."), VoiceApi { _, _ -> jsonOf("saved" to true, "ok" to true) }.saveKey("gemini", "k"))
    }

    @Test
    fun useRemoveAndTestNameOnlyWhatTheyChange() = runTest {
        val mac = FakeMac { m ->
            when (m) {
                "voice.use" -> jsonOf("engine" to "api", "provider" to "gemini", "providerName" to "Google Gemini Live", "model" to "gemini-2.5-pro")
                "voice.key.remove" -> jsonOf("removed" to true)
                "voice.test" -> jsonOf("ok" to true, "message" to "The key works.", "providerName" to "OpenAI Realtime")
                else -> error(m)
            }
        }
        val api = VoiceApi(mac::call)
        assertEquals("gemini-2.5-pro", api.use("gemini", model = "gemini-2.5-pro").model)
        api.use("gemini", voice = "Kore")
        api.use(null)
        assertTrue(api.removeKey("openai"))
        assertEquals(KeyCheck(true, "The key works."), api.test("openai"))
        assertEquals(
            listOf(
                "voice.use" to jsonOf("provider" to "gemini", "model" to "gemini-2.5-pro"),
                "voice.use" to jsonOf("provider" to "gemini", "voice" to "Kore"),
                "voice.use" to jsonOf(),
                "voice.key.remove" to jsonOf("provider" to "openai"),
                "voice.test" to jsonOf("provider" to "openai"),
            ),
            mac.calls,
        )
    }

    @Test
    fun theFallbackSaysWhyInWordsNotACode() {
        assertEquals("Check the key on the Mac.", VoiceConversation.fallbackNotice("badKey", " Check the key on the Mac. "))
        val texts = listOf("badKey", "quota", "network", "keyLocked", "other").associateWith { VoiceConversation.fallbackNotice(it, null) }
        texts.forEach { (reason, text) ->
            assertFalse("$reason shows a code", text.contains(":") && text.substringAfterLast(":").trim() == reason)
            assertTrue(text.endsWith("."))
        }
        assertFalse(texts.values.any { "badKey" in it || "keyLocked" in it })
        assertEquals(5, texts.values.toSet().size)
        assertTrue(texts.getValue("keyLocked").contains("login password"))
        assertEquals(texts.getValue("other"), VoiceConversation.fallbackNotice("somethingNew", ""))
        assertEquals(texts.getValue("other"), VoiceConversation.fallbackNotice(null, null))
    }
}
