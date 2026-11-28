package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstancesTest {
    @Test
    fun parsesTheInstanceList() {
        val list = InstanceList.parse(parseJsonObject(
            """
            {"current": "work",
             "instances": [
               {"id": "work", "name": "Work", "path": "~/knowledgebase", "exists": true, "projects": 4},
               {"id": "client-a", "name": "Client A", "path": "~/clients/a", "exists": false, "projects": 0},
               {"id": "bare", "path": "~/bare"},
               {"name": "no id: skipped"}
             ]}
            """.trimIndent(),
        )!!)
        assertEquals("work", list.current)
        assertEquals(listOf("work", "client-a", "bare"), list.instances.map { it.id })
        assertEquals(InstanceInfo("work", "Work", "~/knowledgebase", exists = true, projects = 4), list.open)
        assertFalse(list.find("client-a")!!.exists)
        assertEquals(InstanceInfo("bare", "bare", "~/bare", exists = true, projects = null), list.find("bare"))
        assertTrue(list.hasChoice)
        assertFalse(InstanceList("work", listOf(list.open!!)).hasChoice)
        assertNull(InstanceList.parse(parseJsonObject("""{"instances": []}""")!!).current)
    }

    @Test
    fun helloNamesTheInstanceAndTheLauncher() {
        val info = HelloInfo.parse(parseJsonObject(
            """{"name": "Studio", "version": 3, "scope": "acme", "scopes": [], "sections": [],
                "instance": {"id": "work", "name": "Work"}, "launcher": {"port": 7879}}""",
        )!!)
        assertEquals(InstanceRef("work", "Work"), info.instance)
        assertEquals(7879, info.launcherPort)
    }

    @Test
    fun helloWithTheLauncherOffHasNoPort() {
        val info = HelloInfo.parse(parseJsonObject("""{"name": "Studio", "instance": {"id": "work"}, "launcher": null}""")!!)
        assertNull(info.launcherPort)
        assertEquals(InstanceRef("work", "work"), info.instance)
        assertNull(HelloInfo.parse(parseJsonObject("""{"name": "Studio", "launcher": {"port": 0}}""")!!).launcherPort)
    }

    @Test
    fun anOlderMacsHelloHasNeitherInstanceNorLauncher() {
        val info = HelloInfo.parse(parseJsonObject("""{"name": "Studio", "version": 2, "scope": "acme", "scopes": [], "sections": ["research"]}""")!!)
        assertNull(info.instance)
        assertNull(info.launcherPort)
        assertEquals(listOf("research"), info.sections)
    }

    @Test
    fun createAsksForANameAndAFolder() {
        assertEquals("Give the instance a name.", InstanceList.createProblem(" ", "~/kb"))
        assertEquals("Choose a folder on the Mac for it.", InstanceList.createProblem("Client B", null))
        assertNull(InstanceList.createProblem("Client B", "~/clients/b"))
        assertEquals(
            jsonOf("name" to "Client B", "path" to "~/clients/b", "open" to true),
            InstanceList.createParams(" Client B ", "~/clients/b", open = true),
        )
        assertFalse(InstanceList.createParams("Client B", "~/clients/b", open = false).containsKey("open"))
    }

    @Test
    fun readsWhetherTheMacSwitches() {
        assertEquals(InstanceSwitchAnswer("client-b", true), InstanceSwitchAnswer.parse(parseJsonObject("""{"id": "client-b", "switching": true}""")!!))
        assertEquals(InstanceSwitchAnswer(null, false), InstanceSwitchAnswer.parse(parseJsonObject("""{"switching": false}""")!!))
    }

    @Test
    fun aNewFolderComesBackToPick() {
        assertEquals(jsonOf("parent" to "~/src", "name" to "garden"), FolderListing.createParams("~/src", " garden "))
        assertEquals(
            FolderListing.Folder("garden", "~/src/garden", false),
            FolderListing.created(parseJsonObject("""{"folder": {"name": "garden", "path": "~/src/garden", "repository": false}}""")!!),
        )
        assertNull(FolderListing.created(parseJsonObject("""{"folder": {"name": "no path"}}""")!!))
        assertNull(FolderListing.created(parseJsonObject("""{}""")!!))
    }
}

class ProjectInstanceTest {
    private val billing = ScopeRef("billing", "Billing", "billing", "Billing")

    @Test
    fun aNewProjectGoesIntoTheOpenInstanceUnlessAnotherIsPicked() {
        val d = ProjectDraft.new().withTitle("Garden").addLocation("~/src/garden").addScope("Garden")
        assertFalse(d.forSave().containsKey("instance"))
        assertEquals(d, d.withInstance("work", open = "work"))

        val elsewhere = d.withInstance("client-a", open = "work")
        assertTrue(elsewhere.elsewhere)
        assertEquals("client-a", elsewhere.forSave().text("instance"))
        assertFalse(elsewhere.withInstance("work", open = "work").forSave().containsKey("instance"))
    }

    @Test
    fun anotherInstancesProjectCantReadTheOpenOnesScopes() {
        val d = ProjectDraft.new().withTitle("Garden").addLocation("~/src/garden").addScope("Garden")
        val key = d.scopes.single().key
        val reading = d.toggleRelated(key, "billing")
        assertEquals(listOf("billing"), reading.readable(key, listOf(billing)).map { it.id })

        val elsewhere = reading.withInstance("client-a", open = "work")
        assertEquals(emptyList<String>(), elsewhere.scope(key)?.related)
        assertEquals(emptyList<ScopeRef>(), elsewhere.readable(key, listOf(billing)))
    }

    @Test
    fun aSavedProjectStaysInItsInstance() {
        val saved = ProjectDraft("acme", "Acme Go")
        assertEquals(saved, saved.withInstance("client-a", open = "work"))
        assertFalse(saved.copy(instance = "client-a").forSave().containsKey("instance"))
    }
}
