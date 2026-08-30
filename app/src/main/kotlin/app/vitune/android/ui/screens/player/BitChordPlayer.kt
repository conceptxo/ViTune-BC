package app.vitune.android.ui.screens.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.R
import app.vitune.android.models.ui.toUiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.PlayerService
import app.vitune.android.ui.components.SeekBar
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.thumbnail
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.favoritesIcon
import app.vitune.core.ui.Dimensions
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
    onOpenLyrics: () -> Unit,
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
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
                    style = typography.s.semiBold.copy(color = colorPalette.textSecondary),
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

        BasicText(
            text = "Tap for lyrics",
            style = typography.xs.semiBold.copy(color = colorPalette.textSecondary),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLyrics() }
        )

        Spacer(modifier = Modifier.height(16.dp))

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
            Icon(
                imageVector = Icons.Filled.FastRewind,
                contentDescription = null,
                tint = colorPalette.text,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { binder.player.forceSeekToPrevious() }
            )

            Icon(
                imageVector = if (shouldBePlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = colorPalette.text,
                modifier = Modifier
                    .size(44.dp)
                    .clickable {
                        if (shouldBePlaying) binder.player.pause() else {
                            if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                            binder.player.play()
                        }
                    }
            )

            Icon(
                imageVector = Icons.Filled.FastForward,
                contentDescription = null,
                tint = colorPalette.text,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { binder.player.forceSeekToNext() }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = null,
                tint = if (shuffleOn) colorPalette.accent else colorPalette.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        shuffleOn = !shuffleOn
                        binder.player.shuffleModeEnabled = shuffleOn
                    }
            )

            Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = null,
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) colorPalette.accent else colorPalette.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        binder.player.repeatMode = repeatMode
                    }
            )

            Icon(
                imageVector = Icons.Filled.AllInclusive,
                contentDescription = null,
                tint = if (PlayerPreferences.trackLoopEnabled) colorPalette.accent else colorPalette.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
                    }
            )

            Icon(
                imageVector = Icons.Filled.QueueMusic,
                contentDescription = null,
                tint = colorPalette.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onOpenQueue() }
            )
        }
    }
}
