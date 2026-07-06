package com.cloudstreamweb.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Versioned API contract: the OpenAPI spec lives in
 * `resources/openapi/documentation.yaml` and is served interactively at `/swagger`,
 * plus the raw spec at `/openapi.yaml`. Public endpoints (outside the API prefix, hence not
 * subject to auth): they are documentation, not data.
 */
fun Application.configureOpenApi() {
    val specResource = "openapi/documentation.yaml"
    routing {
        swaggerUI(path = "/swagger", swaggerFile = specResource)

        get("/openapi.yaml") {
            val yaml = this::class.java.classLoader.getResource(specResource)?.readText()
            if (yaml == null) {
                call.respondText("spec not found", status = io.ktor.http.HttpStatusCode.NotFound)
            } else {
                call.respondText(yaml, contentType = io.ktor.http.ContentType("application", "yaml"))
            }
        }
    }
}
