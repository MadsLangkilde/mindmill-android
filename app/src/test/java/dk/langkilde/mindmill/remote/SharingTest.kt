package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    @Test fun suggestionsFromEntryOrSession() {
        val s = Suggestion.list(parseJsonObject("""{"suggestions":[
            {"id":"acme/inbox/a","section":"facts","title":"Builds use JDK 21","summary":"Gradle needs 21","reason":"Said twice"},
            {"id":"acme/inbox/b","section":"followups"},
            {"id":"acme/inbox/a","title":"duplicate"},
            {"title":"no id"}
        ]}"""))
        assertEquals(listOf("acme/inbox/a", "acme/inbox/b"), s.map { it.id })
        assertEquals("Said twice", s[0].reason)
        assertEquals("acme/inbox/b", s[1].title)
        assertTrue(Suggestion.list(parseJsonObject("{}")).isEmpty())
    }

    @Test fun sharingStateAndWhatMakingItPrivateMeans() {
        val shared = Sharing.parse(parseJsonObject("""{"state":"pushed","title":"Shared in acme-go","shared":true,"canChange":true,"repo":"acme-go"}"""))!!
        assertTrue(shared.shared)
        assertTrue(shared.canChange)
        assertTrue("the acme-go repository" in shared.privateQuestion())
        val inbox = Sharing.parse(parseJsonObject("""{"state":"private","canChange":false}"""))!!
        assertFalse(inbox.shared)
        assertEquals("Private", inbox.title)
        assertFalse(inbox.canChange)
        assertTrue("the project's repository" in inbox.privateQuestion())
        assertTrue(Sharing.parse(parseJsonObject("""{"state":"unpushed"}"""))!!.shared)
        assertNull(Sharing.parse(null))
    }

    @Test fun defaultsPerKind() {
        val d = SharingDefaults.parse(parseJsonObject("""{"scope":"android","repo":"acme-android","defaults":[
            {"section":"facts","title":"Facts","visibility":"shared"},
            {"section":"notes","title":"Notes","visibility":"private"},
            {"title":"no section"}
        ]}""")!!)
        assertTrue(d.canShare)
        assertEquals(listOf(true, false), d.defaults.map { it.shared })
        assertEquals(listOf(true, true), d.with("notes", true).defaults.map { it.shared })
        assertFalse(SharingDefaults.parse(parseJsonObject("""{"defaults":[]}""")!!).canShare)
        assertEquals("shared", SharingDefaults.visibility(true))
        assertEquals("private", SharingDefaults.visibility(false))
    }

    @Test fun entryIdsAndNotes() {
        assertEquals("notes", EntryIds.section("acme/notes/2026-11-26-pricing"))
        assertEquals("research", EntryIds.section("acme/android/research/2026-11-20-auth"))
        assertNull(EntryIds.section("goals/run"))
        assertEquals("Ask Anna about the pricing page", NoteText.titleFrom("\n# Ask Anna about the pricing page\nand the copy"))
        val long = NoteText.titleFrom("We should look at how the onboarding flow handles people who already have an account somewhere")
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= NoteText.TITLE_LENGTH + 1)
        assertFalse(long.contains("somewh"))
        assertEquals("Note", NoteText.titleFrom("   "))
        assertEquals("Filed", NoteText.statusTitle("filed"))
        assertEquals("Open", NoteText.statusTitle(null))
    }
}
