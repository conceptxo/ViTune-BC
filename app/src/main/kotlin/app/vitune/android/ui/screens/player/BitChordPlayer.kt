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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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

    val artScale by animateFloatAsState(
        targetValue = if (shouldBePlaying) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "artScale"
    )

    var shuffleOn by remember { mutableStateOf(binder.player.shuffleModeEnabled) }
    var repeatMode by remember { mutableStateOf(binder.player.repeatMode) }

    // --- inline synced lyric line (replaces static "Tap for lyrics" once available) ---
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
    // --- end inline synced lyric line ---

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // Full-bleed artwork, edge to edge, no card/shadow/rounded corners
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )

            Lyrics(
                mediaId = mediaItem.mediaId,
                isDisplayed = isShowingLyrics,
                onDismiss = { onShowLyrics(false) },
                ensureSongInserted = { Database.insert(mediaItem) },
                mediaMetadataProvider = { mediaItem.mediaMetadata },
                durationProvider = { binder.player.duration.takeIf { it > 0 } ?: C.TIME_UNSET },
                onOpenDialog = {},
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shouldShowSynchronizedLyrics = PlayerPreferences.isShowingSynchronizedLyrics,
                setShouldShowSynchronizedLyrics = { PlayerPreferences.isShowingSynchronizedLyrics = it },
                showControls = true
            )
        }

        // Everything below the artwork stretches to fill exactly the remaining
        // screen height — never clips, never scrolls.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 28.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))

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

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowLyrics(true) }
                    .padding(vertical = 10.dp)
            ) {
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

            Spacer(modifier = Modifier.height(6.dp))

            SeekBar(
                binder = binder,
                position = position,
                media = media,
                alwaysShowDuration = true,
                style = PlayerPreferences.SeekBarStyle.Static
            )

            // Flexible gap: absorbs whatever space is left so the controls
            // below always sit at the bottom, regardless of screen height.
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
                        .size(64.dp)
                ) {
                    AnimatedPlayPauseButton(
                        playing = shouldBePlaying,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                    )
                }

                IconButton(
                    icon = R.drawable.play_skip_forward,
                    color = colorPalette.text,
                    onClick = { binder.player.forceSeekToNext() },
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                IconButton(
                    icon = R.drawable.shuffle,
                    enabled = shuffleOn,
                    onClick = {
                        shuffleOn = !shuffleOn
                        binder.player.shuffleModeEnabled = shuffleOn
                    },
                    modifier = Modifier.size(22.dp)
                )

                IconButton(
                    icon = R.drawable.repeat,
                    enabled = repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = {
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        binder.player.repeatMode = repeatMode
                    },
                    modifier = Modifier.size(22.dp)
                )

                IconButton(
                    icon = R.drawable.infinite,
                    enabled = PlayerPreferences.trackLoopEnabled,
                    onClick = { PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled },
                    modifier = Modifier.size(20.dp)
                )

                IconButton(
                    icon = R.drawable.queue_music,
                    color = colorPalette.text,
                    onClick = { onOpenQueue() },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
