package com.tgfinder.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgfinder.AppContainer
import com.tgfinder.data.db.DownloadEntity
import com.tgfinder.download.DownloadService
import com.tgfinder.player.PlayerActivity
import com.tgfinder.tmdb.TmdbImages
import com.tgfinder.ui.common.CenteredMessage
import com.tgfinder.ui.common.PosterCard
import com.tgfinder.ui.common.containerViewModel
import com.tgfinder.ui.theme.ImdbYellow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(private val c: AppContainer) : ViewModel() {
    val all: StateFlow<List<DownloadEntity>?> = c.downloads.all.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun pause(key: String) = viewModelScope.launch { c.downloads.pause(key) }
    fun resume(key: String) = viewModelScope.launch { c.downloads.resume(key) }
    fun cancel(key: String) = viewModelScope.launch { c.downloads.cancel(key) }
    fun delete(key: String, onResult: (Boolean) -> Unit) = viewModelScope.launch { onResult(c.downloads.deleteCompleted(key)) }
    fun forget(key: String) = viewModelScope.launch { c.downloads.forget(key) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen() {
    val vm = containerViewModel { DownloadsViewModel(it) }
    val all by vm.all.collectAsState()
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    var cannotDelete by remember { mutableStateOf<DownloadEntity?>(null) }

    val list = all ?: return
    val inProgress = list.filter { it.status != DownloadEntity.COMPLETED }
    val done = list.filter { it.status == DownloadEntity.COMPLETED }

    if (list.isEmpty()) {
        CenteredMessage("No downloads yet", "Open a title and tap Download on any version. Videos are saved to Movies/TGFinder.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp))
        }
        if (inProgress.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("In progress") }
            items(inProgress, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { d ->
                ProgressRow(d, onPause = { vm.pause(d.key) }, onResume = { vm.resume(d.key) }, onCancel = { vm.cancel(d.key) })
            }
        }
        if (done.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Downloaded · tap to play, long-press to delete") }
            items(done, key = { it.key }) { d ->
                PosterCard(
                    title = d.title,
                    subtitle = listOfNotNull(d.subtitle, DownloadService.formatBytes(d.totalBytes)).joinToString(" · "),
                    posterUrl = TmdbImages.poster(d.posterPath),
                    fallback = null,
                    fallbackPath = d.thumbPath?.takeIf { java.io.File(it).exists() },
                    topEndBadge = d.quality?.takeIf { it != "Unknown" },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            val uri = d.contentUri
                            if (uri != null) context.startActivity(PlayerActivity.localIntent(context, uri, d.key))
                        },
                        onLongClick = { toDelete = d },
                    ),
                )
            }
        }
    }

    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete download?") },
            text = { Text("“${d.title}” will be deleted from Movies/TGFinder on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    vm.delete(d.key) { ok ->
                        if (ok) Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show() else cannotDelete = d
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
    cannotDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { cannotDelete = null },
            title = { Text("Couldn't delete the file") },
            text = { Text("Android did not allow TG Finder to delete this file (it may have been saved by an earlier install). Delete it from your Files or Gallery app. Remove it from this list?") },
            confirmButton = { TextButton(onClick = { cannotDelete = null; vm.forget(d.key) }) { Text("Remove from list") } },
            dismissButton = { TextButton(onClick = { cannotDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = ImdbYellow, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun ProgressRow(d: DownloadEntity, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(d.quality, d.chatTitle).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (d.status) {
                    DownloadEntity.QUEUED, DownloadEntity.DOWNLOADING ->
                        IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, contentDescription = "Pause") }
                    DownloadEntity.PAUSED -> IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume") }
                    DownloadEntity.FAILED -> IconButton(onClick = onResume) { Icon(Icons.Filled.Refresh, contentDescription = "Retry") }
                }
                if (d.status != DownloadEntity.SAVING) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                }
            }
            val fraction = if (d.totalBytes > 0) (d.downloadedBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, color = ImdbYellow, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            val statusText = when (d.status) {
                DownloadEntity.QUEUED -> "Waiting…"
                DownloadEntity.PAUSED -> "Paused"
                DownloadEntity.SAVING -> "Saving to Movies/TGFinder…"
                DownloadEntity.FAILED -> d.error ?: "Failed"
                else -> "Downloading"
            }
            Text(
                "$statusText · ${(fraction * 100).toInt()}% · ${DownloadService.formatBytes(d.downloadedBytes)} of ${DownloadService.formatBytes(d.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (d.status == DownloadEntity.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
