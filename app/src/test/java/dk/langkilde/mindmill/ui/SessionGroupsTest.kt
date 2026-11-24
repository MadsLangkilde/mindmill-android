package dk.langkilde.mindmill.ui

import dk.langkilde.mindmill.remote.parseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupsTest {
    private fun s(
        id: String, space: String = "plan", role: String = "solo", kind: String? = null, kindTitle: String? = null,
        archived: Boolean = false, updated: Double = 0.0, state: String = "idle", scope: String? = null, lead: String? = null,
    ) = SessionInfo(id, id, space, role, state, false, updated, 0, scope, kind, kindTitle, archived, null, lead)

    @Test fun parsesTheNewFields() {
        val o = parseJsonObject(
            """{"id":"a","title":"T","space":"plan","role":"solo","state":"waiting","kind":"research","kindTitle":"Research",
               "archived":true,"origin":{"kind":"research","id":"billing/research/x","title":"Card fees"},"scope":"billing"}""",
        )!!
        val p = SessionInfo.parse(o)
        assertEquals("research", p.kind)
        assertEquals("Research", p.kindTitle)
        assertTrue(p.archived)
        assertEquals("Card fees", p.origin)
        assertEquals("billing", p.scope)
        assertFalse("archived never needs you", p.needsYou)
        assertEquals("Ideas", SessionInfo.parse(parseJsonObject("""{"id":"b","origin":"Ideas"}""")!!).origin)
        val old = SessionInfo.parse(parseJsonObject("""{"id":"c","title":"Old","space":"plan","role":"solo"}""")!!)
        assertFalse(old.archived)
        assertEquals(null, old.kind)
    }

    @Test fun planGroupsByKindInTheMacsOrderWithArchivedApart() {
        val list = listOf(
            s("q1", kind = "questions", updated = 5.0),
            s("as", role = "assistant", kind = "assistant"),
            s("i1", kind = "ideas", updated = 1.0),
            s("i2", kind = "ideas", updated = 9.0),
            s("r1", kind = "research"),
            s("w1", kind = "workItems"),
            s("x1", kind = "brandNew", kindTitle = "Brand new"),
            s("old", kind = "ideas", archived = true),
            s("b1", space = "build"),
        )
        val g = SessionGroups.plan(list, nowSeconds = 10.0)
        assertEquals(listOf("assistant", "workItems", "research", "ideas", "questions", "brandNew"), g.groups.map { it.id })
        assertEquals(listOf("Assistant", "Work items", "Research", "Ideas", "Questions", "Brand new"), g.groups.map { it.title })
        assertEquals(listOf("i2", "i1"), g.groups.first { it.id == "ideas" }.rows.map { it.session.id })
        assertEquals(listOf("old"), g.archived.map { it.id })
        assertTrue(g.groups.flatMap { it.rows }.none { it.session.space == "build" })
    }

    @Test fun olderMacWithoutKindGroupsByRole() {
        val g = SessionGroups.plan(listOf(s("as", role = "assistant"), s("p1"), s("p2", role = "lead")))
        assertEquals(listOf("assistant", "questions"), g.groups.map { it.id })
        assertEquals(2, g.groups[1].rows.size)
        assertTrue(g.archived.isEmpty())
    }

    @Test fun kindTitleFromTheMacWins() {
        val g = SessionGroups.plan(listOf(s("e", kind = "entries", kindTitle = "About entries")))
        assertEquals("About entries", g.groups.single().title)
    }

    @Test fun needsYouCounts() {
        val g = SessionGroups.plan(listOf(s("a", kind = "ideas", state = "waiting"), s("b", kind = "ideas", state = "blocked"), s("c", kind = "ideas")))
        assertEquals(2, g.groups.single().needsYou)
    }

    @Test fun overridesMoveSessionsInAndOutOfTheArchive() {
        val list = listOf(s("a", kind = "ideas"), s("b", kind = "ideas", archived = true))
        val g = SessionGroups.plan(SessionGroups.withOverrides(list, mapOf("a" to true, "b" to false)))
        assertEquals(listOf("b"), g.groups.single().rows.map { it.session.id })
        assertEquals(listOf("a"), g.archived.map { it.id })
    }

    @Test fun buildGroupsByScopeInProjectOrderWithHelpersUnderLeads() {
        val list = listOf(
            s("w1", space = "build", scope = "web", updated = 3.0),
            s("b1", space = "build", scope = "billing", role = "lead", updated = 2.0),
            s("h1", space = "build", scope = "billing", role = "member", lead = "b1", updated = 9.0),
            s("z1", space = "build", scope = "zeta", updated = 1.0),
            s("n1", space = "build"),
            s("o1", space = "build", role = "overseer", scope = "billing"),
            s("a1", space = "build", scope = "billing", archived = true),
            s("p1", space = "plan", scope = "billing"),
        )
        val g = SessionGroups.build(list, listOf("billing" to "Billing", "web" to "Web shop"))
        assertEquals(listOf("Billing", "Web shop", "zeta", "No project"), g.groups.map { it.title })
        assertEquals(listOf(SessionRow(list[1]), SessionRow(list[2], 1)), g.groups[0].rows)
        assertEquals(listOf("a1"), g.archived.map { it.id })
    }

    @Test fun helperWhoseLeadIsGoneStaysOnTop() {
        val g = SessionGroups.build(listOf(s("h", space = "build", role = "member", lead = "gone", scope = "web")), listOf("web" to "Web"))
        assertEquals(listOf(SessionRow(g.groups.single().rows.single().session, 0)), g.groups.single().rows)
    }

    @Test fun planPutsQuietOldSessionsInOlder() {
        val now = 100.0 * 24 * 3600
        val day = 24 * 3600.0
        val list = listOf(
            s("new", kind = "ideas", updated = now - day),
            s("old", kind = "ideas", updated = now - 20 * day),
            s("oldWaiting", kind = "ideas", updated = now - 20 * day, state = "waiting"),
            s("oldWorking", kind = "research", updated = now - 30 * day, state = "working"),
            s("oldArchived", kind = "ideas", updated = now - 30 * day, archived = true),
        )
        val g = SessionGroups.plan(list, nowSeconds = now)
        assertEquals(listOf("research", "ideas"), g.groups.map { it.id })
        assertEquals(listOf("new", "oldWaiting"), g.groups[1].rows.map { it.session.id })
        assertEquals(listOf("old"), g.older.map { it.id })
        assertEquals(listOf("oldArchived"), g.archived.map { it.id })
    }

    @Test fun macsNeedsYouWinsAndArchivedNeverNeedsYou() {
        val waitingButNot = SessionInfo.parse(parseJsonObject("""{"id":"a","state":"waiting","needsYou":false}""")!!)
        assertFalse(waitingButNot.needsYou)
        val archived = SessionInfo.parse(parseJsonObject("""{"id":"b","state":"waiting","archived":true}""")!!)
        assertFalse(archived.needsYou)
        assertTrue(SessionInfo.parse(parseJsonObject("""{"id":"c","state":"idle","needsYou":true}""")!!).needsYou)
    }

    @Test fun linkedConversationsKnowWhereTheyLive() {
        val o = parseJsonObject("""{"id":"link:meeting:x","title":"Stand-up chat","space":"plan","role":"linked","kind":"meetings",
            "link":{"screen":"meeting","id":"billing/meetings/standup"}}""")!!
        val p = SessionInfo.parse(o)
        assertTrue(p.isLink)
        assertEquals("meeting", p.linkScreen)
        assertEquals("billing/meetings/standup", p.linkId)
        assertFalse(SessionInfo.parse(parseJsonObject("""{"id":"s1"}""")!!).isLink)
    }

    @Test fun originTitles() {
        assertEquals("From Research", SessionGroups.originTitle("acme/research/2026-11-20-auth"))
        assertEquals("From Follow-ups", SessionGroups.originTitle("billing/followups/vat"))
        assertEquals("From Goals", SessionGroups.originTitle("goals/run-a-marathon"))
        assertEquals(null, SessionGroups.originTitle(null))
        assertEquals(null, SessionGroups.originTitle("odd"))
    }

    @Test fun stateTitles() {
        assertEquals("Needs you", SessionGroups.stateTitle("waiting"))
        assertEquals("Needs permission", SessionGroups.stateTitle("blocked"))
        assertEquals("Not running", SessionGroups.stateTitle("stopped"))
    }
}
