package dk.langkilde.mindmill.remote

import dk.langkilde.mindmill.delivery.Delivery
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TestSourcesTest {
    private val sources = TestSources.parse(parseJsonObject(
        """
        {"local": {"branch": "main"},
         "sessions": [
           {"id": "s1", "title": "Fix the login flow", "branch": "feature/team/1234-login-flow"},
           {"id": "s2", "title": "A very long session title that goes on and on about what it does", "branch": ""},
           {"id": "s3", "title": "Short", "branch": "short"},
           {"title": "no id: skipped"}
         ]}
        """.trimIndent(),
    )!!)

    @Test
    fun parsesTheMacsVersionAndTheSessionsVersions() {
        assertEquals("main", sources.localBranch)
        assertEquals(listOf("s1", "s2", "s3"), sources.sessions.map { it.id })
        assertEquals(BuildSource("s1", "Fix the login flow", "feature/team/1234-login-flow"), sources.find("s1"))
        assertNull(sources.find("s2")!!.branch)
        assertTrue(sources.supported)
        val bare = TestSources.parse(parseJsonObject("""{"local": {"branch": null}, "sessions": []}""")!!)
        assertNull(bare.localBranch)
        assertEquals("This Mac's version", bare.localLabel)
        assertTrue(bare.sessions.isEmpty())
    }

    @Test
    fun labelsShowTheTitleAndTheLastPartOfTheBranchShortened() {
        val login = sources.find("s1")!!
        assertEquals("“Fix the login flow” (1234-login-flow)", login.label)
        assertEquals("“Fix the login flow”, on feature/team/1234-login-flow", login.fullLabel)
        assertTrue(login.isShortened)

        val long = sources.find("s2")!!
        assertEquals("“A very long session title that goes on…”", long.label)
        assertTrue(long.label.length < long.fullLabel.length)
        assertTrue(long.isShortened)

        val short = sources.find("s3")!!
        assertEquals("“Short” (short)", short.label)
        assertFalse(short.isShortened)

        assertEquals("This Mac's version (main)", sources.localLabel)
        assertFalse(sources.isLocalShortened)
        val longBranch = TestSources("release/2026-11/the-big-winter-update", emptyList())
        assertEquals("This Mac's version (release/2026-11/the-big-winte…)", longBranch.localLabel)
        assertEquals("This Mac's version, on release/2026-11/the-big-winter-update", longBranch.localFullLabel)
        assertTrue(longBranch.isLocalShortened)
        assertEquals("x", BuildSource.lastPart("a/b/x"))
        assertEquals("plain", BuildSource.lastPart("plain"))
    }

    @Test
    fun buildBuildsThePickedSessionOnlyWhileItIsListed() {
        val login = sources.find("s1")!!
        assertEquals("s1", TestSources.sessionToBuild(login, sources))
        assertNull(TestSources.sessionToBuild(null, sources))
        assertNull(TestSources.sessionToBuild(BuildSource("gone", "Gone"), sources))
        assertEquals("s1", TestSources.sessionToBuild(login, null))
        assertNull(TestSources.sessionToBuild(login, TestSources.MISSING))
    }

    @Test
    fun thePickIsKeptPerScopeAndGoesBackToTheMacsVersionWhenItsSessionIsGone() {
        val picks = BuildFromPicks()
        val login = sources.find("s1")!!
        picks.pick("acme", login)
        picks.pick("other", sources.find("s3"))
        assertEquals(login, picks.of("acme"))
        assertNull(picks.of("billing"))

        picks.reconcile("acme", TestSources(null, listOf(login.copy(title = "Fix the login flow, again"))))
        assertEquals("Fix the login flow, again", picks.of("acme")!!.title)

        picks.reconcile("acme", TestSources("main", listOf(BuildSource("s9", "Another"))))
        assertNull(picks.of("acme"))
        assertEquals("s3", picks.of("other")!!.id)

        picks.reconcile("other", TestSources.MISSING)
        assertNull(picks.of("other"))

        picks.pick(null, login)
        assertEquals(login, picks.of(null))
        picks.pick(null, null)
        assertNull(picks.of(null))

        picks.pick("acme", login)
        picks.clear()
        assertTrue(picks.picks.value.isEmpty())
    }

    @Test
    fun anOlderMacAnswersTestSourcesWithoutItAndOtherFailuresStillFail() = runTest {
        for (code in listOf("unknown_method", "unavailable")) {
            val r = TestSources.load("acme") { _, _ -> throw RemoteException(code, "nope") }
            assertEquals(TestSources.MISSING, r)
            assertFalse(r.supported)
        }
        try {
            TestSources.load("acme") { _, _ -> throw RemoteException("failed", "git failed") }
            fail("should fail")
        } catch (e: RemoteException) {
            assertEquals("failed", e.code)
        }
        var asked: Pair<String, JsonObject>? = null
        val r = TestSources.load("acme") { m, p -> asked = m to p; jsonOf("local" to jsonOf("branch" to "main")) }
        assertEquals("test.sources" to jsonOf("scope" to "acme"), asked)
        assertEquals("main", r.localBranch)
    }

    @Test
    fun theLogAndItsFirstError() {
        val log = BuildLog.parse(parseJsonObject("""{"log": "> Task :app:compile\nerror: x", "firstError": "  Unresolved reference: foo "}""")!!)
        assertEquals("> Task :app:compile\nerror: x", log.log)
        assertEquals("Unresolved reference: foo", log.firstError)
        assertNull(BuildLog.parse(parseJsonObject("""{"log": "ok", "firstError": null}""")!!).firstError)
        assertNull(BuildLog.parse(parseJsonObject("""{"log": "ok", "firstError": " "}""")!!).firstError)
        assertEquals("", BuildLog.parse(parseJsonObject("{}")!!).log)
    }

    @Test
    fun aDeliverySaysWhetherItHasALogAndWhoseVersionItIs() {
        val d = Delivery.parse(parseJsonObject("""{"id": "d1", "kind": "apk", "title": "App", "state": "failed", "hasLog": true, "session": "s1"}""")!!)!!
        assertTrue(d.hasLog)
        assertEquals("s1", d.session)
        val old = Delivery.parse(parseJsonObject("""{"id": "d2", "kind": "apk", "title": "App", "state": "failed"}""")!!)!!
        assertFalse(old.hasLog)
        assertNull(old.session)
        assertNull(Delivery.parse(parseJsonObject("""{"id": "d3", "kind": "apk", "session": null, "hasLog": false}""")!!)!!.session)
    }

    @Test
    fun deliverCarriesTheChosenSession() = runTest {
        val sent = mutableListOf<JsonObject>()
        val runner = ActionRunner({ _, p -> sent += p; jsonOf("status" to "done", "speech" to "Delivered.") }, { true })

        assertTrue(runner.deliverTest("android", "acme", TestSources.sessionToBuild(sources.find("s1"), sources)).ok)
        assertEquals("test.deliver", sent.last().string("name"))
        assertEquals(jsonOf("target" to "android", "scope" to "acme", "session" to "s1"), sent.last().obj("params"))

        runner.deliverTest("android", "acme", TestSources.sessionToBuild(BuildSource("gone", "Gone"), sources))
        assertFalse(sent.last().obj("params")!!.containsKey("session"))

        runner.deliverTest("android", null, "")
        assertEquals(jsonOf("target" to "android"), sent.last().obj("params"))
    }

    @Test
    fun anOlderMacIsTheOneThatAnswersUnknownMethodOrUnavailable() {
        assertTrue(RemoteException("unknown_method", "").isMissingOnMac)
        assertTrue(RemoteException("unavailable", "").isMissingOnMac)
        assertFalse(RemoteException("failed", "").isMissingOnMac)
        assertFalse(RemoteException("not_found", "").isMissingOnMac)
    }
}
