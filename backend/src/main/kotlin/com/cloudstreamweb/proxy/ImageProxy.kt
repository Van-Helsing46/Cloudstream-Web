package com.cloudstreamweb.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import io.ktor.utils.io.copyTo
import java.net.URL

/**
 * Poster/thumbnail proxy. Unlike stream links, `posterUrl` fields are rendered directly by
 * the browser as `<img src>`/`background-image` — with no proxy in front, that request goes
 * out over the *client's own* network/DNS rather than the backend's (which resolves via
 * DNS-over-HTTPS, see [com.cloudstreamweb.net.DohDns]). A client on a network that blocks or
 * hijacks DNS for the provider's image CDN (common for streaming-site domains on Italian
 * ISPs/mobile carriers, and easy to hit incidentally over a VPN) then simply can't load the
 * image, even though the backend — and so the rest of the app — reaches it fine.
 *
 * `GET /api/v1/image?url=<enc>`: same SSRF guard as the streaming proxy, cached for a day
 * (posters don't change), no header injection needed (none of the providers require one for
 * images so far — add a `headers` param like `/stream` if that changes).
 */
fun Route.imageProxy(http: HttpClient) {
    get("/image") {
        val target = call.request.queryParameters.getOrFail("url")
        val parsed = runCatching { URL(target) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid URL"))
        val ssrfError = validateTarget(parsed)
        if (ssrfError != null) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to ssrfError))
        }

        http.prepareGet(target) {
            header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
        }.execute { upstream ->
            if (!upstream.status.isSuccess()) {
                return@execute call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "upstream answered ${upstream.status}"),
                )
            }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytesWriter(
                contentType = upstream.contentType(),
                status = upstream.status,
                contentLength = upstream.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            ) {
                upstream.bodyAsChannel().copyTo(this)
            }
        }
    }
}

private fun HttpStatusCode.isSuccess() = value in 200..299
