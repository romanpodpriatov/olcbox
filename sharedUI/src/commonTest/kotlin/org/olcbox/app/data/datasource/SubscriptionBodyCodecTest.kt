package org.olcbox.app.data.datasource

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class SubscriptionBodyCodecTest {

    private val body =
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443?security=reality&pbk=abc&sid=ff#DE\n" +
            "hysteria2://pass@1.2.3.4:8443?sni=example.com#DE-hy2\n"

    @Test
    fun decodesStandardBase64Body() {
        val encoded = Base64.Default.encode(body.encodeToByteArray())
        assertEquals(body, SubscriptionBodyCodec.decodeBase64(encoded))
    }

    @Test
    fun decodesWithoutPadding() {
        val encoded = Base64.Default.encode(body.encodeToByteArray()).trimEnd('=')
        assertEquals(body, SubscriptionBodyCodec.decodeBase64(encoded))
    }

    @Test
    fun decodesUrlSafeVariant() {
        val encoded = Base64.UrlSafe.encode(body.encodeToByteArray())
        assertEquals(body, SubscriptionBodyCodec.decodeBase64(encoded))
    }

    @Test
    fun decodesNewlineWrappedBase64() {
        val encoded = Base64.Default.encode(body.encodeToByteArray())
            .chunked(48)
            .joinToString("\n")
        assertEquals(body, SubscriptionBodyCodec.decodeBase64(encoded))
    }

    @Test
    fun rejectsPlaintextLinkList() {
        // ':' and '#' fail the alphabet gate — plaintext must go down the direct path.
        assertNull(SubscriptionBodyCodec.decodeBase64(body))
    }

    @Test
    fun rejectsJson() {
        assertNull(SubscriptionBodyCodec.decodeBase64("""{"locations":[{"id":"x"}]}"""))
    }

    @Test
    fun rejectsBase64OfNonLinkPayload() {
        val encoded = Base64.Default.encode("just some random words with no links".encodeToByteArray())
        assertNull(SubscriptionBodyCodec.decodeBase64(encoded))
    }

    @Test
    fun rejectsShortInput() {
        assertNull(SubscriptionBodyCodec.decodeBase64("dGVzdA=="))
    }

    @Test
    fun realCoordinatorShapeRoundTrips() {
        // Mirror of the prod /sub/{token} shape: base64(vless+hy2 lines, trailing \n).
        val encoded = Base64.Default.encode(body.encodeToByteArray())
        val decoded = SubscriptionBodyCodec.decodeBase64(encoded)!!
        assertTrue(decoded.lineSequence().any { it.startsWith("vless://") })
        assertTrue(decoded.lineSequence().any { it.startsWith("hysteria2://") })
    }
}
