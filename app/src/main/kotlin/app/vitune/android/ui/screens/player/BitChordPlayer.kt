package app.vitune.android.ui.screens.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.Database
import app.vitune.android.R
import app.vitune.android.models.ui.toUiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.PlayerService
import app.vitune.android.ui.components.SeekBar
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.thumbnail
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.favoritesIcon
import app.vitune.core.ui.utils.px
import coil3.compose.AsyncImage

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

    Box(modifier = modifier.fillMaxWidth()) {
        AsyncImage(
            model = metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .blur(60.dp)
                .graphicsLayer { alpha = 0.5f }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.6f to colorPalette.background0.copy(alpha = 0.85f),
                        1f to colorPalette.background0
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .shadow(14.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorPalette.background2)
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
                BasicText(
                    text = "Tap for lyrics",
                    style = typography.xs.semiBold.secondary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            SeekBar(
                binder = binder,
                position = position,
                media = media,
                alwaysShowDuration = true,
                style = PlayerPreferences.SeekBarStyle.Static
            )

            Spacer(modifier = Modifier.height(20.dp))

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
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clickable {
                            shuffleOn = !shuffleOn
                            binder.player.shuffleModeEnabled = shuffleOn
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicText(
                        text = "Shuffle",
                        style = typography.xxs.semiBold.let {
                            if (shuffleOn) it.copy(color = colorPalette.accent) else it.secondary
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .clickable {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            binder.player.repeatMode = repeatMode
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicText(
                        text = "Repeat",
                        style = typography.xxs.semiBold.let {
                            if (repeatMode != Player.REPEAT_MODE_OFF) it.copy(color = colorPalette.accent) else it.secondary
                        }
                    )
                }

                IconButton(
                    icon = R.drawable.infinite,
                    enabled = PlayerPreferences.trackLoopEnabled,
                    onClick = { PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled },
                    modifier = Modifier.size(20.dp)
                )

                Box(
                    modifier = Modifier
                        .clickable { onOpenQueue() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicText(
                        text = "Queue",
                        style = typography.xxs.semiBold.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
