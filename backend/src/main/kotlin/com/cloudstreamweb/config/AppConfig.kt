package com.cloudstreamweb.config

import java.io.File

/**
 * Application configuration from environment variables.
 * Single place where the environment is read: the rest of the code receives [AppConfig].
 */
data class AppConfig(
    val port: Int,
    val host: String,
    val dataDir: File,
    /** Access password. If empty/absent, auth is disabled (handy in dev). */
    val authPassword: String?,
    /** Secret used to sign the session token; default provided, overridable via env. */
    val authSecret: String,
    /** Extra allowed CORS origins (besides same-origin). Empty in prod (FE served by Ktor). */
    val corsHosts: List<String>,
    /** Per-provider search timeout, ms. */
    val providerSearchTimeoutMs: Long,
    /** Static frontend build directory, if present (Docker/prod). */
    val frontendDir: File?,
    /** Log format: "json" (structured, prod) or "text" (readable, dev). */
    val logFormat: String,
) {
    val authEnabled: Boolean get() = !authPassword.isNullOrBlank()

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): AppConfig {
            val dataDir = File(env("CLOUDSTREAM_WEB_DATA") ?: "data")
            val frontend = env("FRONTEND_DIR")?.let(::File)
                ?: File("static").takeIf { it.isDirectory }
            return AppConfig(
                port = env("PORT")?.toIntOrNull() ?: 8080,
                host = env("HOST") ?: "0.0.0.0",
                dataDir = dataDir,
                authPassword = env("AUTH_PASSWORD")?.takeIf { it.isNotBlank() },
                authSecret = env("AUTH_SECRET")?.takeIf { it.isNotBlank() }
                    ?: "cloudstream-web-default-secret-change-me",
                corsHosts = env("CORS_HOSTS")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                providerSearchTimeoutMs = env("SEARCH_TIMEOUT_MS")?.toLongOrNull() ?: 15_000L,
                frontendDir = frontend?.takeIf { it.isDirectory },
                logFormat = (env("LOG_FORMAT") ?: "text").lowercase(),
            )
        }
    }
}
