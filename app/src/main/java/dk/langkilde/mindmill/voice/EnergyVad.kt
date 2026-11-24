package dk.langkilde.mindmill.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A plain energy voice detector for barge-in: speech is a run of frames clearly louder than the room's noise floor
 * (which it learns as it goes), held for a short while so a cough or a clink doesn't count.
 */
class EnergyVad(
    private val frameMs: Int = 20,
    private val holdMs: Int = 180,
    private val marginDb: Double = 12.0,
    private val minDb: Double = -45.0,
    private val warmupMs: Int = 250,
) {
    private var floorDb = -60.0
    private var loudMs = 0
    private var elapsedMs = 0

    fun reset() {
        loudMs = 0
        elapsedMs = 0
    }

    /** Feeds one frame of 16-bit PCM; true once the user is talking. */
    fun feed(samples: ShortArray, count: Int = samples.size): Boolean {
        val db = levelDb(samples, count)
        elapsedMs += frameMs
        val threshold = max(minDb, floorDb + marginDb)
        if (db > threshold && elapsedMs > warmupMs) {
            loudMs += frameMs
        } else {
            loudMs = 0
            floorDb = if (db < floorDb) db else floorDb * 0.95 + db * 0.05
        }
        return loudMs >= holdMs
    }

    companion object {
        fun levelDb(samples: ShortArray, count: Int = samples.size): Double {
            if (count <= 0) return -96.0
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i] / 32768.0
                sum += s * s
            }
            val rms = sqrt(sum / count)
            return if (rms <= 1e-6) -96.0 else 20 * log10(rms)
        }
    }
}
