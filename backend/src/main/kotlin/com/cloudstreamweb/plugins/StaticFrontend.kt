package com.cloudstreamweb.plugins

import com.cloudstreamweb.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.routing.routing

/**
 * Serves the static frontend build, if `frontendDir` is configured (Docker/prod).
 * Same-origin with the backend: session cookies and the streaming proxy work without CORS.
 *
 * `singlePageApplication` serves existing assets and, for non-file paths, falls back to
 * `index.html`: this keeps React's client-side routing (`/media/...`, `/library`, …) working
 * on refresh or direct links. The more specific API/`/health` routes take priority over the catch-all.
 */
fun Application.configureStaticFrontend(config: AppConfig) {
    val dir = config.frontendDir ?: run {
        log.info("Static frontend not configured (dev: use the Vite server).")
        return
    }
    log.info("Serving the static frontend from ${dir.absolutePath}")

    routing {
        singlePageApplication {
            useResources = false
            filesPath = dir.absolutePath
            defaultPage = "index.html"
        }
    }
}
