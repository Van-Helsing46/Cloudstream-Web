package com.cloudstreamweb.download

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, READY, FAILED, CANCELED }

/**
 * A background HLS→MP4 remux job. `progress` is null while the source duration is
 * unknown (ffprobe failed or hasn't returned yet) — the frontend shows an indeterminate
 * spinner in that case instead of a filled ring.
 */
@Serializable
data class DownloadJob(
    val id: String,
    val status: DownloadStatus,
    val progress: Double? = null,
    val filename: String,
    val sizeBytes: Long? = null,
    val error: String? = null,
    val createdAt: Long,
)

@Serializable
data class StartDownloadRequest(
    /** Identifies the episode for dedup (see [DownloadManager]'s episode index): a second
     * request for the same (providerId, episodeId) while a job is still active reuses it
     * instead of starting a redundant remux, and reattaches to a still-cached finished one. */
    val providerId: String,
    val episodeId: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isM3u8: Boolean,
    val filename: String,
)
