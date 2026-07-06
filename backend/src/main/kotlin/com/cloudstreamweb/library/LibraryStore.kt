package com.cloudstreamweb.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * User library persistence on JSON files. Embedded and dependency-free: good enough
 * for personal self-hosted use; SQLite/H2 remains a possible upgrade.
 *
 * The history keeps a single entry per (providerId, episodeId): playing again updates
 * the position. The list is ordered by `updatedAt` descending (most recent first).
 */
class LibraryStore(private val stateDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateFile = File(stateDir, "library.json")
    private val mutex = Mutex()

    private var state: LibraryState = runCatching {
        json.decodeFromString<LibraryState>(stateFile.readText())
    }.getOrDefault(LibraryState())

    fun watchlist(): List<LibraryItem> = state.watchlist
    fun history(): List<HistoryEntry> = state.history

    /**
     * "Continue watching" entries, one per series/movie: for each (providerId, mediaId)
     * the most recent entry (history is ordered by updatedAt descending), finished ones excluded.
     */
    fun continueWatching(): List<HistoryEntry> =
        state.history
            .distinctBy { it.providerId to it.mediaId }
            .filterNot { it.finished }

    /** All progress entries of a series/movie (for the detail page: per-episode bars). */
    fun entriesForMedia(providerId: String, mediaId: String): List<HistoryEntry> =
        state.history.filter { it.providerId == providerId && it.mediaId == mediaId }

    suspend fun addToWatchlist(req: AddWatchlistRequest): LibraryItem = mutex.withLock {
        val item = LibraryItem(
            providerId = req.providerId,
            mediaId = req.mediaId,
            title = req.title,
            type = req.type,
            posterUrl = req.posterUrl,
            year = req.year,
            addedAt = Instant.now().toString(),
        )
        // dedup on (providerId, mediaId): the new entry replaces and goes first
        val rest = state.watchlist.filterNot {
            it.providerId == req.providerId && it.mediaId == req.mediaId
        }
        state = state.copy(watchlist = listOf(item) + rest)
        save()
        item
    }

    suspend fun removeFromWatchlist(providerId: String, mediaId: String): Boolean = mutex.withLock {
        val before = state.watchlist.size
        state = state.copy(
            watchlist = state.watchlist.filterNot {
                it.providerId == providerId && it.mediaId == mediaId
            },
        )
        if (state.watchlist.size != before) { save(); true } else false
    }

    fun inWatchlist(providerId: String, mediaId: String): Boolean =
        state.watchlist.any { it.providerId == providerId && it.mediaId == mediaId }

    /** Records/updates the resume position of an episode. */
    suspend fun recordProgress(req: ProgressRequest): HistoryEntry = mutex.withLock {
        val entry = HistoryEntry(
            providerId = req.providerId,
            mediaId = req.mediaId,
            episodeId = req.episodeId,
            title = req.title,
            episodeName = req.episodeName,
            season = req.season,
            episode = req.episode,
            posterUrl = req.posterUrl,
            positionSeconds = req.positionSeconds,
            durationSeconds = req.durationSeconds,
            updatedAt = Instant.now().toString(),
        )
        val rest = state.history.filterNot {
            it.providerId == req.providerId && it.episodeId == req.episodeId
        }
        state = state.copy(history = listOf(entry) + rest)
        save()
        entry
    }

    /** Saved position for an episode, if any (for the player's resume). */
    fun progressFor(providerId: String, episodeId: String): HistoryEntry? =
        state.history.firstOrNull { it.providerId == providerId && it.episodeId == episodeId }

    private fun save() {
        stateDir.mkdirs()
        atomicWrite(stateFile, json.encodeToString(LibraryState.serializer(), state))
    }
}

/**
 * Atomic write: writes to a temp file and then renames, so a crash mid-write cannot
 * leave the JSON corrupted (the old file stays intact until the rename succeeds).
 */
internal fun atomicWrite(target: java.io.File, content: String) {
    val tmp = java.io.File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(content)
    try {
        java.nio.file.Files.move(
            tmp.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        // Fallback (e.g. filesystems without atomic move support)
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
