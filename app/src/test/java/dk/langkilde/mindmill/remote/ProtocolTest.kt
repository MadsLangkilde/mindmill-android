package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class FrameCodecTest {
    @Test
    fun roundTripsHeaderAndPayload() {
        val payload = ByteArray(1280) { (it % 251).toByte() }
        val frame = BinaryFrame.voice("c1", 7, payload)
        val decoded = BinaryFrame.decode(frame.encode())!!
        assertEquals(frame, decoded)
        assertEquals("voice", decoded.kind)
        assertEquals("pcm16k", decoded.format)
        assertEquals(7L, decoded.seq)
        assertEquals("c1", decoded.header.string("conversation"))
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun headerLengthIsFourBytesBigEndian() {
        val bytes = BinaryFrame.screen("m1", 2, byteArrayOf(1, 2, 3)).encode()
        val len = ByteBuffer.wrap(bytes, 0, 4).int
        val header = String(bytes, 4, len, Charsets.UTF_8)
        assertTrue(header.contains("\"kind\":\"screen\""))
        assertTrue(header.contains("\"format\":\"jpeg\""))
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.copyOfRange(4 + len, bytes.size))
    }

    @Test
    fun decodesAFileChunkFromTheMac() {
        val header = """{"kind":"file","delivery":"d9","seq":3,"last":true}""".toByteArray()
        val bytes = ByteBuffer.allocate(4 + header.size + 2).putInt(header.size).put(header).put(byteArrayOf(9, 8)).array()
        val f = BinaryFrame.decode(bytes)!!
        assertEquals("file", f.kind)
        assertTrue(f.isLast)
        assertEquals(3L, f.seq)
        assertArrayEquals(byteArrayOf(9, 8), f.payload)
    }

    @Test
    fun emptyPayloadIsFine() {
        val f = BinaryFrame(jsonOf("kind" to "file", "delivery" to "d", "seq" to 0, "last" to true), ByteArray(0))
        assertEquals(f, BinaryFrame.decode(f.encode()))
    }

    @Test
    fun rejectsBrokenFrames() {
        assertNull(BinaryFrame.decode(byteArrayOf(0, 0)))
        assertNull(BinaryFrame.decode(ByteBuffer.allocate(6).putInt(100).put(byteArrayOf(1, 2)).array()))
        assertNull(BinaryFrame.decode(ByteBuffer.allocate(7).putInt(3).put("abc".toByteArray()).array()))
    }
}

class PairingInviteTest {
    private val fp = "3f581ace41bb1689166f6a285ee49f7d8892fe4e216f6ccf8e73a313d8eee726"

    @Test
    fun parsesTheMacsInvite() {
        val i = PairingInvite.parse(
            "kb://pair?v=1&hosts=192.168.1.5,100.101.2.3,mac.tail1234.ts.net&port=7878&fp=$fp&code=123456&name=Mads%E2%80%99s%20MacBook",
        )!!
        assertEquals(listOf("192.168.1.5", "100.101.2.3", "mac.tail1234.ts.net"), i.hosts)
        assertEquals(7878, i.port)
        assertEquals(fp, i.fingerprint)
        assertEquals("123456", i.code)
        assertEquals("Mads’s MacBook", i.name)
        assertEquals(1, i.version)
    }

    @Test
    fun acceptsUpperCaseAndColonFingerprintsAndWhitespace() {
        val colons = fp.uppercase().chunked(2).joinToString(":")
        val i = PairingInvite.parse("  kb://pair?hosts=10.0.0.2&port=9000&fp=$colons&code=000111\n")!!
        assertEquals(fp, i.fingerprint)
        assertEquals("Mindmill", i.name)
        assertEquals(9000, i.port)
    }

    @Test
    fun codeIsOptionalSoAPastedInviteCanAskForIt() {
        assertEquals("", PairingInvite.parse("kb://pair?hosts=a.local&port=7878&fp=$fp")!!.code)
    }

    @Test
    fun rejectsWhatIsNotAnInvite() {
        assertNull(PairingInvite.parse("https://pair?hosts=a&port=1&fp=$fp"))
        assertNull(PairingInvite.parse("kb://pair?hosts=&port=7878&fp=$fp"))
        assertNull(PairingInvite.parse("kb://pair?hosts=a&port=70000&fp=$fp"))
        assertNull(PairingInvite.parse("kb://pair?hosts=a&port=7878&fp=abc"))
        assertNull(PairingInvite.parse("kb://pair?hosts=a&port=7878"))
        assertNull(PairingInvite.parse("hello"))
    }
}

class CertificatePinTest {
    private val pemA = """
        -----BEGIN CERTIFICATE-----
        MIIBaDCCAQ6gAwIBAgIUb6gHsR428n9VHiOCTGf1s8uQAPswCgYIKoZIzj0EAwIw
        GjEYMBYGA1UEAwwPTWluZG1pbGwgVGVzdCBhMB4XDTI2MTEyMjE5MjEwMVoXDTM2
        MTExOTE5MjEwMVowGjEYMBYGA1UEAwwPTWluZG1pbGwgVGVzdCBhMFkwEwYHKoZI
        zj0CAQYIKoZIzj0DAQcDQgAEd6WV1402mE6kkaMuvZ6XwIUwHC+MUXJmS5fSu+9t
        w06LCmC8qWG/6rR0rmjc6gy/qVNkwS72vyPTXLuQlyRVNaMyMDAwHQYDVR0OBBYE
        FJgVHen+uBJmXvoqpIsvvZC2Oqi4MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0E
        AwIDSAAwRQIhAJ+uDI/Wfxsm5RRpLU4RTxqCoK4pH27W8dg/xF4ocdD+AiAqFtnk
        XM1w9fohyzFAXNCTSrXY1ECeKkFNNHwh7PRpww==
        -----END CERTIFICATE-----
    """.trimIndent()
    private val pemB = """
        -----BEGIN CERTIFICATE-----
        MIIBZzCCAQ6gAwIBAgIUTcBuO23ECkzuzF858weDtuJuNP8wCgYIKoZIzj0EAwIw
        GjEYMBYGA1UEAwwPTWluZG1pbGwgVGVzdCBiMB4XDTI2MTEyMjE5MjEwMVoXDTM2
        MTExOTE5MjEwMVowGjEYMBYGA1UEAwwPTWluZG1pbGwgVGVzdCBiMFkwEwYHKoZI
        zj0CAQYIKoZIzj0DAQcDQgAE2hPfA6Cps+JYKaAbT+yBXSUxOBr4hnf1jhgi9zA/
        9Q3DMIg/0ct/Wt/VQPigVDfFiCJbrhkQbKprg23k8SZ07qMyMDAwHQYDVR0OBBYE
        FD8cpQvy9FkWHhJuYFugEs7H3H+nMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0E
        AwIDRwAwRAIgJhFh2JOpRmBHBrCYK5z6kPx8tdFPSWEjaKRwK8er3/gCIHdK48Yh
        HorHSExP/B3kgo2j3Tt/jrkQFTRt/VEOOGEd
        -----END CERTIFICATE-----
    """.trimIndent()
    private val fpA = "3f581ace41bb1689166f6a285ee49f7d8892fe4e216f6ccf8e73a313d8eee726"

    private fun cert(pem: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    @Test
    fun fingerprintIsSha256OfDerInLowerCaseHex() {
        assertEquals(fpA, PinnedTrustManager.sha256Hex(cert(pemA).encoded))
    }

    @Test
    fun acceptsThePinnedCertificate() {
        PinnedTrustManager(fpA).checkServerTrusted(arrayOf(cert(pemA)), "ECDHE_ECDSA")
        PinnedTrustManager(fpA.uppercase()).checkServerTrusted(arrayOf(cert(pemA)), "ECDHE_ECDSA")
    }

    @Test
    fun rejectsAnyOtherCertificate() {
        val tm = PinnedTrustManager(fpA)
        assertFalse(tm.accepts(cert(pemB).encoded))
        try {
            tm.checkServerTrusted(arrayOf(cert(pemB)), "ECDHE_ECDSA")
            fail("accepted the wrong certificate")
        } catch (_: CertificateException) {
        }
        try {
            tm.checkServerTrusted(arrayOf(cert(pemB), cert(pemA)), "ECDHE_ECDSA")
            fail("accepted a chain whose leaf isn't pinned")
        } catch (_: CertificateException) {
        }
        try {
            tm.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
            fail("accepted no certificate")
        } catch (_: CertificateException) {
        }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAMalformedPin() {
        PinnedTrustManager("not-a-fingerprint")
    }
}

class MessageTest {
    @Test
    fun encodesRequestsAndDecodesEachKind() {
        val req = RemoteMessage.Request("1", "pair", jsonOf("code" to "123456", "name" to "Pixel 9"))
        assertEquals(req, RemoteMessage.decode(req.encode()))
        assertEquals(
            RemoteMessage.Response("2", jsonOf("token" to "t")),
            RemoteMessage.decode("""{"id":"2","result":{"token":"t"}}"""),
        )
        assertEquals(
            RemoteMessage.Failure("3", "unknown_method", "Update Mindmill on the Mac."),
            RemoteMessage.decode("""{"id":"3","error":{"code":"unknown_method","message":"Update Mindmill on the Mac."}}"""),
        )
        assertEquals(
            RemoteMessage.Event("index.changed", JsonObject(emptyMap())),
            RemoteMessage.decode("""{"event":"index.changed"}"""),
        )
        assertNull(RemoteMessage.decode("not json"))
    }

    @Test
    fun unknownMethodReadsAsUpdateTheMac() {
        assertTrue(RemoteException(ErrorCodes.UNKNOWN_METHOD, "x").userMessage().contains("Update Mindmill on the Mac"))
    }

    @Test
    fun jsonHelpersReadNumbersAndStrings() {
        val o = jsonOf("n" to 3.0, "s" to "x", "l" to listOf("a", "b"), "b" to true)
        assertEquals(3, o.int("n"))
        assertEquals("3.0", o.text("n"))
        assertNull(o.string("n"))
        assertEquals(listOf("a", "b"), o.strings("l"))
        assertEquals(true, o.bool("b"))
        assertEquals(JsonPrimitive("x"), o["s"])
    }
}
