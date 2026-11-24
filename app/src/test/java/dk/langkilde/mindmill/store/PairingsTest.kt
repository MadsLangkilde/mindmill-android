package dk.langkilde.mindmill.store

import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.parseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingsTest {
    private val studio = StoredPairing("Studio", listOf("192.168.1.5"), 7878, "aa".repeat(32), "tok-a", scope = "android")
    private val laptop = StoredPairing("MacBook", listOf("192.168.1.9"), 7878, "bb".repeat(32), "tok-b")
    private val mini = StoredPairing("Mini", listOf("100.64.0.3"), 7878, "cc".repeat(32), "tok-c")

    @Test
    fun pairingAnotherMacKeepsTheOthersAndMakesItActive() {
        val p = Pairings().paired(studio).paired(laptop)
        assertEquals(listOf(studio, laptop), p.macs)
        assertEquals(laptop, p.active)
        assertTrue(p.isActive(laptop))
        assertFalse(p.isActive(studio))
    }

    @Test
    fun pairingTheSameMacAgainReplacesItsEntryAndKeepsItsScope() {
        val again = studio.copy(token = "tok-new", hosts = listOf("10.0.0.2"), scope = null, macName = "Studio (new name)")
        val p = Pairings().paired(studio).paired(laptop).paired(again.copy(fingerprint = studio.fingerprint.uppercase()))
        assertEquals(2, p.macs.size)
        assertEquals("tok-new", p.macs[0].token)
        assertEquals("Studio (new name)", p.macs[0].macName)
        assertEquals("android", p.macs[0].scope)
        assertEquals("tok-new", p.active?.token)
    }

    @Test
    fun activatingSwitchesAndAnUnknownMacChangesNothing() {
        val p = Pairings().paired(studio).paired(laptop)
        assertEquals(studio, p.activate(studio.fingerprint).active)
        assertEquals(p, p.activate("ff".repeat(32)))
    }

    @Test
    fun removingTheActiveMacMovesToTheNextOneThenTheOneBefore() {
        val p = Pairings().paired(studio).paired(laptop).paired(mini).activate(laptop.fingerprint)
        val a = p.removed(laptop.fingerprint)
        assertEquals(listOf(studio, mini), a.macs)
        assertEquals(mini, a.active)
        val b = a.removed(mini.fingerprint)
        assertEquals(studio, b.active)
        val c = b.removed(studio.fingerprint)
        assertTrue(c.isEmpty)
        assertNull(c.active)
    }

    @Test
    fun removingAnotherMacKeepsTheActiveOne() {
        val p = Pairings().paired(studio).paired(laptop).activate(studio.fingerprint).removed(laptop.fingerprint)
        assertEquals(studio, p.active)
        assertEquals(listOf(studio), p.macs)
    }

    @Test
    fun theScopeIsKeptPerMac() {
        val p = Pairings().paired(studio).paired(laptop)
            .updated(laptop.fingerprint) { it.copy(scope = "billing") }
        assertEquals("android", p.find(studio.fingerprint)?.scope)
        assertEquals("billing", p.find(laptop.fingerprint)?.scope)
        assertEquals("android", p.activate(studio.fingerprint).active?.scope)
    }

    @Test
    fun theStoreRoundTripsSeveralMacs() {
        val store = PairingStore(MemoryKv(), XorCipher())
        val p = Pairings().paired(studio).paired(laptop).activate(studio.fingerprint)
        store.savePairings(p)
        assertEquals(p.macs, store.loadPairings().macs)
        assertEquals(studio, store.loadPairings().active)
    }

    @Test
    fun theSinglePairingOfAnOlderPhoneMovesOverWithItsScope() {
        val kv = MemoryKv()
        val cipher = XorCipher()
        val old = studio.copy(scope = null, lastHost = "192.168.1.5")
        kv.put(PairingStore.LEGACY_ENTRY_PAIRING, cipher.encrypt(old.toJson().toString().toByteArray()))
        kv.put(PairingStore.ENTRY_SETTINGS, jsonOf("scope" to "acme", "keylessOnly" to true, "mode" to "build").toString())

        val store = PairingStore(kv, cipher)
        val loaded = store.loadPairings()
        assertEquals(listOf(old.copy(scope = "acme")), loaded.macs)
        assertEquals(old.fingerprint, loaded.active?.fingerprint)

        assertEquals(PairingStore.ENTRIES, kv.names())
        val settings = parseJsonObject(kv.get(PairingStore.ENTRY_SETTINGS)!!)!!
        assertFalse(settings.containsKey(AppSettings.LEGACY_FIELD_SCOPE))
        assertEquals(AppSettings(keylessOnly = true, mode = "build"), store.loadSettings())

        assertEquals(loaded, PairingStore(kv, cipher).loadPairings())
    }

    @Test
    fun anOldPairingThatCantBeReadLeavesThePhoneUnpaired() {
        val kv = MemoryKv()
        kv.put(PairingStore.LEGACY_ENTRY_PAIRING, "not base64 at all")
        val store = PairingStore(kv, object : BlobCipher {
            override fun encrypt(plain: ByteArray) = ""
            override fun decrypt(sealed: String): ByteArray? = null
        })
        assertTrue(store.loadPairings().isEmpty)
    }

    @Test
    fun theActiveMacFallsBackToTheFirstWhenItsEntryIsGone() {
        val p = Pairings(listOf(studio, laptop), "ff".repeat(32))
        assertEquals(studio, p.active)
        assertEquals(studio.fingerprint, Pairings.fromJson(p.toJson()).active?.fingerprint)
    }
}
