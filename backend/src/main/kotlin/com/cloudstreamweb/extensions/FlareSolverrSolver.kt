package com.cloudstreamweb.extensions

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Backs [CloudflareKiller]'s solver seam with a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr)
 * instance — a headless-browser service run as a sidecar (see docker/deploy) that solves Cloudflare's
 * "Just a moment…" challenge and hands back the `cf_clearance` cookie + the User-Agent it used.
 *
 * A provider's link extractor may hit a video CDN gated by Cloudflare; it goes through
 * `CloudflareKiller` as an OkHttp interceptor, which on an active challenge now delegates here
 * instead of degrading.
 *
 * IP/UA caveat: a `cf_clearance` cookie is bound to the solving browser's egress IP and User-Agent.
 * This works when FlareSolverr shares the backend's public IP (the usual sidecar/LAN setup, RNF-1) and
 * the same UA is replayed — which [CloudflareKiller] does with [CloudflareKiller.Clearance.userAgent].
 *
 * Solving spins a real browser (seconds), so clearances are cached per host with a TTL and concurrent
 * solves for the same host are collapsed.
 */
class FlareSolverrSolver(
    /** FlareSolverr v1 endpoint, e.g. `http://flaresolverr:8191/v1`. */
    private val endpoint: String,
    private val maxTimeoutMs: Long = 60_000,
    private val cacheTtlMs: Long = 10 * 60_000,
) : CloudflareKiller.CloudflareSolver {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private class Cached(val clearance: CloudflareKiller.Clearance, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val hostLocks = ConcurrentHashMap<String, Any>()

    override fun solve(url: String): CloudflareKiller.Clearance? {
        val host = runCatching { URI.create(url).host }.getOrNull() ?: return null
        fresh(host)?.let { return it }
        // Collapse concurrent solves for the same host: a browser spin is expensive and the first
        // clearance serves everyone waiting.
        return synchronized(hostLocks.computeIfAbsent(host) { Any() }) {
            fresh(host) ?: requestSolve(url)?.also { cache[host] = Cached(it, System.currentTimeMillis()) }
        }
    }

    private fun fresh(host: String): CloudflareKiller.Clearance? =
        cache[host]?.takeIf { System.currentTimeMillis() - it.at < cacheTtlMs }?.clearance

    private fun requestSolve(url: String): CloudflareKiller.Clearance? {
        val payload = mapper.createObjectNode().apply {
            put("cmd", "request.get")
            put("url", url)
            put("maxTimeout", maxTimeoutMs)
        }
        val req = HttpRequest.newBuilder(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMillis(maxTimeoutMs + 15_000))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()
        val resp = runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { log.warn("FlareSolverr unreachable at {}: {}", endpoint, it.message); return null }
        if (resp.statusCode() !in 200..299) {
            log.warn("FlareSolverr HTTP {} for {}", resp.statusCode(), url)
            return null
        }
        val root = mapper.readTree(resp.body())
        if (root.path("status").asText() != "ok") {
            log.warn("FlareSolverr status '{}' for {}: {}", root.path("status").asText(), url, root.path("message").asText())
            return null
        }
        val solution = root.path("solution")
        val cookies = solution.path("cookies")
            .associate { it.path("name").asText() to it.path("value").asText() }
            .filterKeys { it.isNotEmpty() }
        if (cookies.isEmpty()) {
            log.warn("FlareSolverr returned no cookies for {}", url)
            return null
        }
        val ua = solution.path("userAgent").asText("").takeIf { it.isNotBlank() }
        log.info("FlareSolverr cleared {} ({} cookies)", url, cookies.size)
        return CloudflareKiller.Clearance(cookies, ua)
    }
}
