package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FactoryConfigTest {
    private val answer = parseJsonObject(
        """
        {"factory": {"id": "f1", "name": "Night shift", "status": "paused", "running": true},
         "rules": {"scopes": ["billing"], "assignedToMe": true, "pbis": true, "bugs": false, "sprint": "any",
                   "questions": "checkpointsOnly", "checkpoints": ["planReady", "beforePR"],
                   "customCheckpoints": [{"id": "C1", "title": "Before touching payments", "instruction": "", "on": true}, "Legacy ask"],
                   "strategyRules": [{"id": "R1", "match": "tag", "value": "ble", "strategy": "tdd"}],
                   "askTexts": {"planReady": "Show me the plan"},
                   "maxParallel": 2, "duration": "until", "until": "2026-11-24T06:00:00Z",
                   "futureRule": {"x": 1}},
         "problems": ["Pick at least one scope", {"message": "Nothing to ask at"}],
         "options": {"strategies": [{"slug": "tdd", "title": "Test first"}],
                     "checkpoints": [{"id": "planReady", "title": "Plan ready", "detail": "Steps first"}],
                     "maxParallel": [1, 2, 3]}}
        """.trimIndent(),
    )!!

    @Test fun parsesTheEditorAnswer() {
        val c = FactoryConfig.parse(answer)
        assertEquals("f1", c.draft.id)
        assertEquals("Night shift", c.draft.name)
        assertEquals("paused", c.status)
        assertTrue(c.running)
        assertEquals(listOf("Pick at least one scope", "Nothing to ask at"), c.problems)
        assertEquals(listOf("tdd"), c.options.strategies.map { it.id })
        assertEquals(listOf(1, 2, 3), c.options.maxParallel)
        assertEquals(3, c.options.sprint.size)
        assertEquals(listOf("billing"), c.draft.scopes)
        assertFalse(c.draft.bugs)
        assertEquals("any", c.draft.sprint)
        assertEquals(2, c.draft.maxParallel)
        assertEquals(Instant.parse("2026-11-24T06:00:00Z"), c.draft.until)
        assertEquals(listOf("Before touching payments", "Legacy ask"), c.draft.customAsks.map { it.title })
        assertEquals("ble", c.draft.strategyRules.single().value)
        assertEquals("Show me the plan", c.draft.askTexts["planReady"])
    }

    @Test fun newFactoryHasNoIdAndDefaults() {
        val c = FactoryConfig.parse(jsonOf("factory" to null, "name" to "Software Factory 2", "rules" to jsonOf("scopes" to listOf("billing"))))
        assertNull(c.draft.id)
        assertEquals("Software Factory 2", c.draft.name)
        assertTrue(c.draft.assignedToMe)
        assertTrue(c.draft.pbis && c.draft.bugs)
        assertEquals(3, c.draft.maxParallel)
        assertEquals(setOf("missingKnowledge", "beforePR"), c.draft.checkpoints)
        assertEquals(emptyList<String>(), c.draft.localProblems())
        assertEquals(listOf("Give the factory a name."), c.draft.withName(" ").localProblems())
        assertEquals(listOf("Choose at least one project."), c.draft.withScopes(emptyList()).localProblems())
        val silent = c.draft.setChoice("questions", "checkpointsOnly").withCheckpoint("missingKnowledge", false).withCheckpoint("beforePR", false)
        assertEquals(listOf("Tick at least one moment to ask, or turn asking off."), silent.localProblems())
        assertEquals(emptyList<String>(), silent.setChoice("questions", "never").localProblems())
    }

    @Test fun editsKeepUnknownKeys() {
        val d = FactoryConfig.parse(answer).draft
            .setBool("bugs", true)
            .setChoice("sprint", "current")
            .withMaxParallel(4)
            .withCheckpoint("designChoice", true)
            .withCheckpoint("beforePR", false)
            .withScopes(listOf("billing", "web", "billing"))
            .withName("Day shift")
        val r = d.rules
        assertEquals(parseJsonObject("""{"x": 1}"""), r["futureRule"])
        assertEquals(JsonPrimitive(true), r["bugs"])
        assertEquals(JsonPrimitive(4), r["maxParallel"])
        assertEquals(JsonArray(listOf("designChoice", "planReady").map(::JsonPrimitive)), r["checkpoints"])
        assertEquals(listOf("billing", "web"), d.scopes)
        val save = d.forSave()
        assertEquals(JsonPrimitive("f1"), save["id"])
        assertEquals(JsonPrimitive("Day shift"), save["name"])
        assertEquals(r, save["rules"])
    }

    @Test fun rulesAndAsksEditInPlace() {
        val d = FactoryConfig.parse(answer).draft
        val rules = d.strategyRules + FactoryDraft.StrategyRule.new("title").with(value = "login", strategy = "")
        val d2 = d.withStrategyRules(rules.map { if (it.id == "R1") it.with(strategy = "review") else it })
        assertEquals(listOf("review", ""), d2.strategyRules.map { it.strategy })
        assertEquals("R1", d2.strategyRules.first().id)
        assertTrue(d2.strategyRules.last().id.isNotBlank())

        val asks = d.customAsks.map { if (it.title == "Legacy ask") it.with(on = false, instruction = "Say why") else it }
        val d3 = d.withCustomAsks(asks)
        assertEquals(listOf(true, false), d3.customAsks.map { it.on })
        assertEquals("Say why", d3.customAsks.last().instruction)

        val d4 = d.withAskText("planReady", "  ").withAskText("unsure", "Ask me about money")
        assertEquals(mapOf("unsure" to "Ask me about money"), d4.askTexts)
    }

    @Test fun duplicateDropsTheId() {
        val copy = FactoryConfig.parse(answer).draft.duplicate("Night shift copy")
        assertNull(copy.id)
        assertFalse(copy.forSave().containsKey("id"))
        assertEquals(JsonPrimitive("Night shift copy"), copy.forSave()["name"])
        assertEquals(FactoryConfig.parse(answer).draft.rules, copy.rules)
    }

    @Test fun checkpointsFollowDelivery() {
        val fromMac = FactoryOptions.parse(
            parseJsonObject(
                """{"checkpoints": [{"id": "planReady", "title": "Plan ready", "requires": []},
                   {"id": "beforePR", "title": "Before the PR", "requires": ["openPRs"]},
                   {"id": "beforeComplete", "title": "Before merging", "requires": ["openPRs", "completePRs"]}]}""",
            ),
        ).checkpoints
        val d = FactoryConfig.parse(answer).draft
        assertEquals(listOf("planReady", "beforePR"), d.askableCheckpoints(fromMac).map { it.id })
        assertEquals(listOf("planReady"), d.setBool("openPRs", false).askableCheckpoints(fromMac).map { it.id })
        assertEquals(3, d.setBool("completePRs", true).askableCheckpoints(fromMac).size)
        val known = d.askableCheckpoints(emptyList()).map { it.id }
        assertTrue("beforePR" in known && "beforeComplete" !in known && "missingKnowledge" in known)
    }

    @Test fun localProblems() {
        val d = FactoryConfig.parse(answer).draft
            .setBool("pbis", false)
            .withStrategyRules(listOf(FactoryDraft.StrategyRule.new("tag")))
            .withCustomAsks(listOf(FactoryDraft.CustomAsk.new("")))
        assertEquals(listOf("Pick PBIs, bugs or both.", "Rule 1 needs a value.", "Your ask 1 needs a title."), d.localProblems())
    }

    @Test fun datesReadAndWriteBothForms() {
        val t = Instant.parse("2026-11-24T06:00:00Z")
        assertEquals(JsonPrimitive("2026-11-24T06:00:00Z"), FactoryDates.write(t, null))
        val swift = FactoryDates.write(t, JsonPrimitive(800_000_000.0))
        assertEquals(t, FactoryDates.read(swift))
        assertEquals(t, FactoryDates.read(JsonPrimitive(t.epochSecond.toDouble())))
        assertNull(FactoryDates.read(JsonPrimitive("soon")))
    }
}
