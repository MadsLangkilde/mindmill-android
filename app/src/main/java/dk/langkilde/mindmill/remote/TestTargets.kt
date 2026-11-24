package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

/** One target of `test.status`: what a Build session can deliver to test, and what stops it when it can't. */
data class TestTarget(
    val id: String,
    val title: String,
    val ready: Boolean,
    /** Why this computer can't deliver it at all (an iPhone build on Windows). */
    val unavailable: String? = null,
    /** The set-up items that aren't done yet. */
    val missing: List<String> = emptyList(),
) {
    val canDeliver: Boolean get() = ready && unavailable == null

    /** Why it can't be delivered now, in one line; null when it can. */
    val whyNot: String?
        get() = when {
            unavailable != null -> unavailable
            ready -> null
            missing.isNotEmpty() -> "Needs: " + missing.joinToString(", ")
            else -> "Not set up yet"
        }

    companion object {
        fun list(status: JsonObject?): List<TestTarget> = status.objects("targets").mapNotNull { t ->
            val id = t.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TestTarget(
                id = id,
                title = t.string("title") ?: id,
                ready = t.bool("ready") == true,
                unavailable = t.string("unavailable")?.takeIf { it.isNotBlank() },
                missing = t.objects("checklist").filter { it.string("status") != "ok" }.mapNotNull { it.string("title") },
            )
        }
    }
}
