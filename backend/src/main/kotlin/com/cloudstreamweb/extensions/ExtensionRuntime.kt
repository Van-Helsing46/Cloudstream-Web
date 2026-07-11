package com.cloudstreamweb.extensions

import com.cloudstreamweb.provider.Provider

/**
 * Cloudstream extension runtime on the JVM: providers are recompiled from source against
 * `com.github.recloudstream.cloudstream:library-jvm` and instantiated as plain JVM classes.
 *
 * There is no DEX→JAR conversion: the `.cs3` downloaded by the [ExtensionManager] is only an
 * archived artifact (metadata/versioning); execution goes through the recompiled version. The
 * bridge is "instantiate the provider (`MainAPI`) and adapt it to the internal domain" via
 * [MainApiProviderAdapter]. The Android coupling of the original extension (the
 * `Plugin.load(Context)` and the Settings UI) is rewritten server-side: configuration that on
 * Android came from SharedPreferences becomes a constructor parameter.
 */
interface ExtensionRuntime {
    /** `internalName`s (as in the repository manifest) this runtime knows it can execute up front. */
    val supported: Set<String>

    /**
     * True if the runtime attempts *any* extension (converting/recompiling on demand), so success
     * can't be known before install. Used by the catalog to still advertise it as installable.
     */
    val attemptsAnyExtension: Boolean get() = false

    /** Instantiates the provider for [ext], or null if the runtime cannot execute it. */
    fun instantiate(ext: InstalledExtension): Provider?

    /** Deletes any cached build artifacts for [internalName] (converted jars, recompiled classes). */
    fun cleanup(internalName: String) {}
}

/**
 * First production runtime: **bundled** extensions, compiled together with the backend
 * (sources in `extensions/bundled/`). The factory list plays the role of the Android
 * `Plugin.load()`. Next step: on-demand source compilation (or DEX→JAR for `.cs3` files).
 *
 * The public repository bundles no extensions: the map is empty. To make one executable,
 * copy its Kotlin source into `extensions/bundled/` and register the factory here with the
 * `internalName` from the repository manifest, for example:
 * ```
 * "ExtensionName" to { MainApiProviderAdapter(original.pkg.ExtensionName()) },
 * ```
 * (full walkthrough in `extensions/README.md`).
 */
class BundledExtensionRuntime : ExtensionRuntime {

    private val factories: Map<String, () -> Provider> = emptyMap()

    override val supported: Set<String> get() = factories.keys

    override fun instantiate(ext: InstalledExtension): Provider? =
        factories[ext.internalName]?.invoke()
}
