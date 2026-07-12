package com.cloudstreamweb.extensions

import com.cloudstreamweb.provider.Provider
import com.lagradost.cloudstream3.MainAPI
import org.slf4j.LoggerFactory
import software.coley.dextranslator.model.ApplicationData
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/** File name under which the [ExtensionManager] stores an extension's `.cs3`. Shared so the */
/** dynamic runtime can locate the same file from an `internalName`. */
internal fun cs3FileName(internalName: String): String =
    internalName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cs3"

/**
 * Strada A runtime: runs user-installed `.cs3` extensions **without recompiling them**.
 *
 * For each extension it converts the Android `classes.dex` to a JVM jar (dex2jar), then loads it
 * with a per-extension child [URLClassLoader] whose parent is the app classpath. That delegation is
 * the crux: the extension's `com.lagradost.cloudstream3.*` references resolve against `library-jvm`
 * and our [com.lagradost.cloudstream3.network.CloudflareKiller] shim, so no per-extension patching
 * is needed. It then finds the concrete `MainAPI`, instantiates it, and adapts it via
 * [MainApiProviderAdapter].
 *
 * The gap analysis (repo-wide, 2026-07-11) showed this covers the catalog/navigation path for every
 * provider and link resolution for all but the anti-bot ones (which need the browser bridge behind
 * the [CloudflareKiller] shim's solver seam).
 *
 * SECURITY: this executes third-party bytecode. It is meant for the self-hosted/LAN deployment
 * (RNF-1); there is no sandbox yet — per-extension classloader isolation is the only boundary.
 */
class DynamicExtensionRuntime(
    private val cs3Dir: File,
    private val jarCacheDir: File,
    /**
     * Seed for providers whose only constructor takes a `String` — on Android this came from the
     * plugin's SharedPreferences (e.g. Arte: a language code). Defaults to Italian for this repo.
     */
    private val defaultConstructorSeed: String = "it",
) : ExtensionRuntime {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Best-effort set for the catalog's `runtimeSupported` flag: the installed `.cs3` basenames.
     * Activation does not rely on this — it calls [instantiate] directly — so an imperfect reverse
     * of the (rare) name sanitization only affects the advisory flag, not execution.
     */
    override val supported: Set<String>
        get() = (cs3Dir.listFiles { f -> f.isFile && f.extension == "cs3" } ?: emptyArray())
            .map { it.nameWithoutExtension }
            .toSet()

    // Converts any installed .cs3 on demand: worth advertising as installable in the catalog.
    override val attemptsAnyExtension: Boolean get() = true

    override fun cleanup(internalName: String) {
        File(jarCacheDir, "$internalName.jar").delete()
    }

    override fun instantiate(ext: InstalledExtension): ActivationOutcome {
        val internalName = ext.internalName
        val cs3 = File(cs3Dir, cs3FileName(internalName)).takeIf { it.isFile }
            ?: return ActivationOutcome.Failed("dynamic: no .cs3 on disk")
        return runCatching { loadProvider(internalName, cs3) }
            .onFailure { log.warn("Dynamic load of '{}' failed: {}", internalName, it.toString()) }
            .fold(
                onSuccess = { ActivationOutcome.Activated(it) },
                // Some JVM errors (e.g. VerifyError on malformed converted bytecode) have a
                // multi-line message with a full bytecode dump — keep only the first line for
                // the API/UI; the full detail is still in the WARN log above.
                onFailure = {
                    val reason = it.message?.lineSequence()?.first() ?: it::class.simpleName ?: "unknown error"
                    ActivationOutcome.Failed("dynamic: $reason")
                },
            )
    }

    private fun loadProvider(internalName: String, cs3: File): Provider {
        val jar = convertedJar(internalName, cs3)
        // Defense-in-depth: refuse third-party bytecode that uses process/exit/native APIs.
        check(ExtensionSecurityScanner.isSafeToLoad(ExtensionSecurityScanner.scanJar(jar), internalName)) {
            "blocked by security scan (prohibited API usage)"
        }
        // Parent = the classloader that has library-jvm + NiceHttp + the CloudflareKiller shim.
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), MainAPI::class.java.classLoader)
        val api = findMainApi(jar, loader) ?: error("no concrete MainAPI class found in converted jar")
        log.info("Dynamically loaded extension '{}' → provider '{}'", internalName, api.name)
        return MainApiProviderAdapter(api)
    }

    /** Converts `classes.dex`→jar (R8-based translation), cached until the `.cs3` changes (mtime). */
    private fun convertedJar(internalName: String, cs3: File): File {
        val jar = File(jarCacheDir, "$internalName.jar")
        if (jar.isFile && jar.lastModified() >= cs3.lastModified()) return jar
        jarCacheDir.mkdirs()
        val dex = readClassesDex(cs3)
        val classes = ApplicationData.fromDex(dex).exportToJvmClassMap()
        val tmp = File(jarCacheDir, "$internalName.jar.tmp").also { it.delete() }
        JarOutputStream(tmp.outputStream().buffered()).use { jos ->
            for ((fqName, bytes) in classes) {
                jos.putNextEntry(JarEntry(fqName.replace('.', '/') + ".class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        try {
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.FileSystemException) {
            // Windows: the previous jar can still be held open by an old URLClassLoader from an
            // earlier attempt (e.g. reinstall after a failed activation). It was converted from the
            // same .cs3, so it is byte-equivalent — use it instead of failing the whole activation.
            tmp.delete()
            if (!jar.isFile) throw e
            log.warn("Converted jar for '{}' is locked; reusing the existing one ({})", internalName, e.toString())
        }
        return jar
    }

    private fun readClassesDex(cs3: File): ByteArray =
        ZipFile(cs3).use { zip ->
            val entry = zip.getEntry("classes.dex")
                ?: error("${cs3.name}: no classes.dex (not a .cs3?)")
            zip.getInputStream(entry).use { it.readBytes() }
        }

    private fun findMainApi(jar: File, loader: ClassLoader): MainAPI? {
        val classNames = JarFile(jar).use { jf ->
            jf.entries().asSequence()
                .filter { it.name.endsWith(".class") && '$' !in it.name }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .toList()
        }
        for (name in classNames) {
            val c = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (!MainAPI::class.java.isAssignableFrom(c) ||
                Modifier.isAbstract(c.modifiers) ||
                c == MainAPI::class.java
            ) continue
            instantiateMainApi(c)?.let { return it }
        }
        return null
    }

    /** No-arg first; then a single-`String` constructor with [defaultConstructorSeed] (config seed). */
    private fun instantiateMainApi(c: Class<*>): MainAPI? {
        runCatching { return c.getDeclaredConstructor().newInstance() as MainAPI }
        val stringCtor = c.constructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        }
        return stringCtor?.let {
            runCatching { it.newInstance(defaultConstructorSeed) as MainAPI }.getOrNull()
        }
    }
}
