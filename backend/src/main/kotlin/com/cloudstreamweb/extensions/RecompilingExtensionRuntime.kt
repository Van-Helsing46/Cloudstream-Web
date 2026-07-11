package com.cloudstreamweb.extensions

import com.cloudstreamweb.provider.Provider
import com.lagradost.cloudstream3.MainAPI
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Modifier
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Strada B automated — recompile an extension from its public source against the current
 * `library-jvm` + our shims, instead of running its shipped (Android-built) bytecode.
 *
 * WHY THIS EXISTS
 * ---------------
 * Dynamic DEX loading ([DynamicExtensionRuntime]) runs the extension's bytecode as-is, which
 * inlines whatever version of Cloudstream's `parseJson` the extension was built against. Older
 * inlined versions resolve the kotlinx `serializer()` eagerly outside the Jackson fallback, so
 * extensions with Jackson DTOs fail at runtime with "Serializer not found" (e.g. StreamingCommunity).
 * Recompiling from source inlines the *current* `parseJson` (correct Jackson fallback) and lets the
 * serialization plugin regenerate anything needed — fixing that whole class of failures. Proven:
 * recompiled StreamingCommunity returns 501 titles where the dynamic path returned none.
 *
 * HOW
 * ---
 * From the extension's `repositoryUrl` (Cloudstream convention: one folder per provider named after
 * the extension, sources under `src/main/kotlin`), fetch the `.kt` files (skipping the Android-only
 * `*Plugin.kt`/`Settings.kt`), synthesize a `BuildConfig` if referenced, and compile them with the
 * embedded Kotlin compiler against the backend's own classpath (which carries `library-jvm`,
 * NiceHttp, Jackson, jsoup, coroutines and the `CloudflareKiller`/`android.util.Log` shims). Then
 * load the classes, find the concrete `MainAPI`, and adapt it.
 *
 * SECURITY: compiles and runs third-party source; intended for the self-hosted/LAN deployment
 * (RNF-1). Per-extension classloader is the only isolation so far.
 */
class RecompilingExtensionRuntime(
    private val workDir: File,
    /** Injected into a synthesized `BuildConfig.TMDB_API` when an extension references it. */
    private val tmdbApiKey: String = "",
) : ExtensionRuntime {

    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    // Advisory only: this runtime attempts any extension that has a source repo, but it cannot
    // promise success pre-install, so it does not advertise names for the catalog `runtimeSupported`
    // flag. Actual capability is reported by the install result.
    override val supported: Set<String> get() = emptySet()

    // Recompiles any extension that has public source: worth advertising as installable.
    override val attemptsAnyExtension: Boolean get() = true

    override fun cleanup(internalName: String) {
        val name = sanitize(internalName)
        // Remove this extension's per-version class caches and its source checkout, precisely
        // (the `-<version>`/`-src` suffix avoids clobbering a differently-named extension).
        val mine = Regex("^" + Regex.escape(name) + "-(\\d+|src)$")
        workDir.listFiles { f -> f.isDirectory && mine.matches(f.name) }?.forEach { it.deleteRecursively() }
    }

    override fun instantiate(ext: InstalledExtension): Provider? {
        val repoUrl = ext.repositoryUrl?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { recompileAndLoad(ext, repoUrl) }
            .onFailure { log.warn("Recompile of '{}' failed: {}", ext.internalName, it.toString()) }
            .getOrNull()
    }

    private fun recompileAndLoad(ext: InstalledExtension, repoUrl: String): Provider? {
        val classesDir = File(workDir, "${sanitize(ext.internalName)}-${ext.version}")
        val marker = File(classesDir, ".compiled")
        if (!marker.isFile) {
            val srcDir = File(workDir, "${sanitize(ext.internalName)}-src").also { it.deleteRecursively(); it.mkdirs() }
            val sources = fetchSources(ext.internalName, repoUrl, srcDir)
            if (sources.isEmpty()) {
                log.warn("No Kotlin sources found for '{}' under {}", ext.internalName, repoUrl)
                return null
            }
            classesDir.deleteRecursively(); classesDir.mkdirs()
            compile(sources, classesDir)
            marker.writeText(java.time.Instant.now().toString())
        }
        // Defense-in-depth: refuse recompiled code that uses process/exit/native APIs.
        if (!ExtensionSecurityScanner.isSafeToLoad(ExtensionSecurityScanner.scanClassesDir(classesDir), ext.internalName)) {
            return null
        }
        val loader = URLClassLoader(arrayOf(classesDir.toURI().toURL()), MainAPI::class.java.classLoader)
        val api = findMainApi(classesDir, loader)
        if (api == null) {
            log.warn("No concrete MainAPI in recompiled '{}'", ext.internalName)
            return null
        }
        log.info("Recompiled extension '{}' from source → provider '{}'", ext.internalName, api.name)
        return MainApiProviderAdapter(api)
    }

    // ---- Source fetching (GitHub / GitLab / Gitea·Forgejo) ----

    /** Downloads the extension's `.kt` sources into [destDir]; returns the written files. */
    private fun fetchSources(internalName: String, repoUrl: String, destDir: File): List<File> {
        val forge = forgeOf(repoUrl) ?: run { log.warn("Unsupported repo host: {}", repoUrl); return emptyList() }
        // Repos publish source either on the default branch (built .cs3 on a side branch) or on a
        // side branch (`code`/`src`) with the default branch holding only the build. Probe both.
        val branches = (listOf(forge.defaultBranch()) + SOURCE_BRANCHES).distinct()
        for (branch in branches) {
            val paths = forge.kotlinPaths(branch)
                .filter { it.startsWith("$internalName/") && it.contains("/src/main/kotlin/") }
            if (paths.isNotEmpty()) return download(forge, branch, paths, destDir)
        }
        return emptyList()
    }

    private fun download(forge: GitForge, branch: String, paths: List<String>, destDir: File): List<File> {
        val written = mutableListOf<File>()
        var pkg: String? = null
        var referencesBuildConfig = false
        for (path in paths) {
            val body = get(forge.rawUrl(branch, path)) ?: continue
            // Android UI/entry-point files (Plugin/Settings/Fragment/Activity) aren't needed to run
            // the provider and won't compile without the Android SDK — skip them by their imports.
            if (isAndroidCoupled(body)) continue
            // Mirror the repo package path so relative refs resolve.
            val rel = path.substringAfter("/src/main/kotlin/")
            val out = File(destDir, rel).also { it.parentFile.mkdirs() }
            // The source targets whatever library-jvm API it was built against; the current one may
            // have hardened some APIs to error-level deprecations or added opt-in requirements.
            // Suppress those so a version-drifted-but-valid provider still compiles.
            out.writeText(SUPPRESS_HEADER + body)
            written += out
            if (pkg == null) pkg = Regex("(?m)^package\\s+([\\w.]+)").find(body)?.groupValues?.get(1)
            if ("BuildConfig" in body) referencesBuildConfig = true
        }
        // Synthesize BuildConfig if the sources use it (the Android build generated it from gradle).
        if (referencesBuildConfig && pkg != null) {
            val pkgDir = File(destDir, pkg.replace('.', '/')).also { it.mkdirs() }
            val buildConfig = File(pkgDir, "BuildConfig.kt")
            buildConfig.writeText(
                "package $pkg\n" +
                    "object BuildConfig { const val TMDB_API: String = \"${tmdbApiKey.replace("\"", "")}\" }\n",
            )
            written += buildConfig
        }
        return written
    }

    private fun forgeOf(repoUrl: String): GitForge? {
        val host = runCatching { URI.create(repoUrl).host }.getOrNull()?.lowercase() ?: return null
        val m = Regex("https?://[^/]+/([^/]+)/([^/#?]+)").find(repoUrl) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        // Probing several branches means some API/tree calls legitimately 404 — fetch them quietly.
        val quietGet: (String) -> String? = { url -> get(url, quiet = true) }
        return when {
            host == "github.com" -> GitHubForge(owner, repo, quietGet)
            "gitlab" in host -> GitLabForge(host, owner, repo, quietGet)
            else -> GiteaForge(host, owner, repo, quietGet) // Gitea/Forgejo: Disroot, Codeberg, …
        }
    }

    /** HTTP GET returning the body on 2xx, else null. [quiet] suppresses the warning (branch probes). */
    private fun get(url: String, quiet: Boolean = false): String? {
        val req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "cloudstream-web").build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        return if (resp.statusCode() in 200..299) resp.body() else {
            if (!quiet) log.warn("GET {} -> HTTP {}", url, resp.statusCode()); null
        }
    }

    // ---- Compilation ----

    private fun compile(sources: List<File>, outDir: File) {
        // In-process compilation: the backend's own classpath carries every dependency the extension
        // needs (library-jvm, NiceHttp, Jackson, jsoup, coroutines) plus our shims and the embedded
        // Kotlin compiler. The classpath is passed as an argument (not a command line), which avoids
        // the OS command-length limit that a huge dependency classpath would blow past.
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) errors += (location?.let { "${it.path}:${it.line}: " } ?: "") + message
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = sources.map { it.absolutePath }
            classpath = System.getProperty("java.class.path")
            destination = outDir.absolutePath
            jvmTarget = "21"
            noStdlib = true
            noReflect = true
        }
        // The embedded compiler shares JVM-wide static state (intellij-core), so it is not safe to
        // run concurrently — serialize compilations. They are infrequent (install/update only).
        val exit = synchronized(COMPILE_LOCK) { K2JVMCompiler().exec(collector, Services.EMPTY, args) }
        check(exit.code == 0 && outDir.walkTopDown().any { it.extension == "class" }) {
            "kotlinc failed ($exit)" + if (errors.isEmpty()) "" else ":\n" + errors.take(10).joinToString("\n")
        }
    }

    // ---- Loading ----

    private fun findMainApi(classesDir: File, loader: ClassLoader): MainAPI? {
        val names = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name }
            .map { it.relativeTo(classesDir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .toList()
        for (name in names) {
            val c = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (!MainAPI::class.java.isAssignableFrom(c) ||
                Modifier.isAbstract(c.modifiers) || c == MainAPI::class.java
            ) continue
            runCatching { return c.getDeclaredConstructor().newInstance() as MainAPI }
            val strCtor = c.constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
            strCtor?.let { runCatching { return it.newInstance("it") as MainAPI } }
        }
        return null
    }

    /** True if the file needs the Android SDK (androidx, or android.* other than util.Log). */
    private fun isAndroidCoupled(body: String): Boolean =
        Regex("(?m)^\\s*import\\s+androidx\\.").containsMatchIn(body) ||
            Regex("(?m)^\\s*import\\s+android\\.(?!util\\.)").containsMatchIn(body)

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** Serializes in-process Kotlin compilations (the embedded compiler isn't concurrency-safe). */
private val COMPILE_LOCK = Any()

/** Prepended to each recompiled source: tolerate API drift (error-deprecations, opt-in) vs current library-jvm. */
private const val SUPPRESS_HEADER =
    "@file:Suppress(\"DEPRECATION\", \"DEPRECATION_ERROR\", \"OPT_IN_USAGE\", \"OPT_IN_USAGE_ERROR\", " +
        "\"UNCHECKED_CAST\", \"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\")\n"

/** Branches to probe for source when the default branch only holds the built `.cs3`. */
private val SOURCE_BRANCHES = listOf("code", "src", "master", "main", "dev")

/** Matches a `.kt` file path in a git host's tree/JSON listing. */
private val KT_PATH = Regex("\"path\"\\s*:\\s*\"([^\"]+\\.kt)\"")

private val DEFAULT_BRANCH = Regex("\"default_branch\"\\s*:\\s*\"([^\"]+)\"")

/** Minimal git-host abstraction: enough to list an extension's Kotlin sources and fetch them raw. */
private interface GitForge {
    fun defaultBranch(): String
    /** All `.kt` file paths in [branch]'s tree (empty if the branch/tree is missing). */
    fun kotlinPaths(branch: String): List<String>
    fun rawUrl(branch: String, path: String): String
}

private class GitHubForge(val owner: String, val repo: String, val get: (String) -> String?) : GitForge {
    override fun defaultBranch() =
        get("https://api.github.com/repos/$owner/$repo")?.let { DEFAULT_BRANCH.find(it)?.groupValues?.get(1) } ?: "master"

    override fun kotlinPaths(branch: String) =
        get("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1")
            ?.let { body -> KT_PATH.findAll(body).map { it.groupValues[1] }.toList() } ?: emptyList()

    override fun rawUrl(branch: String, path: String) =
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
}

private class GitLabForge(val host: String, val owner: String, val repo: String, val get: (String) -> String?) : GitForge {
    private val project = java.net.URLEncoder.encode("$owner/$repo", Charsets.UTF_8)

    override fun defaultBranch() =
        get("https://$host/api/v4/projects/$project")?.let { DEFAULT_BRANCH.find(it)?.groupValues?.get(1) } ?: "main"

    override fun kotlinPaths(branch: String): List<String> {
        val paths = mutableListOf<String>()
        var page = 1
        while (page <= 25) { // page cap: guards against pathological repos
            val url = "https://$host/api/v4/projects/$project/repository/tree" +
                "?recursive=true&per_page=100&page=$page&ref=$branch"
            val body = get(url) ?: break
            val entries = Regex("\"path\"").findAll(body).count()
            paths += KT_PATH.findAll(body).map { it.groupValues[1] }
            if (entries < 100) break // last page
            page++
        }
        return paths
    }

    override fun rawUrl(branch: String, path: String) = "https://$host/$owner/$repo/-/raw/$branch/$path"
}

private class GiteaForge(val host: String, val owner: String, val repo: String, val get: (String) -> String?) : GitForge {
    override fun defaultBranch() =
        get("https://$host/api/v1/repos/$owner/$repo")?.let { DEFAULT_BRANCH.find(it)?.groupValues?.get(1) } ?: "main"

    override fun kotlinPaths(branch: String) =
        get("https://$host/api/v1/repos/$owner/$repo/git/trees/$branch?recursive=true&per_page=1000")
            ?.let { body -> KT_PATH.findAll(body).map { it.groupValues[1] }.toList() } ?: emptyList()

    override fun rawUrl(branch: String, path: String) = "https://$host/$owner/$repo/raw/branch/$branch/$path"
}
