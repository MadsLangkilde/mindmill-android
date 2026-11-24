package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsConfigTest {
    private val answer = parseJsonObject(
        """
        {"current": "android", "home": "/Users/me",
         "projects": [
           {"id": "acme", "title": "Acme Go",
            "locations": [
              {"name": "Android app", "path": "~/src/acme-android", "exists": true, "repository": true},
              {"name": "iOS app", "path": "~/src/acme-ios", "exists": false, "repository": true}
            ],
            "scopes": [
              {"id": "acme", "title": "Acme Go", "wide": true, "paths": ["~/src/acme-android", "~/src/acme-ios"], "remotes": [], "includes": [], "related": [], "entries": 40},
              {"id": "android", "title": "Android", "wide": false, "paths": ["~/src/acme-android"], "remotes": ["git@x:a.git"], "includes": ["ios"], "related": ["billing"], "entries": 12},
              {"id": "ios", "title": "iOS", "paths": ["~/src/acme-ios"], "entries": 3}
            ]},
           {"id": "billing", "title": "Billing",
            "locations": [{"name": "Service", "path": "~/src/billing"}],
            "scopes": [{"id": "billing", "title": "Billing", "wide": true, "paths": ["~/src/billing"]}]},
           {"title": "no id: skipped"}
         ]}
        """.trimIndent(),
    )!!

    private val cfg = ProjectsConfig.parse(answer)
    private val acme = cfg.project("acme")!!

    @Test
    fun parsesProjectsLocationsAndScopes() {
        assertEquals("android", cfg.current)
        assertEquals("/Users/me", cfg.home)
        assertEquals(listOf("acme", "billing"), cfg.projects.map { it.id })
        assertEquals(ProjectLocation("iOS app", "~/src/acme-ios", exists = false, repository = true), acme.locations[1])
        val android = acme.scopes[1]
        assertEquals("id:android", android.key)
        assertEquals(listOf("ios"), android.includes)
        assertEquals(listOf("billing"), android.related)
        assertEquals(listOf("git@x:a.git"), android.remotes)
        assertEquals(12, android.entries)
        assertEquals("acme", acme.wideScope?.id)
        assertEquals(listOf("acme", "android", "ios", "billing"), cfg.allScopes.map { it.id })
        assertEquals("Android", cfg.scopeTitle("android"))
    }

    @Test
    fun parsesAFolderListing() {
        val l = FolderListing.parse(parseJsonObject(
            """{"path": "~/src", "parent": "~", "folders": [{"name": "kb", "path": "~/src/kb", "repository": true}, {"path": "~/src/notes"}, {"name": "x"}]}""",
        )!!)
        assertEquals("~/src", l.path)
        assertEquals("~", l.parent)
        assertEquals("src", l.name)
        assertEquals(listOf(FolderListing.Folder("kb", "~/src/kb", true), FolderListing.Folder("notes", "~/src/notes", false)), l.folders)
        assertNull(FolderListing.parse(parseJsonObject("""{"path": "~", "parent": null, "folders": []}""")!!).parent)
    }

    @Test
    fun anUnchangedProjectSavesAsItCame() {
        val save = ProjectDraft.from(acme).forSave()
        assertEquals("acme", save.text("id"))
        val project = save.obj("project")!!
        assertEquals("Acme Go", project.string("title"))
        assertNull(project["newId"])
        assertEquals(listOf("Android app", "iOS app"), project.objects("locations").map { it.string("name") })
        assertEquals(setOf("name", "path"), project.objects("locations")[0].keys)
        val scopes = project.objects("scopes")
        assertEquals(listOf("acme", "android", "ios"), scopes.map { it.string("id") })
        assertEquals(true, scopes[0].bool("wide"))
        assertEquals(listOf("ios"), scopes[1].strings("includes"))
        assertEquals(listOf("billing"), scopes[1].strings("related"))
        assertEquals(listOf("git@x:a.git"), scopes[1].strings("remotes"))
        assertFalse(scopes[1].containsKey("entries"))
    }

    @Test
    fun aNewProjectNeedsATitleAndALocation() {
        val d = ProjectDraft.new()
        assertTrue(d.isNew)
        assertEquals(
            listOf("Give the project a title.", "Add at least one location: a folder on the Mac the project's code is in."),
            d.problems(),
        )
        val ready = d.withTitle("Garden").addLocation("~/src/garden", repository = true)
        assertEquals(emptyList<String>(), ready.problems())
        assertEquals(ProjectLocation("garden", "~/src/garden", repository = true), ready.locations.single())

        val save = ready.withNewId(" garden-app ").forSave()
        assertEquals("", save.text("id"))
        assertEquals("garden-app", save.obj("project").string("newId"))
        assertEquals(emptyList<JsonObject>(), save.obj("project").objects("scopes"))
        assertEquals(listOf("The id can't have spaces or slashes."), ready.withNewId("my garden").problems())
    }

    @Test
    fun theFirstScopeIsProjectWideWithoutPathsOfItsOwn() {
        val d = ProjectDraft.new().withTitle("Garden").addLocation("~/a").addLocation("~/b").addScope("Garden").addScope("Web")
        val (wide, web) = d.scopes
        assertTrue(wide.wide)
        assertEquals(emptyList<String>(), wide.paths)
        assertFalse(web.wide)
        assertEquals(emptyList<String>(), web.paths)
        assertNull(web.id)
        assertFalse(wide.key == web.key)
        val later = d.addLocation("~/c")
        assertEquals(emptyList<String>(), later.wideScope?.paths)
        assertEquals(emptyList<String>(), later.scope(web.key)?.paths)
        val saved = later.forSave().obj("project").objects("scopes")[1]
        assertNull(saved["id"])
        assertEquals("Web", saved.string("title"))
    }

    @Test
    fun addingTheSameFolderTwiceAddsOneLocation() {
        val d = ProjectDraft.from(acme)
        assertEquals(d, d.addLocation("~/src/acme-android"))
    }

    @Test
    fun removingALocationStopsScopesWorkingOnIt() {
        val d = ProjectDraft.from(acme).removeLocation("~/src/acme-ios")
        assertEquals(listOf("~/src/acme-android"), d.locations.map { it.path })
        assertEquals(listOf("~/src/acme-android"), d.scope("id:acme")?.paths)
        assertEquals(emptyList<String>(), d.scope("id:ios")?.paths)
    }

    @Test
    fun renamingAndTogglingChangeOnlyThatScope() {
        val d = ProjectDraft.from(acme)
            .renameLocation("~/src/acme-ios", "iPhone app")
            .renameScope("id:ios", "iPhone")
            .togglePath("id:ios", "~/src/acme-android")
            .toggleInclude("id:ios", "android")
            .toggleRelated("id:ios", "billing")
            .toggleRelated("id:android", "billing")
        assertEquals("iPhone app", d.locations[1].name)
        val ios = d.scope("id:ios")!!
        assertEquals("iPhone", ios.title)
        assertEquals("ios", ios.id)
        assertEquals(listOf("~/src/acme-ios", "~/src/acme-android"), ios.paths)
        assertEquals(listOf("android"), ios.includes)
        assertEquals(listOf("billing"), ios.related)
        assertEquals(emptyList<String>(), d.scope("id:android")?.related)
    }

    @Test
    fun makingAnotherScopeProjectWideLeavesExactlyOne() {
        val d = ProjectDraft.from(acme).setWide("id:android")
        assertEquals(listOf("android"), d.scopes.filter { it.wide }.map { it.id })
        assertEquals(emptyList<String>(), d.scope("id:android")?.includes)
    }

    @Test
    fun removingAScopeIsDeletedOnSaveAndOthersStopUsingIt() {
        val d = ProjectDraft.from(acme).removeScope("id:ios")
        assertEquals(listOf("acme", "android"), d.scopes.map { it.id })
        assertEquals(emptyList<String>(), d.scope("id:android")?.includes)
        assertEquals(listOf("ios"), d.removedScopes(acme).map { it.id })
        assertEquals(3, d.removedScopes(acme).single().entries)
        assertEquals(emptyList<ProjectScope>(), ProjectDraft.from(acme).removedScopes(acme))
        assertEquals(emptyList<ProjectScope>(), d.removedScopes(null))
    }

    @Test
    fun theProjectWideScopeStaysWhileOthersNeedSomewhereToMoveTo() {
        val d = ProjectDraft.from(acme)
        assertFalse(d.canRemove("id:acme"))
        assertEquals(d, d.removeScope("id:acme"))
        assertTrue(d.canRemove("id:android"))
    }

    @Test
    fun aScopeCanShowTheProjectsOtherSavedScopesAndReadAnyOther() {
        val d = ProjectDraft.from(acme).addScope("Web")
        assertEquals(listOf("ios"), d.includable("id:android").map { it.id })
        val newKey = d.scopes.last().key
        assertEquals(listOf("android", "ios"), d.includable(newKey).map { it.id })
        assertEquals(listOf("acme", "ios", "billing"), d.readable("id:android", cfg.allScopes).map { it.id })
        assertEquals("Billing", d.readable("id:android", cfg.allScopes).last().projectTitle)
    }

    @Test
    fun blankAndRepeatedNamesAreProblems() {
        val d = ProjectDraft.from(acme)
            .renameLocation("~/src/acme-ios", "android APP")
            .renameLocation("~/src/acme-android", "Android app")
            .renameScope("id:ios", " ")
            .addScope("android")
        assertEquals(
            listOf("Two locations are called “Android app”.", "Every scope needs a title.", "Two scopes are called “Android”."),
            d.problems(),
        )
        assertEquals(listOf("Every location needs a name."), ProjectDraft.from(acme).renameLocation("~/src/acme-ios", "").problems())
    }

    @Test
    fun scopesSaveOnlyPathsThatAreLocations() {
        val d = ProjectDraft.from(acme).togglePath("id:ios", "~/elsewhere")
        val ios = d.forSave().obj("project").objects("scopes")[2]
        assertEquals(listOf("~/src/acme-ios"), ios.strings("paths"))
    }
}
