package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class LiveChatTest {
    private class Fx : LiveEffects {
        val log = mutableListOf<String>()
        val spoken = mutableListOf<Pair<String, String>>()
        val sent = mutableListOf<String>()
        var timer: Long? = null
        var stops = 0
        override fun startListening() { log += "listen" }
        override fun stopListening() { log += "stopListening" }
        override fun speak(text: String, utteranceId: String) { spoken += utteranceId to text }
        override fun stopSpeaking() { log += "stopSpeaking" }
        override fun startBargeInWatch() { log += "watch" }
        override fun stopBargeInWatch() { log += "unwatch" }
        override fun send(text: String) { sent += text }
        override fun stopSession() { stops++ }
        override fun startSendTimer(ms: Long) { timer = ms }
        override fun cancelSendTimer() { timer = null }
    }

    /** Says [text] and pauses long enough for it to go. */
    private fun LiveChatMachine.say(fx: Fx, vararg text: String) {
        text.forEach { partial(it); heard(it) }
        assertTrue("the pause timer runs", fx.timer != null)
        fx.timer = null
        pauseOver()
    }

    private fun LiveChatMachine.finishSpeaking(fx: Fx) = fx.spoken.forEach { utteranceDone(it.first) }

    @Test fun collectsWhatIsSaidUntilAPauseThenSendsItAndAcknowledges() {
        val fx = Fx()
        val m = LiveChatMachine(fx, Acknowledgements(Random(1)))
        m.start()
        assertEquals(LivePhase.LISTENING, m.phase)
        m.partial("what needs")
        m.heard("  what needs me ")
        assertTrue("nothing goes after one short pause", fx.sent.isEmpty())
        assertEquals(LiveChatMachine.PAUSE_MS, fx.timer)
        m.partial("today")
        assertEquals("talking again holds the send", null, fx.timer)
        m.heard("today")
        assertEquals("what needs me today", m.draft)
        m.pauseOver()
        assertEquals(listOf("what needs me today"), fx.sent)
        assertEquals("", m.draft)
        assertTrue(m.working)
        assertEquals(LivePhase.SPEAKING, m.phase)
        val ack = fx.spoken.single()
        assertTrue(ack.second in Acknowledgements.PHRASES.getValue(LiveLanguage.EN))
        assertTrue("the recogniser is off while the phone talks", "stopListening" in fx.log)

        fx.log.clear()
        m.utteranceDone(ack.first)
        assertEquals("it listens while Claude works", LivePhase.WAITING, m.phase)
        assertEquals(listOf("unwatch", "listen"), fx.log)

        m.reply("Two things need you.")
        assertEquals(LivePhase.SPEAKING, m.phase)
        m.turnDone()
        assertEquals("still speaking", LivePhase.SPEAKING, m.phase)
        m.utteranceDone(fx.spoken[1].first)
        assertEquals(LivePhase.LISTENING, m.phase)
        assertFalse(m.working)
    }

    @Test fun whatIsSaidWhileClaudeWorksIsAddedAndRepliesWaitForTheUser() {
        val fx = Fx()
        val m = LiveChatMachine(fx, Acknowledgements(Random(2)))
        m.start()
        m.say(fx, "Fix the login bug")
        m.finishSpeaking(fx)
        assertEquals(LivePhase.WAITING, m.phase)

        m.partial("and also")
        m.reply("I found the bug.")
        assertEquals("a reply doesn't cut the user off", 1, fx.spoken.size)
        m.heard("and also check the logout")
        m.pauseOver()
        assertEquals(listOf("Fix the login bug", "and also check the logout"), fx.sent)
        assertTrue(fx.spoken[1].second in Acknowledgements.ADDED.getValue(LiveLanguage.EN))
        assertEquals("the held reply follows", "I found the bug.", fx.spoken[2].second)
        assertTrue(m.working)
    }

    @Test fun sayingStopWhileClaudeWorksStopsTheSession() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.say(fx, "Refactor everything")
        m.finishSpeaking(fx)
        m.heard("Stop!")
        assertEquals(1, fx.stops)
        assertTrue("stop isn't sent as a message", fx.sent == listOf("Refactor everything"))
        assertEquals(LivePhrases.stopped(LiveLanguage.EN), fx.spoken.last().second)
        m.reply("the rest of the stopped work")
        assertEquals("nothing more of the stopped turn is said", LivePhrases.stopped(LiveLanguage.EN), fx.spoken.last().second)
        m.finishSpeaking(fx)
        assertEquals(LivePhase.LISTENING, m.phase)
        m.heard("stop")
        assertEquals("stop with nothing running does nothing", 1, fx.stops)
        assertEquals(null, fx.timer)
    }

    @Test fun stopCommandsAreWholeUtterances() {
        listOf("Stop", "stop it.", "Hold op", "annuller", "Mindmill, stop", "cancel that").forEach {
            assertTrue(it, StopCommand.matches(it))
        }
        listOf("stop the build after the tests", "don't stop", "cancel the meeting").forEach {
            assertFalse(it, StopCommand.matches(it))
        }
    }

    @Test fun talkingOverTheReplyStopsItAndListensForMore() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.heard(" ")
        assertEquals(null, fx.timer)
        m.say(fx, "hi")
        m.reply("A long answer")
        m.bargeIn()
        assertEquals("Claude still works, so what's said is added", LivePhase.WAITING, m.phase)
        assertTrue("stopSpeaking" in fx.log)
        val before = fx.spoken.size
        m.reply("the rest of the old turn")
        assertEquals("the rest of the interrupted turn isn't spoken", before, fx.spoken.size)
        m.say(fx, "next")
        m.reply("New answer")
        assertEquals("New answer", fx.spoken.last().second)
    }

    @Test fun talkingOverTheAcknowledgementStillReadsTheReply() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.say(fx, "what's new")
        assertEquals("ack", fx.spoken.single().first.substringBefore(":"))
        m.bargeIn()
        assertEquals(LivePhase.WAITING, m.phase)
        m.reply("Three things changed today.")
        assertEquals("Three things changed today.", fx.spoken.last().second)
    }

    @Test fun theSendButtonSendsAtOnceWithTheWordsStillComingIn() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.heard("open the")
        m.partial("backlog")
        m.sendNow("backlog")
        assertEquals(listOf("open the backlog"), fx.sent)
        assertEquals(null, fx.timer)
    }

    @Test fun aFailedSendSaysSoAndListensAgain() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.say(fx, "hi")
        m.failed("That wasn't sent.")
        m.finishSpeaking(fx)
        assertEquals(LivePhase.LISTENING, m.phase)
        assertFalse(m.working)
        m.end()
        assertEquals(LivePhase.IDLE, m.phase)
    }

    @Test fun acknowledgementsNeverRepeatInARowAndFollowTheLanguage() {
        val acks = Acknowledgements(Random(7))
        var last = ""
        repeat(200) {
            val next = acks.next(LiveLanguage.DA)
            assertNotEquals(last, next)
            assertTrue(next in Acknowledgements.PHRASES.getValue(LiveLanguage.DA))
            last = next
        }
        assertEquals(LiveLanguage.DA, LiveLanguage.of("da-DK"))
        assertEquals(LiveLanguage.EN, LiveLanguage.of("en-US", Locale.forLanguageTag("da-DK")))
        assertEquals(LiveLanguage.DA, LiveLanguage.of("auto", Locale.forLanguageTag("da-DK")))
        assertEquals(LiveLanguage.EN, LiveLanguage.of(null, Locale.US))
    }

    @Test fun repliesAreReadWithoutMarkdownAndOptionsBriefly() {
        val reply = """
            ## Today
            You have **two** things: a [review](https://x.y/z) and `kb` facts.

            | a | b |
            |---|---|
            | 1 | 2 |

            ```kotlin
            val x = 1
            ```
            - First item
            - Second item

            ```choices
            Do the review (Recommended)
            Later
            Skip it
            ```
        """.trimIndent()
        assertEquals(
            "Today. You have two things: a review and kb facts. First item. Second item. Options: Do the review, Later or Skip it.",
            SpokenText.of(reply, LiveLanguage.EN),
        )
        assertEquals("Muligheder: Ja eller Nej.", SpokenText.options(listOf("Ja", "Nej"), LiveLanguage.DA))
        val long = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val said = SpokenText.of(long, LiveLanguage.EN, limit = 200)
        assertTrue(said.length < 260)
        assertTrue(said.endsWith("The rest is in the chat."))
    }

    @Test fun followsOneTurnUntilTheSessionStopsWorking() {
        val f = ReplyFollower()
        val before = listOf(ChatItem("user", "old"), ChatItem("assistant", "old answer"))
        f.begin(before.size)
        val pending = before + ChatItem("user", "new", pending = true)
        assertEquals(ReplyFollower.Update(emptyList(), false), f.follow(pending, busy = false))
        f.sent()
        val working = before + ChatItem("user", "new") + ChatItem("tool", "Read\tx") + ChatItem("assistant", "Looking.")
        val u1 = f.follow(working, busy = true)
        assertEquals(listOf("Looking."), u1.say.map { it.text })
        assertFalse(u1.done)
        val u2 = f.follow(working + ChatItem("assistant", "Found it."), busy = false)
        assertEquals(listOf("Found it."), u2.say.map { it.text })
        assertTrue(u2.done)
        assertTrue(f.spokeAny)
        assertFalse(f.following)
    }
}
