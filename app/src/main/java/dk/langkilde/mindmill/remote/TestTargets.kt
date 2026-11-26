package dk.langkilde.mindmill.remote

import dk.langkilde.mindmill.delivery.Delivery
import kotlinx.serialization.json.JsonObject

/** What the Test screen offers for one target. */
sealed interface BuildChoice {
    data object Build : BuildChoice
    data object Building : BuildChoice
    data class Blocked(val why: String) : BuildChoice

    companion object {
        /** [starting]: targets the phone just asked for, before the Mac lists their delivery as building. */
        fun of(target: TestTarget, deliveries: List<Delivery>, starting: Set<String>): BuildChoice = when {
            target.id in starting || deliveries.any { it.target == target.id && it.isBuilding } -> Building
            else -> target.whyNot?.let(::Blocked) ?: Build
        }
    }
}

/** One target of `test.status`: what a Build session can deliver to test, and what stops it when it can't. */
data class TestTarget(
    val id: String,
    val title: String,
    val ready: Boolean,
    /** Why this computer can't deliver it at all (an iPhone build on Windows). */
    val unavailable: String? = null,
    /** The required set-up items that aren't done yet; optional ones (Tailscale for away from home) don't block. */
    val missing: List<String> = emptyList(),
    val type: String? = null,
    /** How it reaches the phone: apk, url or preview. */
    val kind: String? = null,
) {
    val buildLabel: String
        get() = when (kind) {
            "apk" -> "Build and send to this phone"
            "url" -> "Start it and open it here"
            else -> "Build a preview"
        }

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
        /** Only what this phone can try itself: Android apps and websites (a Mac that doesn't say: everything). */
        fun forAndroid(type: String?): Boolean = type == null || type == "android" || type == "web"

        fun list(status: JsonObject?): List<TestTarget> = status.objects("targets").mapNotNull { t ->
            val id = t.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TestTarget(
                id = id,
                title = t.string("title") ?: id,
                ready = t.bool("ready") == true,
                unavailable = t.string("unavailable")?.takeIf { it.isNotBlank() },
                missing = t.objects("checklist").filter { it.string("status") != "ok" && it.bool("required") != false }.mapNotNull { it.string("title") },
                type = t.string("type"),
                kind = t.string("kind"),
            )
        }
    }
}
