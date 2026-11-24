package dk.langkilde.mindmill.ui

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NavStateTest {
    private val plan = NavState.initial(Mode.PLAN)

    private fun NavState.screens() = history.map { it.screen }

    @Test
    fun moreThenItemThenBackWalksBackToMoreThenStartThenExits() {
        var s = plan.selectTab(Screen.More).go(Screen.Facts(section = "inbox")).go(Screen.Entry("e1"))
        assertEquals(listOf(Screen.Home, Screen.More, Screen.Facts(section = "inbox"), Screen.Entry("e1")), s.screens())

        s = s.back()!!
        assertEquals(Screen.Facts(section = "inbox"), s.top)
        s = s.back()!!
        assertEquals(Screen.More, s.top)
        s = s.back()!!
        assertEquals(Screen.Home, s.top)
        assertNull(s.back())
    }

    @Test
    fun openingFromHomePushesAndNeverReplacesTheRoot() {
        val s = plan.go(Screen.NewSession()).go(Screen.SessionChat("1", "A"))
        assertEquals(listOf(Screen.Home, Screen.NewSession(), Screen.SessionChat("1", "A")), s.screens())
        assertEquals(Screen.Home, s.tab.root)
        assertEquals(Screen.NewSession(), s.back()!!.top)
    }

    @Test
    fun openingWhatIsOnTopDoesNothing() {
        val s = plan.go(Screen.Voice)
        assertEquals(s, s.go(Screen.Voice))
    }

    @Test
    fun eachSectionKeepsItsOwnStack() {
        val s = plan.selectTab(Screen.Backlog).go(Screen.RunAction("workitem.create"))
            .selectTab(Screen.Meetings).go(Screen.MeetingDetail("m", "M"))
            .selectTab(Screen.Backlog)
        assertEquals(Screen.RunAction("workitem.create"), s.top)
        assertEquals(Screen.MeetingDetail("m", "M"), s.selectTab(Screen.Meetings).top)
    }

    @Test
    fun reselectingTheCurrentSectionPopsToItsRoot() {
        val s = plan.selectTab(Screen.More).go(Screen.Tags).go(Screen.Entry("x")).selectTab(Screen.More)
        assertEquals(listOf(Screen.Home, Screen.More), s.screens())
        assertFalse(s.canGoUp)
    }

    @Test
    fun backFromAnotherSectionsRootGoesToTheStartSectionAsItWasLeft() {
        val s = plan.go(Screen.NewSession()).selectTab(Screen.Sessions("plan"))
        assertEquals(listOf(Screen.Home, Screen.NewSession(), Screen.Sessions("plan")), s.screens())
        val back = s.back()!!
        assertEquals(Screen.NewSession(), back.top)
        assertEquals(Screen.Home, back.tab.root)
    }

    @Test
    fun modeSwitchKeepsBothStacksAndBackUndoesItOnce() {
        var s = plan.selectTab(Screen.More).go(Screen.Tags)
            .switchMode(Mode.BUILD).selectTab(Screen.Factories)
        assertEquals(Mode.BUILD, s.mode)
        assertEquals(Screen.Factories, s.top)

        s = s.switchMode(Mode.PLAN)
        assertEquals(Screen.Tags, s.top)
        s = s.switchMode(Mode.BUILD)
        assertEquals(Screen.Factories, s.top)

        s = s.back()!!
        assertEquals(Screen.MyWork, s.top)
        s = s.back()!!
        assertEquals(Mode.PLAN, s.mode)
        assertEquals(Screen.Tags, s.top)
        s = s.back()!!.back()!!
        assertEquals(Screen.Home, s.top)
        assertNull(s.back())
    }

    @Test
    fun replaceSwapsTheTopButNeverASectionRoot() {
        val created = plan.go(Screen.NewEntry("facts")).replace(Screen.Entry("new"))
        assertEquals(listOf(Screen.Home, Screen.Entry("new")), created.screens())
        val onRoot = plan.replace(Screen.Entry("x"))
        assertEquals(listOf(Screen.Home, Screen.Entry("x")), onRoot.screens())
    }

    @Test
    fun aListBesideItsDetailOpensInPlaceOfTheDetail() {
        val s = plan.selectTab(Screen.Sessions("plan"))
        val listId = s.tab.top.id
        val opened = s.openFromList(listId, Screen.SessionChat("1", "A")).go(Screen.Entry("deeper"))
            .openFromList(listId, Screen.SessionChat("2", "B"))
        assertEquals(listOf(Screen.Home, Screen.Sessions("plan"), Screen.SessionChat("2", "B")), opened.screens())
        assertEquals(Screen.Sessions("plan"), opened.back()!!.top)
    }

    @Test
    fun entriesKnowTheirSection() {
        val s = plan.selectTab(Screen.More).go(Screen.Tags).switchMode(Mode.BUILD)
        val ids = s.plan.tabs[1].entries.map { it.id }
        ids.forEach { assertEquals("plan/1", s.sectionOf(it)) }
        assertEquals("build/0", s.sectionOf(s.build.start.top.id))
        assertNull(s.sectionOf(999))
    }

    @Test
    fun survivesBeingSavedAndRestored() {
        val item = buildJsonObject { put("id", JsonPrimitive(42)); put("title", JsonPrimitive("Fix it")) }
        val s = plan.selectTab(Screen.Backlog).go(Screen.WorkItemDetail(item))
            .switchMode(Mode.BUILD).go(Screen.RunAction("x", mapOf("a" to "b"))).go(Screen.AllActions(null))
        val restored = NavState.decode(s.encode())
        assertEquals(s, restored)
        assertNull(NavState.decode("not json"))
    }

    @Test
    fun everyBackStepDropsExactlyTheLastShownEntry() {
        val screens = listOf(Screen.More, Screen.Backlog, Screen.Meetings, Screen.Sessions("plan"), Screen.Factories, Screen.MyWork)
        val pushes = listOf(Screen.Tags, Screen.Entry("a"), Screen.Entry("b"), Screen.NewSession(), Screen.Voice, Screen.Knowledge)
        val random = Random(7)
        repeat(300) {
            var s = NavState.initial(if (random.nextBoolean()) Mode.PLAN else Mode.BUILD)
            repeat(random.nextInt(1, 25)) {
                s = when (random.nextInt(6)) {
                    0 -> s.selectTab(screens.random(random))
                    1, 2 -> s.go(pushes.random(random))
                    3 -> s.switchMode(Mode.entries.random(random))
                    4 -> s.replace(pushes.random(random))
                    else -> s.back() ?: s
                }
            }
            var steps = 0
            while (true) {
                val before = s.history
                val ids = before.map { it.id }
                assertEquals("ids are unique", ids.size, ids.toSet().size)
                val next = s.back()
                if (next == null) {
                    assertEquals(1, before.size)
                    break
                }
                assertEquals(before.dropLast(1), next.history)
                s = next
                assertTrue(++steps < 200)
            }
        }
    }

    @Test
    fun navigatorBackSaysWhenThereIsNothingLeft() {
        val n = Navigator(plan)
        n.go(Screen.Voice)
        assertTrue(n.back())
        assertFalse(n.back())
        assertNotNull(n.state)
    }

    @Test
    fun anotherMacStartsBothModesOverInTheSameMode() {
        val s = plan.selectTab(Screen.Backlog).go(Screen.Entry("e1")).switchMode(Mode.BUILD).go(Screen.SessionChat("s", "S"))
        val r = s.restarted()
        assertEquals(Mode.BUILD, r.mode)
        assertEquals(listOf(Screen.MyWork), r.screens())
        assertEquals(listOf(Screen.Home), r.plan.history.map { it.screen })
        assertNull(r.previousMode)
        assertNull(r.back())
        assertTrue(r.go(Screen.Voice).tab.top.id >= s.nextId)
    }
}
