package dk.langkilde.mindmill.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTargetTest {
    @Test fun readyMissingAndUnavailableTargets() {
        val r = parseJsonObject("""{"targets":[
            {"id":"android","title":"Android app","ready":true,"checklist":[{"id":"a","title":"Gradle","status":"ok"}]},
            {"id":"web","title":"Web preview","ready":false,"checklist":[{"title":"Build command","status":"missing"},{"title":"Port","status":"unknown"},{"title":"Node","status":"ok"}]},
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
}
