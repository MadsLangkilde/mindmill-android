package dk.langkilde.mindmill.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerModelTest {
    private fun s(id: String, space: String = "plan", updated: Double, needs: Boolean = false, archived: Boolean = false, role: String = "solo") =
        SessionInfo(id, id, space, role, if (needs) "waiting" else "idle", false, updated, 0, archived = archived, needsYouFlag = needs)

    @Test fun destinationsAreTheMacsSectionsWithoutSessionsAndProjectThenMore() {
        val sections = listOf(NavSection("home", "Home"), NavSection("sessions", "Sessions"), NavSection("project", "Project"),
            NavSection("research", "Research", group = "Planning"))
        assertEquals(listOf("home", "research", "more"), DrawerModel.destinations(sections).map { it.id })
        assertEquals(Screen.More, DrawerModel.destinations(emptyList()).last().screen(Mode.PLAN))
        val build = AppStructure.default().forPhone().sections(Mode.BUILD)
        assertEquals(listOf("overview", "factories", "testing", "more"), DrawerModel.destinations(build).map { it.id })
    }

    @Test fun sessionsOfTheModeNeedingYouFirstThenTheMostRecent() {
        val all = listOf(
            s("p1", updated = 10.0), s("p2", updated = 30.0), s("old-needs", updated = 1.0, needs = true),
            s("arch", updated = 50.0, archived = true), s("b1", space = "build", updated = 40.0),
            s("b2", space = "build", updated = 5.0, needs = true), s("ov", space = "build", updated = 60.0, role = "overseer"),
            s("p3", updated = 20.0),
        )
        assertEquals(listOf("old-needs", "p2", "p3"), DrawerModel.sessions(all, Mode.PLAN, recent = 2).map { it.id })
        assertEquals(1, DrawerModel.hidden(all, Mode.PLAN, recent = 2))
        assertEquals(listOf("b2", "b1"), DrawerModel.sessions(all, Mode.BUILD).map { it.id })
        val asking = all.map { if (it.id == "ov") s("ov", space = "build", updated = 60.0, role = "overseer", needs = true) else it }
        assertEquals(listOf("ov", "b2", "b1"), DrawerModel.sessions(asking, Mode.BUILD).map { it.id })
    }
}
