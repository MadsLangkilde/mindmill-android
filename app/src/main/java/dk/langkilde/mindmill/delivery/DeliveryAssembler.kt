package dk.langkilde.mindmill.delivery

import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.PinnedTrustManager
import dk.langkilde.mindmill.remote.string
import java.io.OutputStream
import java.security.MessageDigest
import java.util.TreeMap

/**
 * Puts a delivered file back together from its `file` frames, writing chunks in `seq` order as they come and
 * hashing on the way, then checks the SHA-256 that `test.download` announced. The first chunk's `seq` (0 or 1) is
 * the start; a chunk that arrives early waits for the ones before it.
 */
class DeliveryAssembler(
    val delivery: String,
    private val expectedSha256: String,
    private val expectedSize: Long?,
    private val expectedChunks: Int?,
    private val out: OutputStream,
) {
    sealed interface Progress {
        data class Receiving(val bytes: Long, val chunks: Int) : Progress
        data class Complete(val bytes: Long, val sha256: String) : Progress
        data class Failed(val reason: String) : Progress
    }

    private val digest = MessageDigest.getInstance("SHA-256")
    private val early = TreeMap<Long, ByteArray>()
    private var next: Long? = null
    private var lastSeq: Long? = null
    private var written = 0L
    private var chunks = 0
    private var finished: Progress? = null

    val bytesReceived get() = written

    fun accepts(frame: BinaryFrame) = frame.kind == "file" && frame.header.string("delivery") == delivery

    fun add(frame: BinaryFrame): Progress {
        finished?.let { return it }
        if (!accepts(frame)) return Progress.Receiving(written, chunks)
        val seq = frame.seq
        if (next == null) next = if (seq <= 1) seq else 0
        if (frame.isLast) lastSeq = seq
        if (seq < next!! || early.containsKey(seq)) return Progress.Receiving(written, chunks)
        early[seq] = frame.payload
        while (true) {
            val chunk = early.remove(next!!) ?: break
            out.write(chunk)
            digest.update(chunk)
            written += chunk.size
            chunks++
            next = next!! + 1
        }
        if (expectedSize != null && written > expectedSize) return fail("The file is bigger than announced.")
        val last = lastSeq
        if (last != null && next!! > last) {
            out.flush()
            if (early.isNotEmpty()) return fail("Chunks arrived after the last one.")
            if (expectedChunks != null && expectedChunks > 0 && chunks != expectedChunks) {
                return fail("Got $chunks of $expectedChunks chunks.")
            }
            if (expectedSize != null && written != expectedSize) return fail("Got $written of $expectedSize bytes.")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(expectedSha256.trim(), ignoreCase = true)) return fail("The file's SHA-256 doesn't match. It was not installed.")
            return Progress.Complete(written, sha).also { finished = it }
        }
        return Progress.Receiving(written, chunks)
    }

    private fun fail(reason: String) = Progress.Failed(reason).also { finished = it }

    companion object {
        fun sha256Hex(bytes: ByteArray) = PinnedTrustManager.sha256Hex(bytes)
    }
}
