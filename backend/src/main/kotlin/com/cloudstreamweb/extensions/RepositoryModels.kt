package com.cloudstreamweb.extensions

import kotlinx.serialization.Serializable

/**
 * Models of the Cloudstream repository format.
 *
 * A repository can be reached in two forms:
 * - `repo.json` — object with `pluginLists`: URLs pointing to one or more `plugins.json`
 * - `plugins.json` — directly the array of plugin manifests
 * The `ExtensionManager` accepts both.
 */

/** Upstream `repo.json`. */
@Serializable
data class RepoManifest(
    val name: String,
    val description: String? = null,
    val manifestVersion: Int? = null,
    val pluginLists: List<String> = emptyList(),
)

/** One entry of an upstream `plugins.json` (unmapped fields are ignored while parsing). */
@Serializable
data class PluginManifest(
    val url: String,                       // URL of the .cs3
    val name: String,
    val internalName: String,
    val version: Int = 0,
    val status: Int = 1,                   // 0=down, 1=ok, 2=slow, 3=beta (Cloudstream convention)
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val repositoryUrl: String? = null,
    val language: String? = null,
    val tvTypes: List<String> = emptyList(),
    val iconUrl: String? = null,
    val apiVersion: Int? = null,
    val fileSize: Long? = null,
    val fileHash: String? = null,          // "sha256-<hex>"
)

/** Repository registered by the user (persisted). */
@Serializable
data class RepositoryRef(
    val url: String,                       // URL as provided (repo.json or plugins.json)
    val name: String,
    val pluginLists: List<String>,
)

/** Installed extension (persisted). */
@Serializable
data class InstalledExtension(
    val internalName: String,
    val name: String,
    val version: Int,
    val cs3Url: String,
    val repositoryUrl: String? = null,
    val language: String? = null,
    val installedAt: String,               // ISO-8601
    /** True if a runtime currently has a registered provider for this extension. */
    val active: Boolean = false,
    /** Why activation failed, when [active] is false (from the runtime that was tried). */
    val activationError: String? = null,
)

/** Overall ExtensionManager state, serialized to `state.json`. */
@Serializable
data class ExtensionsState(
    val repositories: List<RepositoryRef> = emptyList(),
    val installed: List<InstalledExtension> = emptyList(),
)

// ---- API response DTOs ----

/** Plugin as seen by the client: manifest + local state. */
@Serializable
data class AvailablePlugin(
    val internalName: String,
    val name: String,
    val version: Int,
    val status: Int,
    val description: String? = null,
    val language: String? = null,
    val tvTypes: List<String> = emptyList(),
    val iconUrl: String? = null,
    val repositoryUrl: String? = null,
    /** Display names of the user's registered repositories that list this extension (can be more than one). */
    val sourceRepositories: List<String> = emptyList(),
    /** Locally installed version, if any. */
    val installedVersion: Int? = null,
    /**
     * true if the runtime can execute this extension (recompiled and bundled).
     * false = installable but not executable until it gets recompiled for the JVM.
     */
    val runtimeSupported: Boolean,
    /** true if a provider is currently registered for the installed version (null if not installed). */
    val active: Boolean? = null,
    /** Why activation failed, when [active] is false. */
    val activationError: String? = null,
)

/** Install/update outcome, with the provider activation state. */
@Serializable
data class InstallResult(
    val extension: InstalledExtension,
    /** true if the provider has been registered and can be queried via /api/v1. */
    val runtimeActive: Boolean,
    val message: String? = null,
)

/** One extension bumped to a newer version by [ExtensionManager.updateAll]. */
@Serializable
data class UpdatedExtension(
    val internalName: String,
    val name: String,
    val fromVersion: Int,
    val toVersion: Int,
)

/** One extension that failed to update in an [ExtensionManager.updateAll] pass. */
@Serializable
data class FailedUpdate(
    val internalName: String,
    val error: String,
)

/** Outcome of updating every installed extension at once (manual button or the daily schedule). */
@Serializable
data class UpdateAllSummary(
    val updated: List<UpdatedExtension>,
    /** internalNames already at the latest version found in the registered repositories. */
    val upToDate: List<String>,
    val failed: List<FailedUpdate>,
)
