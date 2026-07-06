package com.cloudstreamweb.extensions

import com.cloudstreamweb.provider.ProviderRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateFile = File(stateDir, "state.json")
    private val cs3Dir = File(stateDir, "cs3")
    private val mutex = Mutex()

    private var state: ExtensionsState = runCatching {
        json.decodeFromString<ExtensionsState>(stateFile.readText())
    }.getOrDefault(ExtensionsState())

    /** internalName → registered providerId (for unregister on uninstall). */
    private val activeProviders = mutableMapOf<String, String>()

    // ---- Repositories ----

    fun repositories(): List<RepositoryRef> = state.repositories

    /** Registers a repository from a `repo.json` URL or directly a `plugins.json` one. */
    suspend fun addRepository(url: String): RepositoryRef {
        val body = http.get(url).bodyAsText()
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
                    runtimeSupported = m.internalName in runtime.supported,
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
        state = state.copy(installed = state.installed.filter { it.internalName != internalName })
        save()
        true
    }

    /** On startup: reactivates the providers of installed extensions the runtime supports. */
    fun activateInstalled() {
        state.installed.forEach { tryActivate(it.internalName) }
    }

    // ---- Internals ----

    private suspend fun installFromManifest(manifest: PluginManifest): InstallResult {
        val bytes = http.get(manifest.url).bodyAsBytes()
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

        val active = tryActivate(entry.internalName)
        return InstallResult(
            extension = entry,
            runtimeActive = active,
            message = if (active) null else
                "Installed, but the JVM runtime does not (yet) have a recompiled version of this extension",
        )
    }

    private fun tryActivate(internalName: String): Boolean {
        if (internalName in activeProviders) return true
        val provider = runtime.instantiate(internalName) ?: return false
        registry.register(provider)
        activeProviders[internalName] = provider.info.id
        return true
    }

    private suspend fun findManifest(internalName: String): PluginManifest? =
        state.repositories
            .flatMap { it.pluginLists }
            .distinct()
            .flatMap { fetchPluginList(it) }
            .firstOrNull { it.internalName == internalName }

    private suspend fun fetchPluginList(url: String): List<PluginManifest> =
        json.decodeFromString(http.get(url).bodyAsText())

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

    private fun cs3File(internalName: String) =
        File(cs3Dir, internalName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cs3")

    private fun save() {
        stateDir.mkdirs()
        com.cloudstreamweb.library.atomicWrite(
            stateFile,
            json.encodeToString(ExtensionsState.serializer(), state),
        )
    }
}
