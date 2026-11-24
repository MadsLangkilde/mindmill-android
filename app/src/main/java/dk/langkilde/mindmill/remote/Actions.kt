package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The answer of `action.run` / `action.confirm` / `action.cancel`. */
data class ActionOutcome(
    val status: Status,
    val speech: String,
    val data: JsonObject = EmptyJson,
    val token: String? = null,
    /** The whole `data`, which may also be a list or a plain value. */
    val dataElement: JsonElement? = null,
) {
    enum class Status { DONE, NEEDS_CONFIRMATION, FAILED, CANCELLED }

    val ok get() = status == Status.DONE

    companion object {
        fun parse(o: JsonObject): ActionOutcome {
            val status = when (o.string("status")) {
                "done" -> Status.DONE
                "needs_confirmation" -> Status.NEEDS_CONFIRMATION
                "cancelled" -> Status.CANCELLED
                else -> Status.FAILED
            }
            val raw = o["data"]?.takeIf { it !is JsonNull }
            return ActionOutcome(status, o.string("speech").orEmpty(), o.obj("data") ?: EmptyJson, o.string("token"), raw)
        }
    }
}

/**
 * Runs an action through the Mac's action layer. When the Mac asks for confirmation, [ask] gets the question
 * (a dialog, or the voice screen) and its yes or no becomes `action.confirm` or `action.cancel`.
 */
class ActionRunner(
    private val call: suspend (method: String, params: JsonObject) -> JsonObject,
    private val ask: suspend (question: String) -> Boolean,
) {
    suspend fun run(name: String, params: JsonObject = EmptyJson, voice: Boolean = false): ActionOutcome {
        val first = ActionOutcome.parse(
            call("action.run", jsonOf("name" to name, "params" to params, "voice" to (if (voice) true else null))),
        )
        if (first.status != ActionOutcome.Status.NEEDS_CONFIRMATION) return first
        val token = first.token ?: return first.copy(status = ActionOutcome.Status.FAILED)
        return if (ask(first.speech)) {
            ActionOutcome.parse(call("action.confirm", jsonOf("token" to token)))
        } else {
            runCatching { call("action.cancel", jsonOf("token" to token)) }
            ActionOutcome(ActionOutcome.Status.CANCELLED, "Cancelled.")
        }
    }
}
