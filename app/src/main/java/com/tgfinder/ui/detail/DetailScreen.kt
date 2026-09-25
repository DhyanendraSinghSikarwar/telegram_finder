package com.tgfinder.ui.detail

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.tgfinder.AppContainer
import com.tgfinder.data.db.DownloadEntity
import com.tgfinder.data.db.TmdbMatchEntity
import com.tgfinder.download.DownloadService
import com.tgfinder.player.PlayerActivity
import com.tgfinder.search.MediaGroup
import com.tgfinder.search.VideoVersion
import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.tmdb.TitleDetails
import com.tgfinder.tmdb.TmdbAuthException
import com.tgfinder.tmdb.TmdbImages
import com.tgfinder.tmdb.TmdbKeyMissingException
import com.tgfinder.ui.common.Banner
import com.tgfinder.ui.common.BannerKind
import com.tgfinder.ui.common.CenteredMessage
import com.tgfinder.ui.common.LocalContainer
import com.tgfinder.ui.common.SmallBadge
import com.tgfinder.ui.common.TelegramThumb
import com.tgfinder.ui.common.containerViewModel
import com.tgfinder.ui.common.formatDuration
import com.tgfinder.ui.common.formatRuntime
import com.tgfinder.ui.theme.ImdbYellow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale

class DetailViewModel(private val c: AppContainer, key: String) : ViewModel() {
    val group: MediaGroup? = c.session.group(key)
    val match: TmdbMatchEntity? = c.session.match(key)

    val details = MutableStateFlow<TitleDetails?>(null)
    val loading = MutableStateFlow(match != null)
    val error = MutableStateFlow<String?>(null)

    val downloads: StateFlow<Map<String, DownloadEntity>> = c.downloads.all
        .map { list -> list.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        if (match != null) load()
    }

    fun load() = viewModelScope.launch {
        val m = match ?: return@launch
        loading.value = true
        error.value = null
        try {
            details.value = c.tmdb.details(m)
        } catch (e: TmdbKeyMissingException) {
            error.value = ErrorMessages.TMDB_KEY_MISSING
        } catch (e: TmdbAuthException) {
            error.value = e.message
        } catch (e: IOException) {
            error.value = "Could not load details from TMDB. ${ErrorMessages.forNetwork(e)}"
        } catch (e: Exception) {
            error.value = "Could not load details from TMDB."
        } finally {
            loading.value = false
        }
    }

    fun download(version: VideoVersion) = viewModelScope.launch {
        val g = group ?: return@launch
        val title = details.value?.title ?: match?.title ?: g.displayTitle
        val subtitle = listOfNotNull((details.value?.year ?: match?.year ?: g.parsed.year)?.toString(), g.parsed.episodeLabel)
            .joinToString(" · ").ifBlank { null }
        val thumb = if (version.thumbFileId != 0) runCatching { c.td.downloadSmallFile(version.thumbFileId) }.getOrNull() else null
        c.downloads.enqueue(version, title, subtitle, details.value?.posterPath ?: match?.posterPath, thumb)
    }

    fun pause(key: String) = viewModelScope.launch { c.downloads.pause(key) }
    fun resume(key: String) = viewModelScope.launch { c.downloads.resume(key) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(groupKey: String, onBack: () -> Unit) {
    val vm = containerViewModel(key = groupKey) { DetailViewModel(it, groupKey) }
    val group = vm.group
    val context = LocalContext.current
    if (group == null) {
        CenteredMessage("This result has expired", "Go back and search again.") { OutlinedButton(onClick = onBack) { Text("Back") } }
        return
    }
    val details by vm.details.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val match = vm.match
    val hasTmdb = LocalContainer.current.prefs.config.collectAsState().value.hasTmdb

    var pendingDownload by remember { mutableStateOf<VideoVersion?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val version = pendingDownload ?: return@rememberLauncherForActivityResult
        pendingDownload = null
        val storageDenied = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            results[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false
        if (storageDenied) {
            Toast.makeText(context, "Storage permission is needed to save videos to Movies/TGFinder.", Toast.LENGTH_LONG).show()
        } else {
            vm.download(version) // notifications are optional; the download runs either way
        }
    }
    fun startDownload(version: VideoVersion) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) vm.download(version) else {
            pendingDownload = version
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    val title = details?.title ?: match?.title ?: group.displayTitle
    val year = details?.year ?: match?.year ?: group.parsed.year
    val posterPath = details?.posterPath ?: match?.posterPath
    val backdropPath = details?.backdropPath ?: match?.backdropPath

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        // ---- Backdrop + poster header ----
        item {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(BACKDROP_HEIGHT)) {
                    if (backdropPath != null) {
                        AsyncImage(TmdbImages.backdrop(backdropPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        TelegramThumb(group.bestThumb, Modifier.fillMaxSize())
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, MaterialTheme.colorScheme.background))),
                    )
                }
                FilledIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
                    modifier = Modifier.statusBarsPadding().padding(8.dp),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = BACKDROP_HEIGHT - 72.dp, start = 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        Modifier
                            .width(112.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        if (posterPath != null) {
                            AsyncImage(TmdbImages.posterLarge(posterPath), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            TelegramThumb(group.bestThumb, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            year?.toString(),
                            group.parsed.episodeLabel,
                            formatRuntime(details?.runtimeMinutes),
                            details?.seasons?.let { if (it == 1) "1 season" else "$it seasons" },
                        ).joinToString(" · ")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            details?.certification?.let {
                                Spacer(Modifier.width(8.dp))
                                SmallBadge(it, background = MaterialTheme.colorScheme.surfaceVariant, foreground = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (match == null) {
                    Banner(
                        if (!hasTmdb) ErrorMessages.TMDB_KEY_MISSING
                        else "No TMDB match for this title. Showing the Telegram file name.",
                        BannerKind.INFO,
                    )
                }
                details?.let { d ->
                    if (d.genres.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        }
                    }
                    RatingRow(d.rating, d.voteCount)
                    d.trailerYoutubeKey?.let { key ->
                        OutlinedButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No app can open YouTube links.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Watch trailer")
                        }
                    }
                    d.tagline?.let { Text(it, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (d.overview.isNotBlank()) Text(d.overview, style = MaterialTheme.typography.bodyMedium)
                    if (d.directors.isNotEmpty()) {
                        Row {
                            Text(if (d.mediaType == "tv") "Created by  " else "Director  ", fontWeight = FontWeight.Bold)
                            Text(d.directors.joinToString(", "), color = ImdbYellow)
                        }
                    }
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ImdbYellow)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading details…")
                    }
                }
                error?.let {
                    Banner(it, BannerKind.WARNING)
                    OutlinedButton(onClick = { vm.load() }) { Text("Retry") }
                }
            }
        }

        details?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    SectionHeader("Top cast")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast) { member ->
                            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (member.profilePath != null) {
                                        AsyncImage(TmdbImages.profile(member.profilePath), contentDescription = member.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(member.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                                member.character?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Box(Modifier.padding(top = 12.dp)) { SectionHeader("Available versions (${group.versions.size})") } }
        items(group.versions, key = { it.key }) { version ->
            VersionCard(
                version = version,
                download = downloads[version.key],
                onStream = { context.startActivity(PlayerActivity.streamIntent(context, version.fileId, version.key)) },
                onDownload = { startDownload(version) },
                onPlayOffline = { uri -> context.startActivity(PlayerActivity.localIntent(context, uri, version.key)) },
                onPause = { vm.pause(version.key) },
                onResume = { vm.resume(version.key) },
            )
        }
    }
}

private val BACKDROP_HEIGHT = 230.dp

@Composable
private fun SectionHeader(text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(22.dp).background(ImdbYellow, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RatingRow(rating: Double, votes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = ImdbYellow, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(6.dp))
        Text(String.format(Locale.US, "%.1f", rating), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("/10", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            "${NumberFormat.getIntegerInstance().format(votes)} votes · TMDB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VersionCard(
    version: VideoVersion,
    download: DownloadEntity?,
    onStream: () -> Unit,
    onDownload: () -> Unit,
    onPlayOffline: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallBadge(version.qualityLabel)
                Spacer(Modifier.width(8.dp))
                val tech = listOfNotNull(version.parsed.source, version.parsed.codec, formatDuration(version.durationSec)).joinToString(" · ")
                Text(tech.ifBlank { version.mimeType }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "${DownloadService.formatBytes(version.sizeBytes)} · ${version.chatTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (version.fileName.isNotBlank()) {
                Text(version.fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (download != null && download.status != DownloadEntity.COMPLETED && download.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = ImdbYellow,
                )
            }
            download?.error?.takeIf { download.status == DownloadEntity.FAILED }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStream, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stream")
                }
                when (download?.status) {
                    DownloadEntity.COMPLETED -> OutlinedButton(onClick = { download.contentUri?.let(onPlayOffline) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ImdbYellow)
                        Spacer(Modifier.width(4.dp))
                        Text("Play offline")
                    }
                    DownloadEntity.QUEUED, DownloadEntity.DOWNLOADING, DownloadEntity.SAVING ->
                        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                            val pct = if (download.totalBytes > 0) (download.downloadedBytes * 100 / download.totalBytes) else 0
                            Text(if (download.status == DownloadEntity.SAVING) "Saving…" else "Pause · $pct%")
                        }
                    DownloadEntity.PAUSED, DownloadEntity.FAILED ->
                        OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (download.status == DownloadEntity.FAILED) "Retry" else "Resume")
                        }
                    else -> OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}
