package com.tgfinder.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgfinder.AppContainer
import com.tgfinder.data.db.TmdbMatchEntity
import com.tgfinder.search.MediaGroup
import com.tgfinder.search.VideoSearchSession
import com.tgfinder.telegram.ConnectionStatus
import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.telegram.FloodWaitException
import com.tgfinder.tmdb.TmdbAuthException
import com.tgfinder.tmdb.TmdbImages
import com.tgfinder.tmdb.TmdbKeyMissingException
import com.tgfinder.ui.common.Banner
import com.tgfinder.ui.common.BannerKind
import com.tgfinder.ui.common.CenteredMessage
import com.tgfinder.ui.common.PosterCard
import com.tgfinder.ui.common.containerViewModel
import com.tgfinder.ui.theme.ImdbYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ResultItem(val group: MediaGroup, val match: TmdbMatchEntity?, val matching: Boolean)

data class SearchUiState(
    val query: String = "",
    val submitted: String = "",
    val items: List<ResultItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val tmdbError: String? = null,
)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(SearchUiState())
    val connection: StateFlow<ConnectionStatus> = c.td.connection
    val tmdbMissing: StateFlow<Boolean> = c.prefs.config.map { !it.hasTmdb }
        .stateIn(viewModelScope, SharingStarted.Eagerly, !c.prefs.config.value.hasTmdb)

    /** Seconds left of a Telegram flood wait, ticking down; 0 when none. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val floodWaitSeconds: StateFlow<Int> = c.td.floodWaitUntil.flatMapLatest { until ->
        flow {
            while (true) {
                val left = ((until - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
                emit(left)
                if (left == 0) break
                delay(1_000)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var session: VideoSearchSession? = null
    private var job: Job? = null
    private var groups: List<MediaGroup> = emptyList()
    private val matches = HashMap<String, TmdbMatchEntity?>()
    private val matching = HashSet<String>()

    fun onQueryChange(q: String) = state.update { it.copy(query = q) }

    fun search() {
        val q = state.value.query.trim()
        if (q.length < 2) {
            state.update { it.copy(error = "Type at least 2 characters.") }
            return
        }
        job?.cancel()
        val s = VideoSearchSession(c.td, q)
        session = s
        groups = emptyList()
        state.update { it.copy(submitted = q, items = emptyList(), loading = true, loadingMore = false, error = null, hasMore = false) }
        job = viewModelScope.launch { load(s) }
    }

    fun loadMore() {
        val s = session ?: return
        if (job?.isActive == true || !s.hasMore || state.value.error != null) return
        state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch { load(s) }
    }

    fun retry() {
        state.update { it.copy(error = null) }
        if (groups.isEmpty()) search() else loadMore()
    }

    private suspend fun load(s: VideoSearchSession) {
        try {
            // Pages whose videos were all filtered out add nothing to the grid; keep going a little
            // so infinite scroll does not stall, but never loop without bound.
            val before = groups.size
            var pages = 0
            do {
                groups = s.loadMore()
                pages++
            } while (groups.size == before && s.hasMore && pages < 4)
            if (session !== s) return
            publish(s)
            requestMatches(groups)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FloodWaitException) {
            state.update { it.copy(error = ErrorMessages.floodWait(e.seconds)) }
        } catch (e: Exception) {
            state.update { it.copy(error = ErrorMessages.forTelegram(e)) }
        } finally {
            if (session === s) state.update { it.copy(loading = false, loadingMore = false, hasMore = s.hasMore) }
        }
    }

    private fun requestMatches(list: List<MediaGroup>) {
        if (!c.tmdb.hasKey) return
        for (g in list) {
            if (g.key in matches || g.key in matching) continue
            matching += g.key
            viewModelScope.launch {
                try {
                    matches[g.key] = c.tmdb.match(g.parsed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TmdbKeyMissingException) {
                    matches[g.key] = null
                } catch (e: TmdbAuthException) {
                    matches[g.key] = null
                    state.update { it.copy(tmdbError = e.message) }
                } catch (e: IOException) {
                    // Not cached; a later search will retry.
                    state.update { it.copy(tmdbError = "Could not reach TMDB: ${ErrorMessages.forNetwork(e)}") }
                } catch (e: Exception) {
                    matches[g.key] = null
                } finally {
                    matching -= g.key
                    session?.let { publish(it) }
                }
            }
        }
    }

    private fun publish(s: VideoSearchSession) {
        if (session !== s) return
        state.update { st ->
            st.copy(items = groups.map { ResultItem(it, matches[it.key], it.key in matching) }, hasMore = s.hasMore)
        }
    }

    fun onOpen(item: ResultItem) = c.session.put(item.group, item.match)
}

@Composable
fun SearchScreen(onOpenDetail: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = containerViewModel { SearchViewModel(it) }
    val state by vm.state.collectAsState()
    val flood by vm.floodWaitSeconds.collectAsState()
    val connection by vm.connection.collectAsState()
    val tmdbMissing by vm.tmdbMissing.collectAsState()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()

    LaunchedEffect(grid) {
        snapshotFlow {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TG Finder",
                color = ImdbYellow,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Search movies & series in your chats") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
            enabled = flood == 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connection == ConnectionStatus.WAITING_FOR_NETWORK) Banner(ErrorMessages.NO_INTERNET, BannerKind.ERROR)
            if (flood > 0) Banner(ErrorMessages.floodWait(flood), BannerKind.WARNING)
            if (tmdbMissing) {
                Box(Modifier.clickable(onClick = onOpenSettings)) { Banner(ErrorMessages.TMDB_KEY_MISSING, BannerKind.WARNING) }
            } else {
                state.tmdbError?.let { Banner(it, BannerKind.WARNING) }
            }
        }

        when {
            state.loading && state.items.isEmpty() -> CenteredMessage("Searching…", "Looking through all your chats for “${state.submitted}”.") {
                CircularProgressIndicator(color = ImdbYellow)
            }
            state.error != null && state.items.isEmpty() -> CenteredMessage("Search failed", state.error) {
                if (flood == 0) OutlinedButton(onClick = vm::retry) { Text("Try again") }
            }
            state.submitted.isEmpty() -> CenteredMessage(
                "Find videos in your Telegram",
                "Search every channel and group you have joined. Only videos and video files are shown; " +
                    "protected content is skipped.",
            )
            state.items.isEmpty() -> CenteredMessage("No videos found", "Nothing matched “${state.submitted}”. Try a shorter title or different spelling.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                state = grid,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.group.key }) { item ->
                    val m = item.match
                    val parsed = item.group.parsed
                    PosterCard(
                        title = m?.title ?: item.group.displayTitle,
                        subtitle = listOfNotNull((m?.year ?: parsed.year)?.toString(), parsed.episodeLabel).joinToString(" · "),
                        posterUrl = TmdbImages.poster(m?.posterPath),
                        fallback = item.group.bestThumb,
                        rating = m?.rating,
                        topEndBadge = item.group.versions.size.takeIf { it > 1 }?.let { "$it versions" }
                            ?: item.group.versions.first().qualityLabel.takeIf { it != "Unknown" },
                        bottomStartBadge = parsed.episodeLabel,
                        loading = item.matching,
                        modifier = Modifier.clickable {
                            vm.onOpen(item)
                            onOpenDetail(item.group.key)
                        },
                    )
                }
                if (state.loadingMore || (state.hasMore && state.error == null)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = ImdbYellow, strokeWidth = 2.dp)
                        }
                    }
                }
                if (state.error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Banner(state.error!!, BannerKind.ERROR)
                            if (flood == 0) OutlinedButton(onClick = vm::retry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}
