package com.hallide.weartubeplus.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local persistence: subscriptions, history, playback positions, watch later,
 * recent searches. Plain JSON files in filesDir, atomic tmp+rename writes.
 * NewPipe-style local accounts: no Google sign-in anywhere.
 *
 * Everything is parsed once and kept in memory. The UI reads these on almost
 * every tap (isSubscribed, position, inWatchLater), so re-reading and re-parsing
 * JSON per call was pure main-thread I/O on a watch. Writes still go to disk
 * immediately, except positions, which are coalesced (see [savePosition]).
 */
object Store {

    private lateinit var dir: File
    private val lock = Any()

    private var subscriptionsCache: List<Channel>? = null
    private var historyCache: List<Video>? = null
    private var watchLaterCache: List<Video>? = null
    private var playlistsCache: List<Playlist>? = null
    private var searchesCache: List<String>? = null
    private var positions: JSONObject? = null

    /** Positions change every few seconds; write at most this often. */
    private const val POSITION_WRITE_INTERVAL_MS = 30_000L
    private var positionsDirty = false
    private var lastPositionsWrite = 0L

    fun init(context: Context) = synchronized(lock) {
        dir = context.filesDir
        subscriptionsCache = null
        historyCache = null
        watchLaterCache = null
        playlistsCache = null
        searchesCache = null
        positions = null
        positionsDirty = false
        lastPositionsWrite = 0L
    }

    // ---- generic helpers (call with [lock] held) ----

    private fun readArrayLocked(name: String): JSONArray {
        val f = File(dir, name)
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
    }

    private fun writeLocked(name: String, content: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(dir, name))
    }

    // ---- subscriptions ----

    fun subscriptions(): List<Channel> = synchronized(lock) {
        subscriptionsCache ?: run {
            val arr = readArrayLocked("subscriptions.json")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map(Channel::fromJson)
                .also { subscriptionsCache = it }
        }
    }

    fun isSubscribed(channelId: String): Boolean = subscriptions().any { it.id == channelId }

    fun toggleSubscription(channel: Channel): Boolean = synchronized(lock) {
        val current = subscriptions().toMutableList()
        val existing = current.indexOfFirst { it.id == channel.id }
        val nowSubscribed = if (existing >= 0) { current.removeAt(existing); false } else { current.add(0, channel); true }
        subscriptionsCache = current
        writeLocked("subscriptions.json", toJsonArray(current) { it.toJson() })
        nowSubscribed
    }

    // ---- history ----

    fun history(): List<Video> = synchronized(lock) {
        historyCache ?: run {
            val arr = readArrayLocked("history.json")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map(Video::fromJson)
                .also { historyCache = it }
        }
    }

    fun addHistory(video: Video) = synchronized(lock) {
        val current = history().filterNot { it.id == video.id }.toMutableList()
        current.add(0, video)
        while (current.size > 200) current.removeAt(current.size - 1)
        historyCache = current
        writeLocked("history.json", toJsonArray(current) { it.toJson() })
    }

    fun clearHistory() = synchronized(lock) {
        historyCache = emptyList()
        writeLocked("history.json", "[]")
    }

    // ---- positions (resume) ----

    fun position(videoId: String): Long = synchronized(lock) {
        positionsLocked().optLong(videoId, 0L)
    }

    /**
     * Records a resume point in memory and only writes every
     * [POSITION_WRITE_INTERVAL_MS]. Callers flush on stop/background, so at most
     * half a minute is lost if the process is killed abruptly.
     */
    fun savePosition(videoId: String, seconds: Long) = synchronized(lock) {
        val o = positionsLocked()
        o.put(videoId, seconds)
        // cap size: drop arbitrary old keys beyond 300
        if (o.length() > 300) {
            val names = o.names()
            if (names != null && names.length() > 0) o.remove(names.optString(0))
        }
        positionsDirty = true
        val now = System.currentTimeMillis()
        if (lastPositionsWrite == 0L || now - lastPositionsWrite >= POSITION_WRITE_INTERVAL_MS) {
            writePositionsLocked()
        }
    }

    /** Persist any pending position updates now. Safe to call often. */
    fun flush() = synchronized(lock) {
        if (positionsDirty) writePositionsLocked()
    }

    private fun positionsLocked(): JSONObject = positions ?: run {
        val f = File(dir, "positions.json")
        val o = try {
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) { JSONObject() }
        positions = o
        o
    }

    private fun writePositionsLocked() {
        val o = positions ?: return
        writeLocked("positions.json", o.toString())
        positionsDirty = false
        lastPositionsWrite = System.currentTimeMillis()
    }

    // ---- watch later ----

    fun watchLater(): List<Video> = synchronized(lock) {
        watchLaterCache ?: run {
            val arr = readArrayLocked("watchlater.json")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map(Video::fromJson)
                .also { watchLaterCache = it }
        }
    }

    fun inWatchLater(videoId: String): Boolean = watchLater().any { it.id == videoId }

    fun toggleWatchLater(video: Video): Boolean = synchronized(lock) {
        val current = watchLater().toMutableList()
        val idx = current.indexOfFirst { it.id == video.id }
        val nowIn = if (idx >= 0) { current.removeAt(idx); false } else { current.add(0, video); true }
        watchLaterCache = current
        writeLocked("watchlater.json", toJsonArray(current) { it.toJson() })
        nowIn
    }

    // ---- saved playlists ----

    fun savedPlaylists(): List<Playlist> = synchronized(lock) {
        playlistsCache ?: run {
            val arr = readArrayLocked("playlists.json")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map(Playlist::fromJson)
                .also { playlistsCache = it }
        }
    }

    fun isPlaylistSaved(playlistId: String): Boolean = savedPlaylists().any { it.id == playlistId }

    fun togglePlaylist(playlist: Playlist): Boolean = synchronized(lock) {
        val current = savedPlaylists().toMutableList()
        val idx = current.indexOfFirst { it.id == playlist.id }
        val nowSaved = if (idx >= 0) { current.removeAt(idx); false } else { current.add(0, playlist); true }
        playlistsCache = current
        writeLocked("playlists.json", toJsonArray(current) { it.toJson() })
        nowSaved
    }

    // ---- recent searches ----

    fun recentSearches(): List<String> = synchronized(lock) {
        searchesCache ?: run {
            val arr = readArrayLocked("searches.json")
            (0 until arr.length()).map { arr.optString(it) }
                .filter { it.isNotEmpty() }
                .also { searchesCache = it }
        }
    }

    fun addSearch(q: String) = synchronized(lock) {
        val current = recentSearches().filterNot { it.equals(q, ignoreCase = true) }.toMutableList()
        current.add(0, q)
        while (current.size > 20) current.removeAt(current.size - 1)
        searchesCache = current
        writeLocked("searches.json", JSONArray(current).toString())
    }

    fun clearSearches() = synchronized(lock) {
        searchesCache = emptyList()
        writeLocked("searches.json", "[]")
    }

    private inline fun <T> toJsonArray(items: List<T>, crossinline toJson: (T) -> JSONObject): String {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        return arr.toString()
    }
}
