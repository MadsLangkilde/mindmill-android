package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val RemoteJson = Json { ignoreUnknownKeys = true; isLenient = true }

val EmptyJson = JsonObject(emptyMap())

fun JsonObject?.text(key: String): String? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

fun JsonObject?.string(key: String): String? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

fun JsonObject?.int(key: String): Int? = long(key)?.toInt()

fun JsonObject?.long(key: String): Long? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
}

fun JsonObject?.double(key: String): Double? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.doubleOrNull ?: p.content.toDoubleOrNull()
}

fun JsonObject?.bool(key: String): Boolean? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull
}

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

fun JsonObject?.array(key: String): List<JsonElement> = (this?.get(key) as? JsonArray) ?: emptyList()

fun JsonObject?.objects(key: String): List<JsonObject> = array(key).filterIsInstance<JsonObject>()

fun JsonObject?.strings(key: String): List<String> = when (val v = this?.get(key)) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
    is JsonPrimitive -> if (v is JsonNull || v.content.isEmpty()) emptyList() else listOf(v.content)
    else -> emptyList()
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to v.toJsonElement() })

fun parseJsonObject(text: String): JsonObject? =
    runCatching { RemoteJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
