package com.musix.android

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

private val blue = Color(0xFF4C8DFF)
private val background = Color(0xFF091323)
private val panel = Color(0xFF15243A)

data class Song(val id: Long, val title: String, val artist: String, val album: String, val duration: Long, val uri: android.net.Uri)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var currentId by mutableLongStateOf(-1L)
    private var playing by mutableStateOf(false)
    private var progress by mutableLongStateOf(0L)
    private var total by mutableLongStateOf(0L)
    private var libraryVersion by mutableIntStateOf(0)
    private var importMessage by mutableStateOf<String?>(null)
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
        }
        override fun onPlaybackStateChanged(playbackState: Int) { total = controller?.duration?.coerceAtLeast(0L) ?: 0L }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(listener)
                    playing = c.isPlaying
                    currentId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                } catch (_: Exception) { }
            }, MoreExecutors.directExecutor())
        }
        setContent {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) }
            val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) libraryVersion++ }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) {
                    val imported = importFiles(uris)
                    importMessage = if (imported > 0) "Imported $imported song${if (imported == 1) "" else "s"}" else "No audio files could be imported"
                    libraryVersion++
                }
            }
            LaunchedEffect(granted, libraryVersion) { songs = loadSongs(granted) }
            LaunchedEffect(controller) {
                while (true) { progress = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L; total = controller?.duration?.coerceAtLeast(0L) ?: 0L; delay(500) }
            }
            MusixScreen(songs, granted, { request.launch(permission) }, { libraryVersion++ },
                { importLauncher.launch(arrayOf("audio/*")) }, importMessage, { importMessage = null },
                controller, currentId, playing, progress, total, ::playSong)
        }
    }
    private fun importFiles(uris: List<Uri>): Int {
        val folder = File(filesDir, "imported_music").apply { mkdirs() }
        var count = 0
        for (uri in uris) {
            try {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Audio"
                val extension = name.substringAfterLast('.', "mp3").lowercase().takeIf { it in setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac") } ?: "mp3"
                val destination = File(folder, "${UUID.randomUUID()}.$extension")
                contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { input.copyTo(it) } }
                    ?: continue
                if (destination.length() == 0L) { destination.delete(); continue }
                // Store the original display name alongside the copied audio.
                File(folder, "${destination.name}.title").writeText(name.substringBeforeLast('.', name))
                count++
            } catch (_: Exception) { /* Skip files that cannot be opened. */ }
        }
        return count
    }
    private fun importedSongs(): List<Song> {
        val folder = File(filesDir, "imported_music")
        return folder.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac") }?.map { file ->
            var title = File(folder, "${file.name}.title").takeIf { it.exists() }?.readText() ?: file.nameWithoutExtension
            var artist = "Unknown artist"
            var album = "Imported music"
            var duration = 0L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: title
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) { } finally { try { retriever.release() } catch (_: Exception) { } }
            Song(-file.nameWithoutExtension.hashCode().toLong().let { if (it < 0) -it else it } - 1L, title, artist, album, duration, Uri.fromFile(file))
        } ?: emptyList()
    }
    private fun loadSongs(hasPermission: Boolean): List<Song> {
        val result = mutableListOf<Song>()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        if (hasPermission) contentResolver.query(collection, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                result += Song(id, cursor.getString(1) ?: "Unknown title", cursor.getString(2) ?: "Unknown artist", cursor.getString(3) ?: "Unknown album", cursor.getLong(4), ContentUris.withAppendedId(collection, id))
            }
        }
        return (result + importedSongs()).distinctBy { it.uri }.sortedBy { it.title.lowercase() }
    }
    private fun playSong(song: Song, queue: List<Song>) {
        controller?.let { c ->
            val items = queue.map { track ->
                MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()).build()
            }
            c.setMediaItems(items, queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0), 0L)
            c.prepare(); c.play()
        }
    }
    override fun onDestroy() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
private fun MusixScreen(songs: List<Song>, granted: Boolean, request: () -> Unit, refresh: () -> Unit, importMusic: () -> Unit, importMessage: String?, dismissMessage: () -> Unit, player: MediaController?, currentId: Long, playing: Boolean, progress: Long, total: Long, play: (Song, List<Song>) -> Unit) {
    var tab by remember { mutableStateOf("Songs") }
    var search by remember { mutableStateOf("") }
    var nowPlaying by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("Title") }
    var seek by remember { mutableFloatStateOf(-1f) }
    val current = songs.find { it.id == currentId }
    val filtered = remember(songs, search, tab, sort) {
        val list = songs.filter { search.isBlank() || listOf(it.title, it.artist, it.album).any { value -> value.contains(search, true) } }
        when (sort) { "Artist" -> list.sortedBy { it.artist.lowercase() }; "Album" -> list.sortedBy { it.album.lowercase() }; "Longest" -> list.sortedByDescending { it.duration }; else -> list.sortedBy { it.title.lowercase() } }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = blue, background = background, surface = panel, onBackground = Color.White, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, null, tint = blue, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Musix", fontSize = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = importMusic) { Icon(Icons.Default.LibraryAdd, "Import music") }
                IconButton(onClick = refresh) { Icon(Icons.Default.Refresh, "Refresh library") }
            }
            if (importMessage != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(importMessage, modifier = Modifier.weight(1f), color = blue)
                    TextButton(onClick = dismissMessage) { Text("OK") }
                }
            }
            if (!granted && songs.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, null, tint = blue, modifier = Modifier.size(70.dp))
                    Spacer(Modifier.height(20.dp)); Text("Your music, beautifully organized", fontSize = 22.sp)
                    Spacer(Modifier.height(12.dp)); Text("Allow audio access to browse music stored on this Android device.")
                    Spacer(Modifier.height(24.dp)); Button(onClick = importMusic) { Text("Import Music") }
                    Spacer(Modifier.height(8.dp)); TextButton(onClick = request) { Text("Allow Music Access for Automatic Scanning") }
                }
            } else if (nowPlaying) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nowPlaying = false }) { Icon(Icons.Default.KeyboardArrowDown, "Back") }
                        Text("NOW PLAYING", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.weight(0.5f))
                    Surface(shape = RoundedCornerShape(32.dp), color = panel, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = blue, modifier = Modifier.size(130.dp)) }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(current?.title ?: "No track selected", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(current?.artist ?: "Select a song", color = Color.LightGray)
                    Spacer(Modifier.height(25.dp))
                    Slider(value = if (seek >= 0f) seek else if (total > 0) (progress.toFloat() / total).coerceIn(0f, 1f) else 0f, onValueChange = { seek = it }, onValueChangeFinished = { player?.seekTo(((total.coerceAtLeast(0)) * seek).toLong()); seek = -1f })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(progress)); Text(time(total)) }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(34.dp)) }
                        FilledIconButton(onClick = { if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", modifier = Modifier.size(38.dp)) }
                        IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(34.dp)) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        IconButton(onClick = { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (player?.shuffleModeEnabled == true) blue else Color.White) }
                        IconButton(onClick = { player?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }) { Icon(Icons.Default.Repeat, "Repeat", tint = if (player?.repeatMode != Player.REPEAT_MODE_OFF) blue else Color.White) }
                    }
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Songs", "Albums", "Artists").forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) }) }
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search music") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${filtered.size} songs", color = Color.LightGray, modifier = Modifier.weight(1f))
                    var expanded by remember { mutableStateOf(false) }
                    Box { TextButton(onClick = { expanded = true }) { Text("Sort: $sort") }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { listOf("Title", "Artist", "Album", "Longest").forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { sort = choice; expanded = false }) } } }
                }
                if (songs.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.LibraryMusic, null, tint = blue, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No music yet", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Choose audio files to add them to your library", color = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = importMusic) { Icon(Icons.Default.LibraryAdd, null); Spacer(Modifier.width(8.dp)); Text("Import Music") }
                    }
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                    when (tab) {
                        "Songs" -> items(filtered, key = { it.id }) { song -> SongRow(song, { play(song, filtered) }) }
                        "Albums" -> items(filtered.groupBy { it.album }.toList().sortedBy { it.first }) { (album, tracks) -> GroupRow(album, "${tracks.size} tracks", { play(tracks.first(), tracks) }) }
                        else -> items(filtered.groupBy { it.artist }.toList().sortedBy { it.first }) { (artist, tracks) -> GroupRow(artist, "${tracks.size} tracks", { play(tracks.first(), tracks) }) }
                    }
                }
            }
            if (current != null && !nowPlaying) {
                Surface(color = panel, modifier = Modifier.fillMaxWidth().clickable { nowPlaying = true }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, tint = blue, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(current.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current.artist, color = Color.LightGray, maxLines = 1) }
                        IconButton(onClick = { if (playing) player?.pause() else player?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                        IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next") }
                    }
                }
            }
        }
    }
}
@Composable private fun SongRow(song: Song, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(52.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = blue) } }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${song.artist} • ${song.album}", color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(time(song.duration), color = Color.LightGray, fontSize = 12.sp)
    }
}
@Composable private fun GroupRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Album, null, tint = blue, modifier = Modifier.size(46.dp)); Spacer(Modifier.width(16.dp))
        Column { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.LightGray) }
    }
}
private fun time(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return "%d:%02d".format(s / 60, s % 60) }
