package app.vitune.android.ui.items

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.models.PlaylistPreview
import app.vitune.android.ui.components.themed.TextPlaceholder
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.medium
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.thumbnail
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.onOverlay
import app.vitune.core.ui.overlay
import app.vitune.core.ui.shimmer
import app.vitune.core.ui.utils.px
import app.vitune.core.ui.utils.roundedShape
import app.vitune.providers.innertube.Innertube
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Composable
fun PlaylistItem(
    @DrawableRes icon: Int,
    colorTint: Color,
    name: String?,
    songCount: Int?,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    alternative: Boolean = false
) = PlaylistItem(
    thumbnailContent = {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorTint),
            modifier = Modifier
                .align(Alignment.Center)
                .size(24.dp)
        )
    },
    songCount = songCount,
    name = name,
    channelName = null,
    thumbnailSize = thumbnailSize,
    modifier = modifier,
    alternative = alternative
)

@Composable
fun PlaylistItem(
    playlist: PlaylistPreview,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    alternative: Boolean = false
) {
    val thumbnailSizePx = thumbnailSize.px
    val thumbnails by remember {
        playlist.thumbnail?.let { flowOf(listOf(it)) }
            ?: Database
                .playlistThumbnailUrls(playlist.playlist.id)
                .distinctUntilChanged()
                .map { urls ->
                    urls.map { it.thumbnail(thumbnailSizePx / 2) }
                }
    }.collectAsState(initial = emptyList(), context = Dispatchers.IO)

    PlaylistItem(
        thumbnailContent = {
            if (thumbnails.toSet().size == 1) AsyncImage(
                model = thumbnails.first().thumbnail(thumbnailSizePx),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = it
            ) else Box(modifier = it.fillMaxSize()) {
                listOf(
                    Alignment.TopStart,
                    Alignment.TopEnd,
                    Alignment.BottomStart,
                    Alignment.BottomEnd
                ).forEachIndexed { index, alignment ->
                    AsyncImage(
                        model = thumbnails.getOrNull(index),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(alignment)
                            .fillMaxSize(.5f)
                    )
                }
            }
        },
        songCount = playlist.songCount,
        name = playlist.playlist.name,
        channelName = null,
        thumbnailSize = thumbnailSize,
        modifier = modifier,
        alternative = alternative
    )
}

@Composable
fun PlaylistItem(
    playlist: Innertube.PlaylistItem,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    alternative: Boolean = false
) = PlaylistItem(
    thumbnailUrl = playlist.thumbnail?.url,
    songCount = playlist.songCount,
    name = playlist.info?.name,
    channelName = playlist.channel?.name,
    thumbnailSize = thumbnailSize,
    modifier = modifier,
    alternative = alternative
)

@Composable
fun PlaylistItem(
    thumbnailUrl: String?,
    songCount: Int?,
    name: String?,
    channelName: String?,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    alternative: Boolean = false
) = PlaylistItem(
    thumbnailContent = {
        AsyncImage(
            model = thumbnailUrl?.thumbnail(thumbnailSize.px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = it
        )
    },
    songCount = songCount,
    name = name,
    channelName = channelName,
    thumbnailSize = thumbnailSize,
    modifier = modifier,
    alternative = alternative
)

// ====================================================================
//  MAIN PLAYLIST ITEM — Subtle Glass Edition
//  - VERY subtle glass (5% white — barely visible, not a "rectangle")
//  - Thin white border (20% — glass edge highlight)
//  - Cover image with shadow for depth
//  - Playlist name CENTERED below cover, tight
// ====================================================================
@Composable
fun PlaylistItem(
    thumbnailContent: @Composable BoxScope.(modifier: Modifier) -> Unit,
    songCount: Int?,
    name: String?,
    channelName: String?,
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    alternative: Boolean = false
) {
    val (colorPalette, typography, thumbnailShapeCorners) = LocalAppearance.current

    // ---- SUBTLE GLASS CONTAINER ----
    // Very low alpha (5%) so it looks like frosted glass, NOT a grey rectangle.
    // The thin white border (20%) creates the "glass edge" without a visible fill.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // ---- PLAYLIST COVER (with shadow) ----
            Box(
                modifier = Modifier
                    .then(
                        if (alternative) Modifier.fillMaxWidth().aspectRatio(1f)
                        else Modifier.requiredSize(thumbnailSize)
                    )
                    .padding(6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(14.dp),
                        clip = false
                    )
            ) {
                thumbnailContent(Modifier.fillMaxSize())

                songCount?.let {
                    BasicText(
                        text = "$songCount",
                        style = typography.xxs.medium.color(colorPalette.onOverlay),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(all = Dimensions.items.gap)
                            .background(
                                color = colorPalette.overlay,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .align(Alignment.BottomEnd)
                    )
                }
            }

            // ---- PLAYLIST NAME (CENTERED, directly below cover, tight) ----
            BasicText(
                text = name.orEmpty(),
                style = typography.xs.semiBold.center.copy(
                    color = colorPalette.text,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        blurRadius = 3f,
                        offset = Offset(1f, 1f)
                    )
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            if (channelName?.isNotBlank() == true) BasicText(
                text = channelName,
                style = typography.xs.semiBold.secondary.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.4f),
                        blurRadius = 2f,
                        offset = Offset(1f, 1f)
                    )
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun PlaylistItemPlaceholder(
    thumbnailSize: Dp,
    modifier: Modifier = Modifier,
    alternative: Boolean = false
) {
    val (colorPalette, _, _, thumbnailShape) = LocalAppearance.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(
                modifier = Modifier
                    .then(
                        if (alternative) Modifier.fillMaxWidth().aspectRatio(1f)
                        else Modifier.requiredSize(thumbnailSize)
                    )
                    .padding(6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color = colorPalette.shimmer)
            )
            TextPlaceholder(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}
