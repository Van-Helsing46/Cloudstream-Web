package com.cloudstreamweb

import com.cloudstreamweb.config.AppConfig
import com.cloudstreamweb.extensions.BundledExtensionRuntime
import com.cloudstreamweb.extensions.ExtensionManager
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

    // Shared client: extension downloads + streaming proxy (no global request
    // timeout: streams are long-lived).
    val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
    val extensionManager = ExtensionManager(
        registry = registry,
        runtime = BundledExtensionRuntime(),
        stateDir = File(config.dataDir, "extensions-state"),
        http = httpClient,
    )
    extensionManager.activateInstalled()

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
