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
        override fun startListening() { log += "listen" }
        override fun stopListening() { log += "stopListening" }
        override fun speak(text: String, utteranceId: String) { spoken += utteranceId to text }
        override fun stopSpeaking() { log += "stopSpeaking" }
        override fun startBargeInWatch() { log += "watch" }
        override fun stopBargeInWatch() { log += "unwatch" }
        override fun send(text: String) { sent += text }
    }

    @Test fun acknowledgesAtOnceThenSpeaksTheReplyThenListensAgain() {
        val fx = Fx()
        val m = LiveChatMachine(fx, Acknowledgements(Random(1)))
        m.start()
        assertEquals(LivePhase.LISTENING, m.phase)
        m.heard("  what needs me today ")
        assertEquals(listOf("what needs me today"), fx.sent)
        assertEquals(LivePhase.WAITING, m.phase)
        val ack = fx.spoken.single()
        assertTrue(ack.second in Acknowledgements.PHRASES.getValue(LiveLanguage.EN))

        m.reply("Two things need you.")
        assertEquals(LivePhase.SPEAKING, m.phase)
        m.turnDone()
        assertEquals("still speaking", LivePhase.SPEAKING, m.phase)
        fx.log.clear()
        m.utteranceDone(ack.first)
        m.utteranceDone(fx.spoken[1].first)
        assertEquals(LivePhase.LISTENING, m.phase)
        assertEquals(listOf("unwatch", "listen"), fx.log)
    }

    @Test fun blankSpeechJustListensAndTalkingOverTheReplyStopsIt() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.heard(" ")
        assertTrue(fx.sent.isEmpty())
        m.heard("hi")
        m.reply("A long answer")
        m.bargeIn()
        assertEquals(LivePhase.LISTENING, m.phase)
        assertTrue("stopSpeaking" in fx.log)
        val before = fx.spoken.size
        m.reply("the rest of the old turn")
        assertEquals("the rest of the interrupted turn isn't spoken", before, fx.spoken.size)
        m.heard("next")
        m.reply("New answer")
        assertEquals("New answer", fx.spoken.last().second)
    }

    @Test fun aFailedSendSaysSoAndListensAgain() {
        val fx = Fx()
        val m = LiveChatMachine(fx)
        m.start()
        m.heard("hi")
        m.failed("That wasn't sent.")
        fx.spoken.forEach { m.utteranceDone(it.first) }
        assertEquals(LivePhase.LISTENING, m.phase)
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
