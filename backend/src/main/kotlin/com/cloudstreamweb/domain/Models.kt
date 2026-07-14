package com.cloudstreamweb.domain

import kotlinx.serialization.Serializable

/**
 * Internal domain model, conceptually aligned with Cloudstream's `MainAPI`.
 * It is the contract the frontend consumes; providers (extensions) adapt to it.
 */

@Serializable
enum class MediaType { MOVIE, TV_SERIES, ANIME, LIVE, OTHER }

@Serializable
data class SearchItem(
    val id: String,            // provider-specific opaque identifier (usually a URL)
    val providerId: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String? = null,
    val year: Int? = null,
)

@Serializable
data class SearchResponse(
    val query: String,
    val results: List<SearchItem>,
    /** Providers that failed in this aggregated search: providerId → message. */
    val errors: Map<String, String> = emptyMap(),
)

/** Section of a provider's main page, e.g. "Featured", "Documentaries". */
@Serializable
data class HomeSection(
    val title: String,
    val items: List<SearchItem>,
    /** Layout hint: landscape posters instead of portrait ones. */
    val isHorizontal: Boolean = false,
)

@Serializable
data class HomeResponse(
    val providerId: String,
    val page: Int,
    val sections: List<HomeSection>,
    /** Set (with empty sections) if the provider call failed or timed out; the response stays 200. */
    val error: String? = null,
)

@Serializable
data class Episode(
    val id: String,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
)

@Serializable
data class MediaDetail(
    val id: String,
    val providerId: String,
    val title: String,
    val type: MediaType,
    val plot: String? = null,
    val posterUrl: String? = null,
    val year: Int? = null,
    val episodes: List<Episode> = emptyList(),
)

/**
 * Resolved link to a video source. The headers are the reason the proxy exists:
 * the browser cannot set them (e.g. Referer/User-Agent required by the CDN).
 */
@Serializable
data class StreamLink(
    val url: String,
    val quality: String? = null,   // e.g. "1080p"
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val language: String? = null,
    val supportedTypes: List<MediaType> = emptyList(),
    /** true if the provider exposes a main page. */
    val hasMainPage: Boolean = false,
)
