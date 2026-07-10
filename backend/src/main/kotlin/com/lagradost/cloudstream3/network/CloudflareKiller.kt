package com.lagradost.cloudstream3.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.LoggerFactory

/**
 * Server-side (headless JVM) replacement for Cloudstream's Android `CloudflareKiller`.
 *
 * WHY THIS FILE LIVES IN `com.lagradost.cloudstream3.network`
 * ----------------------------------------------------------
 * DEX-converted extensions (Strada A) reference this class by its exact fully-qualified name.
 * `library-jvm` does not ship it (verified: gap analysis 2026-07-09/11 — it is the single real
 * functional gap across the whole ItaliaInStreaming repo). By providing a same-named class on the
 * backend classpath, the child `URLClassLoader` that loads an extension resolves the reference to
 * this implementation (parent-first delegation). No fork/rebuild of `library-jvm` needed.
 *
 * WHAT IT DOES
 * ------------
 * Extensions use `CloudflareKiller` purely as an `okhttp3.Interceptor` (`new` + pass as the
 * `interceptor` param of NiceHttp's `app.get`, or a direct `intercept(chain)` call). So the only
 * hard contract is: no-arg constructor + `Interceptor`.
 *
 * The Android original solves Cloudflare's JS "under attack" challenge with a real WebView. A
 * headless server has none, so this class is staged:
 *  - **v1 (this file, no browser):** inject a realistic browser User-Agent and reuse per-host
 *    cookies, then proceed. This is enough whenever Cloudflare is NOT actively challenging (the
 *    common case for these providers). If an active challenge IS detected and no solver is wired,
 *    it degrades gracefully (returns the challenge response, logs once) instead of throwing.
 *  - **v2 (future, Playwright — "Fase 5"):** wire [solver] to a headless browser that solves the
 *    challenge and returns the `cf_clearance` cookie + UA; this class then retries with them.
 *
 * The [solver] seam keeps the browser dependency out of this class: the dynamic runtime injects it
 * if/when a browser bridge exists.
 */
class CloudflareKiller : Interceptor {

    /** Captured cookies per host, reused on later requests (mirrors upstream `savedCookies`). */
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        val firstTry = request.newBuilder().apply {
            header("User-Agent", BROWSER_USER_AGENT)
            cookieHeader(savedCookies[host])?.let { header("Cookie", it) }
        }.build()

        val response = chain.proceed(firstTry)
        if (!isCloudflareChallenge(response)) return response

        // Active Cloudflare challenge. Without a browser solver we cannot pass it: degrade.
        val activeSolver = solver
        if (activeSolver == null) {
            if (loggedDegradationHosts.add(host)) {
                log.warn("Cloudflare challenge on {} and no browser solver wired — passing through unsolved", host)
            }
            return response
        }

        val clearance = runCatching { activeSolver.solve(request.url.toString()) }
            .onFailure { log.warn("Cloudflare solver failed for {}: {}", host, it.message) }
            .getOrNull() ?: return response

        savedCookies[host] = clearance.cookies
        response.close()
        val retried = request.newBuilder().apply {
            header("User-Agent", clearance.userAgent ?: BROWSER_USER_AGENT)
            cookieHeader(clearance.cookies)?.let { header("Cookie", it) }
        }.build()
        return chain.proceed(retried)
    }

    /**
     * Headers (UA + captured cookies) for a URL — the other member extensions occasionally read to
     * reuse a cleared session on a plain request. Empty cookie set if nothing was captured yet.
     */
    fun getCookieHeaders(url: String): Headers {
        val host = url.toHttpUrlOrNull()?.host
        val builder = Headers.Builder().add("User-Agent", BROWSER_USER_AGENT)
        cookieHeader(host?.let { savedCookies[it] })?.let { builder.add("Cookie", it) }
        return builder.build()
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        if (response.header("cf-mitigated") == "challenge") return true
        // Cheap, non-consuming peek: challenge pages are small and carry telltale markers.
        val body = runCatching { response.peekBody(PEEK_BYTES).string().lowercase() }.getOrDefault("")
        return CHALLENGE_MARKERS.any { it in body }
    }

    private fun cookieHeader(cookies: Map<String, String>?): String? =
        cookies?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    /** Returned by a [CloudflareSolver]: the cookies (and UA) that clear the challenge. */
    data class Clearance(val cookies: Map<String, String>, val userAgent: String? = null)

    /** Pluggable challenge solver (v2 backs this with a headless browser). */
    fun interface CloudflareSolver {
        /** Solve the challenge for [url]; return the clearance, or null if it could not. */
        fun solve(url: String): Clearance?
    }

    companion object {
        private val log = LoggerFactory.getLogger(CloudflareKiller::class.java)

        /** Realistic desktop UA so origins don't reject a header-less client outright. */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * Globally injectable challenge solver. Null (default) = v1 graceful degradation.
         * The dynamic extension runtime sets this once if a browser bridge is available.
         */
        @Volatile
        @JvmStatic
        var solver: CloudflareSolver? = null

        private const val PEEK_BYTES = 64L * 1024L
        private val CHALLENGE_MARKERS =
            listOf("just a moment", "__cf_chl", "cf-mitigated", "challenge-platform", "cf_chl_opt")

        /** Hosts already warned about, so degradation logs once per host, not per request. */
        private val loggedDegradationHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}
