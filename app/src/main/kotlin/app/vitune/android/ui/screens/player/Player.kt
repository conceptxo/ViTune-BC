package app.vitune.android.ui.screens.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import app.vitune.android.ui.components.BottomSheet
import app.vitune.android.ui.components.BottomSheetState
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.rememberBottomSheetState
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
import app.vitune.core.ui.collapsedPlayerProgressBar
import app.vitune.core.ui.utils.isLandscape
import app.vitune.core.ui.utils.px
import app.vitune.core.ui.utils.roundedShape
import app.vitune.core.ui.utils.songBundle
import app.vitune.providers.innertube.models.NavigationEndpoint
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================
// Lightweight twinkling starfield used behind the floating mini player pill.
// Colors come from rememberArtworkColors() in ArtworkMesh.kt (untouched).
// ============================================================================

private const val MiniPlayerStarCount = 36
private const val MiniPlayerTwinkleCycleSeconds = 6.5f
private const val MiniPlayerFrameIntervalMs = 40L

private data class MiniPlayerStar(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val opacity: Float,
    val twinklePattern: Int,
    val twinkles: Boolean
)

private fun seededUnit(index: Int, multiplier: Int, offset: Int): Float =
    (((index * multiplier + offset) % 1000 + 1000) % 1000) / 1000f

private fun twinkleGlow(pattern: Int, timeSeconds: Float): Float {
    val phase = (((timeSeconds / MiniPlayerTwinkleCycleSeconds) + pattern * 0.21f) % 1f)
    val pulse = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    return pulse * pulse
}

@Composable
private fun MiniPlayerGalaxyBackground(
    modifier: Modifier = Modifier,
    meshPalette: MeshPalette
) {
    val stars = remember {
        List(MiniPlayerStarCount) { index ->
            MiniPlayerStar(
                x = seededUnit(index, 29, 7),
                y = seededUnit(index, 47, 13),
                sizePx = if (seededUnit(index, 17, 3) < 0.6f) 1.2f else 2f,
                opacity = 0.35f + seededUnit(index, 71, 19) * 0.45f,
                twinklePattern = (index * 11 + 5) % 4,
                twinkles = (index * 23 + 3) % 5 == 0
            )
        }
    }

    var frameMillis by remember { mutableLongStateOf(0L) }

    val skyColors = meshPalette.colors.ifEmpty {
        listOf(Color(0xFF120018), Color(0xFF1B0330), Color(0xFF05010A), Color.White)
    }

    val topColor by animateColorAsState(skyColors.getOrElse(0) { Color(0xFF120018) }, tween(900), label = "gTop")
    val midColor by animateColorAsState(skyColors.getOrElse(1) { Color(0xFF1B0330) }, tween(900), label = "gMid")
    val bottomColor by animateColorAsState(skyColors.getOrElse(2) { Color(0xFF05010A) }, tween(900), label = "gBottom")
    val glowColor by animateColorAsState(skyColors.getOrElse(3) { Color.White }, tween(900), label = "gGlow")

    LaunchedEffect(Unit) {
        var lastDrawn = 0L
        while (true) {
            val next = withFrameMillis { it }
            if (next - lastDrawn >= MiniPlayerFrameIntervalMs || next < lastDrawn) {
                lastDrawn = next
                frameMillis = next
            }
        }
    }

    Canvas(modifier = modifier) {
        val timeSeconds = frameMillis / 1000f

        drawRect(
            brush = Brush.verticalGradient(
                0f to topColor.copy(alpha = 0.55f),
                0.6f to midColor.copy(alpha = 0.55f),
                1f to bottomColor.copy(alpha = 0.55f)
            ),
            size = size
        )

        stars.forEach { star ->
            val center = Offset(size.width * star.x, size.height * star.y)
            val coreRadius = star.sizePx / 2f
            val coreAlpha = star.opacity
            val glowAlpha = if (star.twinkles) coreAlpha * twinkleGlow(star.twinklePattern, timeSeconds) else 0f

            if (glowAlpha > 0.02f) {
                drawCircle(
                    color = glowColor.copy(alpha = glowAlpha * 0.16f),
                    radius = coreRadius + 3f,
                    center = center
                )
            }
            drawCircle(
                color = Color.White.copy(alpha = coreAlpha),
                radius = coreRadius,
                center = center
            )
        }
    }
}

@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp
    ),
    windowInsets: WindowInsets = WindowInsets.systemBars
) = with(PlayerPreferences) {
    val menuState = LocalMenuState.current
    val (colorPalette, typography, thumbnailCornerSize) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current

    val pipHandler = rememberPipHandler()

    PersistMapCleanup(prefix = "queue/suggestions")

    var mediaItem by remember(binder) {
        mutableStateOf(
            value = binder?.player?.currentMediaItem,
            policy = neverEqualPolicy()
        )
    }
    var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.shouldBePlaying == true) }

    var likedAt by remember(mediaItem) {
        mutableStateOf(
            value = null,
            policy = object : SnapshotMutationPolicy<Long?> {
                override fun equivalent(a: Long?, b: Long?): Boolean {
                    mediaItem?.mediaId?.let {
                        query {
                            Database.like(it, b)
                        }
                    }
                    return a == b
                }
            }
        )
    }

    LaunchedEffect(mediaItem) {
        mediaItem?.mediaId?.let { mediaId ->
            Database
                .likedAt(mediaId)
                .distinctUntilChanged()
                .collect { likedAt = it }
        }
    }

    binder?.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                mediaItem = newMediaItem
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.shouldBePlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.shouldBePlaying
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

    // Fixed, moderate corner radius (not a full stadium) so the circular
    // thumbnail always has room to sit fully inside the curve.
    val miniPlayerShape = RoundedCornerShape(28.dp)

    val artworkColors = rememberArtworkColors(imageUrl = metadata?.artworkUri?.toString())

    if (mediaItem != null) BottomSheet(
        state = layoutState,
        modifier = modifier.fillMaxSize(),
        onDismiss = {
            binder?.let { onDismiss(it) }
            layoutState.dismissSoft()
        },
        backHandlerEnabled = !menuState.isDisplayed,
        collapsedContent = { innerModifier ->
            Box(
                modifier = Modifier
                    .let { modifier ->
                        if (horizontalSwipeToClose) modifier.onSwipe(
                            animateOffset = true,
                            onSwipeOut = { animationJob ->
                                binder?.let { onDismiss(it) }
                                animationJob.join()
                                layoutState.dismissSoft()
                            }
                        ) else modifier
                    }
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clip(miniPlayerShape)
                    .background(colorPalette.background1)
                    .then(innerModifier)
                    .padding(horizontalBottomPaddingValues)
            ) {
                MiniPlayerGalaxyBackground(
                    modifier = Modifier.matchParentSize(),
                    meshPalette = artworkColors
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawRect(
                                color = colorPalette.collapsedPlayerProgressBar,
                                topLeft = Offset.Zero,
                                size = Size(
                                    width = runCatching {
                                        size.width * (position.toFloat() / duration.absoluteValue)
                                    }.getOrElse { 0f },
                                    height = size.height
                                )
                            )
                        }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                ) {
                Spacer(modifier = Modifier.width(2.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(Dimensions.items.collapsedPlayerHeight)
                ) {
                    AsyncImage(
                        model = metadata?.artworkUri?.thumbnail(Dimensions.thumbnails.song.px),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                            .background(colorPalette.background0)
                            .size(42.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .height(Dimensions.items.collapsedPlayerHeight)
                        .weight(1f)
                ) {
                    AnimatedContent(
                        targetState = metadata?.title?.toString().orEmpty(),
                        label = "",
                        transitionSpec = { fadeIn() togetherWith fadeOut() }
                    ) { text ->
                        BasicText(
                            text = text,
                            style = typography.xs.semiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AnimatedVisibility(visible = metadata?.artist != null) {
                        AnimatedContent(
                            targetState = metadata?.artist?.toString().orEmpty(),
                            label = "",
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { text ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BasicText(
                                    text = text,
                                    style = typography.xs.semiBold.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                AnimatedVisibility(visible = extras?.explicit == true) {
                                    Image(
                                        painter = painterResource(R.drawable.explicit),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.text),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(Dimensions.items.collapsedPlayerHeight)
                ) {
                    AnimatedVisibility(visible = isShowingPrevButtonCollapsed) {
                        IconButton(
                            icon = R.drawable.play_skip_back,
                            color = colorPalette.text,
                            onClick = { binder?.player?.forceSeekToPrevious() },
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .size(20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clickable(
                                onClick = {
                                    if (shouldBePlaying) binder?.player?.pause()
                                    else {
                                        if (binder?.player?.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                        binder?.player?.play()
                                    }
                                },
                                indication = ripple(bounded = false),
                                interactionSource = remember { MutableInteractionSource() }
                            )
                            .clip(CircleShape)
                    ) {
                        AnimatedPlayPauseButton(
                            playing = shouldBePlaying,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .size(23.dp)
                        )
                    }

                    IconButton(
                        icon = R.drawable.play_skip_forward,
                        color = colorPalette.text,
                        onClick = { binder?.player?.forceSeekToNext() },
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))
                }
            }
        }
    ) {
        var isShowingStatsForNerds by rememberSaveable { mutableStateOf(false) }
        var isShowingLyricsDialog by rememberSaveable { mutableStateOf(false) }

        if (isShowingLyricsDialog) LyricsDialog(onDismiss = { isShowingLyricsDialog = false })

        val playerBottomSheetState = rememberBottomSheetState(
            dismissedBound = 64.dp + horizontalBottomPaddingValues.calculateBottomPadding(),
            expandedBound = layoutState.expandedBound
        )

        val containerModifier = Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.5f to colorPalette.background1,
                    1f to colorPalette.background0
                )
            )
            .padding(
                windowInsets
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    .asPaddingValues()
            )
            .padding(bottom = playerBottomSheetState.collapsedBound)

        val thumbnailContent: @Composable (modifier: Modifier) -> Unit = { innerModifier ->
            Pip(
                numerator = 1,
                denominator = 1,
                modifier = innerModifier
            ) {
                Thumbnail(
                    isShowingLyrics = isShowingLyrics,
                    onShowLyrics = { isShowingLyrics = it },
                    isShowingStatsForNerds = isShowingStatsForNerds,
                    onShowStatsForNerds = { isShowingStatsForNerds = it },
                    onOpenDialog = { isShowingLyricsDialog = true },
                    likedAt = likedAt,
                    setLikedAt = { likedAt = it },
                    modifier = Modifier
                        .nestedScroll(layoutState.preUpPostDownNestedScrollConnection)
                        .pinchToToggle(
                            key = isShowingLyricsDialog,
                            direction = PinchDirection.Out,
                            threshold = 1.05f,
                            onPinch = {
                                if (isShowingLyrics) isShowingLyricsDialog = true
                            }
                        )
                        .pinchToToggle(
                            key = isShowingLyricsDialog,
                            direction = PinchDirection.In,
                            threshold = .95f,
                            onPinch = {
                                pipHandler.enterPictureInPictureMode()
                            }
                        )
                )
            }
        }

        val controlsContent: @Composable (modifier: Modifier) -> Unit = { innerModifier ->
            Controls(
                media = mediaItem?.toUiMedia(duration),
                binder = binder,
                likedAt = likedAt,
                setLikedAt = { likedAt = it },
                shouldBePlaying = shouldBePlaying,
                position = position,
                modifier = innerModifier
            )
        }

        if (isLandscape) Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = containerModifier.padding(top = 32.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(0.66f)
                    .padding(bottom = 16.dp)
            ) {
                thumbnailContent(Modifier.padding(horizontal = 16.dp))
            }

            controlsContent(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxHeight()
                    .weight(1f)
            )
        } else Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = containerModifier.padding(top = 54.dp)
        ) {
            BitChordPlayer(
                mediaItem = mediaItem!!,
                binder = binder!!,
                shouldBePlaying = shouldBePlaying,
                position = position,
                duration = duration,
                likedAt = likedAt,
                setLikedAt = { likedAt = it },
                isShowingLyrics = isShowingLyrics,
                onShowLyrics = { isShowingLyrics = it },
                onOpenQueue = {},
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
        var audioDialogOpen by rememberSaveable { mutableStateOf(false) }

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
            SliderDialogBody(
                provideState = { remember(pitch) { mutableFloatStateOf(pitch) } },
                onSlideComplete = { pitch = it },
                min = 0f,
                max = 2f,
                toDisplay = {
                    if (it <= 0.01f) stringResource(R.string.minimum_speed_value)
                    else stringResource(R.string.format_multiplier, "%.2f".format(it))
                },
                steps = 39,
                label = stringResource(R.string.playback_pitch)
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

        var boostDialogOpen by rememberSaveable { mutableStateOf(false) }

        if (boostDialogOpen) {
            fun submit(state: Float) = transaction {
                mediaItem?.mediaId?.let { mediaId ->
                    Database.setLoudnessBoost(
                        songId = mediaId,
                        loudnessBoost = state.takeUnless { it == 0f }
                    )
                }
            }

            SliderDialog(
                onDismiss = { boostDialogOpen = false },
                title = stringResource(R.string.volume_boost)
            ) {
                SliderDialogBody(
                    provideState = {
                        val state = remember { mutableFloatStateOf(0f) }

                        LaunchedEffect(mediaItem) {
                            mediaItem?.mediaId?.let { mediaId ->
                                Database
                                    .loudnessBoost(mediaId)
                                    .distinctUntilChanged()
                                    .collect { state.floatValue = it ?: 0f }
                            }
                        }

                        state
                    },
                    onSlideComplete = { submit(it) },
                    min = -20f,
                    max = 20f,
                    toDisplay = { stringResource(R.string.format_db, "%.2f".format(it)) }
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    SecondaryTextButton(
                        text = stringResource(R.string.reset),
                        onClick = { submit(0f) }
                    )
                }
            }
        }

        if (binder != null) Queue(
            layoutState = playerBottomSheetState,
            binder = binder,
            beforeContent = {
                if (playerLayout == PlayerPreferences.PlayerLayout.New) IconButton(
                    onClick = { trackLoopEnabled = !trackLoopEnabled },
                    icon = R.drawable.infinite,
                    enabled = trackLoopEnabled,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(20.dp)
                ) else Spacer(modifier = Modifier.width(20.dp))
            },
            afterContent = {
                IconButton(
                    icon = R.drawable.ellipsis_horizontal,
                    color = colorPalette.text,
                    onClick = {
                        mediaItem?.let {
                            menuState.display {
                                PlayerMenu(
                                    onDismiss = menuState::hide,
                                    mediaItem = it,
                                    binder = binder,
                                    onShowSpeedDialog = { audioDialogOpen = true },
                                    onShowNormalizationDialog = {
                                        boostDialogOpen = true
                                    }.takeIf { volumeNormalization }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(20.dp)
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            shape = shape
        )
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun PlayerMenu(
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onShowSpeedDialog: (() -> Unit)? = null,
    onShowNormalizationDialog: (() -> Unit)? = null
) {
    val launchEqualizer by rememberEqualizerLauncher(audioSessionId = { binder.player.audioSessionId })

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onStartRadio = {
            binder.stopRadio()
            binder.player.seamlessPlay(mediaItem)
            binder.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
        },
        onGoToEqualizer = launchEqualizer,
        onShowSleepTimer = {},
        onDismiss = onDismiss,
        onShowSpeedDialog = onShowSpeedDialog,
        onShowNormalizationDialog = onShowNormalizationDialog
    )
}

private fun onDismiss(binder: PlayerService.Binder) {
    binder.stopRadio()
    binder.player.clearMediaItems()
}
