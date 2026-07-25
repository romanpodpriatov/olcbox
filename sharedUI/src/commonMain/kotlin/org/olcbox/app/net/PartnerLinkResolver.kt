package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.GeneratedAppInfo

private const val HAPP_PREFIX = "happ://"

/**
 * A partner's `happ://crypt5/…` link hides its subscription URL behind keys that
 * live inside Happ's own binary. We do not open it — the partner that minted the
 * link stored it, so the coordinator asks on our behalf and returns a link we can
 * read. Marker-only, so plain URLs and our own crypt1 links are untouched.
 */
fun isPartnerLink(text: String): Boolean = text.trim().startsWith(HAPP_PREFIX)

sealed interface PartnerLinkResult {
    /** The link the coordinator gave back — normally `olcrtc://crypt1/…`. */
    data class Resolved(val link: String) : PartnerLinkResult

    /** Nobody claims this link: unknown, or the subscription behind it expired. */
    data object NotFound : PartnerLinkResult

    /** We could not find out. Worth retrying; not worth telling the user their link is bad. */
    data object Unavailable : PartnerLinkResult
}

interface PartnerLinkResolver {
    suspend fun resolve(link: String): PartnerLinkResult
}

class HttpPartnerLinkResolver(
    private val httpClient: HttpClient,
    private val apiBase: String = GeneratedAppInfo.RESOLVER_BASE,
) : PartnerLinkResolver {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(link: String): PartnerLinkResult {
        val trimmed = link.trim()
        if (!isPartnerLink(trimmed)) return PartnerLinkResult.NotFound

        val response = runCatching {
            httpClient.post("${apiBase.trimEnd('/')}/partners/resolve-link") {
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                }
                setBody(buildJsonObject { put("link", trimmed) }.toString())
            }
        }.getOrNull() ?: return PartnerLinkResult.Unavailable

        if (response.status.value == 404) return PartnerLinkResult.NotFound
        if (response.status.value !in 200..299) return PartnerLinkResult.Unavailable

        val resolved = runCatching {
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject["link"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

        return if (resolved.isNullOrBlank()) {
            PartnerLinkResult.Unavailable
        } else {
            PartnerLinkResult.Resolved(resolved)
        }
    }
}
