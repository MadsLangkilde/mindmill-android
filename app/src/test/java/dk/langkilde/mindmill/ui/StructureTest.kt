package dk.langkilde.mindmill.ui

import dk.langkilde.mindmill.remote.parseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureTest {
    private val structureJson = """
        {"modes": [
          {"id": "plan", "title": "Plan", "sections": [
            {"id": "today", "title": "Today", "icon": "sun.max"},
            {"id": "inbox", "title": "Inbox", "icon": "tray.and.arrow.down", "count": 3, "needsYou": true},
            {"id": "sessions", "title": "Sessions", "icon": "bubble.left.and.text.bubble.right", "count": 7, "needsYou": 2},
            {"id": "ideas", "title": "Ideas", "icon": "lightbulb"},
            {"id": "backlog", "title": "Backlog", "count": 4},
            {"id": "meetings", "title": "Meetings", "needsYou": false},
            {"id": "pullRequests", "title": "Pull Requests"},
            {"id": "somethingNew", "title": "Something New", "icon": "star"},
            {"title": "no id, skipped"}
          ]},
          {"id": "build", "title": "Build", "sections": [
            {"id": "overview", "title": "Overview"},
            {"id": "factories", "title": "Software Factories", "needsYou": 1},
            {"id": "test", "title": "Test"},
            {"id": "sessions", "title": "Sessions"}
          ]},
          {"id": "other", "sections": []}
        ]}
    """.trimIndent()

    @Test fun parsesModesSectionsCountsAndNeedsYou() {
        val s = AppStructure.parse(parseJsonObject(structureJson)!!)!!
        assertEquals(listOf(Mode.PLAN, Mode.BUILD), s.modes.map { it.mode })
        val plan = s.sections(Mode.PLAN)
        assertEquals(listOf("today", "inbox", "sessions", "ideas", "backlog", "meetings", "pullRequests", "somethingNew"), plan.map { it.id })
        assertEquals(NavSection("inbox", "Inbox", "tray.and.arrow.down", 3, 3), plan[1])
        assertEquals(2, plan[2].needsYou)
        assertEquals(0, plan[5].needsYou)
        assertEquals(5, s.needsYou(Mode.PLAN))
        assertEquals(1, s.needsYou(Mode.BUILD))
    }

    @Test fun noModesIsNull() {
        assertNull(AppStructure.parse(parseJsonObject("""{"modes": []}""")!!))
        assertNull(AppStructure.parse(parseJsonObject("""{}""")!!))
    }

    @Test fun phoneAddsHomeToPlanAndKeepsBuildOverview() {
        val s = AppStructure.parse(parseJsonObject(structureJson)!!)!!.forPhone()
        assertEquals("home", s.sections(Mode.PLAN).first().id)
        assertEquals(listOf("overview", "factories", "test", "sessions"), s.sections(Mode.BUILD).map { it.id })
    }

    @Test fun buildScopeRowsBecomeOneSessionsSection() {
        val json = """{"modes":[{"id":"build","sections":[
            {"id":"overview","title":"Overview","icon":"binoculars.fill","needsYou":1},
            {"id":"factories","title":"Software Factories"},
            {"id":"testing","title":"Testing"},
            {"id":"scope:android","title":"Android","group":"Sessions","count":4,"needsYou":1},
            {"id":"scope:web","title":"Web","group":"Sessions","count":2},
            {"id":"archived","title":"Archived","count":5}]}]}"""
        val build = AppStructure.parse(parseJsonObject(json)!!)!!.forPhone().sections(Mode.BUILD)
        assertEquals(listOf("overview", "factories", "testing", "sessions"), build.map { it.id })
        val sessions = build.last()
        assertEquals(6, sessions.count)
        assertEquals(1, sessions.needsYou)
        assertEquals("Sessions", AppStructure.parse(parseJsonObject(json)!!)!!.sections(Mode.BUILD)[3].group)
        assertEquals(Screen.Test, build[2].screen(Mode.BUILD))
    }

    @Test fun buildWithoutSessionsStillGetsSessions() {
        val build = AppStructure.mergeBuildSessions(listOf(NavSection("overview", "Overview"), NavSection("testing", "Testing")))
        assertEquals(listOf("overview", "testing", "sessions"), build.map { it.id })
        assertNull(build.last().count)
    }

    @Test fun missingModeFallsBackToBuiltIn() {
        val onlyPlan = AppStructure.parse(parseJsonObject("""{"modes":[{"id":"plan","sections":[{"id":"sessions"}]}]}""")!!)!!
        val s = onlyPlan.forPhone()
        assertEquals(listOf("home", "sessions"), s.sections(Mode.PLAN).map { it.id })
        assertEquals(AppStructure.BUILD_DEFAULT, s.sections(Mode.BUILD).map { it.id })
    }

    @Test fun defaultStructureMirrorsTheMacAndDropsUnknownKbSections() {
        val kb = listOf("decisions", "meetings", "facts", "followups", "backlog", "ideas")
        val s = AppStructure.default(kb).forPhone(kb)
        val plan = s.sections(Mode.PLAN).map { it.id }
        assertEquals("home", plan.first())
        assertTrue("sessions" in plan && "today" in plan && "pullRequests" in plan)
        assertTrue("research" !in plan && "references" !in plan)
        assertEquals("Software Factories", s.sections(Mode.BUILD).first { it.id == "factories" }.title)
        val all = AppStructure.default().sections(Mode.PLAN).map { it.id }
        assertEquals(AppStructure.PLAN_DEFAULT, all)
    }

    @Test fun sectionsMapToScreens() {
        fun go(id: String, mode: Mode = Mode.PLAN, title: String = id) = NavSection(id, title).screen(mode, listOf("decisions"))
        assertEquals(Screen.Home, go("home"))
        assertEquals(Screen.Sessions("plan"), go("sessions"))
        assertEquals(Screen.Sessions("build"), go("sessions", Mode.BUILD))
        assertEquals(Screen.MyWork, go("overview", Mode.BUILD))
        assertEquals(Screen.Facts(section = "overview"), go("overview"))
        assertEquals(Screen.Facts(section = "decisions"), go("decisions"))
        assertEquals(Screen.Facts(section = "research"), go("research"))
        assertEquals(Screen.Followups(), go("followups"))
        assertEquals(Screen.PullRequests, go("pullRequests"))
        assertEquals(Screen.Factories, go("factories", Mode.BUILD))
        assertEquals("reminders.list", (go("reminders") as Screen.ActionView).action)
        assertEquals(Screen.AllActions("Something New"), go("somethingNew", title = "Something New"))
    }


    @Test fun widthClasses() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(350f))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(1280f))
    }

    @Test fun modeIdsRoundTrip() {
        assertEquals(Mode.BUILD, Mode.of("build"))
        assertEquals(Mode.PLAN, Mode.of(null))
        assertEquals(Mode.PLAN, Mode.of("nonsense"))
    }

    @Test fun contrastPicksAReadablePair() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val midGrey = 0xFFA0A0A0.toInt()
        val darkGrey = 0xFF2A2A2A.toInt()
        assertEquals(21.0, Contrast.ratio(white, black), 0.01)
        assertEquals(1.0, Contrast.ratio(midGrey, midGrey), 0.01)
        val pairs = listOf(midGrey to white, midGrey to darkGrey, darkGrey to white)
        assertEquals(darkGrey to white, Contrast.pick(pairs, argb = { it }))
        assertEquals(midGrey to white, Contrast.pick(listOf(midGrey to white), argb = { it }))
    }
}
