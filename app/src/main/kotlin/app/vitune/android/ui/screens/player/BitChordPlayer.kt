package app.vitune.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.Database
import app.vitune.android.R
import app.vitune.android.models.Lyrics as LyricsData
import app.vitune.android.models.ui.toUiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.PlayerService
import app.vitune.android.ui.components.SeekBar
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.utils.SynchronizedLyrics
import app.vitune.android.utils.SynchronizedLyricsState
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.thumbnail
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.favoritesIcon
import app.vitune.core.ui.utils.px
import app.vitune.providers.lrclib.LrcParser
import app.vitune.providers.lrclib.toLrcFile
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val INLINE_LYRIC_UPDATE_DELAY = 50L

@Composable
fun BitChordPlayer(
    mediaItem: MediaItem,
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    position: Long,
    duration: Long,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    isShowingLyrics: Boolean,
    onShowLyrics: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val metadata = mediaItem.mediaMetadata
    val media = remember(mediaItem, duration) { mediaItem.toUiMedia(duration) }

    val artworkUrl = remember(metadata) { metadata.artworkUri?.toString() }
    val meshColors = rememberArtworkColors(artworkUrl)

    val artScale by animateFloatAsState(
        targetValue = if (shouldBePlaying) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "artScale"
    )

    var shuffleOn by remember { mutableStateOf(binder.player.shuffleModeEnabled) }
    var repeatMode by remember { mutableStateOf(binder.player.repeatMode) }

    // Local mirror of player volume (0f..1f) so the slider can drag smoothly
    // without waiting on player callbacks for every frame.
    var volume by remember { mutableFloatStateOf(binder.player.volume) }

    var storedLyrics by remember(mediaItem.mediaId) { mutableStateOf<LyricsData?>(null) }

    LaunchedEffect(mediaItem.mediaId) {
        withContext(Dispatchers.IO) {
            Database
                .lyrics(mediaItem.mediaId)
                .distinctUntilChanged()
                .cancellable()
                .collect { storedLyrics = it }
        }
    }

    val lyricsState = remember(storedLyrics) {
        val file = storedLyrics?.synced?.takeIf { it.isNotBlank() }?.let {
            LrcParser.parse(it)?.toLrcFile()
        }

        SynchronizedLyricsState(
            sentences = file?.lines,
            offset = file?.offset?.inWholeMilliseconds ?: 0L
        )
    }

    val synchronizedLyrics = remember(lyricsState) {
        lyricsState.sentences?.let {
            SynchronizedLyrics(it.toImmutableMap()) {
                binder.player.currentPosition + INLINE_LYRIC_UPDATE_DELAY + lyricsState.offset -
                    (storedLyrics?.startTime ?: 0L)
            }
        }
    }

    LaunchedEffect(synchronizedLyrics) {
        val current = synchronizedLyrics ?: return@LaunchedEffect
        while (true) {
            delay(INLINE_LYRIC_UPDATE_DELAY)
            current.update()
        }
    }

    val currentLyricLine = synchronizedLyrics?.let {
        it.sentences.values.toImmutableList().getOrNull(it.index)?.takeIf { line -> line.isNotBlank() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ---- Full-bleed blurred background ----
        // A heavily blurred, darkened copy of the artwork behind everything,
        // instead of (or under) the mesh gradient — gives the "photo fills the
        // whole screen" look from the reference shot. Kept as a separate layer
        // from the sharp artwork above so the crop/scale of the two can differ.
        AsyncImage(
            model = metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(48.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
        )
        MeshGradientBackground(
            palette = meshColors,
            trackKey = mediaItem.mediaId
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // ---- Drag handle ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
            ) {
                AsyncImage(
                    model = metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.82f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.35f)
                            )
                        )
                )

                Lyrics(
                    mediaId = mediaItem.mediaId,
                    isDisplayed = isShowingLyrics,
                    onDismiss = { onShowLyrics(false) },
                    ensureSongInserted = { Database.insert(mediaItem) },
                    mediaMetadataProvider = { mediaItem.mediaMetadata },
                    durationProvider = { binder.player.duration.takeIf { it > 0 } ?: C.TIME_UNSET },
                    onOpenDialog = {},
                    modifier = Modifier.fillMaxSize(),
                    shouldShowSynchronizedLyrics = PlayerPreferences.isShowingSynchronizedLyrics,
                    setShouldShowSynchronizedLyrics = { PlayerPreferences.isShowingSynchronizedLyrics = it },
                    showControls = true
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 28.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = metadata.title?.toString().orEmpty(),
                            style = typography.l.semiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        BasicText(
                            text = metadata.artist?.toString().orEmpty(),
                            style = typography.s.semiBold.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        icon = if (likedAt == null) R.drawable.heart_outline else R.drawable.heart,
                        color = colorPalette.favoritesIcon,
                        onClick = {
                            setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---- Current lyric line, with a trailing chevron ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowLyrics(true) }
                        .padding(vertical = 6.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = currentLyricLine,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "inlineLyricLine"
                        ) { line ->
                            BasicText(
                                text = line ?: "Tap for lyrics",
                                style = typography.xs.semiBold.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    BasicText(
                        text = "\u203A",
                        style = typography.xs.semiBold.secondary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                SeekBar(
                    binder = binder,
                    position = position,
                    media = media,
                    alwaysShowDuration = true,
                    style = PlayerPreferences.SeekBarStyle.Static
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        icon = R.drawable.play_skip_back,
                        color = colorPalette.text,
                        onClick = { binder.player.forceSeekToPrevious() },
                        modifier = Modifier.size(28.dp)
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                if (shouldBePlaying) binder.player.pause() else {
                                    if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                    binder.player.play()
                                }
                            }
                            .background(colorPalette.background2)
                            .size(60.dp)
                    ) {
                        AnimatedPlayPauseButton(
                            playing = shouldBePlaying,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(30.dp)
                        )
                    }

                    IconButton(
                        icon = R.drawable.play_skip_forward,
                        color = colorPalette.text,
                        onClick = { binder.player.forceSeekToNext() },
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ---- Volume slider ----
                // Built by hand rather than pulling in a Slider component, so
                // this doesn't risk a new dependency the way material.icons did.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                ) {
                    BasicText(
                        text = "\uD83D\uDD09",
                        style = typography.xxs.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ ->
                                    val newVolume = (change.position.x / size.width)
                                        .coerceIn(0f, 1f)
                                    volume = newVolume
                                    binder.player.volume = newVolume
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(volume.coerceIn(0f, 1f))
                                .fillMaxSize()
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.85f))
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = "\uD83D\uDD0A",
                        style = typography.xxs.secondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Shuffle / Repeat / Autoplay / Queue toggle row ----
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    ToggleGlyph(
                        glyph = "\u21C4",
                        active = shuffleOn,
                        colorPalette = colorPalette,
                        typography = typography,
                        onClick = {
                            shuffleOn = !shuffleOn
                            binder.player.shuffleModeEnabled = shuffleOn
                        }
                    )

                    ToggleGlyph(
                        glyph = "\u21BB",
                        active = repeatMode != Player.REPEAT_MODE_OFF,
                        colorPalette = colorPalette,
                        typography = typography,
                        onClick = {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            binder.player.repeatMode = repeatMode
                        }
                    )

                    ToggleGlyph(
                        glyph = "\u221E",
                        active = PlayerPreferences.trackLoopEnabled,
                        colorPalette = colorPalette,
                        typography = typography,
                        onClick = {
                            PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
                        }
                    )

                    ToggleGlyph(
                        glyph = "\u2630",
                        active = false,
                        colorPalette = colorPalette,
                        typography = typography,
                        onClick = onOpenQueue
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * One of the circular toggle buttons in the bottom row — a glyph on a
 * translucent pill background that lights up when [active]. Matches the
 * reference screenshot's shuffle/repeat/autoplay/queue row without pulling in
 * any icon library.
 */
@Composable
private fun ToggleGlyph(
    glyph: String,
    active: Boolean,
    colorPalette: app.vitune.core.ui.ColorPalette,
    typography: app.vitune.core.ui.Typography,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = glyph,
            style = typography.s.semiBold.let {
                if (active) it.copy(color = colorPalette.accent) else it.secondary
            }.copy(textAlign = TextAlign.Center)
        )
    }
}
