package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/** The checkpoints a factory can stop at, as the Mac's `FactoryCheckpoint` names them. */
data class FactoryCheckpointInfo(val id: String, val title: String, val detail: String)

object FactoryChoices {
    val sprint = listOf("current" to "Current sprint", "currentAndNext" to "Current and next sprint", "any" to "Any sprint")
    val questions = listOf(
        "whenUnsure" to "Ask when unsure, and at the checkpoints",
        "checkpointsOnly" to "Ask only at the checkpoints",
        "never" to "Never ask: decide and note the assumptions",
    )
    val newWorkItems = listOf(
        "never" to "Don't create work items",
        "propose" to "Propose them for my approval",
        "create" to "Create them, assigned to me, and work on them",
    )
    val duration = listOf("untilStopped" to "Until I stop it", "hours" to "For a number of hours", "until" to "Until a time")
    val match = listOf("bug" to "Bugs", "pbi" to "PBIs", "tag" to "Tagged", "title" to "Title has")
    val parallel = listOf(1, 2, 3, 4, 5, 6, 8, 10)

    val checkpoints = listOf(
        FactoryCheckpointInfo("beforeStart", "Before starting a task", "It tells you how it understood the item and waits for your go-ahead."),
        FactoryCheckpointInfo("planReady", "When the plan is ready, before writing code", "You see the steps it will take and can adjust them."),
        FactoryCheckpointInfo("missingKnowledge", "When it needs knowledge that isn't in the facts", "A business rule, a decision, how an outside system behaves: it asks instead of guessing."),
        FactoryCheckpointInfo("designChoice", "When there are several good ways to build something", "It lays out the options with its recommendation first."),
        FactoryCheckpointInfo("testsFailing", "When tests keep failing", "After a few honest attempts, it tells you what it tried."),
        FactoryCheckpointInfo("beforePR", "Before opening a pull request", "You see the title and a summary of the changes first."),
        FactoryCheckpointInfo("beforeComplete", "Before completing (merging) a pull request", "It waits for your yes before merging."),
    )
}

/** An option the Mac offers in the factory editor (`options` of `factory.config.get`). */
data class FactoryOption(
    val id: String,
    val title: String,
    val detail: String = "",
    val needsValue: Boolean = false,
    /** Rule keys that must be on for this to apply (a checkpoint's delivery settings). */
    val requires: List<String> = emptyList(),
)

/** The choices of `options`, falling back to what the phone knows when an older Mac leaves some out. */
data class FactoryOptions(
    val scopes: List<FactoryOption>,
    val strategies: List<FactoryOption>,
    val checkpoints: List<FactoryOption>,
    val sprint: List<FactoryOption>,
    val questions: List<FactoryOption>,
    val newWorkItems: List<FactoryOption>,
    val duration: List<FactoryOption>,
    val strategyMatch: List<FactoryOption>,
    val maxParallel: List<Int>,
) {
    companion object {
        private fun pairs(l: List<Pair<String, String>>) = l.map { FactoryOption(it.first, it.second) }

        private fun list(o: JsonObject?, key: String, idKey: String = "id"): List<FactoryOption> = o.objects(key).mapNotNull {
            val id = it.text(idKey) ?: return@mapNotNull null
            FactoryOption(id, it.string("title") ?: id, it.string("detail").orEmpty(), it.bool("needsValue") == true, it.strings("requires"))
        }

        fun parse(o: JsonObject?): FactoryOptions = FactoryOptions(
            scopes = list(o, "scopes"),
            strategies = list(o, "strategies", "slug").filter { it.id.isNotBlank() },
            checkpoints = list(o, "checkpoints").ifEmpty { FactoryChoices.checkpoints.map { FactoryOption(it.id, it.title, it.detail) } },
            sprint = list(o, "sprint").ifEmpty { pairs(FactoryChoices.sprint) },
            questions = list(o, "questions").ifEmpty { pairs(FactoryChoices.questions) },
            newWorkItems = list(o, "newWorkItems").ifEmpty { pairs(FactoryChoices.newWorkItems) },
            duration = list(o, "duration").ifEmpty { pairs(FactoryChoices.duration) },
            strategyMatch = list(o, "strategyMatch").ifEmpty {
                FactoryChoices.match.map { FactoryOption(it.first, it.second, needsValue = it.first == "tag" || it.first == "title") }
            },
            maxParallel = o.array("maxParallel").mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.ifEmpty { FactoryChoices.parallel },
        )
    }
}

/** What `factory.config.get` answers: the factory (null for a new one), its rules, what stops it, and the choices. */
data class FactoryConfig(
    val draft: FactoryDraft,
    val status: String?,
    val running: Boolean,
    val problems: List<String>,
    val options: FactoryOptions,
) {
    companion object {
        fun parse(r: JsonObject): FactoryConfig {
            val f = r.obj("factory")
            return FactoryConfig(
                draft = FactoryDraft(f.text("id")?.takeIf { it.isNotBlank() }, f.string("name") ?: r.string("name").orEmpty(), r.obj("rules") ?: EmptyJson),
                status = f.string("status"),
                running = f.bool("running") == true,
                problems = parseProblems(r),
                options = FactoryOptions.parse(r.obj("options")),
            )
        }
    }
}

/**
 * A factory being edited: its id (null until saved), name and `rules`. Edits change only the keys they are about,
 * so rules the phone doesn't know go back to the Mac as they came.
 */
data class FactoryDraft(val id: String?, val name: String, val rules: JsonObject) {
    fun withName(name: String) = copy(name = name)

    private fun rule(key: String, value: JsonElement?) = copy(rules = rules.with(key, value))

    fun bool(key: String, default: Boolean): Boolean = rules.bool(key) ?: default

    /** The Mac's defaults for a new factory's switches. */
    private val boolDefaults = mapOf(
        "assignedToMe" to true, "unassigned" to false, "pbis" to true, "bugs" to true,
        "resumeStarted" to false, "openPRs" to true, "completePRs" to false,
    )
    fun setBool(key: String, on: Boolean) = rule(key, JsonPrimitive(on))

    fun choice(key: String, default: String): String = rules.string(key) ?: default
    fun setChoice(key: String, value: String) = rule(key, JsonPrimitive(value))

    val scopes: List<String> get() = rules.strings("scopes")
    fun withScopes(s: List<String>) = rule("scopes", JsonArray(s.distinct().map(::JsonPrimitive)))

    val assignedToMe get() = bool("assignedToMe", true)
    val unassigned get() = bool("unassigned", false)
    val pbis get() = bool("pbis", true)
    val bugs get() = bool("bugs", true)
    val resumeStarted get() = bool("resumeStarted", false)
    val openPRs get() = bool("openPRs", true)
    val completePRs get() = bool("completePRs", false)
    val sprint get() = choice("sprint", "current")
    val newWorkItems get() = choice("newWorkItems", "propose")
    val questions get() = choice("questions", "whenUnsure")
    val duration get() = choice("duration", "untilStopped")
    val defaultStrategy get() = choice("defaultStrategy", "")

    val maxParallel: Int get() = rules.int("maxParallel") ?: 3
    fun withMaxParallel(n: Int) = rule("maxParallel", JsonPrimitive(n.coerceAtLeast(1)))

    val hours: Double get() = rules.double("hours") ?: 4.0
    fun withHours(h: Double) = rule("hours", JsonPrimitive(h))

    /** When it stops for duration "until": ISO 8601 text (seconds since 2001, Swift's default, is read too). */
    val until: Instant? get() = FactoryDates.read(rules["until"])
    fun withUntil(t: Instant) = rule("until", FactoryDates.write(t, like = rules["until"]))

    val checkpoints: Set<String> get() = (rules["checkpoints"] as? JsonArray)?.let { a -> a.mapNotNull { (it as? JsonPrimitive)?.content }.toSet() }
        ?: setOf("missingKnowledge", "beforePR")

    fun withCheckpoint(id: String, on: Boolean): FactoryDraft {
        val next = if (on) checkpoints + id else checkpoints - id
        return rule("checkpoints", JsonArray(next.sorted().map(::JsonPrimitive)))
    }

    fun checkpointFits(id: String, requires: List<String> = emptyList()): Boolean = when {
        id == "newWorkItems" -> false
        requires.isNotEmpty() -> requires.all { bool(it, boolDefaults[it] ?: false) }
        id == "beforePR" -> openPRs
        id == "beforeComplete" -> openPRs && completePRs
        else -> true
    }

    /**
     * The checkpoints to offer: the Mac's (with what each requires), else the ones the phone knows, that fit the
     * delivery settings as they are now.
     */
    fun askableCheckpoints(fromMac: List<FactoryOption>): List<FactoryOption> {
        val all = fromMac.ifEmpty { FactoryChoices.checkpoints.map { FactoryOption(it.id, it.title, it.detail) } }
        return all.filter { checkpointFits(it.id, it.requires) }
    }

    /** Whether any moment to ask is on: "when unsure", a checkpoint that fits, or one of the user's own. */
    fun hasActiveAsk(fromMac: List<FactoryOption> = emptyList()): Boolean =
        questions == "whenUnsure" ||
            askableCheckpoints(fromMac).any { it.id in checkpoints } ||
            customAsks.any { it.on && it.title.isNotBlank() }

    val askTexts: Map<String, String> get() = rules.obj("askTexts")?.keys?.associateWith { rules.obj("askTexts").text(it).orEmpty() }.orEmpty()

    fun withAskText(id: String, text: String): FactoryDraft {
        val t = text.trim()
        val current = rules.obj("askTexts") ?: EmptyJson
        return rule("askTexts", current.with(id, if (t.isEmpty()) null else JsonPrimitive(t)))
    }

    data class StrategyRule(val raw: JsonObject) {
        val id get() = raw.text("id").orEmpty()
        val match get() = raw.string("match") ?: "bug"
        val value get() = raw.string("value").orEmpty()
        val strategy get() = raw.string("strategy").orEmpty()
        val needsValue get() = match == "tag" || match == "title"

        fun with(match: String? = null, value: String? = null, strategy: String? = null): StrategyRule {
            var o = raw
            match?.let { o = o.with("match", JsonPrimitive(it)) }
            value?.let { o = o.with("value", JsonPrimitive(it)) }
            strategy?.let { o = o.with("strategy", JsonPrimitive(it)) }
            return StrategyRule(o)
        }

        companion object {
            fun new(match: String = "bug") = StrategyRule(
                jsonOf("id" to newId(), "match" to match, "value" to "", "strategy" to ""),
            )
        }
    }

    val strategyRules: List<StrategyRule> get() = rules.objects("strategyRules").map(::StrategyRule)
    fun withStrategyRules(list: List<StrategyRule>) = rule("strategyRules", JsonArray(list.map { it.raw }))

    data class CustomAsk(val raw: JsonObject) {
        val id get() = raw.text("id").orEmpty()
        val title get() = raw.string("title").orEmpty()
        val instruction get() = raw.string("instruction").orEmpty()
        val on get() = raw.bool("on") ?: true

        fun with(title: String? = null, instruction: String? = null, on: Boolean? = null): CustomAsk {
            var o = raw
            title?.let { o = o.with("title", JsonPrimitive(it)) }
            instruction?.let { o = o.with("instruction", JsonPrimitive(it)) }
            on?.let { o = o.with("on", JsonPrimitive(it)) }
            return CustomAsk(o)
        }

        companion object {
            fun new(title: String) = CustomAsk(jsonOf("id" to newId(), "title" to title, "instruction" to "", "on" to true))
        }
    }

    /** Older Macs stored custom asks as plain titles; they come back as objects. */
    val customAsks: List<CustomAsk>
        get() = rules.array("customCheckpoints").mapNotNull {
            when (it) {
                is JsonObject -> CustomAsk(it)
                is JsonPrimitive -> if (it is JsonNull) null else CustomAsk.new(it.content)
                else -> null
            }
        }

    fun withCustomAsks(list: List<CustomAsk>) = rule("customCheckpoints", JsonArray(list.map { it.raw }))

    /** A copy to save as a new factory. */
    fun duplicate(newName: String): FactoryDraft = FactoryDraft(null, newName, rules)

    /** What `factory.config.save` gets. */
    fun forSave(): JsonObject = jsonOf("id" to id, "name" to name.trim(), "rules" to rules)

    /** Problems the phone can see before asking the Mac (the Mac checks the same and more on save). */
    fun localProblems(checkpointOptions: List<FactoryOption> = emptyList()): List<String> = buildList {
        if (name.isBlank()) add("Give the factory a name.")
        if (scopes.isEmpty()) add("Choose at least one project.")
        if (questions != "never" && !hasActiveAsk(checkpointOptions)) add("Tick at least one moment to ask, or turn asking off.")
        if (!pbis && !bugs) add("Pick PBIs, bugs or both.")
        if (!assignedToMe && !unassigned) add("Pick items assigned to you, unassigned ones or both.")
        if (duration == "hours" && hours <= 0) add("The number of hours must be more than 0.")
        strategyRules.forEachIndexed { i, r -> if (r.needsValue && r.value.isBlank()) add("Rule ${i + 1} needs a value.") }
        customAsks.forEachIndexed { i, a -> if (a.title.isBlank()) add("Your ask ${i + 1} needs a title.") }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().uppercase()
    }
}

/** What the Mac said about a factory: blocking problems and warnings, from `problems[]` (strings or `{message}`). */
fun parseProblems(result: JsonObject?, key: String = "problems"): List<String> = result.array(key).mapNotNull {
    when (it) {
        is JsonPrimitive -> it.content.takeIf { s -> s.isNotBlank() && it !is JsonNull }
        is JsonObject -> it.string("message") ?: it.string("text") ?: it.string("title")
        else -> null
    }
}

object FactoryDates {
    /** Seconds between 1970 and 2001-01-01, Swift's reference date. */
    const val REFERENCE_OFFSET = 978_307_200L

    fun read(v: JsonElement?): Instant? {
        val p = v as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return runCatching { Instant.parse(p.content) }.getOrNull()
        val n = p.content.toDoubleOrNull() ?: return null
        return if (n < 1.5e9) Instant.ofEpochMilli(((n + REFERENCE_OFFSET) * 1000).toLong()) else Instant.ofEpochMilli((n * 1000).toLong())
    }

    /** Writes [t] as ISO 8601, or as seconds the way an older Mac sent the old value. */
    fun write(t: Instant, like: JsonElement?): JsonElement {
        val p = like as? JsonPrimitive
        if (p == null || p is JsonNull || p.isString) return JsonPrimitive(t.toString())
        val n = p.content.toDoubleOrNull() ?: return JsonPrimitive(t.toString())
        return JsonPrimitive(if (n >= 1.5e9) t.epochSecond.toDouble() else t.epochSecond.toDouble() - REFERENCE_OFFSET)
    }
}

/** A copy of this object with [key] set, or removed when [value] is null. Keeps the other keys in their order. */
fun JsonObject.with(key: String, value: JsonElement?): JsonObject {
    val m = LinkedHashMap<String, JsonElement>(this)
    if (value == null) m.remove(key) else m[key] = value
    return JsonObject(m)
}
