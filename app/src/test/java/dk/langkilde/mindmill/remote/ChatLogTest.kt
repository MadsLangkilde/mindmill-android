package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogTest {
    private fun answer(count: Int, vararg items: Pair<String, String>, busy: Boolean = false) = jsonOf(
        "session" to jsonOf("id" to "s1", "title" to "Ask Mindmill", "busy" to busy, "state" to "working"),
        "items" to items.map { (r, t) -> jsonOf("role" to r, "text" to t) },
        "count" to count,
    )

    @Test fun appendsWhatIsNew() {
        val log = ChatLog()
        assertFalse(log.apply(answer(2, "user" to "hi", "assistant" to "hello"), 0))
        assertEquals(2, log.since)
        log.apply(answer(3, "tool" to "ran overview", busy = true), log.since)
        assertEquals(listOf("user", "assistant", "tool"), log.items.map { it.role })
        assertTrue(log.busy)
        assertEquals("Ask Mindmill", log.title)
    }

    @Test fun askForAFullReloadWhenTheSessionShrank() {
        val log = ChatLog()
        log.apply(answer(3, "user" to "a", "assistant" to "b", "user" to "c"), 0)
        assertTrue(log.apply(answer(1), 3))
        assertEquals(3, log.items.size)
        log.apply(answer(1, "status" to "cleared"), 0)
        assertEquals(listOf("cleared"), log.items.map { it.text })
    }

    @Test fun pendingMessageGivesWayToTheMacsCopy() {
        val log = ChatLog()
        log.apply(answer(1, "assistant" to "How can I help?"), 0)
        log.addPending("Start a meeting")
        assertEquals(2, log.items.size)
        assertTrue(log.items.last().pending)
        assertEquals(1, log.since)
        log.apply(answer(2, "user" to "Start a meeting"), log.since)
        assertEquals(2, log.items.size)
        assertFalse(log.items.last().pending)
    }

    @Test fun failedSendDropsThePendingMessage() {
        val log = ChatLog()
        log.addPending("hello")
        log.dropPending("hello")
        assertTrue(log.items.isEmpty())
    }

    @Test fun behindWhenTheCountDiffers() {
        val log = ChatLog()
        log.apply(answer(1, "user" to "a"), 0)
        assertFalse(log.isBehind(1))
        assertTrue(log.isBehind(2))
        assertTrue(log.isBehind(null))
    }
}
