package com.hallide.weartubeplus.player

import android.util.Log
import android.content.Context
import com.hallide.weartubeplus.data.Download
import com.hallide.weartubeplus.data.DownloadKind
import com.hallide.weartubeplus.data.DownloadRange
import com.hallide.weartubeplus.data.DownloadStatus
import com.hallide.weartubeplus.data.Downloads
import com.hallide.weartubeplus.data.Net
import com.hallide.weartubeplus.data.Video
import com.hallide.weartubeplus.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline downloader for the progressive muxed stream.
 *
 * Streaming needs [StreamUrls]' per-URL budget rotation because adaptive
 * googlevideo URLs die after ~0.5-2MB. The progressive itag-18 URL does not have
 * that budget (measured at 20.5MB from a single URL), so a download is one
 * sequential ranged copy. If a long video does outlive its URL, the downloader
 * re-resolves and continues from the byte offset already on disk.
 *
 * One transfer runs at a time: a watch has neither the bandwidth nor the battery
 * to make parallel downloads worthwhile, and serial transfers keep player-API
 * calls (one per resolve) rare.
 */
object Downloader {

    private const val MAX_URL_ROTATIONS = 12
    private const val MAX_ATTEMPTS = 4
    private const val PERSIST_EVERY = 4L * 1024 * 1024
    private const val REPORT_EVERY_MS = 400L
    private const val TAG = "WTDownload"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Queue [video] for offline download, or resume a paused/failed one. */
    fun enqueue(video: Video, kind: DownloadKind = DownloadKind.VIDEO) {
        val existing = Downloads.get(video.id)
        if (existing?.isActive == true) return
        when {
            existing == null -> Downloads.upsert(Download(video = video, kind = kind))
            existing.kind != kind -> {
                // Different artifact layout: start clean rather than resume the wrong file.
                Downloads.remove(video.id)
                Downloads.upsert(Download(video = video, kind = kind))
            }
            else -> Downloads.update(video.id) {
                it.copy(status = DownloadStatus.QUEUED, error = null)
            }
        }
        start(video.id)
        appContext?.let { DownloadService.start(it) }
    }

    fun cancel(videoId: String) {
        jobs[videoId]?.cancel()
    }

    fun isRunning(videoId: String): Boolean = jobs[videoId]?.isActive == true

    /** Re-queue transfers interrupted by process death. */
    fun resumeAll() {
        val paused = Downloads.items.value.filter { it.status == DownloadStatus.PAUSED }
        if (paused.isEmpty()) return
        paused.forEach { start(it.videoId) }
        appContext?.let { DownloadService.start(it) }
    }

    private fun start(videoId: String) {
        if (jobs[videoId]?.isActive == true) return
        val job = scope.launch {
            gate.withPermit { run(videoId) }
        }
        jobs[videoId] = job
        job.invokeOnCompletion { jobs.remove(videoId, job) }
    }

    private suspend fun run(videoId: String) {
        val download = Downloads.get(videoId) ?: return
        Downloads.update(videoId) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
        try {
            if (download.kind == DownloadKind.AUDIO) {
                throw IOException("Audio-only downloads are coming soon")
            }
            // forceFresh: a cached bundle's URLs may already be spent or expired.
            val bundle = StreamResolver.resolve(videoId, forceFresh = true)
            val choice = bundle.choices.firstOrNull { it.muxedUrl != null }
                ?: throw IOException("This video has no downloadable stream")
            downloadProgressive(videoId, choice.muxedUrl!!, bundle.streamUa)
            fetchThumb(videoId, download.video.thumbOrFallback)
            Downloads.update(videoId) {
                it.copy(
                    status = DownloadStatus.DONE,
                    bytesTotal = if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone,
                    error = null,
                )
            }
            Log.i(TAG, "done $videoId (${Downloads.videoFile(videoId).length()} bytes)")
        } catch (e: CancellationException) {
            // Pause, don't fail: the .part file is a valid resume point.
            Downloads.update(videoId) {
                if (it.status == DownloadStatus.DONE) it
                else it.copy(status = DownloadStatus.PAUSED)
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "failed $videoId: ${e.message}")
            Downloads.update(videoId) {
                it.copy(status = DownloadStatus.FAILED, error = friendlyError(e, "Download failed"))
            }
        }
    }

    private suspend fun downloadProgressive(videoId: String, initialUrl: String, initialUa: String) {
        val part = Downloads.partFile(videoId)
        part.parentFile?.mkdirs()

        var url = initialUrl
        var ua = initialUa
        var offset = if (part.exists()) part.length() else 0L
        var total = -1L
        var rotations = 0
        var attempts = 0
        var lastPersist = offset
        var lastReport = 0L

        Downloads.update(videoId) { it.copy(bytesDone = offset, bytesTotal = total) }

        while (true) {
            currentCoroutineContext().ensureActive()
            val end = DownloadRange.end(offset)
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$offset-$end")
                .header("User-Agent", ua.ifEmpty { RangedDataSource.streamUserAgent })
                .header("Accept", "*/*")
                .build()

            var resp: Response? = null
            try {
                resp = Net.client.newCall(req).execute()
                val code = resp.code

                if (code == 403 || code == 410) {
                    resp.close()
                    resp = null
                    if (rotations >= MAX_URL_ROTATIONS) {
                        throw IOException("Stream URL expired too many times")
                    }
                    rotations++
                    attempts = 0
                    Log.i(TAG, "URL refused ($code) for $videoId, re-resolving (rotation $rotations)")
                    delay(2_000)
                    val fresh = StreamResolver.resolve(videoId, forceFresh = true)
                    val choice = fresh.choices.firstOrNull { it.muxedUrl != null }
                        ?: throw IOException("This video has no downloadable stream")
                    url = choice.muxedUrl!!
                    ua = fresh.streamUa
                    continue
                }

                if (code != 200 && code != 206) throw IOException("HTTP $code")

                DownloadRange.totalFrom(resp.header("Content-Range"))?.let { total = it }
                val body = resp.body ?: throw IOException("Empty response body")
                val before = offset

                body.byteStream().use { input ->
                    FileOutputStream(part, true).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            offset += n

                            val now = System.currentTimeMillis()
                            if (now - lastReport >= REPORT_EVERY_MS) {
                                lastReport = now
                                Downloads.update(videoId, persistNow = false) {
                                    it.copy(bytesDone = offset, bytesTotal = total)
                                }
                            }
                            if (offset - lastPersist >= PERSIST_EVERY) {
                                lastPersist = offset
                                Downloads.update(videoId) {
                                    it.copy(bytesDone = offset, bytesTotal = total)
                                }
                            }
                        }
                        out.flush()
                    }
                }

                if (offset == before) throw IOException("No progress from stream")
                attempts = 0

                if (total > 0) {
                    if (DownloadRange.isComplete(offset, total)) break
                } else if (offset - before < DownloadRange.CHUNK) {
                    // No Content-Range and a short response: treat as EOF.
                    break
                }
            } catch (e: IOException) {
                resp?.close()
                resp = null
                attempts++
                if (attempts > MAX_ATTEMPTS) throw e
                delay(backoffMs(attempts))
            } finally {
                resp?.close()
            }
        }

        val out = Downloads.videoFile(videoId)
        out.delete()
        if (!part.renameTo(out)) {
            part.copyTo(out, overwrite = true)
            part.delete()
        }
        Downloads.update(videoId) {
            it.copy(bytesDone = out.length(), bytesTotal = out.length())
        }
    }

    private fun fetchThumb(videoId: String, url: String) {
        if (url.isEmpty()) return
        try {
            Net.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body ?: return
                body.byteStream().use { input ->
                    Downloads.thumbFile(videoId).outputStream().use { input.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "thumb failed for $videoId: ${e.message}")
        }
    }

    /** Steep backoff: hammering is what trips the bot checks. */
    private fun backoffMs(attempt: Int): Long = minOf(8_000L, 500L * (1L shl attempt))
}
