package com.cloudstreamweb

import com.cloudstreamweb.config.AppConfig
import com.cloudstreamweb.extensions.BundledExtensionRuntime
import com.cloudstreamweb.extensions.CompositeExtensionRuntime
import com.cloudstreamweb.extensions.DynamicExtensionRuntime
import com.cloudstreamweb.extensions.ExtensionManager
import com.cloudstreamweb.extensions.RecompilingExtensionRuntime
import com.cloudstreamweb.library.LibraryService
import com.cloudstreamweb.library.ProfileStore
import com.cloudstreamweb.plugins.configureAuth
import com.cloudstreamweb.plugins.configureHTTP
import com.cloudstreamweb.plugins.configureLogging
import com.cloudstreamweb.plugins.configureOpenApi
import com.cloudstreamweb.plugins.configureRouting
import com.cloudstreamweb.plugins.configureSerialization
import com.cloudstreamweb.plugins.configureStaticFrontend
import com.cloudstreamweb.provider.ProviderRegistry
import io.ktor.server.application.Application
import kotlinx.coroutines.launch
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

fun main() {
    val config = AppConfig.fromEnv()
    // Before any logging: logback (logback.xml) picks the appender based on this property.
    System.setProperty("LOG_FORMAT", config.logFormat)
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    // No default providers: content only comes from user-installed extensions
    // (add repo → install → the runtime activates them, if bundled/executable).
    val registry = ProviderRegistry(initial = emptyList())

    // If a FlareSolverr sidecar is configured, wire it into the CloudflareKiller shim so extension
    // extractors behind a Cloudflare challenge can resolve links instead of degrading. Static seam:
    // set once, shared by every extension's killer instance.
    config.flareSolverrUrl?.let { url ->
        com.lagradost.cloudstream3.network.CloudflareKiller.solver =
            com.cloudstreamweb.extensions.FlareSolverrSolver(url)
        environment.log.info("Cloudflare solver enabled via FlareSolverr at $url")
    }

    // Shared client: extension downloads + streaming proxy (no global request
    // timeout: streams are long-lived).
    val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
    val extensionsStateDir = File(config.dataDir, "extensions-state")
    // Order by reliability: bundled (curated) → recompiled-from-source (Strada B automated, max
    // coverage) → dynamic DEX→JAR (Strada A, fast fallback when no source is available).
    val runtime = CompositeExtensionRuntime(
        BundledExtensionRuntime(),
        RecompilingExtensionRuntime(
            workDir = File(extensionsStateDir, "recompiled"),
            tmdbApiKey = System.getenv("TMDB_API") ?: "",
        ),
        DynamicExtensionRuntime(
            cs3Dir = File(extensionsStateDir, "cs3"),
            jarCacheDir = File(extensionsStateDir, "dynamic-jars"),
        ),
    )
    val extensionManager = ExtensionManager(
        registry = registry,
        runtime = runtime,
        stateDir = extensionsStateDir,
        http = httpClient,
    )
    // Off the init path: activation does blocking network I/O + in-process compilation per
    // extension, so a slow/unreachable source host must not delay the server accepting requests.
    // `Application` is itself a CoroutineScope tied to the server lifecycle (cancelled on
    // shutdown), so this is scoped correctly without reaching for GlobalScope.
    launch(kotlinx.coroutines.Dispatchers.IO) {
        extensionManager.activateInstalled()
    }

    // Daily auto-update of every installed extension, at ~04:00 local server time. Installed
    // extensions otherwise stay pinned to the version they were installed at forever (the catalog
    // view is always live, but nothing re-installs a newer version on its own) — this and the
    // manual "update all" button in the UI are the only two ways that happens, both going through
    // ExtensionManager.updateAll(). `while (true)` + `delay()` is cancelled cleanly by the
    // Application scope on shutdown, no manual isActive check needed.
    launch(kotlinx.coroutines.Dispatchers.IO) {
        while (true) {
            val now = java.time.LocalDateTime.now()
            var next = now.withHour(4).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            environment.log.info("Next scheduled extension update check: {}", next)
            kotlinx.coroutines.delay(java.time.Duration.between(now, next).toMillis())
            runCatching { extensionManager.updateAll() }
                .onSuccess { summary ->
                    environment.log.info(
                        "Scheduled extension update: {} updated, {} up to date, {} failed{}",
                        summary.updated.size,
                        summary.upToDate.size,
                        summary.failed.size,
                        if (summary.failed.isEmpty()) "" else " (${summary.failed.joinToString { it.internalName }})",
                    )
                }
                .onFailure { environment.log.warn("Scheduled extension update check failed: {}", it.message) }
        }
    }

    // Per-profile library (multi-user) + migration from the single-user format
    val librariesDir = File(config.dataDir, "library")
    val profiles = ProfileStore(
        stateDir = File(config.dataDir, "profiles"),
        librariesDir = librariesDir,
    )
    val libraryService = LibraryService(librariesDir)
    kotlinx.coroutines.runBlocking { libraryService.migrateLegacyIfNeeded(profiles) }

    configureLogging()
    configureSerialization()
    configureHTTP(config)
    configureAuth(config)
    configureRouting(registry, extensionManager, httpClient, profiles, libraryService, config)
    configureOpenApi()
    configureStaticFrontend(config)
}
