package com.cloudstreamweb.download

import com.cloudstreamweb.config.AppConfig
import com.cloudstreamweb.proxy.DEFAULT_USER_AGENT
import com.cloudstreamweb.proxy.validateTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Reconstructs HLS sources into a single MP4 for download, since the browser can't save a
 * multi-segment `.m3u8` stream as one file. Runs ffmpeg as a subprocess (`-c copy`, no
 * re-encoding — this is a remux, not a transcode) and reports progress via `-progress pipe:1`.
 *
 * Job state lives only in memory: [scope] is the application's own lifecycle scope, so every
 * job is abandoned on restart — [wipeAll] clears `downloads/` on startup for exactly that
 * reason (leftover `.part`/`.mp4` files would otherwise never be claimed by any job again).
 * A background loop prunes files older than [AppConfig.downloadRetentionHours] so the
 * container's disk can't fill up with forgotten downloads (explicit requirement: max 24h).
 */
class DownloadManager(private val config: AppConfig, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(DownloadManager::class.java)
    private val downloadsDir = File(config.dataDir, "downloads")
    private val jobs = ConcurrentHashMap<String, DownloadJob>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val coroutineJobs = ConcurrentHashMap<String, Job>()

    // (providerId, episodeId) -> jobId, so a second request for the same episode — from the
    // same browser after a localStorage reset, or from a different device entirely — reuses an
    // in-flight job or a still-cached finished one instead of starting a redundant remux. Not
    // locked against a start()/start() race (check-then-set on a ConcurrentHashMap): a rare
    // simultaneous double-click could still create two jobs for one episode, briefly leaking
    // one — acceptable for personal/LAN scope, not worth a mutex here.
    private val episodeIndex = ConcurrentHashMap<String, String>()
    private fun episodeKey(providerId: String, episodeId: String) = "$providerId $episodeId"

    // At most 2 concurrent remuxes: ffmpeg is CPU/IO heavy and this runs on a small home server.
    private val semaphore = Semaphore(2)

    init {
        downloadsDir.mkdirs()
        wipeAll()
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(Duration.ofHours(1).toMillis())
                pruneExpired()
            }
        }
    }

    fun get(id: String): DownloadJob? = jobs[id]

    /** The finished file on disk, or null if the job isn't [DownloadStatus.READY]. */
    fun fileFor(id: String): File? =
        jobs[id]?.takeIf { it.status == DownloadStatus.READY }?.let { finalFile(id).takeIf(File::exists) }

    fun start(req: StartDownloadRequest): DownloadJob {
        pruneExpired()

        val key = episodeKey(req.providerId, req.episodeId)
        val reusable = episodeIndex[key]?.let { jobs[it] }?.takeIf { it.status in RESUMABLE_STATUSES }
        if (reusable != null) return reusable

        val id = UUID.randomUUID().toString()
        val job = DownloadJob(
            id = id,
            status = DownloadStatus.QUEUED,
            filename = req.filename,
            createdAt = nowEpochSeconds(),
        )
        jobs[id] = job
        episodeIndex[key] = id
        coroutineJobs[id] = scope.launch(Dispatchers.IO) { runJob(id, req) }
        return job
    }

    suspend fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.status != DownloadStatus.QUEUED && job.status != DownloadStatus.RUNNING) return false
        jobs[id] = job.copy(status = DownloadStatus.CANCELED)
        coroutineJobs[id]?.cancel()
        processes[id]?.let { killProcessTree(it) }
        partFile(id).delete()
        finalFile(id).delete()
        return true
    }

    /**
     * Kills the ffmpeg process and waits (briefly) for it to actually exit before the caller
     * deletes its output file. `Process.destroyForcibly()` alone isn't enough on Windows: a
     * binary launched via a PATH shim (e.g. Chocolatey's `ffmpeg.exe` stub) runs the real
     * ffmpeg as a *child* of that shim, so destroying the immediate `Process` handle leaves
     * the actual ffmpeg alive, still writing to (and locking) the `.part` file — killing every
     * descendant closes that gap.
     */
    private suspend fun killProcessTree(process: Process) = withContext(Dispatchers.IO) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        runCatching { process.waitFor(3, TimeUnit.SECONDS) }
    }

    private suspend fun runJob(id: String, req: StartDownloadRequest) {
        if (!req.isM3u8) {
            fail(id, "only HLS sources require a reconstruction job")
            return
        }
        val parsed = runCatching { URL(req.url) }.getOrNull()
        if (parsed == null) {
            fail(id, "invalid URL")
            return
        }
        val ssrfError = validateTarget(parsed)
        if (ssrfError != null) {
            fail(id, ssrfError)
            return
        }

        semaphore.withPermit {
            // Canceled while it was waiting for a permit.
            if (jobs[id]?.status != DownloadStatus.QUEUED) return@withPermit
            jobs[id] = jobs.getValue(id).copy(status = DownloadStatus.RUNNING)
            try {
                val duration = probeDuration(req)
                val audioCodec = probeAudioCodec(req)
                runFfmpeg(id, req, duration, audioCodec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Download job {} failed: {}", id, e.message)
                fail(id, e.message ?: "download failed")
            } finally {
                partFile(id).delete()
                processes.remove(id)
            }
        }
    }

    private fun headerArg(headers: Map<String, String>): String {
        val withUserAgent = if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers + ("User-Agent" to DEFAULT_USER_AGENT)
        } else {
            headers
        }
        return withUserAgent.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    }

    private suspend fun probeDuration(req: StartDownloadRequest): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(
                config.ffprobePath, "-v", "error",
                "-headers", headerArg(req.headers),
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                req.url,
            ).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.toDoubleOrNull()?.takeIf { it > 0 }
        }.getOrNull()
    }

    /**
     * The remux needs `-bsf:a aac_adtstoasc` only when the audio is ADTS AAC (the usual case
     * for HLS/TS) — applying it unconditionally makes ffmpeg hard-fail on any other audio
     * codec (e.g. AC-3 commentary tracks), so this is probed rather than assumed.
     */
    private suspend fun probeAudioCodec(req: StartDownloadRequest): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(
                config.ffprobePath, "-v", "error",
                "-headers", headerArg(req.headers),
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name",
                "-of", "csv=p=0",
                req.url,
            ).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun runFfmpeg(id: String, req: StartDownloadRequest, duration: Double?, audioCodec: String?) =
        withContext(Dispatchers.IO) {
            val part = partFile(id)
            val command = buildList {
                add(config.ffmpegPath)
                add("-headers"); add(headerArg(req.headers))
                add("-i"); add(req.url)
                add("-c"); add("copy")
                if (audioCodec == "aac") {
                    add("-bsf:a"); add("aac_adtstoasc")
                }
                add("-movflags"); add("+faststart")
                add("-progress"); add("pipe:1")
                add("-nostats")
                add("-y")
                // The output path ends in ".mp4.part" (see partFile/finalFile) so ffmpeg can't
                // infer the muxer from the extension — force it explicitly.
                add("-f"); add("mp4")
                add(part.absolutePath)
            }
            val process = try {
                ProcessBuilder(command).start()
            } catch (e: IOException) {
                fail(id, "ffmpeg-missing")
                return@withContext
            }
            processes[id] = process

            // Drain stderr concurrently (kept for the error message) so ffmpeg never blocks
            // writing to a full pipe while we're only reading stdout for progress.
            val stderr = StringBuilder()
            val stderrJob = launch(Dispatchers.IO) {
                runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            }

            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.startsWith("out_time_us=")) {
                        val outTimeUs = line.substringAfter('=').toLongOrNull()
                        if (duration != null && outTimeUs != null) {
                            val progress = (outTimeUs / 1_000_000.0 / duration).coerceIn(0.0, 1.0)
                            jobs[id]?.let { jobs[id] = it.copy(progress = progress) }
                        }
                    }
                }
            }
            val exitCode = process.waitFor()
            stderrJob.join()
            processes.remove(id)

            if (jobs[id]?.status == DownloadStatus.CANCELED) return@withContext
            if (exitCode != 0) {
                fail(id, stderr.toString().takeLast(500).ifBlank { "ffmpeg exited with code $exitCode" })
                return@withContext
            }
            val finalF = finalFile(id)
            if (!part.renameTo(finalF)) {
                fail(id, "could not finalize the downloaded file")
                return@withContext
            }
            jobs[id] = jobs.getValue(id).copy(
                status = DownloadStatus.READY,
                progress = 1.0,
                sizeBytes = finalF.length(),
            )
        }

    private fun fail(id: String, message: String) {
        jobs[id]?.let { jobs[id] = it.copy(status = DownloadStatus.FAILED, error = message) }
    }

    private fun partFile(id: String) = File(downloadsDir, "$id.mp4.part")
    private fun finalFile(id: String) = File(downloadsDir, "$id.mp4")
    private fun nowEpochSeconds() = Instant.now().epochSecond

    private fun wipeAll() {
        downloadsDir.listFiles()?.forEach { it.delete() }
        jobs.clear()
        episodeIndex.clear()
    }

    private fun pruneExpired() {
        val cutoff = nowEpochSeconds() - config.downloadRetentionHours * 3600
        jobs.values
            .filter { it.createdAt < cutoff && it.status != DownloadStatus.RUNNING && it.status != DownloadStatus.QUEUED }
            .forEach { job ->
                finalFile(job.id).delete()
                partFile(job.id).delete()
                jobs.remove(job.id)
            }
        // Dangling entries (their job got pruned above, failed, or was canceled): drop them so
        // a future request for that episode starts a fresh job instead of resolving to nothing.
        episodeIndex.entries.removeIf { (_, jobId) -> jobs[jobId]?.status !in RESUMABLE_STATUSES }
    }

    private companion object {
        val RESUMABLE_STATUSES = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.READY)
    }
}
