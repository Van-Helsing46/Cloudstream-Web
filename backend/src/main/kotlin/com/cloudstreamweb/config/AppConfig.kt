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
    /** FlareSolverr v1 endpoint for solving Cloudflare challenges, e.g. http://flaresolverr:8191/v1. */
    val flareSolverrUrl: String?,
    /** Static frontend build directory, if present (Docker/prod). */
    val frontendDir: File?,
    /** Log format: "json" (structured, prod) or "text" (readable, dev). */
    val logFormat: String,
    /** Resolve hostnames via DNS-over-HTTPS instead of the system resolver (bypasses ISP DNS blocks/hijacking). */
    val dohEnabled: Boolean,
    /** DNS-over-HTTPS endpoint used when [dohEnabled] is true. */
    val dohUrl: String,
    /** Validity of signed stream tokens (used by external players/downloaders), in hours. */
    val streamTokenTtlHours: Long,
    /** Path/binary name for ffmpeg (HLS→MP4 remux for downloads). */
    val ffmpegPath: String,
    /** Path/binary name for ffprobe (duration lookup, for download progress). */
    val ffprobePath: String,
    /** How long a reconstructed download stays on disk before being pruned, in hours. */
    val downloadRetentionHours: Long,
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
                flareSolverrUrl = env("FLARESOLVERR_URL")?.takeIf { it.isNotBlank() },
                frontendDir = frontend?.takeIf { it.isDirectory },
                logFormat = (env("LOG_FORMAT") ?: "text").lowercase(),
                dohEnabled = env("DOH_ENABLED")?.toBooleanStrictOrNull() ?: true,
                dohUrl = env("DOH_URL")?.takeIf { it.isNotBlank() }
                    ?: "https://cloudflare-dns.com/dns-query",
                streamTokenTtlHours = env("STREAM_TOKEN_TTL_HOURS")?.toLongOrNull() ?: 12L,
                ffmpegPath = env("FFMPEG_PATH")?.takeIf { it.isNotBlank() } ?: "ffmpeg",
                ffprobePath = env("FFPROBE_PATH")?.takeIf { it.isNotBlank() } ?: "ffprobe",
                downloadRetentionHours = env("DOWNLOAD_RETENTION_HOURS")?.toLongOrNull() ?: 24L,
            )
        }
    }
}
