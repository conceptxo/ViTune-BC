package app.vitune.android.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.R
import app.vitune.android.models.PipedSession
import app.vitune.android.models.Playlist
import app.vitune.android.models.PlaylistPreview
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.OrderPreferences
import app.vitune.android.preferences.UIStatePreferences
import app.vitune.android.query
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.Menu
import app.vitune.android.ui.components.themed.MenuEntry
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.TextFieldDialog
import app.vitune.android.ui.components.themed.VerticalDivider
import app.vitune.android.ui.items.PlaylistItem
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import app.vitune.android.ui.screens.settings.SettingsEntryGroupText
import app.vitune.android.ui.screens.settings.SettingsGroupSpacer
import app.vitune.android.utils.semiBold
import app.vitune.compose.persist.persist
import app.vitune.compose.persist.persistList
import app.vitune.core.data.enums.BuiltInPlaylist
import app.vitune.core.data.enums.PlaylistSortBy
import app.vitune.core.data.enums.SortOrder
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.piped.Piped
import app.vitune.providers.piped.models.Session
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import app.vitune.providers.piped.models.PlaylistPreview as PipedPlaylistPreview

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Route
@Composable
fun HomePlaylists(
    onBuiltInPlaylist: (BuiltInPlaylist) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPipedPlaylistClick: (Session, PipedPlaylistPreview) -> Unit,
    onSearchClick: () -> Unit
) = with(OrderPreferences) {
    val (colorPalette) = LocalAppearance.current
    val context = LocalContext.current

    var isCreatingANewPlaylist by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var isReorderMode by rememberSaveable { mutableStateOf(false) }
    val menuState = LocalMenuState.current

    if (isCreatingANewPlaylist) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        onDismiss = { isCreatingANewPlaylist = false },
        onAccept = { text ->
            query {
                Database.insert(Playlist(name = text))
            }
        }
    )

    var allItems by persistList<PlaylistPreview>("home/playlists")
    val items = remember(allItems, searchQuery, isSearching) {
        if (isSearching && searchQuery.isNotBlank()) {
            allItems.filter { it.playlist.name.contains(searchQuery, ignoreCase = true) }.toImmutableList()
        } else allItems
    }
    var pipedSessions by persist<Map<PipedSession, List<PipedPlaylistPreview>?>>("home/piped")

    LaunchedEffect(playlistSortBy, playlistSortOrder) {
        Database
            .playlistPreviews(playlistSortBy, playlistSortOrder)
            .collect { allItems = it.toImmutableList() }
    }

    LaunchedEffect(Unit) {
        Database.pipedSessions().collect { sessions ->
            pipedSessions = sessions.associateWith { session ->
                async {
                    Piped.playlist.list(session = session.toApiSession())?.getOrNull()
                }
            }.mapValues { (_, value) -> value.await() }
        }
    }

    val sortOrderIconRotation by animateFloatAsState(
        targetValue = if (playlistSortOrder == SortOrder.Ascending) 0f else 180f,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = ""
    )

    val lazyGridState = rememberLazyGridState()

    val builtInPlaylists by BuiltInPlaylistScreen.shownPlaylistsAsState()

    Box {
        LazyVerticalGrid(
            state = lazyGridState,
            columns = if (UIStatePreferences.playlistsAsGrid)
                GridCells.Adaptive(Dimensions.thumbnails.playlist + Dimensions.items.alternativePadding * 2)
            else GridCells.Fixed(1),
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal)
                .asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.items.alternativePadding),
            verticalArrangement = if (UIStatePreferences.playlistsAsGrid)
                Arrangement.spacedBy(Dimensions.items.alternativePadding)
            else Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .background(colorPalette.background0)
        ) {
            item(key = "header", contentType = 0, span = { GridItemSpan(maxLineSpan) }) {
                Header(title = stringResource(R.string.playlists)) {
                    SecondaryTextButton(
                        text = stringResource(R.string.new_playlist),
                        onClick = { isCreatingANewPlaylist = true }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Search Playlist capsule
                    SecondaryTextButton(
                        text = "Search",
                        onClick = { isSearching = !isSearching; if (!isSearching) searchQuery = "" }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Menu button (star icon with rotation animation)
                    HeaderIconButton(
                        icon = R.drawable.medical,
                        color = colorPalette.text,
                        onClick = {
                            menuState.display {
                                Menu {
                                    MenuEntry(
                                        icon = R.drawable.arrow_down_up,
                                        text = if (isReorderMode) "Done reordering" else "Reorder playlists",
                                        onClick = {
                                            menuState.hide()
                                            isReorderMode = !isReorderMode
                                        }
                                    )
                                    MenuEntry(
                                        icon = R.drawable.medical,
                                        text = if (playlistSortOrder == SortOrder.Ascending) "Sort: Newest First" else "Sort: Oldest First",
                                        onClick = {
                                            menuState.hide()
                                            playlistSortOrder = !playlistSortOrder
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.graphicsLayer { rotationZ = sortOrderIconRotation }
                    )
                }
            }

            // 2x2 Bento Grid Layout for Built-in Playlists
            if (builtInPlaylists.isNotEmpty()) item(key = "built_in_grid", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimensions.items.alternativePadding),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    // Row 1: Favorites & Offline side-by-side
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimensions.items.alternativePadding),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (BuiltInPlaylist.Favorites in builtInPlaylists) {
                            CapsulePlaylistButton(
                                icon = R.drawable.heart,
                                colorTint = colorPalette.red,
                                name = stringResource(R.string.favorites),
                                onClick = { onBuiltInPlaylist(BuiltInPlaylist.Favorites) },
                                modifier = Modifier
                                    .weight(1f)
                                    .animateItem()
                            )
                        }
                        if (BuiltInPlaylist.Offline in builtInPlaylists) {
                            CapsulePlaylistButton(
                                icon = R.drawable.airplane,
                                colorTint = colorPalette.blue,
                                name = stringResource(R.string.offline),
                                onClick = { onBuiltInPlaylist(BuiltInPlaylist.Offline) },
                                modifier = Modifier
                                    .weight(1f)
                                    .animateItem()
                            )
                        }
                    }

                    // Row 2: Top 50 & History side-by-side
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimensions.items.alternativePadding),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (BuiltInPlaylist.Top in builtInPlaylists) {
                            CapsulePlaylistButton(
                                icon = R.drawable.trending,
                                colorTint = colorPalette.red,
                                name = stringResource(R.string.format_my_top_playlist, DataPreferences.topListLength),
                                onClick = { onBuiltInPlaylist(BuiltInPlaylist.Top) },
                                modifier = Modifier
                                    .weight(1f)
                                    .animateItem()
                            )
                        }
                        if (BuiltInPlaylist.History in builtInPlaylists) {
                            CapsulePlaylistButton(
                                icon = R.drawable.history,
                                colorTint = colorPalette.textDisabled,
                                name = stringResource(R.string.history),
                                onClick = { onBuiltInPlaylist(BuiltInPlaylist.History) },
                                modifier = Modifier
                                    .weight(1f)
                                    .animateItem()
                            )
                        }
                    }
                }
            }

            // Search bar (visible when searching)
            if (isSearching) item(key = "search_bar", span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.material3.TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    placeholder = { BasicText("Search playlists...", style = LocalAppearance.current.typography.s.semiBold.copy(color = Color.White.copy(alpha = 0.4f))) },
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.12f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }

            items(
                items = items,
                key = { it.playlist.id }
            ) { playlistPreview ->
                PlaylistItem(
                    playlist = playlistPreview,
                    thumbnailSize = Dimensions.thumbnails.playlist,
                    alternative = UIStatePreferences.playlistsAsGrid,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onPlaylistClick(playlistPreview.playlist) },
                            onLongClick = {
                                if (isReorderMode) {
                                    // TODO: Implement drag-to-reorder for grid
                                    // For now, shows a toast indicating reorder mode
                                }
                            }
                        )
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                )
            }

            pipedSessions
                ?.ifEmpty { null }
                ?.filter { it.value?.isNotEmpty() == true }
                ?.forEach { (session, playlists) ->
                    item(
                        key = "piped-header-${session.username}",
                        contentType = 0,
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        SettingsGroupSpacer()
                        SettingsEntryGroupText(title = session.username)
                    }

                    playlists?.let {
                        items(
                            items = playlists,
                            key = { "piped-${session.username}-${it.id}" }
                        ) { playlist ->
                            PlaylistItem(
                                name = playlist.name,
                                songCount = playlist.videoCount,
                                channelName = null,
                                thumbnailUrl = playlist.thumbnailUrl.toString(),
                                thumbnailSize = Dimensions.thumbnails.playlist,
                                alternative = UIStatePreferences.playlistsAsGrid,
                                modifier = Modifier
                                    .clickable(onClick = {
                                        onPipedPlaylistClick(
                                            session.toApiSession(),
                                            playlist
                                        )
                                    })
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            )
                        }
                    }
                }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyGridState = lazyGridState,
            icon = R.drawable.search,
            onClick = onSearchClick
        )
    }
}

// ====================================================================
//  CAPSULE PLAYLIST BUTTON
//  - Full capsule shape (50% corner radius)
//  - Long-press to set custom cover from gallery
//  - Custom cover shown as blurred background (95% blur)
//  - Glass reflection on top
//  - Icon + label visible on top
// ====================================================================

@Composable
fun CapsulePlaylistButton(
    @DrawableRes icon: Int,
    colorTint: Color,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Custom cover URI for this button (persisted via SharedPreferences)
    val prefs = remember { context.getSharedPreferences("capsule_covers", android.content.Context.MODE_PRIVATE) }
    val coverKey = "cover_${name}"
    var coverUri by remember { mutableStateOf(prefs.getString(coverKey, null)) }

    // Image picker launcher
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(coverKey, uri.toString()).apply()
            coverUri = uri.toString()
        }
    }

    // Capsule shape: fully rounded ends (50% of height)
    val capsuleShape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(capsuleShape)
            // Layer 1: Base glass fill (color tinted)
            .background(colorTint.copy(alpha = 0.10f))
            // Layer 2: If custom cover, show it blurred (95% blur)
            .then(
                if (coverUri != null) {
                    Modifier.background(color = Color.Black)
                } else Modifier
            )
    ) {
        // Blurred cover image (if set)
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(25.dp)  // 95% blur
            )
        }

        // Layer 3: Glass reflection (glossy top highlight)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = 0.35f),
                            0.3f to Color.White.copy(alpha = 0.08f),
                            0.5f to Color.Transparent
                        )
                    )
                )
        )

        // Layer 4: White border (glass edge)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, Color.White.copy(alpha = 0.25f), capsuleShape)
        )

        // Layer 5: Shadow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(6.dp, capsuleShape, clip = false)
        )

        // Content: Icon + Label (on top of everything)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        // Long-press → pick cover from gallery
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorTint),
                modifier = Modifier.size(24.dp)
            )
            BasicText(
                text = name,
                style = LocalAppearance.current.typography.xs.semiBold.copy(
                    color = Color.White,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        blurRadius = 3f
                    )
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

enum class FluidPosition {
    First, Second, Third, Fourth
}
