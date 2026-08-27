package com.cloudstreamweb.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Streaming proxy: the browser cannot set headers such as `Referer`/`User-Agent`
 * required by CDNs, so the stream flows through here.
 *
 * `GET /api/v1/stream?url=<enc>&headers=<enc JSON {name: value}>`
 *
 * - injects the headers declared by the `StreamLink`
 * - rewrites HLS manifests: every URI (playlists, segments, keys) comes back to the proxy
 * - validates the URL against SSRF: http/https only, no private/loopback addresses
 * - forwards `Range` and answers 206/Content-Range for seeking
 */
fun Route.streamProxy(http: HttpClient) {
    val json = Json { ignoreUnknownKeys = true }

    get("/stream") {
        val target = call.request.queryParameters.getOrFail("url")
        val headersParam = call.request.queryParameters["headers"]
        val filenameParam = call.request.queryParameters["filename"]
        val tokenParam = call.request.queryParameters["token"]
        val extraHeaders: Map<String, String> = headersParam
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty()

        // java.net.URL (not URI): tolerant of paths with characters like `[` used by some CDNs.
        val parsed = runCatching { URL(target) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid URL"))
        val ssrfError = validateTarget(parsed)
        if (ssrfError != null) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to ssrfError))
        }

        val rangeHeader = call.request.headers[HttpHeaders.Range]

        http.prepareGet(target) {
            extraHeaders.forEach { (name, value) -> header(name, value) }
            if (HttpHeaders.UserAgent !in extraHeaders.keys.map { it }) {
                header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
            }
            rangeHeader?.let { header(HttpHeaders.Range, it) }
        }.execute { upstream ->
            if (!upstream.status.isSuccessOrPartial()) {
                return@execute call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "upstream answered ${upstream.status}"),
                )
            }

            if (upstream.isHlsManifest(target)) {
                val manifest = upstream.bodyAsText()
                call.respondText(
                    text = rewriteHlsManifest(
                        manifest,
                        baseUrl = target,
                        headersParam = headersParam,
                        tokenParam = tokenParam,
                    ),
                    contentType = ContentType("application", "vnd.apple.mpegurl"),
                )
            } else {
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                upstream.headers[HttpHeaders.ContentRange]?.let {
                    call.response.header(HttpHeaders.ContentRange, it)
                }
                if (filenameParam != null) {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename*=UTF-8''${sanitizeFilename(filenameParam)}",
                    )
                }
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
}

/**
 * Percent-encodes a client-supplied filename for the `filename*=UTF-8''...` form of
 * `Content-Disposition` (RFC 5987/6266): strips path separators/control characters and caps
 * length so it can't inject headers or escape the download's target directory, then
 * percent-encodes. `URLEncoder` follows `application/x-www-form-urlencoded` and would encode
 * spaces as `+` — valid in a query string, but browsers parsing `filename*` only decode `%XX`
 * escapes, so a `+` would end up literally in the saved filename (nearly every episode title
 * has spaces). Substituting `%20` back in avoids that.
 */
internal fun sanitizeFilename(raw: String): String {
    val cleaned = raw
        .replace(Regex("[/\\\\\"\\p{Cntrl}]"), "_")
        .trim()
        .take(120)
        .ifBlank { "stream" }
    return URLEncoder.encode(cleaned, Charsets.UTF_8.name()).replace("+", "%20")
}

internal const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"

private fun HttpStatusCode.isSuccessOrPartial() =
    value in 200..299 // include 206 Partial Content

private fun HttpResponse.isHlsManifest(url: String): Boolean {
    val ct = contentType()?.let { "${it.contentType}/${it.contentSubtype}" }.orEmpty()
    return ct.contains("mpegurl", ignoreCase = true) ||
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}

/**
 * SSRF mitigation: the proxy only fetches http/https towards public hosts.
 * Returns the error message, or null if the URL is acceptable.
 * Note: for personal/LAN use; DNS rebinding is not covered (it would require a pinned
 * resolver in the HTTP client) — accepted trade-off for this scope.
 */
internal suspend fun validateTarget(url: URL): String? {
    val scheme = url.protocol?.lowercase()
    if (scheme != "http" && scheme != "https") return "scheme not allowed: $scheme"
    val host = url.host?.takeIf { it.isNotBlank() } ?: return "missing host"
    val blocked = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host) }.getOrNull()
    } ?: return "unresolvable host: $host"
    val bad = blocked.firstOrNull {
        it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress ||
            it.isAnyLocalAddress || it.isMulticastAddress
    }
    return if (bad != null) "address not allowed by the proxy: ${bad.hostAddress}" else null
}

/**
 * Rewrites an HLS manifest: every URI (non-comment lines and URI="…" attributes of
 * EXT-X-KEY/EXT-X-MAP/EXT-X-MEDIA) is resolved to absolute and redirected to the proxy,
 * propagating the same headers (and signed token, if any — so a manifest copied out to an
 * external player keeps working segment by segment when auth is enabled).
 * Uses java.net.URL (tolerant) to resolve relative URIs; absolute ones pass through
 * untouched (paths containing `[` would break java.net.URI).
 */
internal fun rewriteHlsManifest(
    manifest: String,
    baseUrl: String,
    headersParam: String?,
    tokenParam: String? = null,
): String {
    val base = runCatching { URL(baseUrl) }.getOrNull()

    fun resolve(raw: String): String {
        val r = raw.trim()
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        return base?.let { runCatching { URL(it, r).toString() }.getOrNull() } ?: r
    }

    fun proxied(raw: String): String {
        val absolute = resolve(raw)
        val encodedUrl = URLEncoder.encode(absolute, Charsets.UTF_8.name())
        val headersSuffix = headersParam
            ?.let { "&headers=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }
            .orEmpty()
        val tokenSuffix = tokenParam
            ?.let { "&token=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }
            .orEmpty()
        return "/api/v1/stream?url=$encodedUrl$headersSuffix$tokenSuffix"
    }

    val uriAttribute = Regex("URI=\"([^\"]+)\"")
    return manifest.lineSequence().joinToString("\n") { line ->
        when {
            line.startsWith("#") -> uriAttribute.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
            line.isBlank() -> line
            else -> proxied(line)
        }
    }
}
