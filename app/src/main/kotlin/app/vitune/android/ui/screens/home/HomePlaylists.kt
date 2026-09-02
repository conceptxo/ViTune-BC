package app.vitune.android.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.R
import app.vitune.android.models.PipedSession
import app.vitune.android.models.Playlist
import app.vitune.android.models.PlaylistPreview
import app.vitune.android.preferences.OrderPreferences
import app.vitune.android.preferences.UIStatePreferences
import app.vitune.android.query
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.TextFieldDialog
import app.vitune.android.ui.items.PlaylistItem
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import app.vitune.android.ui.screens.settings.SettingsEntryGroupText
import app.vitune.core.data.enums.BuiltInPlaylist
import app.vitune.core.data.enums.PlaylistSortBy
import app.vitune.core.data.enums.SortOrder
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.piped.Piped
import app.vitune.providers.piped.models.Session
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import app.vitune.providers.piped.models.PlaylistPreview as PipedPlaylistPreview

@Route
@Composable
fun HomePlaylists(
    onBuiltInPlaylist: (BuiltInPlaylist) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPipedPlaylistClick: (Session, PipedPlaylistPreview) -> Unit,
    onSearchClick: () -> Unit
) = with(OrderPreferences) {
    val (colorPalette) = LocalAppearance.current

    var isCreatingANewPlaylist by rememberSaveable { mutableStateOf(false) }

    if (isCreatingANewPlaylist) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        onDismiss = { isCreatingANewPlaylist = false },
        onAccept = { text ->
            query {
                Database.insert(Playlist(name = text))
            }
        }
    )

    var items by persistList<PlaylistPreview>("home/playlists")
    var pipedSessions by persist<Map<PipedSession, List<PipedPlaylistPreview>?>>("home/piped")

    LaunchedEffect(playlistSortBy, playlistSortOrder) {
        Database
            .playlistPreviews(playlistSortBy, playlistSortOrder)
            .collect { items = it.toImmutableList() }
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

    val lazyListState = rememberLazyListState()
    val builtInPlaylists by BuiltInPlaylistScreen.shownPlaylistsAsState()

    val showFloatingActionsContainer = lazyListState.firstVisibleItemIndex > 0 ||
            lazyListState.firstVisibleItemScrollOffset > 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorPalette.background0)
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal)
                .asPaddingValues(),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                Header(title = stringResource(R.string.playlists)) {
                    SecondaryTextButton(
                        text = stringResource(R.string.new_playlist),
                        onClick = { isCreatingANewPlaylist = true }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    HeaderIconButton(
                        icon = if (UIStatePreferences.playlistsAsGrid) R.drawable.grid else R.drawable.list,
                        onClick = { UIStatePreferences.playlistsAsGrid = !UIStatePreferences.playlistsAsGrid }
                    )

                    HeaderIconButton(
                        icon = R.drawable.swap_vert,
                        color = if (playlistSortBy != PlaylistSortBy.DateAdded || playlistSortOrder != SortOrder.Descending) colorPalette.accent else colorPalette.text,
                        rotation = sortOrderIconRotation,
                        onClick = { playlistSortOrder = playlistSortOrder.next() }
                    )

                    HeaderIconButton(
                        icon = R.drawable.search,
                        onClick = onSearchClick
                    )
                }
            }

            items(builtInPlaylists, key = { "builtin_${it.name}" }) { builtInPlaylist ->
                PlaylistItem(
                    icon = builtInPlaylist.icon,
                    colorTint = colorPalette.accent,
                    name = stringResource(builtInPlaylist.title),
                    songCount = null,
                    thumbnailSize = Dimensions.thumbnails.playlist,
                    onClick = { onBuiltInPlaylist(builtInPlaylist) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            items(items, key = { it.id }) { playlist ->
                PlaylistItem(
                    playlist = playlist,
                    thumbnailSize = Dimensions.thumbnails.playlist,
                    onClick = { onPlaylistClick(playlist.toPlaylist()) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            for ((session, playlists) in pipedSessions) {
                if (playlists == null) continue

                item(key = "piped_${session.id}_header") {
                    SettingsEntryGroupText(title = session.name)
                }

                items(playlists, key = { "piped_${session.id}_${it.id}" }) { playlist ->
                    PlaylistItem(
                        thumbnailUrl = playlist.thumbnailUrl,
                        songCount = playlist.songCount,
                        name = playlist.name,
                        channelName = null,
                        thumbnailSize = Dimensions.thumbnails.playlist,
                        onClick = { onPipedPlaylistClick(session.toApiSession(), playlist) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            show = showFloatingActionsContainer
        )
    }
}
