package com.cloudstreamweb.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * User profiles (Netflix-style multi-user): the instance password remains the entry
 * lock, the profile selects which library is used. Persisted in `profiles.json`
 * (atomic writes, like the other stores).
 */
class ProfileStore(
    private val stateDir: File,
    /** Base directory of the per-profile libraries, for cleanup on delete. */
    private val librariesDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateFile = File(stateDir, "profiles.json")
    private val mutex = Mutex()

    private var profiles: List<Profile> = runCatching {
        json.decodeFromString(ListSerializer(Profile.serializer()), stateFile.readText())
    }.getOrDefault(emptyList())

    fun list(): List<Profile> = profiles

    fun exists(id: String): Boolean = profiles.any { it.id == id }

    suspend fun create(req: CreateProfileRequest): Profile = mutex.withLock {
        require(req.name.isNotBlank()) { "profile name is empty" }
        val profile = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = req.name.trim(),
            color = req.color?.takeIf { it.isNotBlank() } ?: defaultColorFor(profiles.size),
            createdAt = Instant.now().toString(),
        )
        profiles = profiles + profile
        save()
        profile
    }

    /** Partial update (name and/or color). Returns null if the profile is unknown. */
    suspend fun update(id: String, req: UpdateProfileRequest): Profile? = mutex.withLock {
        val current = profiles.find { it.id == id } ?: return null
        val name = if (req.name != null) {
            require(req.name.isNotBlank()) { "profile name is empty" }
            req.name.trim()
        } else {
            current.name
        }
        val color = req.color?.takeIf { it.isNotBlank() } ?: current.color
        val updated = current.copy(name = name, color = color)
        profiles = profiles.map { if (it.id == id) updated else it }
        save()
        updated
    }

    /** Removes the profile and its library (watchlist/history). */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        val before = profiles.size
        profiles = profiles.filterNot { it.id == id }
        if (profiles.size == before) return false
        save()
        File(librariesDir, id).deleteRecursively()
        true
    }

    /** Used by the legacy migration: creates a profile with a known id when the registry is empty. */
    suspend fun createWithId(id: String, name: String): Profile = mutex.withLock {
        val profile = Profile(
            id = id,
            name = name,
            color = defaultColorFor(profiles.size),
            createdAt = Instant.now().toString(),
        )
        profiles = profiles + profile
        save()
        profile
    }

    private fun save() {
        stateDir.mkdirs()
        atomicWrite(stateFile, json.encodeToString(ListSerializer(Profile.serializer()), profiles))
    }

    private fun defaultColorFor(index: Int): String =
        listOf("#7c9cff", "#ff8a7c", "#7cd992", "#e6c07b", "#c792ea", "#7cd4e6")[index % 6]
}
