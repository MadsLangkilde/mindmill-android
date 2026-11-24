package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {
    private fun u(t: String) = ChatItem("user", t)
    private fun a(t: String) = ChatItem("assistant", t)
    private fun tool(t: String) = ChatItem("tool", t)
    private fun note(t: String) = ChatItem("status", t)

    @Test fun consecutiveStepsFoldIntoOneRowUntilSomethingIsWritten() {
        val rows = ChatRows.group(listOf(
            u("Fix it"), tool("Read\tMain.kt"), tool("Bash\tRun tests\t./gradlew test"), note("Stopped."), tool("Edit\tMain.kt"),
            a("Done. Want a PR?"), tool("Bash\tgit status"), tool("Grep\tTODO"), u("Yes"), tool("Read\tx"),
        ))
        assertEquals(listOf("m0", "s1", "m5", "s6", "m8", "s9"), rows.map { it.key })
        val first = rows[1] as ChatRow.Steps
        assertEquals(4, first.count)
        assertEquals("Edited Main.kt", first.latest)
        assertEquals(2, (rows[3] as ChatRow.Steps).count)
    }

    @Test fun notesAloneStayMessagesAndErrorsBreakAGroup() {
        val rows = ChatRows.group(listOf(note("Morning routine started."), note("Signed in."), u("Go"), tool("Read\ta"),
            ChatItem("error", "Failed"), tool("Read\tb")))
        assertEquals(listOf("m0", "m1", "m2", "s3", "m4", "s5"), rows.map { it.key })
        assertTrue(rows[3] is ChatRow.Steps)
    }

    @Test fun stepsReadAsTheMacWordsThem() {
        assertEquals(ToolSteps.Step("Run tests", "./gradlew test"), ToolSteps.step("Bash\tRun tests\t./gradlew test"))
        assertEquals("Ran the tests", ToolSteps.step("Bash\tcd app && ./gradlew testDebugUnitTest").title)
        assertEquals("Checked what changed", ToolSteps.step("Bash\tgit status\tgit status").title)
        assertEquals("Searched the knowledge base for “login”", ToolSteps.bash("kb search \"login\""))
        assertEquals("Read Main.kt", ToolSteps.step("Read\tMain.kt").title)
        assertEquals("Searched the code for “TODO”", ToolSteps.step("Grep\tTODO").title)
        assertEquals("Read example.com", ToolSteps.step("WebFetch\thttps://example.com/a").title)
        assertEquals("github: create pr", ToolSteps.step("mcp__github__create_pr").title)
        assertEquals("Stopped.", ToolSteps.step(ChatItem("status", "Stopped.")).title)
    }
}
