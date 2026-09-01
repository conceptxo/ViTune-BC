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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import app.vitune.android.utils.secondary
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
                            // Duration is only needed by the LrcLib/KuGou calls,
                            // not by Innertube — so it's fetched once, in
                            // parallel, instead of blocking every provider
                            // (including Innertube) behind it up front.
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

    // Raw current sentence, not filtered — distinguishes "no lyric line
    // here" (blank, an instrumental gap the source explicitly marked) from
    // "nothing has loaded at all" (null, no line exists at this index yet).
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
                    colorFilter = ColorFilter.tint(colorPalette.text.copy(alpha = 0.6f)),
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
                    style = typography.xs.semiBold.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            BasicText(
                text = "\u203A",
                style = typography.xs.semiBold.secondary
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
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clickable {
                        shuffleOn = !shuffleOn
                        binder.player.shuffleModeEnabled = shuffleOn
                    }
                    .padding(horizontal = 8.dp)
            ) {
                BasicText(
                    text = "Shuffle",
                    style = typography.xs.semiBold.let {
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
                    .padding(horizontal = 8.dp)
            ) {
                BasicText(
                    text = if (repeatMode == Player.REPEAT_MODE_ONE) "Repeat 1" else "Repeat",
                    style = typography.xs.semiBold.let {
                        if (repeatMode != Player.REPEAT_MODE_OFF) it.copy(color = colorPalette.accent) else it.secondary
                    }
                )
            }

            IconButton(
                icon = R.drawable.infinite,
                enabled = PlayerPreferences.trackLoopEnabled,
                onClick = { PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled },
                modifier = Modifier.size(28.dp)
            )

            IconButton(
                icon = R.drawable.ellipsis_horizontal,
                color = colorPalette.text,
                onClick = onOpenQueue,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
