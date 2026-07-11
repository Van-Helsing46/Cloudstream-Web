package com.lagradost.cloudstream3.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareKillerTest {

    @AfterTest
    fun reset() {
        CloudflareKiller.solver = null
    }

    /** Minimal Interceptor.Chain: serves a canned response and records the forwarded request. */
    private class FakeChain(
        private val request: Request,
        private val responder: (Request) -> Response,
    ) : Interceptor.Chain {
        var lastForwarded: Request? = null
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            lastForwarded = request
            return responder(request)
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    private fun response(req: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Challenge")
            .body(body.toResponseBody(null))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun get(url: String) = Request.Builder().url(url).build()

    @Test
    fun `passes through a normal response and injects a browser User-Agent`() {
        val req = get("https://example.com/page")
        val chain = FakeChain(req) { forwarded -> response(forwarded, 200, "hello") }

        val result = CloudflareKiller().intercept(chain)

        assertEquals(200, result.code)
        assertEquals(CloudflareKiller.BROWSER_USER_AGENT, chain.lastForwarded?.header("User-Agent"))
    }

    @Test
    fun `degrades gracefully on an active challenge when no solver is wired`() {
        CloudflareKiller.solver = null
        val req = get("https://protected.example/page")
        val chain = FakeChain(req) { forwarded ->
            response(forwarded, 503, "<title>Just a moment...</title>", mapOf("cf-mitigated" to "challenge"))
        }

        val result = CloudflareKiller().intercept(chain)

        // Returns the challenge response instead of throwing (v1 behaviour).
        assertEquals(503, result.code)
    }

    @Test
    fun `retries with clearance cookies when a solver solves the challenge`() {
        CloudflareKiller.solver = CloudflareKiller.CloudflareSolver { _ ->
            CloudflareKiller.Clearance(cookies = mapOf("cf_clearance" to "TOKEN"), userAgent = "SolverUA/1.0")
        }
        val req = get("https://protected.example/page")
        var call = 0
        val chain = FakeChain(req) { forwarded ->
            call++
            if (call == 1) response(forwarded, 503, "just a moment __cf_chl")
            else response(forwarded, 200, "cleared")
        }

        val killer = CloudflareKiller()
        val result = killer.intercept(chain)

        assertEquals(200, result.code)
        assertEquals("cf_clearance=TOKEN", chain.lastForwarded?.header("Cookie"))
        assertEquals("SolverUA/1.0", chain.lastForwarded?.header("User-Agent"))
        // Clearance is remembered per host for later requests.
        assertEquals(mapOf("cf_clearance" to "TOKEN"), killer.savedCookies["protected.example"])
    }

    @Test
    fun `getCookieHeaders returns UA and no cookie before any clearance`() {
        val headers = CloudflareKiller().getCookieHeaders("https://example.com/x")
        assertEquals(CloudflareKiller.BROWSER_USER_AGENT, headers["User-Agent"])
        assertNull(headers["Cookie"])
        assertTrue(headers.size >= 1)
    }
}
