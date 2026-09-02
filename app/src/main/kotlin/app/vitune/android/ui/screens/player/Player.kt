package app.vitune.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.query
import app.vitune.android.ui.components.BottomSheetState
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.SliderDialog
import app.vitune.android.ui.components.themed.SliderDialogBody
import app.vitune.android.utils.DisposableListener
import app.vitune.android.utils.rememberPipHandler
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.thumbnail
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.routing.OnGlobalRoute
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.px
import app.vitune.core.ui.utils.songBundle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp
    ),
    windowInsets: WindowInsets = WindowInsets.systemBars
) {
    val (colorPalette, typography, _) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val pipHandler = rememberPipHandler()

    with(PlayerPreferences) {
        PersistMapCleanup(prefix = "queue/suggestions")
        var mediaItem by remember(binder) {
            mutableStateOf(
                value = binder?.player?.currentMediaItem,
                policy = neverEqualPolicy()
            )
        }
        
        var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.shouldBePlaying == true) }

        LaunchedEffect(mediaItem) {
            if (mediaItem != null && layoutState.dismissed) {
                layoutState.collapseSoft()
            }
        }
        
        var likedAt by remember(mediaItem) {
            mutableStateOf(
                value = null,
                policy = object : SnapshotMutationPolicy<Long?> {
                    override fun equivalent(a: Long?, b: Long?): Boolean {
                        mediaItem?.mediaId?.let {
                            query { Database.like(it, b) }
                        }
                        return a == b
                    }
                }
            )
        }

        LaunchedEffect(mediaItem) {
            mediaItem?.mediaId?.let { mediaId ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Database
                        .likedAt(mediaId)
                        .distinctUntilChanged()
                        .collect { likedAt = it }
                }
            }
        }

        binder?.player.DisposableListener {
            object : Player.Listener {
                override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                    mediaItem = newMediaItem
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    shouldBePlaying = binder?.player?.shouldBePlaying == true
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    shouldBePlaying = binder?.player?.shouldBePlaying == true
                }
            }
        }

        val metadata = remember(mediaItem) { mediaItem?.mediaMetadata }

        val horizontalBottomPaddingValues = windowInsets
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .asPaddingValues()

        OnGlobalRoute { if (layoutState.expanded) layoutState.collapseSoft() }

        var audioDialogOpen by rememberSaveable { mutableStateOf(false) }

        if (mediaItem != null) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(colorPalette.background0)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontalBottomPaddingValues)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colorPalette.textSecondary.copy(alpha = 0.4f))
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    AsyncImage(
                        model = metadata?.artworkUri?.thumbnail(Dimensions.thumbnails.song.px),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        BasicText(
                            text = metadata?.title?.toString().orEmpty(),
                            style = typography.bodyLarge.copy(color = colorPalette.text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicText(
                            text = metadata?.artist?.toString().orEmpty(),
                            style = typography.bodyMedium.copy(color = colorPalette.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            icon = R.drawable.heart,
                            color = if (likedAt != null) colorPalette.accent else colorPalette.text,
                            onClick = {
                                likedAt = if (likedAt == null) System.currentTimeMillis() else null
                            }
                        )
                        IconButton(
                            icon = R.drawable.volume_up,
                            onClick = { audioDialogOpen = true }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        if (audioDialogOpen) {
            SliderDialog(
                onDismiss = { audioDialogOpen = false },
                title = stringResource(R.string.playback_settings)
            ) {
                SliderDialogBody(
                    provideState = { remember(speed) { mutableFloatStateOf(speed) } },
                    onSlideComplete = { speed = it },
                    min = 0f,
                    max = 2f,
                    toDisplay = {
                        if (it <= 0.01f) stringResource(R.string.minimum_speed_value)
                        else stringResource(R.string.format_multiplier, "%.2f".format(it))
                    },
                    steps = 39,
                    label = stringResource(R.string.playback_speed)
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    SecondaryTextButton(
                        text = stringResource(R.string.reset),
                        onClick = {
                            speed = 1f
                            pitch = 1f
                        }
                    )
                }
            }
        }
    }
}
