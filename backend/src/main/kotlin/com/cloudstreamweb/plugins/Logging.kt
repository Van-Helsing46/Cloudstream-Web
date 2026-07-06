package com.cloudstreamweb.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import org.slf4j.event.Level
import java.util.UUID

/**
 * Observability: structured logging with a per-request identifier.
 *
 * - `CallId` generates/propagates an `X-Request-Id` and puts it in the MDC (`callId`), so every
 *   log line of the same request can be correlated — including those emitted by providers.
 * - `CallLogging` writes one line per request (method, path, status, duration: default format).
 *
 * The output format (structured JSON vs readable text) is chosen by `logback.xml` based on
 * the `LOG_FORMAT` property; the callId flows into both via MDC.
 */
fun Application.configureLogging() {
    install(CallId) {
        header("X-Request-Id")
        generate { UUID.randomUUID().toString().take(8) }
        verify { it.isNotBlank() }
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("callId")
        disableDefaultColors() // no ANSI in the message (noise in JSON logs)
        // Skip the noise of health checks and static assets; log APIs only.
        filter { call -> call.request.path().startsWith("/api/") }
    }
}
