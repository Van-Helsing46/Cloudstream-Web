package com.cloudstreamweb.library

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-user library: one [LibraryStore] per profile, each with its own directory
 * `<librariesDir>/<profileId>/library.json` (lazy). The caller resolves the profile
 * from the `X-Profile-Id` header and gets the store via [forProfile].
 */
class LibraryService(private val librariesDir: File) {
    private val stores = ConcurrentHashMap<String, LibraryStore>()

    fun forProfile(profileId: String): LibraryStore =
        stores.computeIfAbsent(profileId) { id ->
            LibraryStore(stateDir = File(librariesDir, id))
        }

    /**
     * Migration from the single-user (pre-profiles) format: if the old
     * `<librariesDir>/library.json` exists and there are no profiles yet, create the
     * "Main" profile and move the file into its directory. Existing data is preserved.
     */
    suspend fun migrateLegacyIfNeeded(profiles: ProfileStore) {
        val legacy = File(librariesDir, "library.json")
        if (!legacy.isFile || profiles.list().isNotEmpty()) return
        val profile = profiles.createWithId(id = "main", name = "Main")
        val targetDir = File(librariesDir, profile.id).apply { mkdirs() }
        val moved = legacy.renameTo(File(targetDir, "library.json"))
        if (!moved) {
            // Cross-filesystem fallback
            File(targetDir, "library.json").writeText(legacy.readText())
            legacy.delete()
        }
    }
}
