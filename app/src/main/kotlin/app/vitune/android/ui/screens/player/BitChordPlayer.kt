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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.R
import app.vitune.android.Database
import app.vitune.android.models.Lyrics as LyricsData
import app.vitune.android.models.ui.toUiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.service.PlayerService
import app.vitune.android.transaction
import app.vitune.android.ui.components.ThinSlider
import app.vitune.android.ui.icons.BitChordIcons
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
import coil3.request.ImageRequest
import coil3.request.crossfade
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
    onOpenLyricsDialog: () -> Unit = {},
    onExpandQueue: () -> Unit = {},  // NEW: opens the queue sheet (chevron-up arrow)
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val metadata = mediaItem.mediaMetadata
    val media = remember(mediaItem, duration) { mediaItem.toUiMedia(duration) }

    // NOTE: Removed the artScale animation — it was causing the background
    // to visibly shrink/expand on play/pause, which looked jarring.
    // The album art now stays perfectly still (matches BITCHORD/YumaPlayer).

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

    // ThinSlider state — local scrub value while user drags, null = follow player
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    val sliderValue = if (scrubbing) scrubValue
                      else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f)
                      else 0f

    // ====================================================================
    //  HYBRID BACKGROUND (Apple Music / YumaPlayer style)
    //
    //  Layer 1: BLURRED FULL-SCREEN album art (background)
    //           - Fills entire screen edge-to-edge
    //           - 48dp blur radius → frosted glass effect
    //           - Even low-res art looks smooth because it's blurred
    //           - crossfade(400ms) for smooth song transitions
    //
    //  Layer 2: CRISP album art at NORMAL SIZE (horizontal rectangle)
    //           - NOT stretched to fill screen → no pixelation
    //           - Uses ContentScale.Fit → preserves aspect ratio, NO CROPPING
    //             (people/faces stay visible!)
    //           - Bottom edge FADES TO TRANSPARENT → seamless blend with blur
    //           - Empty space around the art is filled by the blurred bg
    //
    //  Layer 3: MULTI-STOP DARK GRADIENT over the bottom half
    //           - Text legibility on top of the blurred bg
    //  ====================================================================
    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)  // Solid black base — prevents system wallpaper
                                   // from showing through during image transitions
    ) {
        // 1. BLURRED FULL-SCREEN BACKGROUND
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px))
                .crossfade(400)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(48.dp)
        )

        // 2. CRISP ALBUM ART — SQUARE (centered, with blurred bg visible around it)
        //    Size: 320dp × 320dp (about 85% of typical phone screen width)
        //    Position: top-center, 24dp from the top
        //    Aspect ratio 1:1 (square) — matches classic music player look.
        //    The blurred background is visible AROUND the square art.
        //    Bottom edge fades softly to transparent so it blends with blur.
        //    NO black gradient on the art — colors stay original.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .size(320.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px))
                    .crossfade(400)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,  // ← Crop = fills the square (centered crop)
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .drawWithContent {
                        drawContent()
                        // Soft alpha mask: full opacity until 85% of image height,
                        // then fade gradually to transparent at 100%.
                        // Only the very bottom 15% fades — colors stay intact.
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black,
                                    0.85f to Color.Black,
                                    1.00f to Color.Transparent
                                )
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )
        }

        // 3. MULTI-STOP DARK GRADIENT (text legibility on blurred bg)
        //    IMPORTANT: This gradient starts at 50% screen height so it does
        //    NOT overlap the square album art (which ends around 40% screen
        //    height). This keeps the cover's colors intact while ensuring
        //    white text is readable on the blurred bg in the lower half.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.00f),
                            0.45f to Color.Black.copy(alpha = 0.00f),
                            0.50f to Color.Black.copy(alpha = 0.10f),
                            0.65f to Color.Black.copy(alpha = 0.45f),
                            0.80f to Color.Black.copy(alpha = 0.80f),
                            1.00f to Color.Black.copy(alpha = 0.97f)
                        )
                    )
                )
        )

        // 4. Full lyrics overlay (when user taps the lyric strip)
        //    onOpenDialog is now wired to onOpenLyricsDialog so the expand icon
        //    (top-left of lyrics overlay) opens the LrcLib search dialog.
        Lyrics(
            mediaId = mediaItem.mediaId,
            isDisplayed = isShowingLyrics,
            onDismiss = { onShowLyrics(false) },
            ensureSongInserted = { Database.insert(mediaItem) },
            mediaMetadataProvider = { mediaItem.mediaMetadata },
            durationProvider = { binder.player.duration.takeIf { it > 0 } ?: C.TIME_UNSET },
            onOpenDialog = onOpenLyricsDialog,
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
                    Icon(
                        imageVector = if (likedAt == null) BitChordIcons.Heart
                                      else BitChordIcons.HeartFilled,
                        contentDescription = "Like",
                        tint = if (likedAt != null) colorPalette.favoritesIcon else Color.White,
                        modifier = Modifier.size(22.dp)
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
                        imageVector = BitChordIcons.MusicNote,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)),
                        modifier = Modifier.size(14.dp)
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
                Image(
                    imageVector = BitChordIcons.ChevronRight,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- ThinSlider (Apple Music style) + timestamps ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicText(
                    text = formatTime(if (scrubbing) (scrubValue * duration).toLong() else position),
                    style = typography.xs.semiBold.copy(color = Color.White.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                ThinSlider(
                    value = sliderValue,
                    onValueChange = {
                        scrubbing = true
                        scrubValue = it
                    },
                    onValueChangeFinished = {
                        val targetMs = (scrubValue * duration).toLong()
                        binder.player.seekTo(targetMs)
                        scrubbing = false
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(
                    text = "-${formatTime((duration - (if (scrubbing) (scrubValue * duration).toLong() else position)).coerceAtLeast(0L))}",
                    style = typography.xs.semiBold.copy(color = Color.White.copy(alpha = 0.6f))
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Main controls: prev / play-pause / next ----
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { binder.player.forceSeekToPrevious() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play/Pause — large icon, no filled circle background
                // Uses BitChordIcons directly with white tint so it shows on blurred bg
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clickable {
                            if (shouldBePlaying) binder.player.pause() else {
                                if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                binder.player.play()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (shouldBePlaying) BitChordIcons.Pause
                                       else BitChordIcons.Play,
                        contentDescription = if (shouldBePlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { binder.player.forceSeekToNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Secondary row: Shuffle / Repeat / Loop / Menu (all as icons) ----
            // This row sits ABOVE the chevron-up arrow (per user's request).
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Shuffle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (shuffleOn) Color.White.copy(alpha = 0.2f) else Color.Transparent
                        )
                        .clickable {
                            shuffleOn = !shuffleOn
                            binder.player.shuffleModeEnabled = shuffleOn
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleOn) Color.White
                               else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Repeat
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) BitChordIcons.RepeatOne
                                       else BitChordIcons.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color.White
                               else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Infinity loop
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
                    Icon(
                        imageVector = BitChordIcons.Infinity,
                        contentDescription = "Loop",
                        tint = if (PlayerPreferences.trackLoopEnabled) Color.White
                               else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Menu (three dots) — opens the player menu (queue, sleep timer, etc.)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Transparent)
                        .clickable { onOpenQueue() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.MoreVertical,
                        contentDescription = "More",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Chevron-up arrow button — opens the queue sheet ----
            // Placed at the VERY BOTTOM (below the 4-icon row), per user's request.
            // Small (24dp) so it doesn't block any other controls.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onExpandQueue() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.chevron_up),
                    contentDescription = "Open Queue",
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Format milliseconds as "M:SS" (e.g. 1:09). */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
