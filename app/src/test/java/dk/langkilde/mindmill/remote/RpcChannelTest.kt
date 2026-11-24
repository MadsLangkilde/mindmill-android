package dk.langkilde.mindmill.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RpcChannelTest {
    private fun sentRequests(sent: List<String>) = sent.map { RemoteMessage.decode(it) as RemoteMessage.Request }

    @Test
    fun answersGoToTheRequestWithTheSameIdEvenOutOfOrder() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<String>()
        val rpc = RpcChannel().apply { sender = { sent += it; true } }
        val a = async { rpc.call("entries.list", jsonOf("scope" to "p", "section" to "facts")) }
        val b = async { rpc.call("sessions.list") }
        val (ra, rb) = sentRequests(sent)
        assertEquals("entries.list", ra.method)
        assertEquals("facts", ra.params.string("section"))
        assertTrue(ra.id != rb.id)
        rpc.onText("""{"id":"${rb.id}","result":{"sessions":[]}}""")
        rpc.onText("""{"id":"${ra.id}","result":{"entries":[{"id":"x"}]}}""")
        assertEquals("x", a.await().objects("entries").single().string("id"))
        assertTrue(b.await().containsKey("sessions"))
        assertEquals(0, rpc.pendingCount)
    }

    @Test
    fun failuresBecomeRemoteExceptions() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<String>()
        val rpc = RpcChannel().apply { sender = { sent += it; true } }
        val call = async { runCatching { rpc.call("meeting.start") } }
        val id = sentRequests(sent).single().id
        rpc.onText("""{"id":"$id","error":{"code":"unknown_method","message":"The Mac doesn't know meeting.start."}}""")
        val e = call.await().exceptionOrNull() as RemoteException
        assertTrue(e.isUnknownMethod)
        assertTrue(e.userMessage().contains("Update Mindmill on the Mac"))
    }

    @Test
    fun eventsReachListenersAndTheFlow() = runTest(UnconfinedTestDispatcher()) {
        val rpc = RpcChannel()
        val heard = mutableListOf<String>()
        rpc.addEventListener { heard += it.name }
        val flowed = mutableListOf<RemoteMessage.Event>()
        backgroundScope.launch { rpc.events.collect { flowed += it } }
        rpc.onText("""{"event":"session.changed","data":{"id":"s1","count":4,"busy":true,"state":"working"}}""")
        rpc.onText("""{"event":"index.changed","data":{}}""")
        assertEquals(listOf("session.changed", "index.changed"), heard)
        assertEquals(4, flowed.first().data.int("count"))
    }

    @Test
    fun aStrayAnswerIsIgnored() {
        RpcChannel().onText("""{"id":"999","result":{}}""")
    }

    @Test
    fun closingFailsEveryWaitingCall() = runTest(UnconfinedTestDispatcher()) {
        val rpc = RpcChannel().apply { sender = { true } }
        val a = async { runCatching { rpc.call("hello") } }
        rpc.failAll()
        assertEquals(ErrorCodes.OFFLINE, (a.await().exceptionOrNull() as RemoteException).code)
        try {
            rpc.call("state")
            fail("called without a socket")
        } catch (e: RemoteException) {
            assertEquals(ErrorCodes.OFFLINE, e.code)
        }
    }

    @Test
    fun binaryFramesReachFrameListeners() {
        val rpc = RpcChannel()
        val got = mutableListOf<BinaryFrame>()
        rpc.addFrameListener { got += it }
        val f = BinaryFrame(jsonOf("kind" to "voice", "conversation" to "c", "format" to "pcm24k", "seq" to 1), byteArrayOf(1, 2))
        rpc.onBinary(f.encode())
        assertEquals(listOf(f), got)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmationFlowTest {
    private class FakeMac(val first: String) {
        val calls = mutableListOf<Pair<String, String>>()
        suspend fun call(method: String, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
            calls += method to params.toString()
            return when (method) {
                "action.run" -> parseJsonObject(first)!!
                "action.confirm" -> jsonOf("status" to "done", "speech" to "Stopping Night shift.")
                "action.cancel" -> jsonOf("status" to "cancelled")
                else -> error(method)
            }
        }
    }

    private val ask = """{"status":"needs_confirmation","token":"tok1","speech":"Stop the factory Night shift?"}"""

    @Test
    fun yesConfirmsWithTheToken() = runTest {
        val mac = FakeMac(ask)
        var question = ""
        val out = ActionRunner(mac::call) { question = it; true }.run("factory.stop", jsonOf("factory" to "Night shift"))
        assertEquals("Stop the factory Night shift?", question)
        assertEquals(ActionOutcome.Status.DONE, out.status)
        assertEquals(listOf("action.run", "action.confirm"), mac.calls.map { it.first })
        assertTrue(mac.calls[0].second.contains("\"name\":\"factory.stop\""))
        assertTrue(mac.calls[1].second.contains("\"token\":\"tok1\""))
    }

    @Test
    fun noCancelsWithTheToken() = runTest {
        val mac = FakeMac(ask)
        val out = ActionRunner(mac::call) { false }.run("factory.stop", jsonOf("factory" to "Night shift"))
        assertEquals(ActionOutcome.Status.CANCELLED, out.status)
        assertEquals(listOf("action.run", "action.cancel"), mac.calls.map { it.first })
        assertTrue(mac.calls[1].second.contains("tok1"))
    }

    @Test
    fun doneNeedsNoQuestion() = runTest {
        val mac = FakeMac("""{"status":"done","speech":"Paused.","data":{"x":1}}""")
        val out = ActionRunner(mac::call) { fail("asked"); true }.run("factory.pause")
        assertTrue(out.ok)
        assertEquals(1, out.data.int("x"))
        assertEquals(1, mac.calls.size)
    }

    @Test
    fun failedPassesTheSpeechThrough() = runTest {
        val mac = FakeMac("""{"status":"failed","speech":"Night shift isn't running."}""")
        val out = ActionRunner(mac::call) { true }.run("factory.stop")
        assertEquals(ActionOutcome.Status.FAILED, out.status)
        assertEquals("Night shift isn't running.", out.speech)
    }
}
