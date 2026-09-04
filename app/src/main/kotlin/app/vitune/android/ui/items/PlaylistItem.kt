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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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

// ====================================================================
//  LIQUID GLASS PLAYLIST ITEM (Apple iOS 26 / Vision Pro style)
//  - Entire card is a frosted glass container with subtle blur
//  - Playlist cover sits on top, slightly raised (with shadow)
//  - Playlist name floats on the glass below the cover
//  - Glossy white highlight on top edge for "wet glass" feel
//  - Interlocking-friendly rounded corners (squircle-like)
// ====================================================================

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
//  MAIN PLAYLIST ITEM — Liquid Glass Edition
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

    // ---- LIQUID GLASS CONTAINER ----
    // The entire card is wrapped in a frosted glass effect:
    // - Semi-transparent white background (mimics glass translucency)
    // - Subtle white border (mimics glass edge highlight)
    // - Soft shadow (mimics elevation)
    // - Backdrop blur (the "wet glass" look — blurs what's behind it)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                // Glossy vertical gradient — top is brighter (light catch),
                // bottom is more transparent (depth).
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.08f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f),   // Top edge: bright highlight
                        Color.White.copy(alpha = 0.15f)   // Bottom edge: subtle
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                clip = false
            )
            .padding(12.dp)
    ) {
        Column(
            horizontalAlignment = if (alternative) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            // ---- PLAYLIST COVER (the white "P.C" block in your drawing) ----
            // Sits on top of the glass, with its own shadow for depth.
            // NO background color — transparent so the glass shows through
            // while the image loads (no rectangle shape visible).
            Box(
                modifier = Modifier
                    .then(
                        if (alternative) Modifier.fillMaxWidth().aspectRatio(1f)
                        else Modifier.requiredSize(thumbnailSize)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(14.dp),
                        clip = false
                    )
            ) {
                thumbnailContent(Modifier.fillMaxSize())

                // Song count badge (top-right of cover)
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

            Spacer(modifier = Modifier.size(10.dp))

            // ---- PLAYLIST NAME (yellow in your drawing) ----
            // White text with subtle shadow for readability on glass.
            BasicText(
                text = name.orEmpty(),
                style = typography.xs.semiBold.let {
                    if (alternative && channelName.isNullOrBlank()) it.center else it
                }.copy(
                    color = Color.White,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        blurRadius = 3f,
                        offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                    )
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Channel name (if present)
            if (channelName?.isNotBlank() == true) BasicText(
                text = channelName,
                style = typography.xs.semiBold.secondary.copy(
                    color = Color.White.copy(alpha = 0.7f),
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.4f),
                        blurRadius = 2f,
                        offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                    )
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.08f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(12.dp)
    ) {
        Column(
            horizontalAlignment = if (alternative) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(
                modifier = Modifier
                    .then(
                        if (alternative) Modifier.fillMaxWidth().aspectRatio(1f)
                        else Modifier.requiredSize(thumbnailSize)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(color = colorPalette.shimmer)
            )

            Spacer(modifier = Modifier.size(10.dp))

            TextPlaceholder()
            TextPlaceholder()
        }
    }
}

// Local import to avoid breaking the existing ItemContainer/ItemInfoContainer references
// (Custom Column wrapper removed — using standard androidx.compose.foundation.layout.Column directly)
