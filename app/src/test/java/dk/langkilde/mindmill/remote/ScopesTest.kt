package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopesTest {
    private val hello = parseJsonObject(
        """
        {"name": "Studio", "version": 2, "scope": "acme",
         "scopes": [
           {"id": "android", "title": "Android", "project": "acme", "projectTitle": "Acme Go", "wide": false},
           {"id": "acme", "title": "Acme Go", "project": "acme", "projectTitle": "Acme Go", "wide": true},
           {"id": "ios", "title": "iOS", "project": "acme", "projectTitle": "Acme Go"},
           {"id": "home", "title": "Overview", "project": "home", "projectTitle": "Home", "wide": true},
           {"id": "", "title": "broken"}
         ]}
        """.trimIndent(),
    )!!

    @Test
    fun helloReadsEachScopesProject() {
        val info = HelloInfo.parse(hello)
        assertEquals(listOf("android", "acme", "ios", "home"), info.scopes.map { it.id })
        assertEquals(Scope("acme", "Acme Go", "acme", "Acme Go", wide = true), info.scopes[1])
        assertEquals(false, info.scopes[2].wide)
    }

    @Test
    fun scopesGroupByProjectWithTheProjectWideOneFirst() {
        val groups = Scopes.grouped(HelloInfo.parse(hello).scopes)
        assertEquals(listOf("Acme Go", "Home"), groups.map { it.title })
        assertEquals(listOf("acme", "android", "ios"), groups[0].scopes.map { it.id })
        assertEquals(listOf("home"), groups[1].scopes.map { it.id })
    }

    @Test
    fun anOlderMacsScopesMakeOneGroupWithoutATitle() {
        val groups = Scopes.grouped(listOf(Scope("a", "A"), Scope("b", "B")))
        assertEquals(1, groups.size)
        assertNull(groups[0].title)
        assertEquals(listOf("a", "b"), groups[0].scopes.map { it.id })
    }

    @Test
    fun aGroupWithoutAProjectTitleUsesItsProjectWideScope() {
        val groups = Scopes.grouped(listOf(Scope("x", "App", "p"), Scope("p", "Everything", "p", wide = true)))
        assertEquals("Everything", groups.single().title)
    }

    @Test
    fun thePhonesOwnScopeWinsWhileTheMacStillHasIt() {
        val scopes = HelloInfo.parse(hello).scopes
        assertEquals("ios", Scopes.resolve("ios", "acme", scopes))
        assertEquals("acme", Scopes.resolve("gone", "acme", scopes))
        assertEquals("acme", Scopes.resolve(null, "acme", scopes))
        assertEquals("android", Scopes.resolve("gone", "also-gone", scopes))
        assertEquals("ios", Scopes.resolve("ios", "acme", null))
        assertEquals("acme", Scopes.resolve(null, "acme", null))
        assertNull(Scopes.resolve(null, null, null))
    }

    @Test
    fun aScopeReadsWithItsProjectOutsideTheMenu() {
        val scopes = HelloInfo.parse(hello).scopes
        assertEquals("Acme Go · Android", Scopes.label(scopes[0]))
        assertEquals("Acme Go", Scopes.label(scopes[1]))
        assertEquals("Home · Overview", Scopes.label(scopes[3]))
        assertEquals("A", Scopes.label(Scope("a", "A")))
    }
}
