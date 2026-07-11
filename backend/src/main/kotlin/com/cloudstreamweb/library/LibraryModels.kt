package com.cloudstreamweb.library

import com.cloudstreamweb.domain.MediaType
import kotlinx.serialization.Serializable

/**
 * User library: watchlist and history/resume.
 *
 * An item is identified by (providerId, mediaId): the mediaId is the opaque `id` used across
 * the rest of the API (usually the content's URL on the provider). We store enough metadata
 * (title, poster) to render the library without re-running `load`.
 */
@Serializable
data class LibraryItem(
    val providerId: String,
    val mediaId: String,
    val title: String,
    val type: MediaType = MediaType.OTHER,
    val posterUrl: String? = null,
    val year: Int? = null,
    val addedAt: String,           // ISO-8601
)

/**
 * History entry with resume position. The position is per single episode/source:
 * `episodeId` distinguishes the episodes of a series (for movies it equals mediaId).
 * `season`/`episode`/`episodeName` enable the per-series view ("resume S1E3").
 */
@Serializable
data class HistoryEntry(
    val providerId: String,
    val mediaId: String,
    val episodeId: String,
    val title: String,
    val episodeName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val positionSeconds: Double,
    val durationSeconds: Double? = null,
    val updatedAt: String,         // ISO-8601
) {
    /** Considered "finished" past 90%: not offered as "continue watching". */
    val finished: Boolean
        get() = durationSeconds?.let { it > 0 && positionSeconds / it >= 0.9 } ?: false
}

@Serializable
data class LibraryState(
    val watchlist: List<LibraryItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

// ---- Request payloads ----

@Serializable
data class AddWatchlistRequest(
    val providerId: String,
    val mediaId: String,
    val title: String,
    val type: MediaType = MediaType.OTHER,
    val posterUrl: String? = null,
    val year: Int? = null,
)

@Serializable
data class ProgressRequest(
    val providerId: String,
    val mediaId: String,
    val episodeId: String,
    val title: String,
    val episodeName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val positionSeconds: Double,
    val durationSeconds: Double? = null,
)

// ---- Profiles (Netflix-style multi-user) ----

/**
 * User profile: the instance password remains the entry lock; the profile
 * selects WHICH library is used (per-profile watchlist/history). No password of its own.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Avatar color in the picker (e.g. "#7c9cff"). */
    val color: String,
    val createdAt: String,         // ISO-8601
    /**
     * Avatar selection: null = initial+color fallback, "preset:<id>" = built-in image
     * (served by the frontend), "upload" = user-uploaded image (served by the backend).
     */
    val avatar: String? = null,
)

@Serializable
data class CreateProfileRequest(
    val name: String,
    val color: String? = null,
    val avatar: String? = null,
)

/**
 * Partial update: omitted fields keep their current value.
 * `avatar` accepts an empty string to explicitly clear it back to the initial+color fallback.
 */
@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val color: String? = null,
    val avatar: String? = null,
)
