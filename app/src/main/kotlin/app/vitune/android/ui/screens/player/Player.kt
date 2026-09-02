package app.vitune.android.ui.screens.player

import app.vitune.compose.routing.LocalRouteHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.ui.toUiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.query
import app.vitune.android.service.PlayerService
import app.vitune.android.transaction
import app.vitune.android.ui.components.BottomSheetState
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.rememberBottomSheetState
import app.vitune.android.ui.screens.albumRoute
import app.vitune.android.ui.screens.artistRoute
import app.vitune.android.ui.components.themed.BaseMediaItemMenu
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.SliderDialog
import app.vitune.android.ui.components.themed.SliderDialogBody
import app.vitune.android.ui.modifiers.PinchDirection
import app.vitune.android.ui.modifiers.onSwipe
import app.vitune.android.ui.modifiers.pinchToToggle
import app.vitune.android.utils.DisposableListener
import app.vitune.android.utils.Pip
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.positionAndDurationState
import app.vitune.android.utils.rememberEqualizerLauncher
import app.vitune.android.utils.rememberPipHandler
import app.vitune.android.utils.seamlessPlay
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.thumbnail
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.routing.OnGlobalRoute
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.ThumbnailRoundness
import app.vitune.core.ui.utils.isLandscape
import app.vitune.core.ui.utils.px
import app.vitune.core.ui.utils.roundedShape
import app.vitune.core.ui.utils.songBundle
import app.vitune.providers.innertube.models.NavigationEndpoint
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue

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
    val routeHandler = LocalRouteHandler.current
    val menuState = LocalMenuState.current
    val (colorPalette, typography, thumbnailCornerSize) = LocalAppearance.current
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

        val (position, duration) = binder?.player.positionAndDurationState()
        val metadata = remember(mediaItem) { mediaItem?.mediaMetadata }
        val extras = remember(metadata) { metadata?.extras?.songBundle }

        val horizontalBottomPaddingValues = windowInsets
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .asPaddingValues()

        OnGlobalRoute { if (layoutState.expanded) layoutState.collapseSoft() }

        var isShowingStatsForNerds by rememberSaveable { mutableStateOf(false) }
        var isShowingLyricsDialog by rememberSaveable { mutableStateOf(false) }
        var audioDialogOpen by rememberSaveable { mutableStateOf(false) }
        var boostDialogOpen by rememberSaveable { mutableStateOf(false) }

        if (mediaItem != null) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(colorPalette.background0)
            ) {
                // 1. Full-bleed background artwork with heavy blur and blend scaling
                AsyncImage(
                    model = metadata?.artworkUri?.thumbnail(Dimensions.thumbnails.song.px),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 0.5f
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }
                        .blur(45.dp)
                )

                // 2. Multi-stop vertical gradient overlay to smoothly melt the image into the app theme
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.2f),
                                    colorPalette.background0.copy(alpha = 0.65f),
                                    colorPalette.background0.copy(alpha = 0.95f),
                                    colorPalette.background0
                                )
                            )
                        )
                )

                // 3. Player Content UI Layer
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontalBottomPaddingValues)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                    // Drag handle / Header bar
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.4f))
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Main focal artwork card with subtle border edge light
                    AsyncImage(
                        model = metadata?.artworkUri?.thumbnail(Dimensions.thumbnails.song.px),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(310.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .border(
                                width = 1.5.dp,
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(28.dp)
                            )
                    )

                    Spacer(modifier = Modifier.height(36.dp))

                    // Track Title & Artist Info matching the reference hierarchy
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        BasicText(
                            text = metadata?.title?.toString().orEmpty(),
                            style = typography.titleLarge.semiBold().copy(color = colorPalette.text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        BasicText(
                            text = metadata?.artist?.toString().orEmpty(),
                            style = typography.bodyMedium.secondary().copy(color = colorPalette.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Playback controls row safely contained inside the main layout column
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
                            icon = R.drawable.volume,
                            onClick = { audioDialogOpen = true }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        if (audioDialogOpen) SliderDialog(
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
