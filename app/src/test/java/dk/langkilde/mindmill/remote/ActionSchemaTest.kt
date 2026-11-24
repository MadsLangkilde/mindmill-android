package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSchemaTest {
    private val listResult = parseJsonObject(
        """
        {"areas": ["Factories", "Meetings"],
         "actions": [
          {"name": "factory.settings", "area": "Factories", "about": "Change how a factory runs.", "risk": "write",
           "input": {"type": "object", "required": ["factory"], "properties": {
             "factory": {"type": "string", "description": "The factory's name or id"},
             "max_parallel": {"type": "integer", "description": "How many work items at once"},
             "questions": {"type": "string", "enum": ["whenUnsure", "checkpointsOnly", "never"]},
             "hours": {"type": "number"},
             "scope": {"type": "string"},
             "tags": {"type": "array", "items": {"type": "string"}},
             "dry": {"type": "boolean"}}}},
          {"name": "meeting.mac.stop", "area": "Meetings", "about": "Stop the meeting on the Mac.", "risk": "destructive",
           "input": {"type": "object", "properties": {}}},
          {"name": "overview", "about": "What needs the user now.", "risk": "read", "input": {"type": "object"}}
         ]}
        """.trimIndent(),
    )!!

    @Test fun parsesActionsWithAreasAndParams() {
        val actions = ActionInfo.list(listResult)
        assertEquals(3, actions.size)
        val f = actions.first { it.name == "factory.settings" }
        assertEquals("Factories", f.areaTitle)
        assertEquals("factory", f.params.first().name)
        assertTrue(f.params.first().required)
        assertEquals("scope", f.params.last().name)
        val q = f.params.first { it.name == "questions" }
        assertEquals(listOf("whenUnsure", "checkpointsOnly", "never"), q.choices)
        assertEquals(ActionParamSpec.Type.STRINGS, f.params.first { it.name == "tags" }.type)
        assertEquals("Max parallel", f.params.first { it.name == "max_parallel" }.label)
        assertTrue(actions.first { it.name == "meeting.mac.stop" }.isRisky)
        assertEquals("general", actions.first { it.name == "overview" }.area)
    }

    @Test fun groupsInTheMacsOrder() {
        val groups = ActionInfo.grouped(ActionInfo.list(listResult), ActionInfo.areaOrder(listResult))
        assertEquals(listOf("Factories", "Meetings", "general"), groups.map { it.first })
    }

    @Test fun searchMatchesNameAboutAndArea() {
        val actions = ActionInfo.list(listResult)
        assertEquals(listOf("meeting.mac.stop"), actions.filter { it.matches("meeting") }.map { it.name })
        assertEquals(3, actions.filter { it.matches(" ") }.size)
        assertEquals(listOf("factory.settings"), actions.filter { it.matches("FACTORIES") }.map { it.name })
    }

    @Test fun formValidatesAndBuildsTypedParams() {
        val spec = ActionInfo.list(listResult).first { it.name == "factory.settings" }
        val values = ActionForm.initial(spec.params, mapOf("scope" to "billing")).toMutableMap()
        assertEquals("billing", values["scope"])
        assertEquals(mapOf("factory" to "Needed"), ActionForm.problems(spec.params, values))
        values["factory"] = "Night shift"
        values["max_parallel"] = "two"
        values["hours"] = "1,5"
        values["questions"] = "sometimes"
        val problems = ActionForm.problems(spec.params, values)
        assertEquals(setOf("max_parallel", "questions"), problems.keys)
        values["max_parallel"] = "2"
        values["questions"] = "never"
        values["tags"] = "ble, login\nui"
        values["dry"] = "true"
        assertTrue(ActionForm.problems(spec.params, values).isEmpty())
        val p = ActionForm.toParams(spec.params, values)
        assertEquals(JsonPrimitive("Night shift"), p["factory"])
        assertEquals(JsonPrimitive(2L), p["max_parallel"])
        assertEquals(JsonPrimitive(1.5), p["hours"])
        assertEquals(JsonPrimitive(true), p["dry"])
        assertEquals(JsonArray(listOf("ble", "login", "ui").map(::JsonPrimitive)), p["tags"])
        assertEquals(JsonPrimitive("billing"), p["scope"])
    }

    @Test fun emptyOptionalValuesAreLeftOut() {
        val spec = ActionInfo.list(listResult).first { it.name == "factory.settings" }
        val p = ActionForm.toParams(spec.params, mapOf("factory" to "A", "hours" to " "))
        assertEquals(setOf("factory"), p.keys)
    }

    @Test fun dataBecomesFieldsRowsAndText() {
        val data = parseJsonObject(
            """
            {"day": "2026-11-23", "entries": [
               {"id": "acme/meetings/standup", "section": "meetings", "title": "Stand-up", "summary": "Daily", "tags": ["team"]}],
             "items": [{"id": 4211, "type": "Bug", "title": "Login fails", "state": "Active"}],
             "body": "Line one\nLine two",
             "names": ["a", "b"]}
            """.trimIndent(),
        )
        val blocks = DataView.blocks(data)
        val fields = blocks.first() as DataBlock.Fields
        assertEquals(listOf("Day" to "2026-11-23"), fields.fields)
        val entries = blocks.filterIsInstance<DataBlock.Rows>().first { it.label == "Entries" }
        assertEquals("Stand-up", entries.rows.single().title)
        assertEquals("Daily", entries.rows.single().subtitle)
        assertEquals(DataRow.Link.Entry("acme/meetings/standup"), entries.rows.single().link)
        val items = blocks.filterIsInstance<DataBlock.Rows>().first { it.label == "Items" }
        assertEquals("#4211 Login fails", items.rows.single().title)
        assertEquals(DataRow.Link.WorkItem(4211), items.rows.single().link)
        assertTrue(blocks.any { it is DataBlock.Text && it.label == "Body" })
        assertTrue(blocks.any { it is DataBlock.Text && it.text == "• a\n• b" })
    }

    @Test fun sessionRowsLinkToTheChat() {
        val row = DataView.row(jsonOf("id" to "abc", "title" to "Plan login", "space" to "plan", "busy" to false))
        assertEquals(DataRow.Link.Session("abc", "Plan login"), row.link)
        assertNull(DataView.row(jsonOf("name" to "Night shift", "status" to "running")).link)
    }

    @Test fun humanizesKeys() {
        assertEquals("Max parallel", humanize("maxParallel"))
        assertEquals("Waiting on", humanize("waiting_on"))
        assertEquals("Open PRs", humanize("openPRs"))
        assertEquals("PR list", humanize("pr list"))
    }
}
