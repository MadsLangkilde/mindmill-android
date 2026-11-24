package dk.langkilde.mindmill.delivery

import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.jsonOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class DeliveryAssemblerTest {
    private val file = Random(42).nextBytes(256 * 1024 * 3 + 1000)
    private val sha = DeliveryAssembler.sha256Hex(file)

    private fun chunks(base: Int = 0, delivery: String = "d1"): List<BinaryFrame> {
        val parts = file.toList().chunked(256 * 1024).map { it.toByteArray() }
        return parts.mapIndexed { i, p ->
            BinaryFrame(jsonOf("kind" to "file", "delivery" to delivery, "seq" to base + i, "last" to (i == parts.size - 1)), p)
        }
    }

    @Test
    fun assemblesInOrderAndChecksTheHash() {
        val out = ByteArrayOutputStream()
        val a = DeliveryAssembler("d1", sha, file.size.toLong(), 4, out)
        val results = chunks().map { a.add(it) }
        results.dropLast(1).forEach { assertTrue(it is DeliveryAssembler.Progress.Receiving) }
        val done = results.last() as DeliveryAssembler.Progress.Complete
        assertEquals(sha, done.sha256)
        assertArrayEquals(file, out.toByteArray())
    }

    @Test
    fun seqMayStartAtOneAndChunksMayArriveEarly() {
        val out = ByteArrayOutputStream()
        val a = DeliveryAssembler("d1", sha.uppercase(), null, null, out)
        val c = chunks(base = 1)
        a.add(c[0]); a.add(c[2]); a.add(c[1])
        val done = a.add(c[3])
        assertTrue(done is DeliveryAssembler.Progress.Complete)
        assertArrayEquals(file, out.toByteArray())
    }

    @Test
    fun aWrongHashFails() {
        val a = DeliveryAssembler("d1", "00".repeat(32), null, null, ByteArrayOutputStream())
        val r = chunks().map { a.add(it) }.last()
        assertTrue(r is DeliveryAssembler.Progress.Failed)
    }

    @Test
    fun aTamperedChunkFails() {
        val a = DeliveryAssembler("d1", sha, null, null, ByteArrayOutputStream())
        val c = chunks().toMutableList()
        c[1] = BinaryFrame(c[1].header, c[1].payload.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertTrue(c.map { a.add(it) }.last() is DeliveryAssembler.Progress.Failed)
    }

    @Test
    fun missingChunkCountFails() {
        val a = DeliveryAssembler("d1", sha, null, 5, ByteArrayOutputStream())
        assertTrue(chunks().map { a.add(it) }.last() is DeliveryAssembler.Progress.Failed)
    }

    @Test
    fun otherDeliveriesAreIgnored() {
        val out = ByteArrayOutputStream()
        val a = DeliveryAssembler("d1", sha, null, null, out)
        chunks(delivery = "d2").forEach { a.add(it) }
        assertEquals(0, out.size())
        assertTrue(chunks().map { a.add(it) }.last() is DeliveryAssembler.Progress.Complete)
    }
}

class PreviewUrlTest {
    @Test
    fun splitsDeliveryPathAndQuery() {
        assertEquals("d7" to "index.html", PreviewWebViewClient.split(PreviewWebViewClient.url("d7")))
        assertEquals("d7" to "index.html", PreviewWebViewClient.split("https://preview.mindmill/d7/"))
        assertEquals("d7" to "assets/app.js", PreviewWebViewClient.split("https://preview.mindmill/d7/assets/app.js"))
        assertEquals(
            "d7" to "try?method=GET&path=%2Fx&body=",
            PreviewWebViewClient.split("https://preview.mindmill/d7/try?method=GET&path=%2Fx&body="),
        )
        assertEquals(null, PreviewWebViewClient.split("https://example.com/d7/index.html"))
        assertEquals(null, PreviewWebViewClient.split("https://preview.mindmill/"))
    }
}
