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
        val loader = URLClassLoader(arrayOf(classesDir.toURI().toURL()), MainAPI::class.java.classLoader)
        val api = findMainApi(classesDir, loader)
        if (api == null) {
            log.warn("No concrete MainAPI in recompiled '{}'", ext.internalName)
            return null
        }
        log.info("Recompiled extension '{}' from source → provider '{}'", ext.internalName, api.name)
        return MainApiProviderAdapter(api)
    }

    // ---- Source fetching (GitHub) ----

    /** Downloads the extension's `.kt` sources into [destDir]; returns the written files. */
    private fun fetchSources(internalName: String, repoUrl: String, destDir: File): List<File> {
        val (owner, repo) = parseGitHub(repoUrl) ?: return emptyList()
        val branch = defaultBranch(owner, repo)
        val paths = treePaths(owner, repo, branch)
            .filter { it.startsWith("$internalName/") && it.contains("/src/main/kotlin/") && it.endsWith(".kt") }
            // Android-only entry point + settings UI: not needed to run the provider.
            .filterNot { it.endsWith("Plugin.kt") || it.endsWith("Settings.kt") }

        val written = mutableListOf<File>()
        var pkg: String? = null
        var referencesBuildConfig = false
        for (path in paths) {
            val body = get("https://raw.githubusercontent.com/$owner/$repo/$branch/$path") ?: continue
            // Mirror the repo package path so relative refs resolve.
            val rel = path.substringAfter("/src/main/kotlin/")
            val out = File(destDir, rel).also { it.parentFile.mkdirs() }
            out.writeText(body)
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

    private fun defaultBranch(owner: String, repo: String): String {
        val body = get("https://api.github.com/repos/$owner/$repo") ?: return "master"
        return Regex("\"default_branch\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "master"
    }

    private fun treePaths(owner: String, repo: String, branch: String): List<String> {
        val body = get("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1") ?: return emptyList()
        return Regex("\"path\"\\s*:\\s*\"([^\"]+\\.kt)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun parseGitHub(repoUrl: String): Pair<String, String>? {
        val m = Regex("github\\.com[/:]([^/]+)/([^/#?]+)").find(repoUrl) ?: return null
        return m.groupValues[1] to m.groupValues[2].removeSuffix(".git")
    }

    private fun get(url: String): String? {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "cloudstream-web")
            .header("Accept", "application/vnd.github+json")
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        return if (resp.statusCode() in 200..299) resp.body() else {
            log.warn("GET {} -> HTTP {}", url, resp.statusCode()); null
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
        val exit = K2JVMCompiler().exec(collector, Services.EMPTY, args)
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

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
