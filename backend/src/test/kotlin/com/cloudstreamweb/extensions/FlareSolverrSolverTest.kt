package com.cloudstreamweb.extensions

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlareSolverrSolverTest {

    private lateinit var server: HttpServer
    private val calls = AtomicInteger(0)
    private var responder: () -> Pair<Int, String> = { 200 to OK_BODY }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1") { ex ->
            calls.incrementAndGet()
            ex.requestBody.readBytes() // drain
            val (code, body) = responder()
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun endpoint() = "http://127.0.0.1:${server.address.port}/v1"

    @Test
    fun `parses cookies and userAgent from a FlareSolverr ok response`() {
        val clearance = FlareSolverrSolver(endpoint()).solve("https://challenged.example/embed/1")

        assertEquals(mapOf("cf_clearance" to "TOKEN", "other" to "x"), clearance?.cookies)
        assertEquals("SolverUA/9.9", clearance?.userAgent)
    }

    @Test
    fun `caches per host so a second solve does not hit FlareSolverr again`() {
        val solver = FlareSolverrSolver(endpoint())
        solver.solve("https://challenged.example/embed/1")
        solver.solve("https://challenged.example/embed/2") // same host

        assertEquals(1, calls.get())
    }

    @Test
    fun `returns null on a FlareSolverr error status`() {
        responder = { 200 to """{"status":"error","message":"boom"}""" }
        assertNull(FlareSolverrSolver(endpoint()).solve("https://challenged.example/embed/1"))
    }

    @Test
    fun `returns null when the solution carries no cookies`() {
        responder = { 200 to """{"status":"ok","solution":{"cookies":[],"userAgent":"x"}}""" }
        assertNull(FlareSolverrSolver(endpoint()).solve("https://challenged.example/embed/1"))
    }

    companion object {
        private val OK_BODY = """
            {
              "status": "ok",
              "solution": {
                "url": "https://challenged.example/embed/1",
                "status": 200,
                "userAgent": "SolverUA/9.9",
                "cookies": [
                  {"name": "cf_clearance", "value": "TOKEN"},
                  {"name": "other", "value": "x"}
                ]
              }
            }
        """.trimIndent()
    }
}
