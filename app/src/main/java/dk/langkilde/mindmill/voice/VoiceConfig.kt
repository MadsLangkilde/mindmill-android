package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.double
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.string
import kotlinx.serialization.json.JsonObject
import java.util.Locale

enum class VoiceEngine(val wire: String) {
    KEYLESS("keyless"), API("api");

    companion object {
        fun from(s: String?): VoiceEngine? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * What `voice.config` says, reduced to the fields the phone uses. Any other field the Mac might send is dropped on
 * parsing: the phone has nowhere to keep a key and never sees one.
 */
data class VoiceConfig(
    val engine: VoiceEngine = VoiceEngine.KEYLESS,
    val provider: String? = null,
    val providerName: String? = null,
    val model: String? = null,
    val rate: Float = 1f,
    val voice: String? = null,
    val confirmsRisky: Boolean = true,
    val language: String = "auto",
) {
    fun locale(device: Locale = Locale.getDefault()): Locale =
        if (language.isBlank() || language == "auto") device else Locale.forLanguageTag(language)

    fun toJson(): JsonObject = jsonOf(
        "engine" to engine.wire, "provider" to provider, "providerName" to providerName, "model" to model,
        "rate" to rate, "voice" to voice, "confirmsRisky" to confirmsRisky, "language" to language,
    )

    companion object {
        val FIELDS = setOf("engine", "provider", "providerName", "model", "rate", "voice", "confirmsRisky", "language")

        fun parse(o: JsonObject?) = VoiceConfig(
            engine = VoiceEngine.from(o.string("engine")) ?: VoiceEngine.KEYLESS,
            provider = o.string("provider"),
            providerName = o.string("providerName"),
            model = o.string("model"),
            rate = (o.double("rate") ?: 1.0).toFloat().coerceIn(0.5f, 2f),
            voice = o.string("voice"),
            confirmsRisky = o.bool("confirmsRisky") ?: true,
            language = o.string("language") ?: "auto",
        )
    }
}
