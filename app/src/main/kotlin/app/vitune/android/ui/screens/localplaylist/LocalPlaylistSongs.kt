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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
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
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.completed
import app.vitune.android.utils.forcePlayAtIndex
import app.vitune.android.utils.forcePlayFromBeginning
import app.vitune.android.utils.launchYouTubeMusic
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.toast
import app.vitune.compose.reordering.animateItemPlacement
import app.vitune.compose.reordering.draggedItem
import app.vitune.compose.reordering.rememberReorderingState
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.BrowseBody
import app.vitune.providers.innertube.requests.playlistPage
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalPlaylistSongs(
    playlist: Playlist,
    songs: ImmutableList<Song>,
    thumbnailUrl: String?,
    onDelete: () -> Unit,
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
    var isSortedOldestFirst by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var isLocked by rememberSaveable { mutableStateOf(true) }

    // Filter + sort songs
    val displaySongs = remember(songs, isSortedOldestFirst, searchQuery, isSearching) {
        var result: ImmutableList<Song> = songs
        if (isSearching && searchQuery.isNotBlank()) {
            result = songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artistsText?.contains(searchQuery, ignoreCase = true) == true
            }.toImmutableList()
        }
        if (isSortedOldestFirst) result.reversed().toImmutableList() else result
    }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            query { Database.update(playlist.copy(thumbnail = uri.toString())) }
        }
    }

    LaunchedEffect(Unit) {
        if (DataPreferences.autoSyncPlaylists) playlist.browseId?.let { browseId ->
            loading = true; sync(playlist, browseId); loading = false
        }
    }

    val reorderingState = rememberReorderingState(
        lazyListState = lazyListState,
        key = displaySongs,
        onDragEnd = { fromIndex, toIndex ->
            transaction { Database.move(playlist.id, fromIndex, toIndex) }
        },
        extraItemCount = 1
    )

    var isRenaming by rememberSaveable { mutableStateOf(false) }
    if (isRenaming) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        initialTextInput = playlist.name,
        onDismiss = { isRenaming = false },
        onAccept = { text -> query { Database.update(playlist.copy(name = text)) } }
    )

    var isDeleting by rememberSaveable { mutableStateOf(false) }
    if (isDeleting) ConfirmationDialog(
        text = stringResource(R.string.confirm_delete_playlist),
        onDismiss = { isDeleting = false },
        onConfirm = { query { Database.delete(playlist) }; onDelete() }
    )

    val (currentMediaId, playing) = playingSong(binder)
    // Only show "Pause" if a song from THIS playlist is ACTUALLY playing
    // (not just loaded/paused). Uses shouldBePlaying for accurate state.
    val isPlayingThisPlaylist = binder?.player?.let { player ->
        player.shouldBePlaying && currentMediaId != null &&
            songs.any { it.id == currentMediaId }
    } ?: false
    val dateFormat = remember { SimpleDateFormat("HH:mm – dd MMMM yyyy", Locale.getDefault()) }
    val createdDate = remember(playlist.id) { dateFormat.format(Date()) }

    // ====================================================================
    //  SIMPMUSIC-STYLE IMMERSIVE PLAYLIST DETAIL (v4)
    //  - Album art as FULL-SCREEN AsyncImage (no card, no clip, no padding)
    //  - 48dp blur creates frosted colored backdrop
    //  - Dark gradient melts from transparent → pitch black #000
    //  - Back/Search/Menu = plain icons (no circles)
    //  - Shuffle/Search = solid circles
    //  - Play/Pause = white pill
    //  - Sort works + song count inside capsule
    //  - Search filters songs in playlist
    // ====================================================================
    Box(modifier = modifier.fillMaxSize()) {
        // ---- 1. FULL-SCREEN BACKGROUND WITH BLUR (60%) ----
        // Uses custom cover OR first song's thumbnail as background.
        val backgroundUrl = thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl
        if (backgroundUrl != null) {
            AsyncImage(
                model = backgroundUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(20.dp)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(colorPalette.background0))
        }

        // ---- 2. DARK GRADIENT (melts into pitch black #000) ----
        // Transparent at top (art visible) → semi-dark middle → pure #000 bottom
        // Zero hard cutoff — smooth, organic, atmospheric blend.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.05f),
                            0.20f to Color.Black.copy(alpha = 0.15f),
                            0.40f to Color.Black.copy(alpha = 0.40f),
                            0.55f to Color.Black.copy(alpha = 0.65f),
                            0.70f to Color.Black.copy(alpha = 0.85f),
                            0.85f to Color.Black.copy(alpha = 0.97f),
                            1.00f to Color.Black
                        )
                    )
                )
        )

        // ---- 4. CONTENT ----
        LookaheadScope {
            LazyColumn(
                state = reorderingState.lazyListState,
                contentPadding = LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues(),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "header", contentType = 0) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // ---- TOP BAR: Back (left) + Search + Menu (right) ----
                        // ALL plain icons — no circles, no glass
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            // Back — plain icon
                            Image(
                                painter = painterResource(R.drawable.chevron_back),
                                contentDescription = "Back",
                                colorFilter = ColorFilter.tint(Color.White),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onDelete() }
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                AnimatedVisibility(loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                }

                                // Menu — plain icon (ONLY this in top bar, no search)
                                Image(
                                    painter = painterResource(R.drawable.ellipsis_horizontal),
                                    contentDescription = "Menu",
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier
                                        .size(28.dp)
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
                                                                        "https://youtube.com/watch?v=$firstSongId&list=${playlist.browseId.drop(2)}"
                                                                    )
                                                                }
                                                            )
                                                            val errorMessage = stringResource(R.string.youtube_music_not_installed)
                                                            MenuEntry(
                                                                icon = R.drawable.musical_notes,
                                                                text = stringResource(R.string.open_in_youtube_music),
                                                                onClick = {
                                                                    menuState.hide()
                                                                    binder?.player?.pause()
                                                                    if (!launchYouTubeMusic(
                                                                        context = context,
                                                                        endpoint = "watch?v=$firstSongId&list=${playlist.browseId.drop(2)}"
                                                                    )) context.toast(errorMessage)
                                                                }
                                                            )
                                                        }
                                                    }
                                                    MenuEntry(
                                                        icon = R.drawable.arrow_down_up,
                                                        text = if (isLocked) "Reorder songs" else "Done reordering",
                                                        onClick = {
                                                            menuState.hide()
                                                            isLocked = !isLocked
                                                        }
                                                    )
                                                    MenuEntry(
                                                        icon = R.drawable.pencil,
                                                        text = stringResource(R.string.rename),
                                                        onClick = { menuState.hide(); isRenaming = true }
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
                                                        onClick = { menuState.hide(); isDeleting = true }
                                                    )
                                                }
                                            }
                                        }
                                )
                            }
                        }

                        // ---- SEARCH BAR (visible only when searching) ----
                        if (isSearching) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(24.dp)),
                                placeholder = { BasicText("Search in playlist...", style = typography.s.semiBold.copy(color = Color.White.copy(alpha = 0.4f))) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.12f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                                    cursorColor = Color.White,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }

                        // ---- CRISP COVER PHOTO (on top of blurred background) ----
                        // If playlist has a custom cover: show it.
                        // If no custom cover: use song thumbnails as collage.
                        // - 1 song: single thumbnail
                        // - 2-3 songs: first 2 thumbnails side by side
                        // - 4+ songs: 2x2 collage of first 4 thumbnails
                        Box(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .size(240.dp)
                                .shadow(
                                    elevation = 6.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    clip = false,
                                    ambientColor = Color.Black,
                                    spotColor = Color.Black
                                )
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorPalette.background1)
                        ) {
                            if (thumbnailUrl != null) {
                                AsyncImage(
                                    model = thumbnailUrl,
                                    contentDescription = "Playlist cover",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (songs.isNotEmpty()) {
                                val collageThumbs = songs.take(4).map { it.thumbnailUrl }
                                if (collageThumbs.size == 1) {
                                    AsyncImage(
                                        model = collageThumbs[0],
                                        contentDescription = "Playlist cover",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        listOf(
                                            Alignment.TopStart,
                                            Alignment.TopEnd,
                                            Alignment.BottomStart,
                                            Alignment.BottomEnd
                                        ).forEachIndexed { index, alignment ->
                                            if (index < collageThumbs.size) {
                                                AsyncImage(
                                                    model = collageThumbs[index],
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .align(alignment)
                                                        .fillMaxSize(0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // ---- PLAYLIST NAME ----
                        BasicText(
                            text = playlist.name,
                            style = typography.l.semiBold.copy(color = Color.White),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        BasicText(
                            text = "Your Playlist",
                            style = typography.s.semiBold.copy(color = Color.White.copy(alpha = 0.8f)),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        BasicText(
                            text = "Created at $createdDate",
                            style = typography.xs.semiBold.copy(color = Color.White.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // ---- CENTER BUTTONS: Shuffle | Play/Pause | Search ----
                        // (Search stays here as a circle — per user's original request)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Shuffle — solid circle
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (displaySongs.isNotEmpty()) {
                                            binder?.stopRadio()
                                            binder?.player?.forcePlayFromBeginning(
                                                displaySongs.shuffled().map { it.asMediaItem }
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

                            // Play/Pause — white pill
                            Box(
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.White)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (displaySongs.isNotEmpty()) {
                                            if (isPlayingThisPlaylist) binder?.player?.pause()
                                            else {
                                                binder?.stopRadio()
                                                binder?.player?.forcePlayFromBeginning(
                                                    displaySongs.map { it.asMediaItem }
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
                                        painter = painterResource(if (isPlayingThisPlaylist) R.drawable.pause else R.drawable.play),
                                        contentDescription = if (isPlayingThisPlaylist) "Pause" else "Play",
                                        colorFilter = ColorFilter.tint(colorPalette.background0),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    BasicText(
                                        text = if (isPlayingThisPlaylist) "Pause" else "Play",
                                        style = typography.s.semiBold.copy(color = colorPalette.background0)
                                    )
                                }
                            }

                            // Search (circle — works, toggles search bar)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        isSearching = !isSearching
                                        if (!isSearching) searchQuery = ""
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // ---- SORT CAPSULE (COMPACT) + SONG COUNT (inside, right side) ----
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { isSortedOldestFirst = !isSortedOldestFirst }
                                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Left: sort icon + text
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.reorder),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.7f)),
                                    modifier = Modifier.size(18.dp)
                                )
                                BasicText(
                                    text = "Sort by: ${if (isSortedOldestFirst) "Oldest First" else "Custom Order"}",
                                    style = typography.xs.semiBold.copy(color = Color.White.copy(alpha = 0.8f))
                                )
                            }

                            // Right: song count (small capsule INSIDE the sort capsule)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                BasicText(
                                    text = "${displaySongs.size}",
                                    style = typography.xxs.semiBold.copy(color = Color.White.copy(alpha = 0.8f))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ---- SONG LIST ----
                itemsIndexed(
                    items = displaySongs,
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
                                        items = displaySongs.map { it.asMediaItem },
                                        index = index
                                    )
                                }
                            )
                            .animateItemPlacement(reorderingState)
                            .draggedItem(reorderingState = reorderingState, index = index)
                            .background(Color.Transparent),
                        song = song,
                        thumbnailSize = Dimensions.thumbnails.song,
                        trailingContent = {
                            if (!isLocked) {
                                ReorderHandle(reorderingState = reorderingState, index = index)
                            }
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
