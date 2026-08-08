package com.herrose.musicplayer

import android.Manifest
import androidx.compose.foundation.lazy.itemsIndexed
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.herrose.musicplayer.data.AppDatabase
import com.herrose.musicplayer.data.FavoriteSong
import com.herrose.musicplayer.data.LyricLine
import com.herrose.musicplayer.data.LyricsRepository
import com.herrose.musicplayer.data.LyricsResult
import com.herrose.musicplayer.data.MusicRepository
import com.herrose.musicplayer.data.Song
import com.herrose.musicplayer.ui.theme.MusicPlayerTheme
import com.herrose.musicplayer.ui.theme.PurpleDark
import com.herrose.musicplayer.ui.theme.PurpleLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            setContent {
                MusicPlayerTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        mediaController?.let { controller ->
                            MusicAppScreen(controller)
                        }
                    }
                }
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

fun formatTime(millis: Long): String {
    if (millis < 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun AlbumArt(uri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxSize(0.4f)
        )
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun MusicAppScreen(controller: MediaController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val favoriteDao = remember { db.favoriteDao() }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var favoriteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showFullPlayer by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            songs = MusicRepository(context).getAllSongs()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(permission)
    }

    LaunchedEffect(Unit) {
        favoriteDao.getAllFavorites().collect { list ->
            favoriteIds = list.map { it.songId }.toSet()
        }
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIndex = controller.currentMediaItemIndex
                if (newIndex in songs.indices) {
                    currentSong = songs[newIndex]
                    currentPosition = 0L
                }
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(isPlaying, currentSong) {
        while (true) {
            if (isPlaying && !isUserSeeking) {
                currentPosition = controller.currentPosition
                duration = controller.duration.coerceAtLeast(0L)
            }
            delay(300)
        }
    }

    fun playSong(song: Song) {
        val index = songs.indexOf(song)
        if (index == -1) return

        val mediaItems = songs.map { s ->
            MediaItem.Builder()
                .setUri(s.uri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setArtworkUri(s.albumArtUri?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()
        }

        controller.setMediaItems(mediaItems, index, 0L)
        controller.prepare()
        controller.play()
        currentSong = song
        currentPosition = 0L
    }

    fun toggleFavorite(song: Song) {
        coroutineScope.launch {
            if (favoriteIds.contains(song.id)) {
                favoriteDao.removeFavorite(
                    FavoriteSong(song.id, song.title, song.artist, song.uri, song.albumArtUri)
                )
            } else {
                favoriteDao.addFavorite(
                    FavoriteSong(song.id, song.title, song.artist, song.uri, song.albumArtUri)
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).padding(16.dp)) {
            Text(
                text = "Your Music",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (!hasPermission) {
                Text("Permission needed to show songs. Please allow audio access.")
            } else if (songs.isEmpty()) {
                Text("No songs found on this device.")
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(songs) { song ->
                        SongRow(
                            song = song,
                            isCurrent = song.id == currentSong?.id,
                            isFavorite = favoriteIds.contains(song.id),
                            onClick = { playSong(song) },
                            onFavoriteClick = { toggleFavorite(song) }
                        )
                    }
                }
            }
        }

        currentSong?.let { song ->
            NowPlayingBar(
                song = song,
                isPlaying = isPlaying,
                isFavorite = favoriteIds.contains(song.id),
                currentPosition = currentPosition,
                duration = duration,
                onPlayPauseClick = {
                    if (controller.isPlaying) controller.pause() else controller.play()
                },
                onPreviousClick = { controller.seekToPreviousMediaItem() },
                onNextClick = { controller.seekToNextMediaItem() },
                onFavoriteClick = { toggleFavorite(song) },
                onSeekStart = { isUserSeeking = true },
                onSeek = { newPosition -> currentPosition = newPosition },
                onSeekEnd = { newPosition ->
                    controller.seekTo(newPosition)
                    isUserSeeking = false
                },
                onExpandClick = { showFullPlayer = true }
            )
        }
    }

    if (showFullPlayer && currentSong != null) {
        FullPlayerScreen(
            song = currentSong!!,
            isPlaying = isPlaying,
            isFavorite = favoriteIds.contains(currentSong!!.id),
            currentPosition = currentPosition,
            duration = duration,
            onPlayPauseClick = {
                if (controller.isPlaying) controller.pause() else controller.play()
            },
            onPreviousClick = { controller.seekToPreviousMediaItem() },
            onNextClick = { controller.seekToNextMediaItem() },
            onFavoriteClick = { toggleFavorite(currentSong!!) },
            onSeekStart = { isUserSeeking = true },
            onSeek = { newPosition -> currentPosition = newPosition },
            onSeekEnd = { newPosition ->
                controller.seekTo(newPosition)
                isUserSeeking = false
            },
            onCollapse = { showFullPlayer = false }
        )
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(
            uri = song.albumArtUri,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = song.title, style = MaterialTheme.typography.bodyLarge)
            Text(text = song.artist, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WaveSeekBar(
    song: Song,
    currentPosition: Long,
    duration: Long,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: (Long) -> Unit,
    waveColor: Color = Color.White,
    waveColorMuted: Color = Color.White.copy(alpha = 0.35f)
) {
    val safeDuration = if (duration > 0) duration else 1L
    val progress = (currentPosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    var dragPosition by remember { mutableStateOf<Long?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(safeDuration) {
                detectDragGestures(
                    onDragStart = { onSeekStart() },
                    onDragEnd = {
                        dragPosition?.let { onSeekEnd(it) }
                        dragPosition = null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        val newPos = (newProgress * safeDuration).toLong()
                        dragPosition = newPos
                        onSeek(newPos)
                    }
                )
            }
            .pointerInput(safeDuration) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    val newPos = (newProgress * safeDuration).toLong()
                    onSeekStart()
                    onSeekEnd(newPos)
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        val amplitude = height * 0.32f
        val waveLength = width / 5f
        val progressX = width * progress

        val playedPath = Path()
        val unplayedPath = Path()

        var x = 0f
        while (x <= width) {
            val y = midY + amplitude * sin((x / waveLength) * 2 * Math.PI.toFloat())
            if (x <= progressX) {
                if (playedPath.isEmpty) playedPath.moveTo(x, y) else playedPath.lineTo(x, y)
            } else {
                if (unplayedPath.isEmpty) unplayedPath.moveTo(x, y) else unplayedPath.lineTo(x, y)
            }
            x += 4f
        }

        drawPath(unplayedPath, color = waveColorMuted, style = Stroke(width = 6f, cap = StrokeCap.Round))
        drawPath(playedPath, color = waveColor, style = Stroke(width = 6f, cap = StrokeCap.Round))

        val thumbY = midY + amplitude * sin((progressX / waveLength) * 2 * Math.PI.toFloat())
        drawCircle(color = Color.White, radius = 8f, center = Offset(progressX, thumbY))
    }
}

@Composable
fun NowPlayingBar(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: (Long) -> Unit,
    onExpandClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable { onExpandClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(
                uri = song.albumArtUri,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(text = song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
            IconButton(onClick = onFavoriteClick, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onPreviousClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = PurpleLight,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(PurpleDark)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onNextClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = PurpleLight,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        WaveSeekBar(
            song = song,
            currentPosition = currentPosition,
            duration = duration,
            onSeekStart = onSeekStart,
            onSeek = onSeek,
            onSeekEnd = onSeekEnd,
            waveColor = PurpleLight,
            waveColorMuted = PurpleDark.copy(alpha = 0.7f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            Text(text = formatTime(duration), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
fun FullPlayerScreen(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: (Long) -> Unit,
    onCollapse: () -> Unit
) {
    BackHandler { onCollapse() }

    var lyricsResult by remember(song.id) { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember(song.id) { mutableStateOf(true) }

    LaunchedEffect(song.id) {
        lyricsLoading = true
        lyricsResult = LyricsRepository.fetchLyrics(song.title, song.artist)
        lyricsLoading = false
    }

    val syncedLines = lyricsResult?.synced
    val activeIndex by remember(syncedLines, currentPosition) {
        derivedStateOf {
            if (syncedLines.isNullOrEmpty()) -1
            else syncedLines.indexOfLast { it.timeMs <= currentPosition }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Dialog(onDismissRequest = onCollapse) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                AlbumArt(
                    uri = song.albumArtUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                WaveSeekBar(
                    song = song,
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeekStart = onSeekStart,
                    onSeek = onSeek,
                    onSeekEnd = onSeekEnd,
                    waveColor = PurpleLight,
                    waveColorMuted = MaterialTheme.colorScheme.surfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = formatTime(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onFavoriteClick, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    IconButton(onClick = onPreviousClick, modifier = Modifier.size(52.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(50))
                            .background(PurpleDark)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = onNextClick, modifier = Modifier.size(52.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(40.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    lyricsLoading -> {
                        Text(
                            text = "Loading lyrics...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !syncedLines.isNullOrEmpty() -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            itemsIndexed(syncedLines) { index, line ->
                                Text(
                                    text = line.text,
                                    style = if (index == activeIndex) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                                    color = if (index == activeIndex) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                    lyricsResult?.plain != null -> {
                        Text(
                            text = lyricsResult!!.plain!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                    else -> {
                        Text(
                            text = "Lyrics not found for this song.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}