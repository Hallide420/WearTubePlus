package com.hallide.weartubeplus.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.hallide.weartubeplus.data.DownloadKind
import com.hallide.weartubeplus.data.DownloadStatus
import com.hallide.weartubeplus.data.Downloads
import com.hallide.weartubeplus.data.Repo
import com.hallide.weartubeplus.data.Store
import com.hallide.weartubeplus.data.Video
import com.hallide.weartubeplus.player.Downloader
import com.hallide.weartubeplus.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailsScreen(
    videoId: String,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenComments: (String) -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val bundle by PlayerController.bundle.collectAsState()
    val upNext by PlayerController.upNext.collectAsState()
    val downloads by Downloads.items.collectAsState()
    val download = downloads.firstOrNull { it.videoId == videoId }

    val cached = remember(videoId) { NavCache.video(videoId) }
    // Fetched only when neither the player bundle nor NavCache knows this video
    // (deep link / process death) — without it Watch Later stores a blank row.
    var fetchedMeta by remember(videoId) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(videoId) {
        if (cached.title.isEmpty() && PlayerController.bundle.value?.title.isNullOrEmpty()) {
            val m = com.hallide.weartubeplus.data.Repo.videoMeta(videoId)
            if (m.first.isNotEmpty()) fetchedMeta = m
        }
    }
    val title = bundle?.title?.ifEmpty { null } ?: cached.title.ifEmpty { null }
        ?: fetchedMeta?.first.orEmpty()
    val channelName = bundle?.channel?.ifEmpty { null } ?: cached.channel.ifEmpty { null }
        ?: fetchedMeta?.second.orEmpty()

    fun videoForDownload(): Video = cached.copy(
        id = videoId,
        title = title.ifEmpty { cached.title },
        channel = channelName.ifEmpty { cached.channel },
    )

    var inWatchLater by remember { mutableStateOf(false) }
    var related by remember { mutableStateOf<List<Video>>(emptyList()) }

    LaunchedEffect(videoId) {
        inWatchLater = withContext(Dispatchers.IO) { Store.inWatchLater(videoId) }
        related = if (upNext.isNotEmpty()) upNext else try {
            Repo.related(videoId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 28.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
            autoCentering = null,
        ) {
            item {
                Text(
                    title,
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            val meta = listOf(cached.views, cached.published).filter { it.isNotEmpty() }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                item {
                    Text(
                        meta,
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (channelName.isNotEmpty() && cached.channelId.isNotEmpty()) {
                item {
                    Chip(
                        onClick = { onOpenChannel(cached.channelId) },
                        label = { Text(channelName, maxLines = 1) },
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Chip(
                    onClick = {
                        val v = if (cached.title.isNotEmpty()) cached
                        else cached.copy(title = title, channel = channelName)
                        inWatchLater = Store.toggleWatchLater(v)
                    },
                    label = { Text(if (inWatchLater) "Saved ✓" else "Watch Later") },
                    icon = { Icon(Icons.Filled.WatchLater, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val d = download
                val label = when (d?.status) {
                    null -> "Download"
                    DownloadStatus.QUEUED -> "Queued · tap to cancel"
                    DownloadStatus.DOWNLOADING -> "${((d?.fraction ?: 0f) * 100).toInt()}% · tap to cancel"
                    DownloadStatus.PAUSED -> "Paused · tap to resume"
                    DownloadStatus.FAILED -> "Failed · tap to retry"
                    DownloadStatus.DONE -> "Downloaded ✓ · tap to delete"
                }
                Chip(
                    onClick = {
                        when (d?.status) {
                            null -> Downloader.enqueue(videoForDownload(), DownloadKind.VIDEO)
                            DownloadStatus.QUEUED,
                            DownloadStatus.DOWNLOADING -> Downloader.cancel(videoId)
                            DownloadStatus.PAUSED,
                            DownloadStatus.FAILED ->
                                Downloader.enqueue(videoForDownload(), d?.kind ?: DownloadKind.VIDEO)
                            DownloadStatus.DONE -> {
                                if (PlayerController.nowPlaying.value?.id == videoId) {
                                    PlayerController.stop()
                                }
                                Downloads.remove(videoId)
                            }
                        }
                    },
                    label = { Text(label, maxLines = 1) },
                    secondaryLabel = if (d?.status == DownloadStatus.FAILED && !d.error.isNullOrEmpty()) {
                        { Text(d?.error.orEmpty(), maxLines = 1) }
                    } else null,
                    icon = {
                        Icon(
                            if (d?.status == DownloadStatus.DONE) Icons.Filled.DownloadDone
                            else Icons.Filled.Download,
                            contentDescription = null,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = { onOpenComments(videoId) },
                    label = { Text("Comments") },
                    icon = { Icon(Icons.Filled.Comment, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (related.isNotEmpty()) {
                item { SectionHeader("Up next") }
                items(related, key = { it.id }) { v ->
                    VideoCard(v) { NavCache.put(v); onOpenVideo(v.id) }
                }
            }
        }
    }
}
