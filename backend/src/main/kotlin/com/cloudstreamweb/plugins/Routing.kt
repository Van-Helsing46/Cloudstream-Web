package com.cloudstreamweb.plugins

import com.cloudstreamweb.config.AppConfig
import com.cloudstreamweb.domain.SearchResponse
import com.cloudstreamweb.extensions.ExtensionManager
import com.cloudstreamweb.library.AddWatchlistRequest
import com.cloudstreamweb.library.CreateProfileRequest
import com.cloudstreamweb.library.LibraryService
import com.cloudstreamweb.library.LibraryStore
import com.cloudstreamweb.library.ProfileStore
import com.cloudstreamweb.library.ProgressRequest
import com.cloudstreamweb.library.UpdateProfileRequest
import com.cloudstreamweb.provider.ProviderRegistry
import com.cloudstreamweb.proxy.streamProxy
import io.ktor.client.HttpClient
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

@Serializable
data class AddRepositoryRequest(val url: String)

@Serializable
data class LoginRequest(val password: String)

fun Application.configureRouting(
    registry: ProviderRegistry,
    extensionManager: ExtensionManager,
    httpClient: HttpClient,
    profiles: ProfileStore,
    libraryService: LibraryService,
    config: AppConfig,
) {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        route("/api/v1") {

            // ---- Auth ----
            route("/auth") {
                get("/status") {
                    val authed = !config.authEnabled ||
                        SessionAuth.tokenValid(
                            call.request.cookies[SESSION_COOKIE],
                            SessionAuth.expectedToken(config),
                        )
                    call.respond(mapOf("authRequired" to config.authEnabled, "authenticated" to authed))
                }

                post("/login") {
                    if (!config.authEnabled) {
                        return@post call.respond(mapOf("authenticated" to true))
                    }
                    val req = call.receive<LoginRequest>()
                    if (req.password != config.authPassword) {
                        return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "wrong password"),
                        )
                    }
                    call.response.cookies.append(
                        Cookie(
                            name = SESSION_COOKIE,
                            value = SessionAuth.expectedToken(config),
                            httpOnly = true,
                            secure = call.isHttps(),
                            path = "/",
                            maxAge = 60 * 60 * 24 * 30, // 30 days
                            extensions = mapOf("SameSite" to "Lax"),
                            encoding = CookieEncoding.RAW,
                        ),
                    )
                    call.respond(mapOf("authenticated" to true))
                }

                post("/logout") {
                    call.response.cookies.append(
                        Cookie(SESSION_COOKIE, "", path = "/", maxAge = 0, encoding = CookieEncoding.RAW),
                    )
                    call.respond(mapOf("authenticated" to false))
                }
            }


            // Active providers
            get("/providers") {
                call.respond(registry.all().map { it.info })
            }

            // Aggregated search across the active providers (or a single one via ?provider=).
            // Parallel and fault-tolerant: a broken/slow provider does not sink the
            // response — it ends up in `errors` with its message.
            get("/search") {
                val query = call.request.queryParameters.getOrFail("q")
                val providerId = call.request.queryParameters["provider"]
                val targets = providerId
                    ?.let { listOfNotNull(registry.get(it)) }
                    ?: registry.all()

                val outcomes = coroutineScope {
                    targets.map { provider ->
                        async {
                            provider.info.id to runCatching {
                                withTimeout(config.providerSearchTimeoutMs) { provider.search(query) }
                            }
                        }
                    }.awaitAll()
                }
                call.respond(
                    SearchResponse(
                        query = query,
                        results = outcomes.flatMap { (_, r) -> r.getOrDefault(emptyList()) },
                        errors = outcomes.mapNotNull { (id, r) ->
                            r.exceptionOrNull()?.let { id to (it.message ?: it::class.simpleName ?: "error") }
                        }.toMap(),
                    ),
                )
            }

            // A provider's main page
            get("/providers/{providerId}/home") {
                val providerId = call.parameters.getOrFail("providerId")
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val provider = registry.get(providerId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown provider"))
                // Bound the third-party provider call so a hung/misbehaving extension can't tie up
                // the request indefinitely (same guard as search).
                call.respond(
                    com.cloudstreamweb.domain.HomeResponse(
                        providerId = providerId,
                        page = page,
                        sections = withTimeout(config.providerSearchTimeoutMs) { provider.mainPage(page) },
                    ),
                )
            }

            // Content detail
            get("/providers/{providerId}/detail") {
                val providerId = call.parameters.getOrFail("providerId")
                val id = call.request.queryParameters.getOrFail("id")
                val provider = registry.get(providerId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown provider"))
                call.respond(withTimeout(config.providerSearchTimeoutMs) { provider.load(id) })
            }

            // Streaming link resolution
            get("/providers/{providerId}/links") {
                val providerId = call.parameters.getOrFail("providerId")
                val id = call.request.queryParameters.getOrFail("id")
                val provider = registry.get(providerId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown provider"))
                call.respond(withTimeout(config.providerSearchTimeoutMs) { provider.loadLinks(id) })
            }

            // ---- Extension management ----
            route("/extensions") {

                // Registered repositories
                get("/repos") {
                    call.respond(extensionManager.repositories())
                }

                // Adds a repository (repo.json URL or directly a plugins.json one)
                post("/repos") {
                    val req = call.receive<AddRepositoryRequest>()
                    runCatching { extensionManager.addRepository(req.url) }
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "invalid repository: ${it.message}"),
                            )
                        }
                }

                delete("/repos") {
                    val url = call.request.queryParameters.getOrFail("url")
                    if (extensionManager.removeRepository(url)) call.respond(mapOf("removed" to url))
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "repository not registered"))
                }

                // Aggregated catalog (with install state and runtime support)
                get {
                    call.respond(extensionManager.listAvailable())
                }

                get("/installed") {
                    call.respond(extensionManager.installed())
                }

                post("/{internalName}/install") {
                    val name = call.parameters.getOrFail("internalName")
                    runCatching { extensionManager.install(name) }
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure {
                            val status = if (it is NoSuchElementException) HttpStatusCode.NotFound
                            else HttpStatusCode.BadGateway
                            call.respond(status, mapOf("error" to (it.message ?: "install failed")))
                        }
                }

                post("/{internalName}/update") {
                    val name = call.parameters.getOrFail("internalName")
                    runCatching { extensionManager.update(name) }
                        .onSuccess { result ->
                            if (result == null) call.respond(mapOf("upToDate" to true))
                            else call.respond(result)
                        }
                        .onFailure {
                            val status = if (it is NoSuchElementException) HttpStatusCode.NotFound
                            else HttpStatusCode.BadGateway
                            call.respond(status, mapOf("error" to (it.message ?: "update failed")))
                        }
                }

                delete("/{internalName}") {
                    val name = call.parameters.getOrFail("internalName")
                    if (extensionManager.uninstall(name)) call.respond(mapOf("uninstalled" to name))
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "extension not installed"))
                }
            }

            // ---- Profiles (multi-user) ----
            route("/profiles") {
                get { call.respond(profiles.list()) }

                post {
                    val req = call.receive<CreateProfileRequest>()
                    runCatching { profiles.create(req) }
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "invalid profile")))
                        }
                }

                put("/{id}") {
                    val id = call.parameters.getOrFail("id")
                    val req = call.receive<UpdateProfileRequest>()
                    runCatching { profiles.update(id, req) }
                        .onSuccess { updated ->
                            if (updated == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown profile"))
                            else call.respond(updated)
                        }
                        .onFailure {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "invalid profile")))
                        }
                }

                delete("/{id}") {
                    val id = call.parameters.getOrFail("id")
                    if (profiles.delete(id)) call.respond(mapOf("deleted" to id))
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown profile"))
                }
            }

            // ---- User library: watchlist + history/resume, per profile ----
            route("/library") {
                // Resolves the profile's store from the X-Profile-Id header; null = 400 already sent.
                suspend fun RoutingContext.libraryOrRespond(): LibraryStore? {
                    val profileId = call.request.headers["X-Profile-Id"]
                    if (profileId.isNullOrBlank() || !profiles.exists(profileId)) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "missing X-Profile-Id header or unknown profile"),
                        )
                        return null
                    }
                    return libraryService.forProfile(profileId)
                }

                get("/watchlist") {
                    val library = libraryOrRespond() ?: return@get
                    call.respond(library.watchlist())
                }

                post("/watchlist") {
                    val library = libraryOrRespond() ?: return@post
                    val req = call.receive<AddWatchlistRequest>()
                    call.respond(HttpStatusCode.Created, library.addToWatchlist(req))
                }

                delete("/watchlist") {
                    val library = libraryOrRespond() ?: return@delete
                    val providerId = call.request.queryParameters.getOrFail("providerId")
                    val mediaId = call.request.queryParameters.getOrFail("mediaId")
                    if (library.removeFromWatchlist(providerId, mediaId))
                        call.respond(mapOf("removed" to mediaId))
                    else
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "not in watchlist"))
                }

                // History; ?continue=true → one entry per series/movie, finished ones excluded
                get("/history") {
                    val library = libraryOrRespond() ?: return@get
                    val onlyContinue = call.request.queryParameters["continue"]?.toBoolean() ?: false
                    call.respond(if (onlyContinue) library.continueWatching() else library.history())
                }

                // Saved position for an episode (for the player's resume)
                get("/progress") {
                    val library = libraryOrRespond() ?: return@get
                    val providerId = call.request.queryParameters.getOrFail("providerId")
                    val episodeId = call.request.queryParameters.getOrFail("episodeId")
                    val entry = library.progressFor(providerId, episodeId)
                        ?: return@get call.respond(HttpStatusCode.NoContent)
                    call.respond(entry)
                }

                // All entries of a series/movie (detail page: "resume SxEy" + episode bars)
                get("/media-progress") {
                    val library = libraryOrRespond() ?: return@get
                    val providerId = call.request.queryParameters.getOrFail("providerId")
                    val mediaId = call.request.queryParameters.getOrFail("mediaId")
                    call.respond(library.entriesForMedia(providerId, mediaId))
                }

                post("/progress") {
                    val library = libraryOrRespond() ?: return@post
                    val req = call.receive<ProgressRequest>()
                    call.respond(library.recordProgress(req))
                }
            }

            // Streaming proxy
            streamProxy(httpClient)
        }
    }
}
