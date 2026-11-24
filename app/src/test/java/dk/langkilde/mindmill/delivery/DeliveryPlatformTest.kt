package dk.langkilde.mindmill.delivery

import dk.langkilde.mindmill.remote.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryPlatformTest {
    @Test fun androidBuildsAndSharedLinksAreKept() {
        assertEquals("apk", Delivery.parse(jsonOf("id" to "d1", "kind" to "apk", "platform" to "android"))?.kind)
        assertEquals("preview", Delivery.parse(jsonOf("id" to "d2", "kind" to "preview"))?.kind)
        assertEquals("url", Delivery.parse(jsonOf("id" to "d3", "kind" to "url", "url" to "https://x"))?.kind)
    }

    @Test fun iPhoneBuildsAndTestFlightAreLeftOut() {
        assertNull(Delivery.parse(jsonOf("id" to "d1", "kind" to "url", "platform" to "ios", "url" to "itms-services://?x")))
        assertNull(Delivery.parse(jsonOf("id" to "d2", "kind" to "testflight")))
        assertFalse(Delivery.isForAndroid(jsonOf("id" to "c1", "title" to "card", "platform" to "ios")))
        assertTrue(Delivery.isForAndroid(jsonOf("id" to "c2", "title" to "card")))
    }
}
