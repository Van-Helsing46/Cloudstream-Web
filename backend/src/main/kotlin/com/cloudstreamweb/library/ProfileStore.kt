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

    /** Uploaded avatar images, one file per profile id (any supported extension). */
    val avatarsDir = File(stateDir, "avatars")

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
            avatar = req.avatar?.takeIf { it.isNotBlank() },
        )
        profiles = profiles + profile
        save()
        profile
    }

    /** Partial update (name/color/avatar). Returns null if the profile is unknown. */
    suspend fun update(id: String, req: UpdateProfileRequest): Profile? = mutex.withLock {
        val current = profiles.find { it.id == id } ?: return null
        val name = if (req.name != null) {
            require(req.name.isNotBlank()) { "profile name is empty" }
            req.name.trim()
        } else {
            current.name
        }
        val color = req.color?.takeIf { it.isNotBlank() } ?: current.color
        // Unlike color, an explicit empty string clears the avatar (back to the initial+color fallback).
        val avatar = if (req.avatar != null) req.avatar.takeIf { it.isNotBlank() } else current.avatar
        val updated = current.copy(name = name, color = color, avatar = avatar)
        profiles = profiles.map { if (it.id == id) updated else it }
        save()
        updated
    }

    /** Removes the profile, its library (watchlist/history) and any uploaded avatar. */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        val before = profiles.size
        profiles = profiles.filterNot { it.id == id }
        if (profiles.size == before) return false
        save()
        File(librariesDir, id).deleteRecursively()
        deleteAvatarFile(id)
        true
    }

    /** Saves an uploaded avatar image for the profile, replacing any previous one. Null if the profile is unknown. */
    suspend fun saveAvatar(id: String, bytes: ByteArray, extension: String): Profile? = mutex.withLock {
        val current = profiles.find { it.id == id } ?: return null
        deleteAvatarFile(id)
        avatarsDir.mkdirs()
        val tmp = File(avatarsDir, "$id.$extension.tmp")
        tmp.writeBytes(bytes)
        java.nio.file.Files.move(
            tmp.toPath(),
            File(avatarsDir, "$id.$extension").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        val updated = current.copy(avatar = "upload")
        profiles = profiles.map { if (it.id == id) updated else it }
        save()
        updated
    }

    /** Removes the uploaded avatar image, if any, and resets `avatar` to null. Null if the profile is unknown. */
    suspend fun clearAvatar(id: String): Profile? = mutex.withLock {
        val current = profiles.find { it.id == id } ?: return null
        deleteAvatarFile(id)
        val updated = current.copy(avatar = null)
        profiles = profiles.map { if (it.id == id) updated else it }
        save()
        updated
    }

    /** The uploaded avatar file for a profile, if one exists on disk. */
    fun avatarFile(id: String): File? =
        avatarsDir.listFiles { f -> f.name.startsWith("$id.") && !f.name.endsWith(".tmp") }?.firstOrNull()

    private fun deleteAvatarFile(id: String) {
        avatarsDir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
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
