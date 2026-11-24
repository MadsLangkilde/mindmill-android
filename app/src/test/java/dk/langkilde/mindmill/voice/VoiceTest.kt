package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.parseJsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private class RecordingEffects : KeylessEffects {
    val log = mutableListOf<String>()
    val spoken = mutableListOf<Pair<String, String>>()
    override fun startListening() { log += "listen" }
    override fun stopListening() { log += "stopListen" }
    override fun speak(text: String, utteranceId: String) { log += "speak:$text"; spoken += utteranceId to text }
    override fun stopSpeaking() { log += "stopSpeak" }
    override fun startBargeInWatch() { log += "watch" }
    override fun stopBargeInWatch() { log += "unwatch" }
    override fun say(text: String) { log += "say:$text" }
    override fun interrupt() { log += "interrupt" }
}

class KeylessVoiceMachineTest {
    private val fx = RecordingEffects()
    private val states = mutableListOf<KeylessState>()
    private val m = KeylessVoiceMachine(fx) { states += it }

    @Test
    fun listensSendsSpeaksAndListensAgain() {
        m.start()
        assertEquals(KeylessState.LISTENING, m.state)
        m.heard("what needs me today")
        assertEquals(KeylessState.SENDING, m.state)
        assertTrue("say:what needs me today" in fx.log)
        m.turnStarted("t1")
        m.sentence("t1", 0, "You have two proposed facts.")
        assertEquals(KeylessState.SPEAKING, m.state)
        assertTrue("watch" in fx.log)
        m.sentence("t1", 1, "And one follow-up.")
        m.done("t1", "You have two proposed facts. And one follow-up.")
        assertEquals(KeylessState.SPEAKING, m.state)
        fx.spoken.forEach { m.utteranceDone(it.first) }
        assertEquals(KeylessState.LISTENING, m.state)
        assertEquals(
            listOf(KeylessState.LISTENING, KeylessState.SENDING, KeylessState.SPEAKING, KeylessState.LISTENING),
            states,
        )
        assertEquals("unwatch", fx.log[fx.log.lastIndexOf("listen") - 1])
    }

    @Test
    fun startsSpeakingOnTheFirstSentenceBeforeTheTurnIsDone() {
        m.start(); m.heard("hi")
        m.sentence("t1", 0, "Hello.")
        assertEquals(listOf("Hello."), fx.spoken.map { it.second })
        m.utteranceDone(fx.spoken[0].first)
        assertEquals(KeylessState.SPEAKING, m.state)
    }

    @Test
    fun speaksSentencesInOrderEvenWhenTheyArriveOutOfOrder() {
        m.start(); m.heard("tell me")
        m.sentence("t1", 0, "One.")
        m.sentence("t1", 2, "Three.")
        m.sentence("t1", 1, "Two.")
        m.sentence("t1", 3, "Four.")
        assertEquals(listOf("One.", "Two.", "Three.", "Four."), fx.spoken.map { it.second })
    }

    @Test
    fun aTurnWithNoSentencesSpeaksTheWholeReply() {
        m.start(); m.heard("x")
        m.done("t1", "Done.")
        assertEquals(listOf("Done."), fx.spoken.map { it.second })
        m.utteranceDone(fx.spoken.single().first)
        assertEquals(KeylessState.LISTENING, m.state)
    }

    @Test
    fun bargeInStopsSpeechSendsInterruptClearsTheQueueAndListens() {
        m.start(); m.heard("read the backlog")
        m.turnStarted("t1")
        m.sentence("t1", 0, "First item.")
        m.sentence("t1", 1, "Second item.")
        assertEquals(2, m.queuedUtterances)
        fx.log.clear()
        m.bargeIn()
        assertEquals(listOf("stopSpeak", "unwatch", "interrupt", "listen"), fx.log)
        assertEquals(KeylessState.LISTENING, m.state)
        assertEquals(0, m.queuedUtterances)
        m.sentence("t1", 2, "Third item.")
        m.done("t1", "whole")
        assertEquals(2, fx.spoken.size)
        assertEquals(KeylessState.LISTENING, m.state)
    }

    @Test
    fun lateSentencesOfAnInterruptedUnnamedTurnAreNotSpokenInTheNextTurn() {
        m.start(); m.heard("first question")
        m.bargeIn()
        m.heard("second question")
        m.sentence("old", 0, "Stale answer.")
        m.turnStarted("new")
        m.sentence("new", 0, "Fresh answer.")
        assertEquals(listOf("Fresh answer."), fx.spoken.map { it.second })
    }

    @Test
    fun errorsAreSpokenThenItListensAgain() {
        m.start(); m.heard("x")
        m.announce("The Mac couldn't do that.")
        assertEquals(KeylessState.SPEAKING, m.state)
        m.utteranceDone(fx.spoken.single().first)
        assertEquals(KeylessState.LISTENING, m.state)
    }

    @Test
    fun blankRecognitionJustListensAgain() {
        m.start(); fx.log.clear()
        m.heard("  ")
        assertEquals(listOf("listen"), fx.log)
        assertEquals(KeylessState.LISTENING, m.state)
    }

    @Test
    fun pauseAndResume() {
        m.start(); m.heard("x"); m.sentence("t", 0, "a")
        m.pause()
        assertEquals(KeylessState.PAUSED, m.state)
        m.sentence("t", 1, "b")
        assertEquals(1, fx.spoken.size)
        m.resume()
        assertEquals(KeylessState.LISTENING, m.state)
    }
}

class VadTest {
    @Test
    fun quietRoomIsNotSpeechButSustainedVoiceIs() {
        val vad = EnergyVad()
        val quiet = ShortArray(320) { ((it % 7) - 3).toShort() }
        val loud = ShortArray(320) { (if (it % 2 == 0) 6000 else -6000).toShort() }
        repeat(50) { assertFalse(vad.feed(quiet)) }
        var spoke = false
        repeat(12) { spoke = vad.feed(loud) || spoke }
        assertTrue(spoke)
    }

    @Test
    fun aShortClickIsIgnored() {
        val vad = EnergyVad()
        val quiet = ShortArray(320)
        val loud = ShortArray(320) { 8000 }
        repeat(30) { vad.feed(quiet) }
        assertFalse(vad.feed(loud))
        assertFalse(vad.feed(loud))
        repeat(5) { assertFalse(vad.feed(quiet)) }
    }
}

private class FakeRemote(var config: VoiceConfig, var engine: VoiceEngine?) : VoiceRemote {
    val calls = mutableListOf<String>()
    val frames = mutableListOf<BinaryFrame>()
    var forced: Boolean? = null
    override suspend fun config() = config.also { calls += "config" }
    override suspend fun start(scope: String?, forceKeyless: Boolean): Pair<String, VoiceEngine?> {
        calls += "start"; forced = forceKeyless
        return "conv1" to engine
    }
    override suspend fun say(conversation: String, text: String): String { calls += "say:$conversation:$text"; return "t1" }
    override suspend fun interrupt(conversation: String) { calls += "interrupt:$conversation" }
    override suspend fun end(conversation: String) { calls += "end:$conversation" }
    override fun sendFrame(frame: BinaryFrame): Boolean { frames += frame; return true }
}

private class FakeDevice : KeylessDevice {
    override var events: DeviceEvents? = null
    val log = mutableListOf<String>()
    val spoken = mutableListOf<Pair<String, String>>()
    var rate = 0f
    override fun configure(rate: Float, locale: Locale, voiceHint: String?) { this.rate = rate; log += "configure:${locale.toLanguageTag()}" }
    override fun startListening() { log += "listen" }
    override fun stopListening() { log += "stopListen" }
    override fun speak(text: String, utteranceId: String) { log += "speak:$text"; spoken += utteranceId to text }
    override fun stopSpeaking() { log += "stopSpeak" }
    override fun startBargeInWatch() { log += "watch" }
    override fun stopBargeInWatch() { log += "unwatch" }
    override fun begin() { log += "begin" }
    override fun finish() { log += "finish" }
}

private class FakeAudio : ApiAudio {
    val log = mutableListOf<String>()
    var mic: ((ByteArray) -> Unit)? = null
    override fun startStreaming(onFrame: (ByteArray) -> Unit) { log += "stream"; mic = onFrame }
    override fun stopStreaming() { log += "stopStream"; mic = null }
    override fun play(pcm24k: ByteArray) { log += "play:${pcm24k.size}" }
    override fun clear() { log += "clear" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EngineSelectionTest {
    private fun TestScope.conversation(config: VoiceConfig, picked: VoiceEngine?): Triple<VoiceConversation, FakeRemote, Pair<FakeDevice, FakeAudio>> {
        val remote = FakeRemote(config, picked)
        val device = FakeDevice()
        val audio = FakeAudio()
        return Triple(VoiceConversation(remote, device, audio, this), remote, device to audio)
    }

    @Test
    fun apiEngineStreamsMicAudioAndPlaysTheMacsAudio() = runTest(UnconfinedTestDispatcher()) {
        val (c, remote, hw) = conversation(VoiceConfig(engine = VoiceEngine.API, providerName = "OpenAI"), VoiceEngine.API)
        val (device, audio) = hw
        c.begin(null, keylessOnly = false)
        assertEquals(VoiceEngine.API, c.engine)
        assertEquals(false, remote.forced)
        assertTrue("stream" in audio.log)
        assertFalse("listen" in device.log)
        audio.mic!!(ByteArray(1280))
        audio.mic!!(ByteArray(1280))
        assertEquals(listOf(0L, 1L), remote.frames.map { it.seq })
        assertEquals("pcm16k", remote.frames[0].format)
        assertEquals("conv1", remote.frames[0].header.let { it["conversation"].toString().trim('"') })
        c.onFrame(BinaryFrame(jsonOf("kind" to "voice", "conversation" to "conv1", "format" to "pcm24k", "seq" to 0), ByteArray(960)))
        assertTrue("play:960" in audio.log)
        c.onEvent("voice.clear", jsonOf("conversation" to "conv1"))
        assertTrue("clear" in audio.log)
        assertEquals("OpenAI via the Mac", c.ui.value.engineLabel)
    }

    @Test
    fun theMacsPickWinsWhenItSaysKeyless() = runTest(UnconfinedTestDispatcher()) {
        val (c, _, hw) = conversation(VoiceConfig(engine = VoiceEngine.API), VoiceEngine.KEYLESS)
        c.begin(null, keylessOnly = false)
        assertEquals(VoiceEngine.KEYLESS, c.engine)
        assertTrue("listen" in hw.first.log)
        assertFalse("stream" in hw.second.log)
    }

    @Test
    fun keylessOnlyForcesKeylessOnTheMacToo() = runTest(UnconfinedTestDispatcher()) {
        val (c, remote, _) = conversation(VoiceConfig(engine = VoiceEngine.API), VoiceEngine.API)
        c.begin(null, keylessOnly = true)
        assertEquals(true, remote.forced)
        assertEquals(VoiceEngine.KEYLESS, c.engine)
    }

    @Test
    fun fallbackSwitchesToKeylessAndKeepsTheConversation() = runTest(UnconfinedTestDispatcher()) {
        val (c, remote, hw) = conversation(VoiceConfig(engine = VoiceEngine.API, rate = 1.3f), VoiceEngine.API)
        val (device, audio) = hw
        c.begin("proj", keylessOnly = false)
        c.onEvent("voice.fallback", jsonOf("conversation" to "conv1", "reason" to "quota", "speech" to "The voice service failed, so I'll carry on with the phone's voice."))
        assertEquals(VoiceEngine.KEYLESS, c.engine)
        assertEquals("conv1", c.conversation)
        assertTrue("stopStream" in audio.log)
        assertEquals("The voice service failed, so I'll carry on with the phone's voice.", device.spoken.single().second)
        device.events!!.utteranceDone(device.spoken.single().first)
        assertEquals(KeylessState.LISTENING, c.machine.state)
        device.events!!.heard("carry on")
        assertTrue("say:conv1:carry on" in remote.calls)
        assertEquals(1.3f, device.rate)
    }

    @Test
    fun keylessTurnEndToEndWithBargeIn() = runTest(UnconfinedTestDispatcher()) {
        val (c, remote, hw) = conversation(VoiceConfig(language = "da-DK"), VoiceEngine.KEYLESS)
        val device = hw.first
        c.begin(null, keylessOnly = false)
        assertTrue("configure:da-DK" in device.log)
        device.events!!.heard("hvad mangler")
        c.onEvent("voice.sentence", jsonOf("conversation" to "conv1", "turn" to "t1", "index" to 0, "text" to "To ting."))
        c.onEvent("voice.sentence", jsonOf("conversation" to "other", "turn" to "t1", "index" to 1, "text" to "Not mine."))
        assertEquals(listOf("To ting."), device.spoken.map { it.second })
        device.events!!.userStartedTalking()
        assertTrue("interrupt:conv1" in remote.calls)
        assertTrue("stopSpeak" in device.log)
        assertEquals(KeylessState.LISTENING, c.machine.state)
        c.end()
        assertTrue("end:conv1" in remote.calls)
    }

    @Test
    fun voiceActionCardsAreShown() = runTest(UnconfinedTestDispatcher()) {
        val (c, _, _) = conversation(VoiceConfig(), VoiceEngine.KEYLESS)
        c.begin(null, false)
        c.onEvent("voice.action", parseJsonObject("""{"conversation":"conv1","name":"factory.stop","status":"needs_confirmation","speech":"Stop it?","data":{"token":"k"}}""")!!)
        val card = c.ui.value.cards.single()
        assertEquals("k", card.token)
        assertEquals("Stop it?", card.speech)
    }
}

class VoiceConfigTest {
    @Test
    fun parsesKnownFieldsAndDropsEverythingElse() {
        val raw = parseJsonObject(
            """{"engine":"api","provider":"openai","providerName":"OpenAI","model":"gpt-realtime","rate":1.25,
               "voice":"alloy","confirmsRisky":false,"language":"en-US",
               "apiKey":"sk-live-123","api_key":"sk-2","secret":"s","token":"t"}""",
        )!!
        val c = VoiceConfig.parse(raw)
        assertEquals(VoiceEngine.API, c.engine)
        assertEquals(1.25f, c.rate)
        assertEquals(Locale.US, c.locale())
        assertFalse(c.confirmsRisky)
        val back = c.toJson()
        assertEquals(VoiceConfig.FIELDS, back.keys)
        assertFalse(back.toString().contains("sk-"))
        val properties = VoiceConfig::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(properties.none { "key" in it || "secret" in it || "token" in it })
    }

    @Test
    fun autoLanguageMeansTheDeviceLocaleAndRateIsClamped() {
        val c = VoiceConfig.parse(jsonOf("language" to "auto", "rate" to 7))
        assertEquals(Locale.GERMANY, c.locale(Locale.GERMANY))
        assertEquals(2f, c.rate)
        assertEquals(VoiceEngine.KEYLESS, VoiceConfig.parse(null).engine)
    }
}
