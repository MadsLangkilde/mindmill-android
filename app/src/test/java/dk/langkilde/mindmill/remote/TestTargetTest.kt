package dk.langkilde.mindmill.remote

import dk.langkilde.mindmill.delivery.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTargetTest {
    @Test fun readyMissingAndUnavailableTargets() {
        val r = parseJsonObject("""{"targets":[
            {"id":"android","title":"Android app","ready":true,"checklist":[{"id":"a","title":"Gradle","status":"ok"}]},
            {"id":"web","title":"Web preview","ready":false,"checklist":[{"title":"Build command","status":"missing"},{"title":"Port","status":"unknown"},{"title":"Tailscale","status":"missing","required":false},{"title":"Node","status":"ok"}]},
            {"id":"ios","title":"iPhone app","ready":true,"unavailable":"Needs a Mac with Xcode"},
            {"title":"no id"}
        ]}""")
        val t = TestTarget.list(r)
        assertEquals(listOf("android", "web", "ios"), t.map { it.id })
        assertTrue(t[0].canDeliver)
        assertNull(t[0].whyNot)
        assertEquals("Needs: Build command, Port", t[1].whyNot)
        assertEquals("Needs a Mac with Xcode", t[2].whyNot)
        assertTrue(TestTarget.list(parseJsonObject("{}")).isEmpty())
    }

    @Test fun aTargetCanBeBuiltUnlessItIsBuildingOrBlocked() {
        val android = TestTarget("android", "Android app", ready = true)
        val web = TestTarget("web", "Web preview", ready = false, missing = listOf("Build command"))
        val building = Delivery.parse(parseJsonObject("""{"id":"d1","target":"android","kind":"apk","title":"Android app","state":"building"}""")!!)!!
        val done = building.copy(id = "d0", state = "ready")
        assertEquals(BuildChoice.Build, BuildChoice.of(android, listOf(done), emptySet()))
        assertEquals(BuildChoice.Building, BuildChoice.of(android, listOf(done, building), emptySet()))
        assertEquals(BuildChoice.Building, BuildChoice.of(android, emptyList(), setOf("android")))
        assertEquals(BuildChoice.Blocked("Needs: Build command"), BuildChoice.of(web, emptyList(), emptySet()))
        assertTrue(building.isBuilding)
        val failed = Delivery.parse(parseJsonObject("""{"id":"d2","kind":"apk","title":"x","state":"failed","message":"Gradle failed."}""")!!)!!
        assertTrue(failed.hasFailed)
        assertEquals("Gradle failed.", failed.message)
    }
}
