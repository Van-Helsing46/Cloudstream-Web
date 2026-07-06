package com.cloudstreamweb.plugins

import com.cloudstreamweb.config.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

fun Application.configureHTTP(config: AppConfig) {
    install(CORS) {
        // In prod the frontend is served by Ktor (same-origin) → no extra hosts needed.
        // In dev the Vite proxy makes requests same-origin. `corsHosts` covers unusual setups.
        if (config.corsHosts.isEmpty()) {
            anyHost()
        } else {
            config.corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
        allowCredentials = true // session cookie
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Profile-Id") // profile selection on the /library endpoints
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal error")),
            )
        }
    }
}
