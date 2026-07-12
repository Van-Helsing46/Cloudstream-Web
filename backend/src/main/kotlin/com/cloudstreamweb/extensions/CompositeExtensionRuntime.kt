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

    override val attemptsAnyExtension: Boolean
        get() = runtimes.any { it.attemptsAnyExtension }

    override fun instantiate(ext: InstalledExtension): ActivationOutcome {
        val failures = mutableListOf<String>()
        for (rt in runtimes) {
            when (val outcome = rt.instantiate(ext)) {
                is ActivationOutcome.Activated -> return outcome
                is ActivationOutcome.Failed -> failures += outcome.reason
            }
        }
        return ActivationOutcome.Failed(failures.joinToString("; "))
    }

    override fun cleanup(internalName: String) = runtimes.forEach { it.cleanup(internalName) }
}
