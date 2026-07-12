package com.cloudstreamweb.extensions

import com.cloudstreamweb.provider.ProviderRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Extension lifecycle management:
 * repository → plugin catalog → install/update/uninstall → runtime activation.
 *
 * With the recompile approach (see [ExtensionRuntime]) the downloaded `.cs3` is NOT directly executable
 * (it is DEX): install archives it and records the metadata; the provider only becomes active if the
 * runtime has the JVM-recompiled version of that extension. The others stay installed
 * with `runtimeActive=false`, ready for when recompilation (or DEX→JAR) covers them.
 *
 * State is persisted in `<stateDir>/state.json`; the `.cs3` files in `<stateDir>/cs3/`.
 */
class ExtensionManager(
    private val registry: ProviderRegistry,
    private val runtime: ExtensionRuntime,
    private val stateDir: File,
    private val http: HttpClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateFile = File(stateDir, "state.json")
    private val cs3Dir = File(stateDir, "cs3")
    private val mutex = Mutex()

    // @Volatile: read from route handlers without the mutex (repositories/installed/listAvailable);
    // writes always go through mutex.withLock, so this only needs to guarantee cross-thread
    // visibility of the reference swap, not exclusion.
    @Volatile
    private var state: ExtensionsState = runCatching {
        json.decodeFromString<ExtensionsState>(stateFile.readText())
    }.getOrDefault(ExtensionsState())

    /** internalName → registered providerId (for unregister on uninstall). */
    private val activeProviders = mutableMapOf<String, String>()

    // ---- Repositories ----

    fun repositories(): List<RepositoryRef> = state.repositories

    /** Registers a repository from a `repo.json` URL or directly a `plugins.json` one. */
    suspend fun addRepository(url: String): RepositoryRef {
        val body = withTimeout(NETWORK_TIMEOUT_MS) { http.get(url).bodyAsText() }
        val ref = if (body.trimStart().startsWith("[")) {
            // direct plugins.json: the repo "is" its only plugin list
            RepositoryRef(url = url, name = url.substringAfterLast('/'), pluginLists = listOf(url))
        } else {
            val manifest = json.decodeFromString<RepoManifest>(body)
            require(manifest.pluginLists.isNotEmpty()) { "repo.json without pluginLists: $url" }
            RepositoryRef(url = url, name = manifest.name, pluginLists = manifest.pluginLists)
        }
        mutex.withLock {
            state = state.copy(repositories = state.repositories.filter { it.url != url } + ref)
            save()
        }
        return ref
    }

    suspend fun removeRepository(url: String): Boolean = mutex.withLock {
        val before = state.repositories.size
        state = state.copy(repositories = state.repositories.filter { it.url != url })
        save()
        state.repositories.size < before
    }

    /** Aggregated plugin catalog across all registered repositories. */
    suspend fun listAvailable(): List<AvailablePlugin> {
        val installedByName = state.installed.associateBy { it.internalName }
        return state.repositories
            .flatMap { repo -> repo.pluginLists }
            .distinct()
            .flatMap { listUrl -> fetchPluginList(listUrl) }
            .distinctBy { it.internalName }
            .map { m ->
                AvailablePlugin(
                    internalName = m.internalName,
                    name = m.name,
                    version = m.version,
                    status = m.status,
                    description = m.description,
                    language = m.language,
                    tvTypes = m.tvTypes,
                    iconUrl = m.iconUrl,
                    repositoryUrl = m.repositoryUrl,
                    installedVersion = installedByName[m.internalName]?.version,
                    // Executable if a runtime already knows it, or if a runtime will attempt any
                    // extension on demand (dynamic DEX / recompile-from-source).
                    runtimeSupported = m.internalName in runtime.supported || runtime.attemptsAnyExtension,
                    active = installedByName[m.internalName]?.active,
                    activationError = installedByName[m.internalName]?.activationError,
                )
            }
    }

    fun installed(): List<InstalledExtension> = state.installed

    // ---- Install / update / uninstall ----

    suspend fun install(internalName: String): InstallResult {
        val manifest = findManifest(internalName)
            ?: throw NoSuchElementException("Plugin '$internalName' not found in the registered repositories")
        return installFromManifest(manifest)
    }

    /** Reinstalls if the repository has a newer version. Returns null if already up to date. */
    suspend fun update(internalName: String): InstallResult? {
        val current = state.installed.firstOrNull { it.internalName == internalName }
            ?: throw NoSuchElementException("Extension '$internalName' is not installed")
        val manifest = findManifest(internalName)
            ?: throw NoSuchElementException("Plugin '$internalName' no longer present in the repositories")
        if (manifest.version <= current.version) return null
        return installFromManifest(manifest)
    }

    suspend fun uninstall(internalName: String): Boolean = mutex.withLock {
        val entry = state.installed.firstOrNull { it.internalName == internalName } ?: return false
        activeProviders.remove(internalName)?.let(registry::unregister)
        cs3File(entry.internalName).delete()
        runtime.cleanup(entry.internalName) // drop the runtime's cached build artifacts
        state = state.copy(installed = state.installed.filter { it.internalName != internalName })
        save()
        true
    }

    /**
     * On startup: reactivates the providers of installed extensions the runtime supports.
     * Recompiling/dex-converting can mean blocking network calls and an in-process kotlinc run
     * per extension — call this from a background coroutine (see `Application.kt`), not the
     * server's init path, so a slow/unreachable source host doesn't delay accepting requests.
     * Being suspend (not a fire-and-forget thread) lets it share [mutex] with install/uninstall,
     * which now run concurrently with it instead of before it.
     */
    suspend fun activateInstalled() {
        val results = state.installed.map { it.internalName to tryActivate(it) }
        val failed = results.filter { !it.second }.map { it.first }
        log.info(
            "Extension activation: {} active, {} failed{}",
            results.size - failed.size,
            failed.size,
            if (failed.isEmpty()) "" else " (${failed.joinToString()}) — see per-extension WARN logs above",
        )
    }

    // ---- Internals ----

    private suspend fun installFromManifest(manifest: PluginManifest): InstallResult {
        val bytes = withTimeout(NETWORK_TIMEOUT_MS) { http.get(manifest.url).bodyAsBytes() }
        verifyHash(bytes, manifest.fileHash)

        val entry = InstalledExtension(
            internalName = manifest.internalName,
            name = manifest.name,
            version = manifest.version,
            cs3Url = manifest.url,
            repositoryUrl = manifest.repositoryUrl,
            language = manifest.language,
            installedAt = Instant.now().toString(),
        )
        mutex.withLock {
            cs3Dir.mkdirs()
            cs3File(entry.internalName).writeBytes(bytes)
            state = state.copy(
                installed = state.installed.filter { it.internalName != entry.internalName } + entry,
            )
            save()
        }

        val active = tryActivate(entry)
        val persisted = state.installed.firstOrNull { it.internalName == entry.internalName } ?: entry
        return InstallResult(
            extension = persisted,
            runtimeActive = active,
            message = if (active) null else persisted.activationError
                ?: "Installed, but the JVM runtime could not execute this extension",
        )
    }

    private suspend fun tryActivate(ext: InstalledExtension): Boolean {
        if (ext.internalName in activeProviders) return true
        // Not locked: recompiling/converting can take seconds and must not block other
        // installs/uninstalls or a concurrent activateInstalled() pass on unrelated extensions.
        val outcome = runtime.instantiate(ext)
        return mutex.withLock {
            // Re-check under the lock: another activation for the same extension (e.g. the
            // startup pass racing a user-triggered reinstall) may have already won.
            if (ext.internalName in activeProviders) return@withLock true
            when (outcome) {
                is ActivationOutcome.Activated -> {
                    registry.register(outcome.provider)
                    activeProviders[ext.internalName] = outcome.provider.info.id
                    updateActivationStateLocked(ext.internalName, active = true, reason = null)
                    true
                }
                is ActivationOutcome.Failed -> {
                    updateActivationStateLocked(ext.internalName, active = false, reason = outcome.reason)
                    false
                }
            }
        }
    }

    /** Records the outcome of the latest activation attempt on the persisted entry, for the API/UI.
     *  Must be called with [mutex] held. */
    private fun updateActivationStateLocked(internalName: String, active: Boolean, reason: String?) {
        val idx = state.installed.indexOfFirst { it.internalName == internalName }
        if (idx < 0) return
        val updated = state.installed.toMutableList()
        updated[idx] = updated[idx].copy(active = active, activationError = reason)
        state = state.copy(installed = updated)
        save()
    }

    private suspend fun findManifest(internalName: String): PluginManifest? =
        state.repositories
            .flatMap { it.pluginLists }
            .distinct()
            .flatMap { fetchPluginList(it) }
            .firstOrNull { it.internalName == internalName }

    private suspend fun fetchPluginList(url: String): List<PluginManifest> =
        json.decodeFromString(withTimeout(NETWORK_TIMEOUT_MS) { http.get(url).bodyAsText() })

    private fun verifyHash(bytes: ByteArray, expected: String?) {
        if (expected.isNullOrBlank()) return
        val algo = expected.substringBefore('-', "sha256").ifBlank { "sha256" }
        val hex = MessageDigest.getInstance(algo.uppercase().replace("SHA", "SHA-"))
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(hex.equals(expected.substringAfter('-'), ignoreCase = true)) {
            "Mismatched .cs3 hash (expected $expected, computed $algo-$hex)"
        }
    }

    private fun cs3File(internalName: String) = File(cs3Dir, cs3FileName(internalName))

    private fun save() {
        stateDir.mkdirs()
        com.cloudstreamweb.library.atomicWrite(
            stateFile,
            json.encodeToString(ExtensionsState.serializer(), state),
        )
    }
}

/** Bounds repository/plugin-list/`.cs3` fetches so an unreachable host can't hang install/update. */
private const val NETWORK_TIMEOUT_MS = 30_000L
