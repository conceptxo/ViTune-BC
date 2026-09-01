package app.vitune.android.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.requests.likedPlaylists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun YouTubePlaylistsScreen(
    onPlaylistClick: (browseId: String, params: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var playlists by remember { mutableStateOf<List<Innertube.PlaylistItem>?>(null) }

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) {
            Innertube.likedPlaylists().getOrNull()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(playlists.orEmpty()) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.info?.name.orEmpty()) },
                    supportingContent = { Text("${playlist.songCount ?: 0} songs") },
                    modifier = Modifier.clickable {
                        playlist.info?.endpoint?.browseId?.let { browseId ->
                            onPlaylistClick(browseId, playlist.info.endpoint?.params)
                        }
                    }
                )
            }
        }
    }
}
