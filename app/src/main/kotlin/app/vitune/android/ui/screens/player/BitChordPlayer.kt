package app.vitune.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.service.PlayerService
import app.vitune.android.transaction
import app.vitune.android.ui.components.SeekBar
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.utils.SynchronizedLyrics
import app.vitune.android.utils.SynchronizedLyricsState
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.thumbnail
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.favoritesIcon
import app.vitune.core.ui.utils.px
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.NextBody
import app.vitune.providers.innertube.requests.lyrics
import app.vitune.providers.kugou.KuGou
import app.vitune.providers.lrclib.LrcLib
import app.vitune.providers.lrclib.LrcParser
import app.vitune.providers.lrclib.toLrcFile
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val INLINE_LYRIC_UPDATE_DELAY = 50L
private const val LYRIC_SEARCH_PHRASE_INTERVAL_MS = 1800L

private val LYRIC_SEARCH_PHRASES = listOf(
    "Almost words",
    "Still looking",
    "Matching the track",
    "One more source to check"
)

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
    onTitleClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val metadata = mediaItem.mediaMetadata
    val media = remember(mediaItem, duration) { mediaItem.toUiMedia(duration) }

    // Subtle scale pulse on the background album art when play/pause toggles
    val artScale by animateFloatAsState(
        targetValue = if (shouldBePlaying) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "artScale"
    )

    var shuffleOn by remember { mutableStateOf(binder.player.shuffleModeEnabled) }
    var repeatMode by remember { mutableStateOf(binder.player.repeatMode) }

    // --- inline synced lyric line, with background auto-fetch ---
    var storedLyrics by remember(mediaItem.mediaId) { mutableStateOf<LyricsData?>(null) }
    var isFetchingLyrics by remember(mediaItem.mediaId) { mutableStateOf(false) }

    LaunchedEffect(mediaItem.mediaId) {
        runCatching {
            withContext(Dispatchers.IO) {
                Database
                    .lyrics(mediaItem.mediaId)
                    .distinctUntilChanged()
                    .cancellable()
                    .collect { currentLyrics ->
                        storedLyrics = currentLyrics

                        if (currentLyrics?.fixed != null && currentLyrics.synced != null) {
                            isFetchingLyrics = false
                            return@collect
                        }

                        isFetchingLyrics = true

                        val album = metadata.albumTitle?.toString()
                        val artist = metadata.artist?.toString().orEmpty()
                        val title = metadata.title?.toString().orEmpty().let {
                            if (mediaItem.mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                                .substringBeforeLast('.')
                                .trim()
                            else it
                        }
                        val strippedTitle = title.split("(")[0].trim()

                        coroutineScope {
                            val durationDeferred = async {
                                var d = withContext(Dispatchers.Main) {
                                    binder.player.duration.takeIf { it > 0 } ?: C.TIME_UNSET
                                }
                                while (d == C.TIME_UNSET) {
                                    delay(100)
                                    d = withContext(Dispatchers.Main) {
                                        binder.player.duration.takeIf { it > 0 } ?: C.TIME_UNSET
                                    }
                                }
                                d
                            }

                            val innertube = async(Dispatchers.IO) {
                                runCatching {
                                    Innertube.lyrics(NextBody(videoId = mediaItem.mediaId))?.getOrNull()
                                }.getOrNull()
                            }

                            val fixed = currentLyrics?.fixed ?: innertube.await() ?: run {
                                val d = durationDeferred.await()
                                runCatching {
                                    LrcLib.bestLyrics(
                                        artist = artist,
                                        title = title,
                                        duration = d.milliseconds,
                                        album = album,
                                        synced = false
                                    )?.map { it?.text }?.getOrNull()
                                }.getOrNull()
                            }

                            val synced = currentLyrics?.synced ?: run {
                                val d = durationDeferred.await()

                                val lrcMain = async(Dispatchers.IO) {
                                    runCatching {
                                        LrcLib.bestLyrics(
                                            artist = artist,
                                            title = title,
                                            duration = d.milliseconds,
                                            album = album
                                        )?.map { it?.text }?.getOrNull()
                                    }.getOrNull()
                                }
                                val lrcRetry = async(Dispatchers.IO) {
                                    runCatching {
                                        LrcLib.bestLyrics(
                                            artist = artist,
                                            title = strippedTitle,
                                            duration = d.milliseconds,
                                            album = album
                                        )?.map { it?.text }?.getOrNull()
                                    }.getOrNull()
                                }
                                val kugou = async(Dispatchers.IO) {
                                    runCatching {
                                        KuGou.lyrics(
                                            artist = artist,
                                            title = title,
                                            duration = d / 1000
                                        )?.map { it?.value }?.getOrNull()
                                    }.getOrNull()
                                }
                                lrcMain.await() ?: lrcRetry.await() ?: kugou.await()
                            }

                            LyricsData(
                                songId = mediaItem.mediaId,
                                fixed = fixed.orEmpty(),
                                synced = synced.orEmpty()
                            ).also {
                                ensureActive()
                                transaction {
                                    runCatching {
                                        Database.insert(mediaItem)
                                        Database.upsert(it)
                                    }
                                }
                            }
                        }

                        isFetchingLyrics = false
                    }
            }
        }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            isFetchingLyrics = false
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

    val currentSentenceRaw = synchronizedLyrics?.let {
        it.sentences.values.toImmutableList().getOrNull(it.index)
    }
    val currentLyricLine = currentSentenceRaw?.takeIf { it.isNotBlank() }
    val isInstrumentalGap = currentSentenceRaw != null && currentSentenceRaw.isBlank()

    var searchPhraseIndex by remember(mediaItem.mediaId) { mutableIntStateOf(0) }
    LaunchedEffect(mediaItem.mediaId, isFetchingLyrics) {
        if (!isFetchingLyrics) return@LaunchedEffect
        while (true) {
            delay(LYRIC_SEARCH_PHRASE_INTERVAL_MS)
            searchPhraseIndex = (searchPhraseIndex + 1) % LYRIC_SEARCH_PHRASES.size
        }
    }

    val showSearchingGlyph = isFetchingLyrics && currentLyricLine == null && !isInstrumentalGap
    val showNoteGlyph = showSearchingGlyph || isInstrumentalGap
    val lyricStripText = when {
        currentLyricLine != null -> currentLyricLine
        isInstrumentalGap -> "Instrumental"
        isFetchingLyrics -> LYRIC_SEARCH_PHRASES[searchPhraseIndex]
        else -> "Tap for lyrics"
    }
    // --- end inline synced lyric line ---

    // ====================================================================
    //  APPLE-MUSIC-STYLE NOW PLAYING
    //  - Top half: crisp album art (full bleed)
    //  - Bottom half: BLURRED album art
    //  - Seamless blend via gradient alpha mask on crisp image's bottom edge
    //  - Dark scrim over the blurred part for text legibility
    //  - Controls overlaid on the blurred bottom half
    // ====================================================================
    Box(modifier = modifier.fillMaxSize()) {
        // 1. BLURRED BACKGROUND — full screen, blurred album art
        //    blur() requires API 31+ (Android 12). On older devices, image is unblurred
        //    (acceptable fallback — text legibility is still preserved by the scrim below).
        AsyncImage(
            model = metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(32.dp)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                }
        )

        // 2. CRISP TOP IMAGE — top 55% of screen, with seamless fade-out at bottom edge
        //    The fade-out is achieved via drawWithContent + Brush.verticalGradient + BlendMode.DstIn.
        //    DstIn keeps destination pixels where source alpha > 0:
        //      - Top 80% of this image: gradient is Color.Black (alpha=1) → image fully visible
        //      - Bottom 20% of this image: gradient fades to Transparent (alpha=0) → image fades out
        //    As the crisp image fades out, the blurred background shows through → seamless blend.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .align(Alignment.TopCenter)
        ) {
            AsyncImage(
                model = metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        // Mask: fade out the bottom 20% of this image to transparent
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = listOf(
                                    0.75f to Color.Black,       // top 75%: keep image fully
                                    1.0f to Color.Transparent   // bottom 25%: fade to transparent
                                )
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )
        }

        // 3. DARK SCRIM on the bottom half — for text legibility on the blurred bg
        //    Transparent at ~50% screen height → black 65% at bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = listOf(
                            0.40f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.25f),
                            0.75f to Color.Black.copy(alpha = 0.55f),
                            1.00f to Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        )

        // 4. Full lyrics overlay (when user taps the lyric strip)
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

        // 5. Top drag-handle pill
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.4f))
        )

        // 6. Main content column — bottom aligned, sits on top of blurred bg + scrim
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 40.dp, bottom = 32.dp)
        ) {
            // ---- Title + Artist + Heart row ----
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = metadata.title?.toString().orEmpty(),
                        style = typography.l.semiBold.copy(color = Color.White),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.let {
                            if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it
                        }
                    )
                    BasicText(
                        text = metadata.artist?.toString().orEmpty(),
                        style = typography.s.semiBold.copy(color = Color.White.copy(alpha = 0.7f)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.let {
                            if (onArtistClick != null) it.clickable(onClick = onArtistClick) else it
                        }
                    )
                }

                // Heart inside a 40dp translucent circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f))
                        .clickable {
                            setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            if (likedAt == null) R.drawable.heart_outline else R.drawable.heart
                        ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            if (likedAt != null) colorPalette.favoritesIcon else Color.White
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Inline lyric strip ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowLyrics(true) }
                    .padding(vertical = 10.dp)
            ) {
                if (showNoteGlyph) {
                    Image(
                        painter = painterResource(R.drawable.musical_notes),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                AnimatedContent(
                    targetState = lyricStripText,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "inlineLyricLine",
                    modifier = Modifier.weight(1f, fill = false)
                ) { line ->
                    BasicText(
                        text = line,
                        style = typography.xs.semiBold.copy(color = Color.White.copy(alpha = 0.7f)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                BasicText(
                    text = "\u203A",
                    style = typography.xs.semiBold.copy(color = Color.White.copy(alpha = 0.7f))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- SeekBar ----
            SeekBar(
                binder = binder,
                position = position,
                media = media,
                alwaysShowDuration = true,
                style = PlayerPreferences.SeekBarStyle.Static
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Main controls: prev / play-pause / next ----
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    icon = R.drawable.play_skip_back,
                    color = Color.White,
                    onClick = { binder.player.forceSeekToPrevious() },
                    modifier = Modifier.size(36.dp)
                )

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clickable {
                            if (shouldBePlaying) binder.player.pause() else {
                                if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                binder.player.play()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedPlayPauseButton(
                        playing = shouldBePlaying,
                        modifier = Modifier.size(48.dp)
                    )
                }

                IconButton(
                    icon = R.drawable.play_skip_forward,
                    color = Color.White,
                    onClick = { binder.player.forceSeekToNext() },
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Secondary row: Shuffle / Repeat / Loop / Queue ----
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Shuffle
                Box(
                    modifier = Modifier
                        .clickable {
                            shuffleOn = !shuffleOn
                            binder.player.shuffleModeEnabled = shuffleOn
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    BasicText(
                        text = "Shuffle",
                        style = typography.xs.semiBold.copy(
                            color = if (shuffleOn) Color.White
                                    else Color.White.copy(alpha = 0.4f)
                        )
                    )
                }

                // Repeat — "1" inside circle when Repeat One, "↻" otherwise
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (repeatMode != Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            binder.player.repeatMode = repeatMode
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else "\u21BB",
                        style = typography.s.semiBold.copy(
                            color = if (repeatMode != Player.REPEAT_MODE_OFF) Color.White
                                    else Color.White.copy(alpha = 0.4f)
                        )
                    )
                }

                // Infinity loop inside circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (PlayerPreferences.trackLoopEnabled) Color.White.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable {
                            PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.infinite),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            if (PlayerPreferences.trackLoopEnabled) Color.White
                            else Color.White.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Queue (ellipsis icon)
                IconButton(
                    icon = R.drawable.ellipsis_horizontal,
                    color = Color.White,
                    onClick = onOpenQueue,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
