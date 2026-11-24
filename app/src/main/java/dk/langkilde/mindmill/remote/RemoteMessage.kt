package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** One JSON text frame of the remote protocol (docs/remote-api.md, Messages). */
sealed interface RemoteMessage {
    data class Request(val id: String, val method: String, val params: JsonObject = EmptyJson) : RemoteMessage
    data class Response(val id: String, val result: JsonElement) : RemoteMessage
    data class Failure(val id: String, val code: String, val message: String) : RemoteMessage
    data class Event(val name: String, val data: JsonObject) : RemoteMessage

    fun encode(): String = when (this) {
        is Request -> jsonOf("id" to id, "method" to method, "params" to params)
        is Response -> JsonObject(mapOf("id" to id.toJsonElement(), "result" to result))
        is Failure -> jsonOf("id" to id, "error" to jsonOf("code" to code, "message" to message))
        is Event -> jsonOf("event" to name, "data" to data)
    }.toString()

    companion object {
        fun decode(text: String): RemoteMessage? {
            val o = parseJsonObject(text) ?: return null
            o.string("event")?.let { return Event(it, o.obj("data") ?: EmptyJson) }
            val id = o.text("id") ?: return null
            o.string("method")?.let { return Request(id, it, o.obj("params") ?: EmptyJson) }
            o.obj("error")?.let { e ->
                return Failure(id, e.string("code") ?: "error", e.string("message") ?: "")
            }
            return Response(id, o["result"] ?: JsonNull)
        }
    }
}

object ErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val BAD_CODE = "bad_code"
    const val BAD_PARAMS = "bad_params"
    const val NOT_FOUND = "not_found"
    const val UNKNOWN_METHOD = "unknown_method"
    const val FAILED = "failed"
    const val TIMEOUT = "timeout"
    const val OFFLINE = "offline"
}

class RemoteException(val code: String, message: String) : Exception(message) {
    val isUnknownMethod get() = code == ErrorCodes.UNKNOWN_METHOD
    val isUnauthorized get() = code == ErrorCodes.UNAUTHORIZED
    val isOffline get() = code == ErrorCodes.OFFLINE || code == ErrorCodes.TIMEOUT
}

/** A sentence for the user about what went wrong. */
fun Throwable.userMessage(): String = when {
    this is RemoteException && isUnknownMethod -> "The Mac's Mindmill doesn't know this yet. Update Mindmill on the Mac."
    this is RemoteException && code == ErrorCodes.OFFLINE -> message?.takeIf { it.isNotBlank() } ?: OFFLINE_MESSAGE
    this is RemoteException && code == ErrorCodes.TIMEOUT -> "The Mac didn't answer in time. Try again."
    this is RemoteException -> message?.takeIf { it.isNotBlank() } ?: "The Mac said no ($code)."
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
