package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer

/**
 * A binary WebSocket frame: a 4-byte big-endian header length, a JSON header, then the payload.
 * Carries audio, screenshots and file chunks (docs/remote-api.md, Binary frames).
 */
class BinaryFrame(val header: JsonObject, val payload: ByteArray) {
    val kind: String? get() = header.string("kind")
    val seq: Long get() = header.long("seq") ?: 0
    val format: String? get() = header.string("format")
    val isLast: Boolean get() = header.bool("last") == true

    fun encode(): ByteArray {
        val h = header.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + h.size + payload.size).putInt(h.size).put(h).put(payload).array()
    }

    override fun equals(other: Any?): Boolean =
        other is BinaryFrame && header == other.header && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    companion object {
        const val MAX_HEADER = 64 * 1024

        fun decode(bytes: ByteArray): BinaryFrame? {
            if (bytes.size < 4) return null
            val len = ByteBuffer.wrap(bytes, 0, 4).int
            if (len <= 0 || len > MAX_HEADER || 4 + len > bytes.size) return null
            val header = parseJsonObject(String(bytes, 4, len, Charsets.UTF_8)) ?: return null
            return BinaryFrame(header, bytes.copyOfRange(4 + len, bytes.size))
        }

        fun screen(meeting: String, seq: Long, jpeg: ByteArray) =
            BinaryFrame(jsonOf("kind" to "screen", "meeting" to meeting, "format" to "jpeg", "seq" to seq), jpeg)

        fun voice(conversation: String, seq: Long, pcm16k: ByteArray) =
            BinaryFrame(jsonOf("kind" to "voice", "conversation" to conversation, "format" to "pcm16k", "seq" to seq), pcm16k)
    }
}
