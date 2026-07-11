package com.cloudstreamweb.extensions

import com.cloudstreamweb.provider.Provider

/**
 * Chains several runtimes: the first that can [instantiate] an extension wins. Lets bundled
 * (Strada B, recompiled) providers take precedence over the dynamic (Strada A, DEX→JAR) loader,
 * so a curated bundled version overrides the generic dynamic one for the same `internalName`.
 */
class CompositeExtensionRuntime(private val runtimes: List<ExtensionRuntime>) : ExtensionRuntime {

    constructor(vararg runtimes: ExtensionRuntime) : this(runtimes.toList())

    override val supported: Set<String>
        get() = runtimes.flatMapTo(mutableSetOf()) { it.supported }

    override fun instantiate(ext: InstalledExtension): Provider? =
        runtimes.firstNotNullOfOrNull { it.instantiate(ext) }
}
