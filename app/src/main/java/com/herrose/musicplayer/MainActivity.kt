package com.herrose.musicplayer

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.herrose.musicplayer.data.MusicRepository
import com.herrose.musicplayer.data.Song
import com.herrose.musicplayer.ui.theme.MusicPlayerTheme
import com.herrose.musicplayer.ui.theme.PurpleDark
import com.herrose.musicplayer.ui.theme.PurpleLight
import kotlinx.coroutines.delay
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
fun MusicAppScreen(controller: MediaController) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isUserSeeking by remember { mutableStateOf(false) }

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
            delay(500)
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
                            onClick = { playSong(song) }
                        )
                    }
                }
            }
        }

        currentSong?.let { song ->
            NowPlayingBar(
                song = song,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                onPlayPauseClick = {
                    if (controller.isPlaying) controller.pause() else controller.play()
                },
                onPreviousClick = { controller.seekToPreviousMediaItem() },
                onNextClick = { controller.seekToNextMediaItem() },
                onSeekStart = { isUserSeeking = true },
                onSeek = { newPosition -> currentPosition = newPosition },
                onSeekEnd = { newPosition ->
                    controller.seekTo(newPosition)
                    isUserSeeking = false
                }
            )
        }
    }
}

@Composable
fun SongRow(song: Song, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = song.title, style = MaterialTheme.typography.bodyLarge)
            Text(text = song.artist, style = MaterialTheme.typography.bodyMedium)
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
    onSeekEnd: (Long) -> Unit
) {
    val safeDuration = if (duration > 0) duration else 1L
    val progress = (currentPosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    val playedColor = PurpleLight
    val unplayedColor = PurpleDark.copy(alpha = 0.7f)

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

        drawPath(unplayedPath, color = unplayedColor, style = Stroke(width = 6f, cap = StrokeCap.Round))
        drawPath(playedPath, color = playedColor, style = Stroke(width = 6f, cap = StrokeCap.Round))

        val thumbY = midY + amplitude * sin((progressX / waveLength) * 2 * Math.PI.toFloat())
        drawCircle(color = Color.White, radius = 8f, center = Offset(progressX, thumbY))
    }
}

@Composable
fun NowPlayingBar(
    song: Song,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(text = song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
            IconButton(onClick = onPreviousClick, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = PurpleLight,
                    modifier = Modifier.size(26.dp)
                )
            }
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(PurpleDark)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onNextClick, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = PurpleLight,
                    modifier = Modifier.size(26.dp)
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
            onSeekEnd = onSeekEnd
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
