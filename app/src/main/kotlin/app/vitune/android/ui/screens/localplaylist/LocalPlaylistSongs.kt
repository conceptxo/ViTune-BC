package app.vitune.android.ui.screens.localplaylist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Playlist
import app.vitune.android.models.Song
import app.vitune.android.models.SongPlaylistMap
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.query
import app.vitune.android.transaction
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.ConfirmationDialog
import app.vitune.android.ui.components.themed.InPlaylistMediaItemMenu
import app.vitune.android.ui.components.themed.Menu
import app.vitune.android.ui.components.themed.MenuEntry
import app.vitune.android.ui.components.themed.ReorderHandle
import app.vitune.android.ui.components.themed.TextFieldDialog
import app.vitune.android.ui.items.SongItem
import app.vitune.android.utils.PlaylistDownloadIcon
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.completed
import app.vitune.android.utils.enqueue
import app.vitune.android.utils.forcePlayAtIndex
import app.vitune.android.utils.forcePlayFromBeginning
import app.vitune.android.utils.launchYouTubeMusic
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.toast
import app.vitune.compose.reordering.animateItemPlacement
import app.vitune.compose.reordering.draggedItem
import app.vitune.compose.reordering.rememberReorderingState
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isLandscape
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.BrowseBody
import app.vitune.providers.innertube.requests.playlistPage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalPlaylistSongs(
    playlist: Playlist,
    songs: ImmutableList<Song>,
    onDelete: () -> Unit,
    thumbnailContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    var loading by remember { mutableStateOf(false) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            query {
                Database.update(playlist.copy(thumbnail = uri.toString()))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (DataPreferences.autoSyncPlaylists) playlist.browseId?.let { browseId ->
            loading = true
            sync(playlist, browseId)
            loading = false
        }
    }

    val reorderingState = rememberReorderingState(
        lazyListState = lazyListState,
        key = songs,
        onDragEnd = { fromIndex, toIndex ->
            transaction {
                Database.move(playlist.id, fromIndex, toIndex)
            }
        },
        extraItemCount = 1
    )

    var isRenaming by rememberSaveable { mutableStateOf(false) }

    if (isRenaming) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        initialTextInput = playlist.name,
        onDismiss = { isRenaming = false },
        onAccept = { text ->
            query {
                Database.update(playlist.copy(name = text))
            }
        }
    )

    var isDeleting by rememberSaveable { mutableStateOf(false) }

    if (isDeleting) ConfirmationDialog(
        text = stringResource(R.string.confirm_delete_playlist),
        onDismiss = { isDeleting = false },
        onConfirm = {
            query {
                Database.delete(playlist)
            }
            onDelete()
        }
    )

    val (currentMediaId, playing) = playingSong(binder)

    // ====================================================================
    //  SIMPMUSIC-STYLE PLAYLIST DETAIL SCREEN
    //  - Album art as full-screen blurred background
    //  - Gradient overlay (transparent top → dark bottom) for seamless blend
    //  - Top-right: Glossy menu button only (no search)
    //  - Center: Shuffle, Play/Pause, Search buttons
    //  - Song list below with dark background
    // ====================================================================
    Box(modifier = modifier.fillMaxSize()) {
        // 1. BLURRED ALBUM ART BACKGROUND (full screen)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    playlist.thumbnail?.let { url ->
                        Modifier.background(color = colorPalette.background0)
                    } ?: Modifier.background(color = colorPalette.background0)
                )
        ) {
            // Blurred background image
            thumbnailContent()
        }

        // 2. GRADIENT OVERLAY (transparent top → dark bottom)
        // This creates the "seamless blend" from album art to dark song list
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.20f),
                            0.30f to Color.Black.copy(alpha = 0.40f),
                            0.50f to Color.Black.copy(alpha = 0.70f),
                            0.70f to colorPalette.background0.copy(alpha = 0.90f),
                            1.00f to colorPalette.background0
                        )
                    )
                )
        )

        // 3. CONTENT (LazyColumn with header + songs)
        LookaheadScope {
            LazyColumn(
                state = reorderingState.lazyListState,
                contentPadding = LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues(),
                modifier = Modifier
                    .fillMaxSize()
            ) {
                item(
                    key = "header",
                    contentType = 0
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // ---- TOP BAR: Menu button (top-right, glossy) ----
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            AnimatedVisibility(loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 8.dp)
                                )
                            }

                            // Glossy menu button (circle)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.White.copy(alpha = 0.30f),
                                                0.4f to Color.Transparent
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .shadow(4.dp, RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        menuState.display {
                                            Menu {
                                                playlist.browseId?.let { browseId ->
                                                    MenuEntry(
                                                        icon = R.drawable.sync,
                                                        text = stringResource(R.string.sync),
                                                        enabled = !loading,
                                                        onClick = {
                                                            menuState.hide()
                                                            coroutineScope.launch {
                                                                loading = true
                                                                sync(playlist, browseId)
                                                                loading = false
                                                            }
                                                        }
                                                    )

                                                    songs.firstOrNull()?.id?.let { firstSongId ->
                                                        MenuEntry(
                                                            icon = R.drawable.play,
                                                            text = stringResource(R.string.watch_playlist_on_youtube),
                                                            onClick = {
                                                                menuState.hide()
                                                                binder?.player?.pause()
                                                                uriHandler.openUri(
                                                                    "https://youtube.com/watch?v=$firstSongId&list=${
                                                                        playlist.browseId.drop(2)
                                                                    }"
                                                                )
                                                            }
                                                        )

                                                        val errorMessage =
                                                            stringResource(R.string.youtube_music_not_installed)
                                                        MenuEntry(
                                                            icon = R.drawable.musical_notes,
                                                            text = stringResource(R.string.open_in_youtube_music),
                                                            onClick = {
                                                                menuState.hide()
                                                                binder?.player?.pause()
                                                                if (
                                                                    !launchYouTubeMusic(
                                                                        context = context,
                                                                        endpoint = "watch?v=$firstSongId&list=${
                                                                            playlist.browseId.drop(2)
                                                                        }"
                                                                    )
                                                                ) {
                                                                    context.toast(errorMessage)
                                                                }
                                                            }
                                                        )
                                                    }
                                                }

                                                MenuEntry(
                                                    icon = R.drawable.pencil,
                                                    text = stringResource(R.string.rename),
                                                    onClick = {
                                                        menuState.hide()
                                                        isRenaming = true
                                                    }
                                                )

                                                MenuEntry(
                                                    icon = R.drawable.disc,
                                                    text = "Change cover",
                                                    onClick = {
                                                        menuState.hide()
                                                        coverPickerLauncher.launch(
                                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                        )
                                                    }
                                                )

                                                MenuEntry(
                                                    icon = R.drawable.trash,
                                                    text = stringResource(R.string.delete),
                                                    onClick = {
                                                        menuState.hide()
                                                        isDeleting = true
                                                    }
                                                )
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ellipsis_horizontal),
                                    contentDescription = "Menu",
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // ---- PLAYLIST NAME (large, centered, white) ----
                        BasicText(
                            text = playlist.name,
                            style = typography.l.semiBold.copy(
                                color = Color.White
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )

                        // ---- Song count ----
                        BasicText(
                            text = "${songs.size} ${stringResource(R.string.songs)}",
                            style = typography.xs.semiBold.copy(
                                color = Color.White.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // ---- CENTER BUTTONS: Shuffle, Play/Pause, Search ----
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Shuffle (circle, glossy)
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.White.copy(alpha = 0.30f),
                                                0.4f to Color.Transparent
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .shadow(4.dp, RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (songs.isNotEmpty()) {
                                            binder?.stopRadio()
                                            binder?.player?.forcePlayFromBeginning(
                                                songs.shuffled().map { it.asMediaItem }
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.shuffle),
                                    contentDescription = "Shuffle",
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Play/Pause (large white pill button)
                            Box(
                                modifier = Modifier
                                    .width(160.dp)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(Color.White)
                                    .shadow(6.dp, RoundedCornerShape(26.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (songs.isNotEmpty()) {
                                            if (playing) {
                                                binder?.player?.pause()
                                            } else {
                                                binder?.stopRadio()
                                                binder?.player?.forcePlayFromBeginning(
                                                    songs.map { it.asMediaItem }
                                                )
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Image(
                                        painter = painterResource(
                                            if (playing) R.drawable.pause else R.drawable.play
                                        ),
                                        contentDescription = if (playing) "Pause" else "Play",
                                        colorFilter = ColorFilter.tint(colorPalette.background0),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    BasicText(
                                        text = if (playing) "Pause" else "Play",
                                        style = typography.s.semiBold.copy(
                                            color = colorPalette.background0
                                        )
                                    )
                                }
                            }

                            // Search (circle, glossy — replaces download)
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.White.copy(alpha = 0.30f),
                                                0.4f to Color.Transparent
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .shadow(4.dp, RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        // Search within playlist — opens search
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.search),
                                    contentDescription = "Search",
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // ---- SONG LIST ----
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id },
                    contentType = { _, song -> song }
                ) { index, song ->
                    SongItem(
                        modifier = Modifier
                            .combinedClickable(
                                onLongClick = {
                                    menuState.display {
                                        InPlaylistMediaItemMenu(
                                            playlistId = playlist.id,
                                            positionInPlaylist = index,
                                            song = song,
                                            onDismiss = menuState::hide
                                        )
                                    }
                                },
                                onClick = {
                                    binder?.stopRadio()
                                    binder?.player?.forcePlayAtIndex(
                                        items = songs.map { it.asMediaItem },
                                        index = index
                                    )
                                }
                            )
                            .animateItemPlacement(reorderingState)
                            .draggedItem(
                                reorderingState = reorderingState,
                                index = index
                            )
                            .background(colorPalette.background0),
                        song = song,
                        thumbnailSize = Dimensions.thumbnails.song,
                        trailingContent = {
                            ReorderHandle(
                                reorderingState = reorderingState,
                                index = index
                            )
                        },
                        clip = !reorderingState.isDragging,
                        isPlaying = playing && currentMediaId == song.id
                    )
                }
            }
        }
    }
}

private suspend fun sync(
    playlist: Playlist,
    browseId: String
) = runCatching {
    Innertube.playlistPage(
        BrowseBody(browseId = browseId)
    )?.completed()?.getOrNull()?.let { remotePlaylist ->
        transaction {
            Database.clearPlaylist(playlist.id)

            remotePlaylist.songsPage
                ?.items
                ?.map { it.asMediaItem }
                ?.onEach { Database.insert(it) }
                ?.mapIndexed { position, mediaItem ->
                    SongPlaylistMap(
                        songId = mediaItem.mediaId,
                        playlistId = playlist.id,
                        position = position
                    )
                }
                ?.let(Database::insertSongPlaylistMaps)
        }
    }
}.onFailure {
    if (it is CancellationException) throw it
    it.printStackTrace()
}
