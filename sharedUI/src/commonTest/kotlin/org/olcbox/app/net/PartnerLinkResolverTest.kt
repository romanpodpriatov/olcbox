package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartnerLinkResolverTest {

    private fun client(status: HttpStatusCode, body: String) = HttpClient(MockEngine { _ ->
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test
    fun onlyHappLinksAreTreatedAsPartnerLinks() {
        assertTrue(isPartnerLink("happ://crypt5/fzvdQQSl2kKPyNPhAeRV4WSh12xLFV8"))
        assertTrue(isPartnerLink("  happ://crypt4/abc  "))
        // A plain subscription URL and our own crypt1 link must fall through to
        // the paths that already handle them.
        assertEquals(false, isPartnerLink("https://sub.reviewassistant.org/sub/j7k9eX"))
        assertEquals(false, isPartnerLink("olcrtc://crypt1/AAAA"))
        assertEquals(false, isPartnerLink(""))
    }

    @Test
    fun aResolvedLinkComesBackVerbatim() = runTest {
        val resolver = HttpPartnerLinkResolver(
            client(HttpStatusCode.OK, """{"link":"olcrtc://crypt1/QUJD"}"""),
            apiBase = "https://proofkit.test/api/v1",
        )
        assertEquals(
            PartnerLinkResult.Resolved("olcrtc://crypt1/QUJD"),
            resolver.resolve("happ://crypt5/abc"),
        )
    }

    @Test
    fun anUnknownLinkIsNotFoundRatherThanAnOutage() = runTest {
        val resolver = HttpPartnerLinkResolver(
            client(HttpStatusCode.NotFound, """{"error":{"code":"NOT_FOUND"}}"""),
            apiBase = "https://proofkit.test/api/v1",
        )
        assertEquals(PartnerLinkResult.NotFound, resolver.resolve("happ://crypt5/abc"))
    }

    @Test
    fun serverTroubleIsUnavailableSoTheUserIsToldToRetry() = runTest {
        val resolver = HttpPartnerLinkResolver(
            client(HttpStatusCode.ServiceUnavailable, ""),
            apiBase = "https://proofkit.test/api/v1",
        )
        assertEquals(PartnerLinkResult.Unavailable, resolver.resolve("happ://crypt5/abc"))
    }

    @Test
    fun aGarbageBodyIsNotMistakenForSuccess() = runTest {
        val resolver = HttpPartnerLinkResolver(
            client(HttpStatusCode.OK, "not json at all"),
            apiBase = "https://proofkit.test/api/v1",
        )
        assertEquals(PartnerLinkResult.Unavailable, resolver.resolve("happ://crypt5/abc"))
    }
}
