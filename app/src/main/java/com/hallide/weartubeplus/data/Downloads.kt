package com.hallide.weartubeplus.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class DownloadKind { VIDEO, AUDIO }

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

/**
 * One offline copy of a video.
 *
 * Only the progressive muxed stream (itag 18, 360p H.264+AAC) is downloaded in
 * v1: unlike adaptive URLs it is not byte-throttled per URL, so a download is a
 * plain sequential ranged copy instead of the rotation dance streaming needs.
 * Audio-only is produced later by remuxing the downloaded file locally.
 */
data class Download(
    val video: Video,
    val kind: DownloadKind = DownloadKind.VIDEO,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = -1L,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val videoId: String get() = video.id

    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING

    fun toJson(): JSONObject = JSONObject()
        .put("video", video.toJson())
        .put("kind", kind.name)
        .put("status", status.name)
        .put("bytesDone", bytesDone)
        .put("bytesTotal", bytesTotal)
        .put("error", error)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject): Download? {
            val v = o.optJSONObject("video") ?: return null
            return Download(
                video = Video.fromJson(v),
                kind = runCatching { DownloadKind.valueOf(o.optString("kind")) }
                    .getOrDefault(DownloadKind.VIDEO),
                status = runCatching { DownloadStatus.valueOf(o.optString("status")) }
                    .getOrDefault(DownloadStatus.PAUSED),
                bytesDone = o.optLong("bytesDone"),
                bytesTotal = o.optLong("bytesTotal", -1L),
                error = o.optString("error").takeIf { it.isNotEmpty() },
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}

/**
 * Download index + on-disk layout.
 *
 * Files live in app-internal storage so no permission is needed and uninstalling
 * cleans them up:
 *
 *     filesDir/downloads/<videoId>/video.mp4
 *     filesDir/downloads/<videoId>/video.mp4.part   (resume)
 *     filesDir/downloads/<videoId>/audio.m4a        (audio-only, M5)
 *     filesDir/downloads/<videoId>/thumb.jpg
 *     filesDir/downloads.json                       (index)
 *
 * Writes are atomic (tmp + rename) and serialized on [lock], matching Store.
 */
object Downloads {

    private lateinit var root: File
    private lateinit var indexFile: File
    private val lock = Any()

    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = _items

    fun init(context: Context) {
        val dir = File(context.filesDir, "downloads")
        dir.mkdirs()
        root = dir
        indexFile = File(context.filesDir, "downloads.json")
        // A row left DOWNLOADING means the process died mid-transfer. The .part
        // file is still valid, so downgrade to PAUSED instead of discarding it.
        val loaded = readIndex().map { d ->
            if (d.status == DownloadStatus.DOWNLOADING) d.copy(status = DownloadStatus.PAUSED) else d
        }
        _items.value = loaded
        persist(loaded)
    }

    fun get(videoId: String): Download? = _items.value.firstOrNull { it.videoId == videoId }

    fun isDownloaded(videoId: String): Boolean =
        get(videoId)?.status == DownloadStatus.DONE && playableFile(videoId).exists()

    fun dir(videoId: String): File = File(root, videoId)
    fun videoFile(videoId: String): File = File(dir(videoId), "video.mp4")
    fun audioFile(videoId: String): File = File(dir(videoId), "audio.m4a")
    fun thumbFile(videoId: String): File = File(dir(videoId), "thumb.jpg")
    fun partFile(videoId: String): File = File(dir(videoId), "video.mp4.part")

    /** The file playback should use for this download, if any. */
    fun playableFile(videoId: String): File = when (get(videoId)?.kind) {
        DownloadKind.AUDIO -> audioFile(videoId)
        else -> videoFile(videoId)
    }

    fun upsert(d: Download) = synchronized(lock) {
        val rest = _items.value.filterNot { it.videoId == d.videoId }
        _items.value = listOf(d) + rest
        persist(_items.value)
    }

    fun update(
        videoId: String,
        persistNow: Boolean = true,
        transform: (Download) -> Download,
    ): Download? = synchronized(lock) {
        val current = _items.value.firstOrNull { it.videoId == videoId } ?: return null
        val next = transform(current)
        _items.value = _items.value.map { if (it.videoId == videoId) next else it }
        if (persistNow) persist(_items.value)
        next
    }

    fun remove(videoId: String): Boolean = synchronized(lock) {
        dir(videoId).deleteRecursively()
        val before = _items.value.size
        _items.value = _items.value.filterNot { it.videoId == videoId }
        persist(_items.value)
        _items.value.size != before
    }

    /** Bytes currently occupied by completed downloads. */
    fun totalBytes(): Long = _items.value
        .filter { it.status == DownloadStatus.DONE }
        .sumOf { playableFile(it.videoId).length() }

    private fun readIndex(): List<Download> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull(Download::fromJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(items: List<Download>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }
}

/** Pure range arithmetic, kept separate so it is unit-testable off-device. */
internal object DownloadRange {
    const val CHUNK: Long = 512L * 1024

    fun end(offset: Long, chunk: Long = CHUNK): Long = offset + chunk - 1

    /** Total size from a `Content-Range: bytes 0-511/12345` header. */
    fun totalFrom(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()

    fun isComplete(offset: Long, total: Long): Boolean = total > 0 && offset >= total
}
