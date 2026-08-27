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
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isM3u8: Boolean,
    val filename: String,
)

@Serializable
data class StartDownloadResponse(val jobId: String)
