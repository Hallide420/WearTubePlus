package com.hallide.weartubeplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.compose.foundation.layout.fillMaxSize
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.lifecycle.lifecycleScope
import com.hallide.weartubeplus.data.Auth
import com.hallide.weartubeplus.data.DownloadKind
import com.hallide.weartubeplus.data.Downloads
import com.hallide.weartubeplus.data.Net
import com.hallide.weartubeplus.data.Settings
import com.hallide.weartubeplus.data.Store
import com.hallide.weartubeplus.data.TvAuth
import com.hallide.weartubeplus.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.hallide.weartubeplus.player.Downloader
import com.hallide.weartubeplus.player.PlayerScreen
import com.hallide.weartubeplus.ui.ChannelScreen
import com.hallide.weartubeplus.ui.CommentsScreen
import com.hallide.weartubeplus.ui.DetailsScreen
import com.hallide.weartubeplus.ui.HomeScreen
import com.hallide.weartubeplus.ui.LibraryScreen
import com.hallide.weartubeplus.ui.MiniPlayer
import com.hallide.weartubeplus.ui.LibrarySectionScreen
import com.hallide.weartubeplus.ui.PlaylistScreen
import com.hallide.weartubeplus.ui.ResultsScreen
import com.hallide.weartubeplus.ui.SearchScreen
import com.hallide.weartubeplus.ui.TvSignInScreen
import com.hallide.weartubeplus.ui.WearTubePlusTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        // keep the activity's stored intent free of one-shot debug extras
        newIntent.removeExtra("open_video")
        newIntent.removeExtra("open_playlist")
        newIntent.removeExtra("open_channel")
        newIntent.removeExtra("open_tab")
        newIntent.removeExtra("download_video")
        setIntent(newIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        Downloads.init(applicationContext)
        Net.init(applicationContext)
        Downloader.init(applicationContext)
        Settings.init(applicationContext)
        Auth.init(applicationContext)
        TvAuth.init(applicationContext)
        // Media3 needs to post a media notification to hold the foreground service
        // that keeps audio alive in the background.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // silent token refresh; consent (if ever needed) only happens via the Library sign-in flow
        lifecycleScope.launch(Dispatchers.IO) { Auth.fetchToken(null) }
        // Debug hooks: adb shell am start ... --es open_video <id> / open_playlist /
        // open_channel / open_tab / download_video.
        //
        // These MUST be consumed. Android hands the activity the same launch intent
        // again every time the task is resumed or the activity is recreated, so a
        // leftover extra kept re-opening the last test video ("Me at the zoo") during
        // ordinary use, looking like a random autoplay bug.
        val debugVideo = intent?.getStringExtra("open_video")
        val debugPlaylist = intent?.getStringExtra("open_playlist")
        val debugChannel = intent?.getStringExtra("open_channel")
        val debugTab = intent?.getStringExtra("open_tab")
        val debugDownload = intent?.getStringExtra("download_video")
        intent?.apply {
            removeExtra("open_video")
            removeExtra("open_playlist")
            removeExtra("open_channel")
            removeExtra("open_tab")
            removeExtra("download_video")
        }
        if (!debugDownload.isNullOrEmpty()) {
            Downloader.enqueue(Video(id = debugDownload, title = ""), DownloadKind.VIDEO)
        }
        setContent {
            WearTubePlusTheme {
                WearTubeNav(debugVideo, debugPlaylist, debugChannel, debugTab)
            }
        }
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

@Composable
private fun WearTubeNav(
    debugVideo: String? = null,
    debugPlaylist: String? = null,
    debugChannel: String? = null,
    debugTab: String? = null,
) {
    val nav: NavHostController = rememberSwipeDismissableNavController()

    androidx.compose.runtime.LaunchedEffect(debugVideo, debugPlaylist, debugChannel, debugTab) {
        // navigate() throws IllegalArgumentException on a route the graph doesn't match,
        // which would be a hard crash — debug hooks take arbitrary strings, so guard them.
        runCatching {
            if (!debugVideo.isNullOrEmpty()) nav.navigate("video/$debugVideo")
            if (!debugPlaylist.isNullOrEmpty()) nav.navigate("playlist/$debugPlaylist")
            if (!debugChannel.isNullOrEmpty()) nav.navigate("channel/$debugChannel")
            if (!debugTab.isNullOrEmpty()) nav.navigate(debugTab)
        }.onFailure { android.util.Log.w("WearTubePlus", "debug nav failed: ${it.message}") }
    }

    fun openVideo(id: String) = nav.navigate("video/$id")
    fun openChannel(id: String) = nav.navigate("channel/$id")
    fun openPlaylist(id: String) = nav.navigate("playlist/$id")
    fun openSearchResults(q: String) {
        // a blank query yields route "results/" which matches nothing -> crash
        if (q.isBlank()) return
        nav.navigate("results/${enc(q)}")
    }

    // The mini player overlays every destination except the player itself, so
    // backing out of a video always leaves a visible, tappable pause control.
    // navigation-compose isn't a dependency (this app uses wear navigation), so
    // observe the back stack through the runtime flow instead.
    val backStackEntry by nav.currentBackStackEntryFlow
        .collectAsState(initial = nav.currentBackStackEntry)
    val onPlayerScreen = backStackEntry?.destination?.route?.startsWith("video/") == true

    androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenVideo = ::openVideo,
                onOpenSearch = { nav.navigate("search") },
                onOpenLibrary = { nav.navigate("library") },
                onOpenNowPlaying = {
                    com.hallide.weartubeplus.player.PlayerController.nowPlaying.value?.let { openVideo(it.id) }
                },
            )
        }
        composable("search") {
            SearchScreen(onSearch = ::openSearchResults)
        }
        composable("results/{query}") { entry ->
            val q = dec(entry.arguments?.getString("query").orEmpty())
            ResultsScreen(
                query = q,
                onOpenVideo = ::openVideo,
                onOpenChannel = ::openChannel,
                onOpenPlaylist = ::openPlaylist,
                onSearch = ::openSearchResults,
            )
        }
        composable("video/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            PlayerScreen(videoId = id, onOpenDetails = { nav.navigate("details/$it") })
        }
        composable("details/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailsScreen(
                videoId = id,
                onOpenVideo = { vid ->
                    nav.popBackStack("video/{id}", inclusive = true)
                    openVideo(vid)
                },
                onOpenChannel = ::openChannel,
                onOpenComments = { nav.navigate("comments/$it") },
            )
        }
        composable("comments/{id}") { entry ->
            CommentsScreen(videoId = entry.arguments?.getString("id").orEmpty())
        }
        composable("channel/{id}") { entry ->
            ChannelScreen(
                channelId = entry.arguments?.getString("id").orEmpty(),
                onOpenVideo = ::openVideo,
            )
        }
        composable("playlist/{id}") { entry ->
            PlaylistScreen(
                playlistId = entry.arguments?.getString("id").orEmpty(),
                onOpenVideo = ::openVideo,
            )
        }
        composable("library") {
            LibraryScreen(
                onOpenVideo = ::openVideo,
                onOpenChannel = ::openChannel,
                onOpenSection = { nav.navigate("library/$it") },
                onSignIn = { nav.navigate("signin") },
            )
        }
        composable("signin") {
            TvSignInScreen(onSignedIn = { nav.popBackStack() })
        }
        composable("library/{section}") { entry ->
            LibrarySectionScreen(
                section = entry.arguments?.getString("section").orEmpty(),
                onOpenVideo = ::openVideo,
                onOpenChannel = ::openChannel,
                onOpenPlaylist = ::openPlaylist,
            )
        }
    }
        if (!onPlayerScreen) {
            MiniPlayer(onOpen = { id -> nav.navigate("video/$id") })
        }
    }
}
