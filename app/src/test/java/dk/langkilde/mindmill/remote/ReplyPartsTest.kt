package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPartsTest {
    private val asking = """
        **Which way?**

        One line of context.

        ```choices
        Start a new session for it (Recommended)
        - Give it to the Android lead
        2. Just talk it through
        ```
    """.trimIndent()

    @Test fun choicesBecomeOptionsWithTheRecommendedOneMarked() {
        val parts = ReplyParts.parse(asking)
        assertEquals(2, parts.size)
        assertTrue((parts[0] as ReplyPart.Text).markdown.contains("Which way?"))
        val c = parts[1] as ReplyPart.Choices
        assertFalse(c.multi)
        assertEquals(
            listOf(
                ChoiceOption("Start a new session for it", true),
                ChoiceOption("Give it to the Android lead", false),
                ChoiceOption("Just talk it through", false),
            ),
            c.options,
        )
    }

    @Test fun multiAndStrategyBlocks() {
        val multi = ReplyParts.trailing("Pick some:\n```choices multi\n- [ ] A\n- [ ] B\n```")!!
        assertTrue(multi.multi)
        assertEquals(listOf("A", "B"), multi.options.map { it.label })
        assertEquals("A, B", ReplyParts.answer(multi, multi.options))
        val st = ReplyParts.trailing("Why tdd.\n```strategy\ntdd (Recommended)\ncrew\n```")!!
        assertTrue(st.strategies)
        assertEquals("Use the “tdd” strategy (tdd).", ReplyParts.answer(st, listOf(st.options[0])))
        assertEquals("No strategy.", ReplyParts.answer(st, listOf(ChoiceOption("No strategy", false))))
    }

    @Test fun otherCodeBlocksStayTextAndEmptyChoicesAreDropped() {
        val parts = ReplyParts.parse("Run this:\n```sh\nls\n```\n```choices\n\n```\nDone.")
        assertEquals(1, parts.size)
        assertTrue((parts[0] as ReplyPart.Text).markdown.contains("```sh"))
    }

    @Test fun onlyAReplyThatEndsWithOptionsAsks() {
        assertTrue(ReplyParts.trailing(asking) != null)
        assertTrue(ReplyParts.trailing("$asking\n\nOr tell me something else.") != null)
        assertNull(ReplyParts.trailing("$asking\n\n## Next\nMore."))
        assertNull(ReplyParts.trailing("$asking\n\n- a list after"))
        assertNull(ReplyParts.trailing("No options here?"))
    }

    @Test fun onlyTheLatestReplyIsActiveAndOnlyWhileTheSessionWaits() {
        val items = listOf(
            ChatItem("assistant", asking),
            ChatItem("user", "Just talk it through"),
            ChatItem("assistant", asking),
            ChatItem("tool", "Read\tx.kt"),
            ChatItem("status", "Stopped."),
        )
        assertEquals(2, ReplyParts.activeIndex(items, busy = false))
        assertNull(ReplyParts.activeIndex(items, busy = true))
        assertNull(ReplyParts.activeIndex(items + ChatItem("user", "Hi", pending = true), busy = false))
        assertNull(ReplyParts.activeIndex(items + ChatItem("error", "Failed"), busy = false))
        assertNull(ReplyParts.activeIndex(listOf(ChatItem("assistant", "No question.")), busy = false))
    }
}
