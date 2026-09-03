package app.vitune.android.ui.screens.player

import android.media.AudioManager
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil3.request.allowHardware
import coil3.SingletonImageLoader
import coil3.toBitmap
import androidx.palette.graphics.Palette
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

private val FallbackColors = listOf(
    Color(0xFF1A1A2E),
    Color(0xFF16213E),
    Color(0xFF0F3460),
    Color(0xFF533483)
)

// Neutral "loading" colors — what BITCHORD uses while the new song's palette
// is being extracted. These are dark grey tones, NOT blue, so there's no
// jarring blue flash when switching songs.
private val LoadingColors = listOf(
    Color(0xFF2A2A2A),  // Dark neutral grey (top)
    Color(0xFF1A1A1A)   // Darker neutral grey (bottom)
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
    onExpandQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    val metadata = mediaItem.mediaMetadata
    val media = remember(mediaItem, duration) { mediaItem.toUiMedia(duration) }

    // --- Extract colors from album art for the gradient background ---
    // BITCHORD-STYLE color transition:
    // DO NOT snap to loading colors (that causes a grey flash).
    // Instead, KEEP the previous song's colors until the new palette is
    // extracted. Then animateColorAsState crossfades DIRECTLY from
    // old → new in ONE smooth 400ms step. No intermediate flash.
    var bgColors by remember { mutableStateOf(LoadingColors) }
    LaunchedEffect(mediaItem.mediaId) {
        // NOTE: Do NOT set bgColors = LoadingColors here!
        // Keeping the previous colors until new ones are ready is what
        // makes the transition smooth (old → new, no grey flash in between).
        val artworkUri = metadata.artworkUri?.toString()
        if (artworkUri != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context)
                        .data(artworkUri)
                        .size(128)
                        .allowHardware(false)
                        .build()
                    val result = SingletonImageLoader.get(context).execute(request)
                    val bitmap = (result as? coil3.request.SuccessResult)?.image?.toBitmap()
                    if (bitmap != null) {
                        val palette = Palette.from(bitmap)
                            .maximumColorCount(8)
                            .generate()
                        val colors = palette.swatches
                            .sortedByDescending { it.population }
                            .map { Color(it.rgb) }
                            .distinct()
                            .take(4)
                        if (colors.isNotEmpty()) {
                            bgColors = colors + FallbackColors.take(4 - colors.size)
                        }
                    }
                }
            }
        }
    }

    // --- Smooth gradient color crossfade (600ms for buttery flow) ---
    // Crossfades DIRECTLY from old colors → new colors in ONE step.
    // No grey flash, no two-step transition. Just a smooth morph.
    val animatedTopColor by animateColorAsState(
        targetValue = bgColors.getOrElse(0) { LoadingColors[0] },
        animationSpec = tween(durationMillis = 600),
        label = "gradientTop"
    )
    val animatedBottomColor by animateColorAsState(
        targetValue = bgColors.getOrElse(1) { LoadingColors[1] },
        animationSpec = tween(durationMillis = 600),
        label = "gradientBottom"
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
                                .substringBeforeLast('.').trim()
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
                                    LrcLib.bestLyrics(artist = artist, title = title,
                                        duration = d.milliseconds, album = album, synced = false
                                    )?.map { it?.text }?.getOrNull()
                                }.getOrNull()
                            }
                            val synced = currentLyrics?.synced ?: run {
                                val d = durationDeferred.await()
                                val lrcMain = async(Dispatchers.IO) {
                                    runCatching {
                                        LrcLib.bestLyrics(artist = artist, title = title,
                                            duration = d.milliseconds, album = album
                                        )?.map { it?.text }?.getOrNull()
                                    }.getOrNull()
                                }
                                val lrcRetry = async(Dispatchers.IO) {
                                    runCatching {
                                        LrcLib.bestLyrics(artist = artist, title = strippedTitle,
                                            duration = d.milliseconds, album = album
                                        )?.map { it?.text }?.getOrNull()
                                    }.getOrNull()
                                }
                                val kugou = async(Dispatchers.IO) {
                                    runCatching {
                                        KuGou.lyrics(artist = artist, title = title, duration = d / 1000
                                        )?.map { it?.value }?.getOrNull()
                                    }.getOrNull()
                                }
                                lrcMain.await() ?: lrcRetry.await() ?: kugou.await()
                            }
                            LyricsData(songId = mediaItem.mediaId, fixed = fixed.orEmpty(),
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
        SynchronizedLyricsState(sentences = file?.lines,
            offset = file?.offset?.inWholeMilliseconds ?: 0L)
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
        while (true) { delay(INLINE_LYRIC_UPDATE_DELAY); current.update() }
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

    // --- ThinSlider state for seekbar ---
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    val sliderValue = if (scrubbing) scrubValue
                      else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f)
                      else 0f

    // --- Volume bar state (system volume) ---
    val audioManager = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }
    var systemVolume by remember { mutableFloatStateOf(
        (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVolume
    )}
    var volumeDragging by remember { mutableStateOf(false) }
    DisposableEffect(audioManager) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
                systemVolume = current.toFloat() / maxVolume
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // --- Double-tap to favorite animation state ---
    var showHeartPop by remember { mutableStateOf(false) }
    LaunchedEffect(showHeartPop) {
        if (showHeartPop) {
            delay(800)
            showHeartPop = false
        }
    }

    // ====================================================================
    //  NEW LAYOUT (Apple Music / BITCHORD inspired)
    //  - Gradient background from album art colors
    //  - "Now Playing" header at top
    //  - Square album art with 10dp corners + shadow (NO gradient on art)
    //  - Double-tap to favorite (heart pop animation)
    //  - Song name + menu button beside it
    //  - Artist name
    //  - Play/pause/skip controls
    //  - Volume bar
    //  - Shuffle/Repeat/Loop/Queue row at bottom
    // ====================================================================
    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)  // Opaque black base — NOTHING shows through
    ) {
        // 1. GRADIENT BACKGROUND from album art colors (VIBRANT — full opacity)
        //    Uses the two most-dominant colors from the album art as a
        //    vertical gradient. Both colors are at full alpha (1.0) so the
        //    background is fully opaque — no transparency, no bleed-through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        // Use ANIMATED colors (smooth crossfade on song change)
                        colors = listOf(
                            animatedTopColor,
                            animatedBottomColor,
                            animatedBottomColor
                        )
                    )
                )
        )
        // 1b. Dark overlay at bottom for text legibility (gentler than before
        //     so the vibrant colors show through more)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.00f),
                            0.50f to Color.Black.copy(alpha = 0.00f),
                            0.70f to Color.Black.copy(alpha = 0.20f),
                            0.85f to Color.Black.copy(alpha = 0.50f),
                            1.00f to Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        )

        // 2. Lyrics overlay (when user taps lyric strip)
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

        // 3. Main content column — bottom aligned, with nav bar padding
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 40.dp)
                .navigationBarsPadding()
        ) {
            // ---- "Now Playing" header + tiny bar ----
            // BRIGHT WHITE (was faded 50% alpha), bumped font size for visibility.
            BasicText(
                text = "Now Playing",
                style = typography.s.semiBold.copy(
                    color = Color.White  // ← Full bright white (was 0.5f alpha)
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.7f))  // ← Brighter bar
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Album art (square, 10dp corners, BIG shadow, double-tap to favorite) ----
            // Aligned to the LEFT (matches song name/artist/lyrics/volume bar alignment)
            // so everything lines up visually.
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .pointerInput(mediaItem.mediaId) {
                        detectTapGestures(
                            onDoubleTap = {
                                setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                                showHeartPop = true
                            }
                        )
                    }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(metadata.artworkUri?.thumbnail(Dimensions.thumbnails.player.song.px))
                        .crossfade(400)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .shadow(20.dp, RoundedCornerShape(10.dp))
                )
                // Heart pop animation on double-tap.
                // Simpler approach: just show the heart when showHeartPop is true.
                // The pop effect comes from the showHeartPop LaunchedEffect delay
                // (in the main composable body) which sets it back to false after 800ms.
                if (showHeartPop) {
                    Image(
                        imageVector = BitChordIcons.HeartFilled,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            if (likedAt != null) colorPalette.favoritesIcon else Color.White
                        ),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(80.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Song name + Menu button (side by side) ----
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
                // Menu button (three dots) — opens PlayerMenu
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpenQueue() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.MoreVertical,
                        contentDescription = "Menu",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Inline lyric strip (tap to open full lyrics) ----
            // Tight padding (2dp) so it sits RIGHT above the seekbar with no gap.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onShowLyrics(true) }
                    .padding(vertical = 2.dp)
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

            Spacer(modifier = Modifier.height(4.dp))

            // ---- Seekbar (ThinSlider) + timestamps ----
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
                    onValueChange = { scrubbing = true; scrubValue = it },
                    onValueChangeFinished = {
                        binder.player.seekTo((scrubValue * duration).toLong())
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

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Play/pause/skip controls ----
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { binder.player.forceSeekToPrevious() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,  // NO ripple/square shape on click
                            onClick = {
                                if (shouldBePlaying) binder.player.pause() else {
                                    if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                    binder.player.play()
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (shouldBePlaying) BitChordIcons.Pause else BitChordIcons.Play,
                        contentDescription = if (shouldBePlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { binder.player.forceSeekToNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Volume bar ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = BitChordIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                ThinSlider(
                    value = systemVolume.coerceIn(0f, 1f),
                    onValueChange = {
                        volumeDragging = true
                        audioManager?.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (it * maxVolume).toInt().coerceIn(0, maxVolume),
                            0
                        )
                    },
                    onValueChangeFinished = { volumeDragging = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = BitChordIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Shuffle / Repeat / Loop / Queue row ----
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Shuffle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (shuffleOn) Color.White.copy(alpha = 0.2f) else Color.Transparent
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            shuffleOn = !shuffleOn
                            binder.player.shuffleModeEnabled = shuffleOn
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleOn) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                // Repeat
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (repeatMode != Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
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
                // Loop
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (PlayerPreferences.trackLoopEnabled) Color.White.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
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
                // Queue — opens the queue sheet
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onExpandQueue() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BitChordIcons.Queue,
                        contentDescription = "Queue",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
