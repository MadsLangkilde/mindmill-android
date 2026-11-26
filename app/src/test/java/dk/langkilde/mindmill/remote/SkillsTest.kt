package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsTest {
    private val skills = SkillInfo.list(parseJsonObject("""{"skills":[
        {"name":"idea","command":"/mindmill:idea","description":"Capture an idea","source":"plugin","plugin":"mindmill"},
        {"name":"research","command":"/mindmill:research","description":"Research a question","source":"plugin","plugin":"mindmill"},
        {"name":"release-notes","command":"/release-notes","source":"user","location":"user","editable":true},
        {"name":"deploy","command":"/deploy","source":"project","location":"project","scope":"android","editable":true},
        {"name":"review","command":"/review","source":"command"},
        {"name":"pr-idea","command":"/gh:pr-idea","source":"plugin"},
        {"command":"/mindmill:idea","source":"plugin"},
        {"description":"no name or command"}
    ]}"""))

    @Test fun parsesAndDropsDuplicatesAndNameless() {
        assertEquals(6, skills.size)
        assertEquals("release-notes", skills[2].name)
        assertTrue(skills[2].editable)
        assertEquals("android", skills[3].scope)
    }

    @Test fun sourceLabels() {
        assertEquals(listOf("mindmill", "mindmill", "Mine", "This project", "Command", "gh"), skills.map { it.sourceLabel })
    }

    @Test fun queryOnlyWhileACommandIsBeingTyped() {
        assertEquals("", SlashSkills.query("/"))
        assertEquals("mind", SlashSkills.query("/mind"))
        assertNull(SlashSkills.query("/mindmill:idea "))
        assertNull(SlashSkills.query("hello /idea"))
        assertNull(SlashSkills.query(""))
    }

    @Test fun prefixMatchesComeBeforeContainsAndThePartAfterThePluginCounts() {
        assertEquals(listOf("/mindmill:idea", "/gh:pr-idea"), SlashSkills.matches(skills, "idea").map { it.command })
        assertEquals(listOf("/review", "/mindmill:research", "/release-notes"), SlashSkills.matches(skills, "re").map { it.command })
        assertEquals(listOf("/mindmill:idea", "/mindmill:research"), SlashSkills.matches(skills, "MINDMILL:").map { it.command })
        assertEquals(listOf("/deploy"), SlashSkills.matches(skills, "dep").map { it.command })
        assertTrue(SlashSkills.matches(skills, "zzz").isEmpty())
        assertEquals(skills, SlashSkills.matches(skills, ""))
        assertEquals("/deploy ", SlashSkills.insert(skills[3]))
    }

    @Test fun groupsInTheScreensOrder() {
        val groups = SkillGroups.of(skills)
        assertEquals(listOf("mine", "project", "plugins", "commands"), groups.map { it.id })
        assertEquals(listOf("/gh:pr-idea", "/mindmill:idea", "/mindmill:research"), groups[2].skills.map { it.command })
        assertEquals(listOf("/review"), groups[3].skills.map { it.command })
        assertTrue(skills[2].isOwn)
        assertTrue(!skills[0].isOwn)
    }

    @Test fun pluginsLeaveInstalledOnesOutOfAvailable() {
        val p = Plugins.parse(parseJsonObject("""{"plugins":[
            {"id":"mindmill@mindmill","name":"mindmill","marketplace":"mindmill","version":"1.2","scope":"user","enabled":false}
        ],"available":[
            {"id":"mindmill@mindmill","name":"mindmill"},
            {"id":"postman@market","marketplace":"market","description":"APIs","installs":1200}
        ]}""")!!)
        assertEquals(1, p.installed.size)
        assertTrue(!p.installed[0].enabled)
        assertEquals(listOf("postman"), p.available.map { it.name })
        assertEquals(1200L, p.available[0].installs)
    }

    @Test fun toolsAndAddingOne() {
        val m = McpServers.parse(parseJsonObject("""{"servers":[
            {"name":"figma","target":"https://mcp.figma.com","status":"needsAuth","origin":"claude.ai","removable":false},
            {"name":"local","target":"http://localhost:3000","status":"connected","origin":"user","removable":true},
            {"target":"nameless"}
        ],"connectorsURL":"https://claude.ai/settings/connectors"}""")!!)
        assertEquals(listOf("figma", "local"), m.servers.map { it.name })
        assertEquals("Needs sign-in", m.servers[0].statusTitle)
        assertEquals("Connector", m.servers[0].originTitle)
        assertEquals("https://claude.ai/settings/connectors", m.connectorsURL)
        assertNull(McpServers.parse(parseJsonObject("""{"servers":[],"connectorsURL":"javascript:alert(1)"}""")!!).connectorsURL)
        assertNull(McpServers.problem("docs", "https://example.com/mcp"))
        assertEquals("Give it a name.", McpServers.problem(" ", "https://x"))
        assertEquals("The name can't have spaces.", McpServers.problem("my docs", "https://x"))
        assertEquals("The address starts with https://.", McpServers.problem("docs", "example.com"))
    }
}
