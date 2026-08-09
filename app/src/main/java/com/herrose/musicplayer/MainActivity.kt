package com.herrose.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.herrose.musicplayer.data.AppDatabase
import com.herrose.musicplayer.data.FavoriteSong
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

enum class AppTab { HOME, FAVORITES, LIBRARY, PLAYLIST }

fun formatTime(millis: Long): String {
    if (millis < 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun FavoriteSong.toSong(): Song = Song(
    id = songId,
    title = title,
    artist = artist,
    duration = 0L,
    uri = uri,
    albumArtUri = albumArtUri
)

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
    var favoriteSongsList by remember { mutableStateOf<List<FavoriteSong>>(emptyList()) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var sleepTimerEndAt by remember { mutableStateOf<Long?>(null) }
    var sleepTimerRemaining by remember { mutableStateOf<Long?>(null) }
    var sleepAtTrackEnd by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }

    val favoriteIds = favoriteSongsList.map { it.songId }.toSet()

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
            favoriteSongsList = list
        }
    }

    fun playFromList(list: List<Song>, song: Song) {
        val index = list.indexOf(song)
        if (index == -1) return

        val mediaItems = list.map { s ->
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
        controller.shuffleModeEnabled = shuffleEnabled
        controller.prepare()
        controller.play()
        currentSong = song
        currentPosition = 0L
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = controller.currentMediaItemIndex
                val list = when (selectedTab) {
                    AppTab.FAVORITES -> favoriteSongsList.map { it.toSong() }
                    else -> songs
                }
                if (idx in list.indices) {
                    currentSong = list[idx]
                    currentPosition = 0L
                }
                if (sleepAtTrackEnd &&
                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                     reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                ) {
                    controller.pause()
                    sleepAtTrackEnd = false
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

    LaunchedEffect(sleepTimerEndAt) {
        while (sleepTimerEndAt != null) {
            val remaining = sleepTimerEndAt!! - System.currentTimeMillis()
            if (remaining <= 0) {
                controller.pause()
                sleepTimerEndAt = null
                sleepTimerRemaining = null
            } else {
                sleepTimerRemaining = remaining
                delay(1000)
            }
        }
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

    fun cycleRepeatMode() {
        val newMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = newMode
        repeatMode = newMode
    }

    fun toggleShuffle() {
        val newValue = !shuffleEnabled
        controller.shuffleModeEnabled = newValue
        shuffleEnabled = newValue
        Toast.makeText(
            context,
            if (newValue) "Shuffle ON" else "Shuffle OFF",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerEndAt = if (minutes == null) null else System.currentTimeMillis() + minutes * 60_000L
        if (minutes == null) sleepTimerRemaining = null
        sleepAtTrackEnd = false
    }

    fun setSleepAtTrackEnd() {
        sleepAtTrackEnd = true
        sleepTimerEndAt = null
        sleepTimerRemaining = null
    }

    fun cancelSleepTimer() {
        sleepTimerEndAt = null
        sleepTimerRemaining = null
        sleepAtTrackEnd = false
    }

    val filteredSongs = if (searchQuery.isBlank()) {
        songs
    } else {
        songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    val favoriteSongs = favoriteSongsList.map { it.toSong() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                AppTab.HOME -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = "YourMusic",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = PurpleLight,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        if (hasPermission && songs.isNotEmpty()) {
                            Text(
                                text = "${songs.size} songs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search songs or artists") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search", tint = PurpleLight) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = PurpleLight)
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PurpleLight,
                                    unfocusedBorderColor = PurpleDark,
                                    cursorColor = PurpleLight,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            )
                        }

                        if (!hasPermission) {
                            Text("Permission needed to show songs. Please allow audio access.")
                        } else if (songs.isEmpty()) {
                            Text("No songs found on this device.")
                        } else if (filteredSongs.isEmpty()) {
                            Text("No results found.")
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(filteredSongs) { song ->
                                    SongRow(
                                        song = song,
                                        isCurrent = song.id == currentSong?.id,
                                        isFavorite = favoriteIds.contains(song.id),
                                        onClick = { playFromList(filteredSongs, song) },
                                        onFavoriteClick = { toggleFavorite(song) }
                                    )
                                }
                            }
                        }
                    }
                }
                AppTab.FAVORITES -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = "Favorites",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "${favoriteSongs.size} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        if (favoriteSongs.isEmpty()) {
                            Text("No favorites yet. Tap the heart icon on a song to add it here.")
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(favoriteSongs) { song ->
                                    SongRow(
                                        song = song,
                                        isCurrent = song.id == currentSong?.id,
                                        isFavorite = true,
                                        onClick = { playFromList(favoriteSongs, song) },
                                        onFavoriteClick = { toggleFavorite(song) }
                                    )
                                }
                            }
                        }
                    }
                }
                AppTab.LIBRARY -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "${songs.size} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        if (songs.isEmpty()) {
                            Text("No songs found on this device.")
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(songs) { song ->
                                    SongRow(
                                        song = song,
                                        isCurrent = song.id == currentSong?.id,
                                        isFavorite = favoriteIds.contains(song.id),
                                        onClick = { playFromList(songs, song) },
                                        onFavoriteClick = { toggleFavorite(song) }
                                    )
                                }
                            }
                        }
                    }
                }
                AppTab.PLAYLIST -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Playlists coming soon",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val navItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = PurpleLight,
            indicatorColor = PurpleDark,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NavigationBar {
            NavigationBarItem(
                selected = selectedTab == AppTab.HOME,
                onClick = { selectedTab = AppTab.HOME },
                icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                label = { Text("Home") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == AppTab.FAVORITES,
                onClick = { selectedTab = AppTab.FAVORITES },
                icon = { Icon(Icons.Filled.Favorite, contentDescription = "Favorites") },
                label = { Text("Favorites") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == AppTab.LIBRARY,
                onClick = { selectedTab = AppTab.LIBRARY },
                icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = "Library") },
                label = { Text("Library") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == AppTab.PLAYLIST,
                onClick = { selectedTab = AppTab.PLAYLIST },
                icon = { Icon(Icons.Filled.QueueMusic, contentDescription = "Playlist") },
                label = { Text("Playlist") },
                colors = navItemColors
            )
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
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            sleepTimerRemaining = sleepTimerRemaining,
            sleepAtTrackEnd = sleepAtTrackEnd,
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
            onRepeatClick = { cycleRepeatMode() },
            onShuffleClick = { toggleShuffle() },
            onSleepTimerSelected = { minutes -> setSleepTimer(minutes) },
            onSleepAtTrackEndSelected = { setSleepAtTrackEnd() },
            onCancelSleepTimer = { cancelSleepTimer() },
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
fun VolumeControl() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    val icon = when {
        volume == 0 -> Icons.Filled.VolumeOff
        volume < maxVolume / 2 -> Icons.Filled.VolumeDown
        else -> Icons.Filled.VolumeUp
    }

    fun updateVolume(newVol: Int) {
        val clamped = newVol.coerceIn(0, maxVolume)
        volume = clamped
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Volume",
            tint = PurpleLight,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(maxVolume) {
                    detectTapGestures { offset ->
                        val newVol = ((offset.x / size.width) * maxVolume).toInt()
                        updateVolume(newVol)
                    }
                }
                .pointerInput(maxVolume) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val newVol = ((change.position.x / size.width) * maxVolume).toInt()
                        updateVolume(newVol)
                    }
                }
        ) {
            val trackHeight = 6.dp.toPx()
            val centerY = size.height / 2f
            val progress = volume.toFloat() / maxVolume.toFloat()

            drawRoundRect(
                color = PurpleDark.copy(alpha = 0.5f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f)
            )

            drawRoundRect(
                color = PurpleLight,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width * progress, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f)
            )

            drawCircle(
                color = Color.White,
                radius = 9.dp.toPx(),
                center = Offset(size.width * progress, centerY)
            )
            drawCircle(
                color = PurpleDark,
                radius = 9.dp.toPx(),
                center = Offset(size.width * progress, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "$volume",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp)
        )
    }
}

@Composable
fun FullPlayerScreen(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    currentPosition: Long,
    duration: Long,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    sleepTimerRemaining: Long?,
    sleepAtTrackEnd: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: (Long) -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onSleepTimerSelected: (Int?) -> Unit,
    onSleepAtTrackEndSelected: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onCollapse: () -> Unit
) {
    BackHandler { onCollapse() }

    var lyricsResult by remember(song.id) { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember(song.id) { mutableStateOf(true) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

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

    Dialog(
        onDismissRequest = onCollapse,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sleepTimerRemaining != null) {
                            Text(
                                text = formatTime(sleepTimerRemaining),
                                style = MaterialTheme.typography.bodySmall,
                                color = PurpleLight,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(onClick = { showSleepTimerDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = "Sleep timer",
                                tint = if (sleepTimerRemaining != null || sleepAtTrackEnd) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onShuffleClick) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffleEnabled) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onRepeatClick) {
                            Icon(
                                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                contentDescription = "Repeat",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AlbumArt(
                        uri = song.albumArtUri,
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .aspectRatio(1f)
                            .shadow(elevation = 12.dp, shape = RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

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

                Spacer(modifier = Modifier.height(6.dp))

                VolumeControl()

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onFavoriteClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onPreviousClick, modifier = Modifier.size(46.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(30.dp)
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
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onNextClick, modifier = Modifier.size(46.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(36.dp))
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(syncedLines) { index, line ->
                                    Text(
                                        text = line.text,
                                        style = if (index == activeIndex) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                                        color = if (index == activeIndex) PurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        lyricsResult?.plain != null -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(
                                        text = lyricsResult!!.plain!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
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

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer") },
            text = {
                Column {
                    listOf(5, 10, 15, 30, 60).forEach { minutes ->
                        Text(
                            text = "$minutes minutes",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSleepTimerSelected(minutes)
                                    showSleepTimerDialog = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                    Text(
                        text = "End of current track",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSleepAtTrackEndSelected()
                                showSleepTimerDialog = false
                            }
                            .padding(vertical = 12.dp)
                    )
                    if (sleepTimerRemaining != null || sleepAtTrackEnd) {
                        Text(
                            text = "Cancel timer",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCancelSleepTimer()
                                    showSleepTimerDialog = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }git add .
    git commit -m "Add bottom navigation (Home/Favorites/Library/Playlist) with polished search bar and branding"
    git push
}