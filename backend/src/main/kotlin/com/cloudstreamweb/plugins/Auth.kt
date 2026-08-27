package com.cloudstreamweb.plugins

import com.cloudstreamweb.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val SESSION_COOKIE = "cs_session"

/**
 * Minimal auth for self-hosted/LAN use: single password via env.
 * Stateless session: the token is HMAC-SHA256(password, secret), so it survives restarts
 * and needs no storage. Carried in an httpOnly cookie, so it also travels automatically on
 * the video tag's requests (hls.js) to the streaming proxy, which cannot send custom headers.
 *
 * If `authPassword` is empty, auth is disabled (handy in development).
 */
object SessionAuth {
    fun expectedToken(config: AppConfig): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.authSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal((config.authPassword ?: "").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison to avoid leaking information via timing. */
    fun tokenValid(provided: String?, expected: String): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
    }

    private fun hmac(config: AppConfig, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.authSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Short-lived signed token for the streaming proxy / download endpoints, so a copied
     * link works in external players (VLC, curl, download managers) that cannot carry the
     * httpOnly session cookie. Format: "<expiryEpochSeconds>.<hmac>".
     */
    fun streamToken(config: AppConfig, expiresAtEpochSec: Long): String {
        val message = "stream:$expiresAtEpochSec"
        return "$expiresAtEpochSec.${hmac(config, message)}"
    }

    fun streamTokenValid(provided: String?, config: AppConfig): Boolean {
        if (provided == null) return false
        val dot = provided.indexOf('.')
        if (dot <= 0) return false
        val expiry = provided.substring(0, dot).toLongOrNull() ?: return false
        if (expiry < System.currentTimeMillis() / 1000) return false
        val expected = hmac(config, "stream:$expiry")
        return MessageDigest.isEqual(provided.substring(dot + 1).toByteArray(), expected.toByteArray())
    }
}

/**
 * Rejects unauthenticated API requests with 401, except the public auth endpoints and
 * `/health`. The static frontend stays public: the login gate is client-side and the
 * underlying APIs are protected anyway.
 */
fun Application.configureAuth(config: AppConfig) {
    if (!config.authEnabled) return
    val expected = SessionAuth.expectedToken(config)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val isApi = path.startsWith("/api/")
        val isPublic = path == "/api/v1/auth/login" || path == "/api/v1/auth/status"
        if (!isApi || isPublic) return@intercept

        // The streaming proxy and download endpoints are also reachable via a short-lived
        // signed `?token=`, since external players/downloaders cannot carry the httpOnly
        // session cookie (Auth.kt doc above explains why the cookie exists in the first place).
        val isStreamLike = path == "/api/v1/stream" || path.startsWith("/api/v1/downloads")
        if (isStreamLike) {
            val queryToken = call.request.queryParameters["token"]
            if (queryToken != null && SessionAuth.streamTokenValid(queryToken, config)) return@intercept
        }

        val token = call.request.cookies[SESSION_COOKIE]
        if (!SessionAuth.tokenValid(token, expected)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            finish()
        }
    }
}

/** true if the request came in over HTTPS (for the cookies' `Secure` flag). */
internal fun ApplicationCall.isHttps(): Boolean = request.origin.scheme == "https"
