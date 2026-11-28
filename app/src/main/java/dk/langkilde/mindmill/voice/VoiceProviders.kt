package dk.langkilde.mindmill.voice

import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import dk.langkilde.mindmill.remote.text
import kotlinx.serialization.json.JsonObject

/**
 * One voice API the Mac knows, from `voice.providers`. [hasKey] only says whether the Mac holds a key: the key itself
 * never comes to the phone. [available] ones work end to end; the others are listed for later.
 */
data class VoiceProvider(
    val id: String,
    val name: String,
    val available: Boolean,
    val hasKey: Boolean,
    val keyPage: String? = null,
    val models: List<String> = emptyList(),
    val model: String? = null,
    val voices: List<String> = emptyList(),
    val voice: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): VoiceProvider? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return VoiceProvider(
                id = id, name = o.string("name")?.takeIf { it.isNotBlank() } ?: id,
                available = o.bool("available") == true, hasKey = o.bool("hasKey") == true,
                keyPage = o.string("keyPage")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                models = o.strings("models"), model = o.string("model")?.takeIf { it.isNotBlank() },
                voices = o.strings("voices"), voice = o.string("voice")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** What `voice.providers` answers; [supported] is false for a Mac older than it. */
data class VoiceProviders(
    val providers: List<VoiceProvider>,
    val active: String? = null,
    val engine: VoiceEngine = VoiceEngine.KEYLESS,
    val supported: Boolean = true,
) {
    val available: List<VoiceProvider> get() = providers.filter { it.available }
    val later: List<VoiceProvider> get() = providers.filterNot { it.available }

    /** The provider voice goes through now; null while the phone's own voice is used. */
    val inUse: VoiceProvider? get() = if (engine == VoiceEngine.API) providers.firstOrNull { it.id == active } else null

    fun isInUse(p: VoiceProvider) = inUse?.id == p.id

    companion object {
        val MISSING = VoiceProviders(emptyList(), supported = false)

        fun parse(o: JsonObject) = VoiceProviders(
            providers = o.objects("providers").mapNotNull(VoiceProvider::parse),
            active = o.string("active")?.takeIf { it.isNotBlank() },
            engine = VoiceEngine.from(o.string("engine")) ?: VoiceEngine.KEYLESS,
        )
    }
}

/** How a key check went (`voice.test`, `voice.key.set`), in a sentence from the Mac. */
data class KeyCheck(val ok: Boolean, val message: String)

/**
 * The Mac's voice API settings, changed from the phone. A key the user types goes out once in [saveKey] and is kept
 * nowhere here: the Mac keeps it in its Keychain and never sends it back.
 */
class VoiceApi(private val call: suspend (method: String, params: JsonObject) -> JsonObject) {
    /** `voice.providers`, or [VoiceProviders.MISSING] from a Mac older than it. */
    suspend fun providers(): VoiceProviders = try {
        VoiceProviders.parse(call("voice.providers", jsonOf()))
    } catch (e: RemoteException) {
        if (!e.isMissingOnMac) throw e
        VoiceProviders.MISSING
    }

    /** `voice.key.set`: the Mac keeps [key], checks it, and voice goes through [provider] from now on. */
    suspend fun saveKey(provider: String, key: String): KeyCheck {
        val r = call("voice.key.set", jsonOf("provider" to provider, "key" to key.trim(), "use" to true))
        val ok = r.bool("ok") == true
        val saved = r.bool("saved") != false
        return KeyCheck(ok, r.string("message") ?: when {
            !saved -> "The Mac didn't keep the key."
            ok -> "The Mac keeps the key, and it works."
            else -> "The Mac keeps the key, but it doesn't work."
        })
    }

    suspend fun removeKey(provider: String): Boolean = call("voice.key.remove", jsonOf("provider" to provider)).bool("removed") != false

    /** `voice.use`: [provider] null goes back to the phone's own voice; [model] and [voice] change only what they name. */
    suspend fun use(provider: String?, model: String? = null, voice: String? = null): VoiceConfig =
        VoiceConfig.parse(call("voice.use", jsonOf("provider" to provider, "model" to model, "voice" to voice)))

    suspend fun test(provider: String): KeyCheck {
        val r = call("voice.test", jsonOf("provider" to provider))
        val ok = r.bool("ok") == true
        return KeyCheck(ok, r.string("message") ?: if (ok) "The key works." else "The key doesn't work.")
    }
}
