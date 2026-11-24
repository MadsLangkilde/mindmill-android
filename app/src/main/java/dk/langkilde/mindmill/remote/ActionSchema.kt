package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One parameter of an action, from the JSON schema in `actions.list`. */
data class ActionParamSpec(
    val name: String,
    val type: Type,
    val about: String,
    val required: Boolean,
    val choices: List<String>,
) {
    enum class Type { STRING, INTEGER, NUMBER, BOOLEAN, STRINGS }

    /** "max_parallel" → "Max parallel". */
    val label: String get() = humanize(name)

    companion object {
        fun parse(name: String, schema: JsonObject?, required: Boolean): ActionParamSpec {
            val type = when (schema.string("type")) {
                "integer" -> Type.INTEGER
                "number" -> Type.NUMBER
                "boolean" -> Type.BOOLEAN
                "array" -> Type.STRINGS
                else -> Type.STRING
            }
            val choices = (schema?.get("enum") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            return ActionParamSpec(name, type, schema.string("description").orEmpty(), required, choices)
        }
    }
}

/** One action of `actions.list`: what voice, the Mac's buttons and the phone can all run. */
data class ActionInfo(
    val name: String,
    val about: String,
    val risk: String,
    val area: String,
    val params: List<ActionParamSpec>,
) {
    /** "factory.start" → "Factory start". */
    val title: String get() = humanize(name.replace('.', ' '))

    val areaTitle: String get() = areaTitle(area)

    val isRisky get() = risk == "destructive" || risk == "external"

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(name, title, about, areaTitle).any { it.lowercase().contains(q) }
    }

    companion object {
        fun parse(o: JsonObject): ActionInfo? {
            val name = o.string("name")?.takeIf { it.isNotBlank() } ?: return null
            val input = o.obj("input")
            val required = input.strings("required").toSet()
            val props = input.obj("properties")
            val params = props?.entries.orEmpty().map { (k, v) -> ActionParamSpec.parse(k, v as? JsonObject, k in required) }
                .sortedWith(compareBy({ !it.required }, { it.name == "scope" }))
            return ActionInfo(name, o.string("about").orEmpty(), o.string("risk") ?: "read", o.string("area") ?: areaFor(name), params)
        }

        fun list(result: JsonObject): List<ActionInfo> = result.objects("actions").mapNotNull(::parse)

        /** The Mac's `areas[]`: the area titles in display order. */
        fun areaOrder(result: JsonObject): List<String> = result.strings("areas")

        /** Grouped by area: in the Mac's [order], then any others alphabetically, with "general" last. */
        fun grouped(actions: List<ActionInfo>, order: List<String> = emptyList()): List<Pair<String, List<ActionInfo>>> =
            actions.groupBy { it.area }.entries
                .sortedWith(
                    compareBy<Map.Entry<String, List<ActionInfo>>>(
                        { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                        { it.key == "general" },
                        { areaTitle(it.key).lowercase() },
                    ),
                )
                .map { (k, v) -> k to v.sortedBy { it.name } }

        /** For a Mac that doesn't send `area` yet: the area from the name's first part. */
        fun areaFor(name: String): String = when (name.substringBefore('.')) {
            "overview", "projects" -> "general"
            "knowledge", "entry", "facts", "fact" -> "knowledge"
            "meetings", "meeting", "calendar" -> "meetings"
            "followups", "followup" -> "followups"
            "backlog", "workitem" -> "backlog"
            "factories", "factory" -> "factories"
            "sessions", "session" -> "sessions"
            "test", "review" -> "test"
            "prs", "pr" -> "prs"
            else -> name.substringBefore('.')
        }

        fun areaTitle(area: String): String = if (area.any { it.isUpperCase() }) area else when (area.lowercase()) {
            "general" -> "General"
            "knowledge" -> "Knowledge"
            "followups" -> "Follow-ups"
            "backlog" -> "Backlog"
            "prs", "pullrequests", "pull_requests" -> "Pull requests"
            "test", "testing" -> "Test"
            else -> humanize(area)
        }
    }
}

/**
 * A form for an action's parameters: every value is kept as the text the user typed (booleans as "true"/"false",
 * lists one item per line or comma) and turned into typed JSON only when it runs.
 */
object ActionForm {
    fun initial(params: List<ActionParamSpec>, defaults: Map<String, String> = emptyMap()): Map<String, String> =
        params.associate { p ->
            p.name to (defaults[p.name] ?: if (p.type == ActionParamSpec.Type.BOOLEAN && p.required) "false" else "")
        }

    /** Problems by parameter name; empty when it can run. */
    fun problems(params: List<ActionParamSpec>, values: Map<String, String>): Map<String, String> = buildMap {
        for (p in params) {
            val v = values[p.name].orEmpty().trim()
            if (v.isEmpty()) {
                if (p.required && p.type != ActionParamSpec.Type.BOOLEAN) put(p.name, "Needed")
                continue
            }
            when (p.type) {
                ActionParamSpec.Type.INTEGER -> if (v.toLongOrNull() == null) put(p.name, "A whole number")
                ActionParamSpec.Type.NUMBER -> if (v.replace(',', '.').toDoubleOrNull() == null) put(p.name, "A number")
                ActionParamSpec.Type.STRING -> if (p.choices.isNotEmpty() && v !in p.choices) put(p.name, "One of ${p.choices.joinToString(", ")}")
                else -> Unit
            }
        }
    }

    fun toParams(params: List<ActionParamSpec>, values: Map<String, String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (p in params) {
            val v = values[p.name].orEmpty().trim()
            if (v.isEmpty()) continue
            out[p.name] = when (p.type) {
                ActionParamSpec.Type.STRING -> JsonPrimitive(v)
                ActionParamSpec.Type.INTEGER -> JsonPrimitive(v.toLongOrNull() ?: continue)
                ActionParamSpec.Type.NUMBER -> JsonPrimitive(v.replace(',', '.').toDoubleOrNull() ?: continue)
                ActionParamSpec.Type.BOOLEAN -> JsonPrimitive(v == "true")
                ActionParamSpec.Type.STRINGS -> JsonArray(splitList(v).map(::JsonPrimitive))
            }
        }
        return JsonObject(out)
    }

    fun splitList(v: String): List<String> = v.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** How to show an action's `data` (or any JSON from the Mac) on a phone. */
sealed interface DataBlock {
    data class Text(val label: String?, val text: String) : DataBlock
    data class Fields(val label: String?, val fields: List<Pair<String, String>>) : DataBlock
    data class Rows(val label: String?, val rows: List<DataRow>) : DataBlock
}

data class DataRow(
    val title: String,
    val subtitle: String,
    val details: List<Pair<String, String>>,
    val link: Link?,
    val raw: JsonObject,
) {
    sealed interface Link {
        data class Entry(val id: String) : Link
        data class Session(val id: String, val title: String) : Link
        data class WorkItem(val id: Int) : Link
    }
}

object DataView {
    private val titleKeys = listOf("title", "name", "text", "question", "id")
    private val subtitleKeys = listOf("summary", "about", "state", "status", "speech", "detail", "date")
    private val longTextKeys = setOf("body", "text", "output", "notes", "summary", "description")

    fun blocks(data: JsonElement?): List<DataBlock> = when (data) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> listOf(DataBlock.Text(null, data.content))
        is JsonArray -> listOfNotNull(arrayBlock(null, data))
        is JsonObject -> objectBlocks(data)
    }

    private fun objectBlocks(o: JsonObject): List<DataBlock> {
        val out = mutableListOf<DataBlock>()
        val fields = mutableListOf<Pair<String, String>>()
        for ((k, v) in o) {
            when {
                v is JsonArray -> arrayBlock(humanize(k), v)?.let(out::add)
                v is JsonObject -> {
                    val inner = scalars(v)
                    if (inner.isNotEmpty()) out += DataBlock.Fields(humanize(k), inner)
                }
                v is JsonPrimitive && v !is JsonNull && v.content.isNotBlank() -> {
                    if (k in longTextKeys && (v.content.length > 80 || '\n' in v.content)) out += DataBlock.Text(humanize(k), v.content)
                    else fields += humanize(k) to v.content
                }
            }
        }
        if (fields.isNotEmpty()) out.add(0, DataBlock.Fields(null, fields))
        return out
    }

    private fun arrayBlock(label: String?, a: JsonArray): DataBlock? {
        if (a.isEmpty()) return null
        val objects = a.filterIsInstance<JsonObject>()
        if (objects.size == a.size) return DataBlock.Rows(label, objects.map(::row))
        val text = a.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
        return DataBlock.Text(label, text.joinToString("\n") { "• $it" })
    }

    private fun scalars(o: JsonObject): List<Pair<String, String>> = o.entries.mapNotNull { (k, v) ->
        when (v) {
            is JsonPrimitive -> if (v is JsonNull || v.content.isBlank()) null else humanize(k) to v.content
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.takeIf { it.isNotEmpty() }?.let { humanize(k) to it.joinToString(", ") }
            else -> null
        }
    }

    fun row(o: JsonObject): DataRow {
        val titleKey = titleKeys.firstOrNull { !o.text(it).isNullOrBlank() }
        var title = titleKey?.let { o.text(it) }.orEmpty()
        val number = o.int("id")?.takeIf { o["id"] is JsonPrimitive && (o["id"] as JsonPrimitive).isString.not() }
        if (number != null && titleKey != "id" && (o.has("state") || o.has("type"))) title = "#$number $title"
        val subtitleKey = subtitleKeys.firstOrNull { it != titleKey && !o.text(it).isNullOrBlank() }
        val subtitle = subtitleKey?.let { o.text(it) }.orEmpty()
        val shown = setOfNotNull(titleKey, subtitleKey, "id")
        val details = o.entries.filter { it.key !in shown }.mapNotNull { (k, v) ->
            when (v) {
                is JsonPrimitive -> if (v is JsonNull || v.content.isBlank()) null else humanize(k) to v.content
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.takeIf { it.isNotEmpty() }?.let { humanize(k) to it.joinToString(", ") }
                is JsonObject -> scalars(v).takeIf { it.isNotEmpty() }?.let { humanize(k) to it.joinToString(" · ") { (a, b) -> "$a: $b" } }
            }
        }.take(8)
        return DataRow(title.ifBlank { "Item" }, subtitle, details, link(o), o)
    }

    private fun JsonObject.has(k: String) = !text(k).isNullOrBlank()

    fun link(o: JsonObject): DataRow.Link? {
        val id = o.text("id") ?: return null
        return when {
            o.has("section") -> DataRow.Link.Entry(id)
            o.has("space") -> DataRow.Link.Session(id, o.string("title") ?: "Session")
            (o.has("state") || o.has("type")) && id.toIntOrNull() != null -> DataRow.Link.WorkItem(id.toInt())
            else -> null
        }
    }
}

/** "max_parallel" or "maxParallel" → "Max parallel". */
fun humanize(key: String): String {
    val spaced = key.replace('_', ' ').replace('-', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .trim().lowercase()
    return spaced.replaceFirstChar { it.uppercase() }
        .replace(Regex("\\bprs\\b", RegexOption.IGNORE_CASE), "PRs")
        .replace(Regex("\\bpr\\b", RegexOption.IGNORE_CASE), "PR")
        .replace(Regex("\\bpbis\\b", RegexOption.IGNORE_CASE), "PBIs")
}
