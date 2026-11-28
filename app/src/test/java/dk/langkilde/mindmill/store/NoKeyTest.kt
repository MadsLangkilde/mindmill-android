package dk.langkilde.mindmill.store

import dk.langkilde.mindmill.remote.objects
import dk.langkilde.mindmill.remote.parseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class MemoryKv : KeyValueStore {
    val map = linkedMapOf<String, String>()
    override fun get(name: String) = map[name]
    override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    override fun names() = map.keys.toSet()
}

class XorCipher : BlobCipher {
    override fun encrypt(plain: ByteArray): String = Base64.getEncoder().encodeToString(plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
    override fun decrypt(sealed: String): ByteArray = Base64.getDecoder().decode(sealed).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
}

/** The phone never keeps an API key: what it persists is the paired Macs (with their device tokens) and plain settings. */
class NoKeyTest {
    private val keyLike = Regex("(?i)(api[_-]?key|apikey|secret|password|openai|anthropic|bearer|\\bkey\\b|key$)")

    private fun assertNoKeyLike(names: Collection<String>) {
        names.forEach { if (keyLike.containsMatchIn(it)) fail("key-like field persisted: $it") }
    }

    @Test
    fun persistedSchemaHasNoKeyLikeField() {
        val kv = MemoryKv()
        val cipher = XorCipher()
        val store = PairingStore(kv, cipher)
        val mac = StoredPairing("Mac", listOf("192.168.1.5", "mac.local"), 7878, "ab".repeat(32), "device-token", "mac.local", "proj", 7879)
        store.savePairings(Pairings().paired(mac))
        store.saveSettings(AppSettings(keylessOnly = true))

        assertEquals(PairingStore.ENTRIES, kv.names())
        assertNoKeyLike(kv.names())

        val all = parseJsonObject(String(cipher.decrypt(kv.map[PairingStore.ENTRY_PAIRINGS]!!)))!!
        assertEquals(Pairings.FIELDS, all.keys)
        assertNoKeyLike(all.keys)
        val pairing = all.objects(Pairings.FIELD_MACS).single()
        assertEquals(StoredPairing.FIELDS, pairing.keys)
        assertNoKeyLike(pairing.keys)

        val settings = parseJsonObject(kv.map[PairingStore.ENTRY_SETTINGS]!!)!!
        assertEquals(AppSettings.FIELDS, settings.keys)
        assertNoKeyLike(settings.keys)

        assertNoKeyLike(StoredPairing::class.java.declaredFields.map { it.name })
        assertNoKeyLike(Pairings::class.java.declaredFields.map { it.name })
        assertNoKeyLike(AppSettings::class.java.declaredFields.map { it.name })
    }

    @Test
    fun theTokenIsOnlyStoredEncrypted() {
        val kv = MemoryKv()
        PairingStore(kv, XorCipher()).savePairings(Pairings().paired(StoredPairing("Mac", listOf("h"), 7878, "ab".repeat(32), "device-token")))
        assertTrue(kv.map.values.none { it.contains("device-token") })
    }

    @Test
    fun pairingRoundTripsAndPrefersTheLastHost() {
        val store = PairingStore(MemoryKv(), XorCipher())
        assertNull(store.loadPairings().active)
        val p = StoredPairing("Mac", listOf("a", "b", "c"), 7878, "ab".repeat(32), "tok", "c")
        store.savePairings(Pairings().paired(p))
        val back = store.loadPairings().active
        assertNotNull(back)
        assertEquals(p, back)
        assertEquals(listOf("c", "a", "b"), back!!.orderedHosts)
        store.savePairings(store.loadPairings().removed(p.fingerprint))
        assertNull(store.loadPairings().active)
    }
}
