package dk.langkilde.mindmill.store

import dk.langkilde.mindmill.remote.HelloInfo
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.parseJsonObject
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.strings
import kotlinx.serialization.json.JsonObject

/**
 * A Mac this phone is paired with, and what the phone keeps for it alone ([scope]). The device token is the only
 * secret, and it only opens this one Mac.
 */
data class StoredPairing(
    val macName: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val lastHost: String? = null,
    /** The phone's own current scope on this Mac; the Mac's current one is separate. */
    val scope: String? = null,
    /** The port of the Mac's launcher, which starts Mindmill when asked, as the last `hello` said; null when it is off. */
    val launcherPort: Int? = null,
) {
    fun toJson(): JsonObject = jsonOf(
        FIELD_NAME to macName, FIELD_HOSTS to hosts, FIELD_PORT to port,
        FIELD_FINGERPRINT to fingerprint, FIELD_TOKEN to token, FIELD_LAST_HOST to lastHost, FIELD_SCOPE to scope,
        FIELD_LAUNCHER_PORT to launcherPort,
    )

    /** Hosts to try, the one that answered last time first. */
    val orderedHosts: List<String> get() = listOfNotNull(lastHost).plus(hosts).distinct()

    /**
     * What a `hello` from this Mac at [host] changes: where it answered, its [name], its launcher, and the phone's scope,
     * forgotten when the Mac no longer has it (another instance opened there, or the scope was deleted).
     */
    fun afterHello(host: String, name: String?, info: HelloInfo): StoredPairing = copy(
        lastHost = host,
        macName = name?.takeIf { it.isNotBlank() } ?: macName,
        launcherPort = info.launcherPort,
        scope = scope?.takeIf { s -> info.scopes.isEmpty() || info.scopes.any { it.id == s } },
    )

    fun isMac(fingerprint: String) = this.fingerprint.equals(fingerprint, ignoreCase = true)

    companion object {
        const val FIELD_NAME = "name"
        const val FIELD_HOSTS = "hosts"
        const val FIELD_PORT = "port"
        const val FIELD_FINGERPRINT = "fingerprint"
        const val FIELD_TOKEN = "token"
        const val FIELD_LAST_HOST = "lastHost"
        const val FIELD_SCOPE = "scope"
        const val FIELD_LAUNCHER_PORT = "launcherPort"
        val FIELDS = setOf(FIELD_NAME, FIELD_HOSTS, FIELD_PORT, FIELD_FINGERPRINT, FIELD_TOKEN, FIELD_LAST_HOST, FIELD_SCOPE, FIELD_LAUNCHER_PORT)

        fun fromJson(o: JsonObject): StoredPairing? {
            val token = o.string(FIELD_TOKEN)?.takeIf { it.isNotEmpty() } ?: return null
            val fp = o.string(FIELD_FINGERPRINT)?.takeIf { it.isNotEmpty() } ?: return null
            val hosts = o.strings(FIELD_HOSTS).takeIf { it.isNotEmpty() } ?: return null
            return StoredPairing(
                o.string(FIELD_NAME) ?: "Mac", hosts, o.int(FIELD_PORT) ?: 7878, fp, token, o.string(FIELD_LAST_HOST), o.string(FIELD_SCOPE),
                o.int(FIELD_LAUNCHER_PORT)?.takeIf { it in 1..65535 },
            )
        }
    }
}

/**
 * Every Mac this phone is paired with, in the order they were paired, and the one it talks to. A Mac is known by its
 * certificate fingerprint: pairing the same Mac again replaces its entry. Pure, so the rules are tested without a
 * phone.
 */
data class Pairings(val macs: List<StoredPairing> = emptyList(), val activeFingerprint: String? = null) {
    /** The Mac the phone talks to: the chosen one, else the first. */
    val active: StoredPairing? get() = activeFingerprint?.let(::find) ?: macs.firstOrNull()

    val isEmpty get() = macs.isEmpty()

    fun find(fingerprint: String): StoredPairing? = macs.firstOrNull { it.isMac(fingerprint) }

    fun isActive(p: StoredPairing): Boolean = active?.isMac(p.fingerprint) == true

    /** [p] just paired: it replaces the same Mac's entry (keeping the phone's scope there) or is added; it becomes the active one. */
    fun paired(p: StoredPairing): Pairings {
        val old = find(p.fingerprint)
        val entry = if (old != null && p.scope == null) p.copy(scope = old.scope) else p
        val list = if (old != null) macs.map { if (it === old) entry else it } else macs + entry
        return Pairings(list, entry.fingerprint)
    }

    /** Talk to [fingerprint] from now on; an unknown Mac changes nothing. */
    fun activate(fingerprint: String): Pairings = find(fingerprint)?.let { copy(activeFingerprint = it.fingerprint) } ?: this

    /** Forgets [fingerprint]. When it was the active one, the next Mac in the list (or the one before it) takes over. */
    fun removed(fingerprint: String): Pairings {
        val at = macs.indexOfFirst { it.isMac(fingerprint) }
        if (at < 0) return this
        val wasActive = active?.isMac(fingerprint) == true
        val list = macs.filterIndexed { i, _ -> i != at }
        val next = if (wasActive) list.getOrNull(at.coerceAtMost(list.lastIndex))?.fingerprint else active?.fingerprint
        return Pairings(list, next)
    }

    fun updated(fingerprint: String, change: (StoredPairing) -> StoredPairing): Pairings =
        copy(macs = macs.map { if (it.isMac(fingerprint)) change(it) else it })

    fun toJson(): JsonObject = jsonOf(FIELD_ACTIVE to active?.fingerprint, FIELD_MACS to macs.map { it.toJson() })

    companion object {
        const val FIELD_ACTIVE = "active"
        const val FIELD_MACS = "macs"
        val FIELDS = setOf(FIELD_ACTIVE, FIELD_MACS)

        fun fromJson(o: JsonObject?): Pairings {
            val macs = o.objects(FIELD_MACS).mapNotNull(StoredPairing::fromJson).distinctBy { it.fingerprint.lowercase() }
            return Pairings(macs, o.string(FIELD_ACTIVE))
        }
    }
}

/** Plain preferences for the phone as a whole: nothing secret, nothing about one Mac. */
data class AppSettings(
    val keylessOnly: Boolean = false,
    val routeToHeadset: Boolean = true,
    val meetingAutoScreens: Boolean = false,
    /** Plan or Build, as last chosen at the top. */
    val mode: String = "plan",
    /** Take the app's colours from the wallpaper (Android 12 and later) instead of Mindmill's own. */
    val wallpaperColours: Boolean = false,
    /** Picking a scope on the phone also makes it the Mac's current one (`project.select`). */
    val alsoSwitchMac: Boolean = false,
) {
    fun toJson(): JsonObject = jsonOf(
        FIELD_KEYLESS_ONLY to keylessOnly,
        FIELD_ROUTE_HEADSET to routeToHeadset, FIELD_MEETING_SCREENS to meetingAutoScreens, FIELD_MODE to mode,
        FIELD_WALLPAPER to wallpaperColours, FIELD_ALSO_SWITCH_MAC to alsoSwitchMac,
    )

    companion object {
        const val FIELD_KEYLESS_ONLY = "keylessOnly"
        const val FIELD_ROUTE_HEADSET = "routeToHeadset"
        const val FIELD_MEETING_SCREENS = "meetingAutoScreens"
        const val FIELD_MODE = "mode"
        const val FIELD_WALLPAPER = "wallpaperColours"
        const val FIELD_ALSO_SWITCH_MAC = "alsoSwitchMac"
        val FIELDS = setOf(FIELD_KEYLESS_ONLY, FIELD_ROUTE_HEADSET, FIELD_MEETING_SCREENS, FIELD_MODE, FIELD_WALLPAPER, FIELD_ALSO_SWITCH_MAC)

        /** Before several Macs, the one scope lived here; it moves to the migrated Mac. */
        const val LEGACY_FIELD_SCOPE = "scope"

        fun fromJson(o: JsonObject?) = AppSettings(
            keylessOnly = o.bool(FIELD_KEYLESS_ONLY) ?: false,
            routeToHeadset = o.bool(FIELD_ROUTE_HEADSET) ?: true,
            meetingAutoScreens = o.bool(FIELD_MEETING_SCREENS) ?: false,
            mode = o.string(FIELD_MODE) ?: "plan",
            wallpaperColours = o.bool(FIELD_WALLPAPER) ?: false,
            alsoSwitchMac = o.bool(FIELD_ALSO_SWITCH_MAC) ?: false,
        )
    }
}

interface KeyValueStore {
    fun get(name: String): String?
    fun put(name: String, value: String?)
    fun names(): Set<String>
}

interface BlobCipher {
    fun encrypt(plain: ByteArray): String
    fun decrypt(sealed: String): ByteArray?
}

/** Everything the app persists: the paired Macs (encrypted with a Keystore key) and plain settings. Nothing else. */
class PairingStore(private val kv: KeyValueStore, private val cipher: BlobCipher) {
    /** The paired Macs. The first time after an update from one Mac, that Mac and its scope move over by themselves. */
    fun loadPairings(): Pairings {
        kv.get(ENTRY_PAIRINGS)?.let { sealed ->
            val plain = cipher.decrypt(sealed) ?: return Pairings()
            return Pairings.fromJson(parseJsonObject(String(plain, Charsets.UTF_8)))
        }
        return migrateSinglePairing() ?: Pairings()
    }

    fun savePairings(p: Pairings) = kv.put(ENTRY_PAIRINGS, cipher.encrypt(p.toJson().toString().toByteArray(Charsets.UTF_8)))

    private fun migrateSinglePairing(): Pairings? {
        val sealed = kv.get(LEGACY_ENTRY_PAIRING) ?: return null
        val old = cipher.decrypt(sealed)?.let { parseJsonObject(String(it, Charsets.UTF_8)) }?.let(StoredPairing::fromJson) ?: return null
        val settings = kv.get(ENTRY_SETTINGS)?.let(::parseJsonObject)
        val scope = old.scope ?: settings.string(AppSettings.LEGACY_FIELD_SCOPE)
        val migrated = Pairings(listOf(old.copy(scope = scope)), old.fingerprint)
        savePairings(migrated)
        kv.put(LEGACY_ENTRY_PAIRING, null)
        if (settings != null) saveSettings(AppSettings.fromJson(settings))
        return migrated
    }

    fun loadSettings(): AppSettings = AppSettings.fromJson(kv.get(ENTRY_SETTINGS)?.let(::parseJsonObject))

    fun saveSettings(s: AppSettings) = kv.put(ENTRY_SETTINGS, s.toJson().toString())

    companion object {
        const val ENTRY_PAIRINGS = "pairings"
        const val ENTRY_SETTINGS = "settings"
        /** One Mac, as the phone kept it before it could pair with several. */
        const val LEGACY_ENTRY_PAIRING = "pairing"
        val ENTRIES = setOf(ENTRY_PAIRINGS, ENTRY_SETTINGS)
    }
}
