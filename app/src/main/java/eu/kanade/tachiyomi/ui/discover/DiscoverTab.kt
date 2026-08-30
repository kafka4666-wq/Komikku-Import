package eu.kanade.tachiyomi.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import mihon.domain.manga.model.toDomainManga

private const val MAX_DISCOVER_ENTRIES = 600
private const val MAX_ENTRIES_PER_SOURCE = 30
private const val MAX_PARALLEL_SOURCES = 4
private const val SOURCE_TIMEOUT_MS = 20_000L

data object DiscoverTab : Tab {
    private fun readResolve(): Any = DiscoverTab

    override val options: TabOptions
        @Composable
        get() {
            val selected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 1u,
                title = "Discover",
                icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                    if (selected) Icons.Outlined.Explore else Icons.Outlined.Explore,
                ),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DoujinDiscoverScreen())
    }

    @Composable
    override fun Content() {
        DoujinDiscoverContent()
    }
}

private enum class DiscoverMode(val label: String) {
    LATEST("Latest"),
    POPULAR("Popular"),
}

private data class DiscoverSource(
    val id: Long,
    val name: String,
)

private data class DiscoverEntry(
    val manga: Manga,
    val sourceName: String,
) {
    val key: String get() = "${manga.source}:${manga.url}"
}

private data class DiscoverState(
    val mode: DiscoverMode = DiscoverMode.LATEST,
    val items: List<DiscoverEntry> = emptyList(),
    val availableSources: List<DiscoverSource> = emptyList(),
    val selectedSourceIds: Set<Long> = emptySet(),
    val sourceSelectionInitialized: Boolean = false,
    val isLoading: Boolean = false,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val failedSources: Int = 0,
    val status: String = "Choose Latest or Popular to load all visible installed extensions.",
)

private class DiscoverScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
) : StateScreenModel<DiscoverState>(DiscoverState()) {

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        val selected = state.value.selectedSourceIds.toMutableSet()
        if (enabled) selected += sourceId else selected -= sourceId
        mutableState.update { it.copy(selectedSourceIds = selected, sourceSelectionInitialized = true) }
    }

    fun selectAllSources() {
        mutableState.update {
            it.copy(
                selectedSourceIds = it.availableSources.mapTo(mutableSetOf()) { source -> source.id },
                sourceSelectionInitialized = true,
            )
        }
    }

    fun clearSourceSelection() {
        mutableState.update { it.copy(selectedSourceIds = emptySet(), sourceSelectionInitialized = true) }
    }

    fun refresh(mode: DiscoverMode = state.value.mode, force: Boolean = false) {
        if (!force && state.value.isLoading && mode == state.value.mode) return
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        mutableState.update {
            it.copy(
                mode = mode,
                items = emptyList(),
                isLoading = true,
                completedSources = 0,
                totalSources = 0,
                failedSources = 0,
                status = "Finding installed extensions…",
            )
        }
        refreshJob = screenModelScope.launch {
            try {
                val initialized = withTimeoutOrNull(15_000L) {
                    sourceManager.isInitialized.first { it }
                } == true
                if (!initialized) {
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            status = "Extensions are still initializing. Tap Refresh after the extension list is ready.",
                        )
                    }
                    return@launch
                }
                val sources = withTimeoutOrNull(5_000L) {
                    withContext(Dispatchers.IO) { sourceManager.getVisibleOnlineSources() }
                }.orEmpty()
                val allSources = sources
                val previousState = state.value
                val selectedSources = if (previousState.sourceSelectionInitialized) {
                    allSources.filter { it.id in previousState.selectedSourceIds }
                } else {
                    allSources
                }
                val selectedSourceIds = selectedSources.mapTo(mutableSetOf()) { it.id }
                val sourceNames = selectedSources.associate { it.id to it.name }
                mutableState.update { current ->
                    current.copy(
                        availableSources = allSources.map { DiscoverSource(it.id, it.name) },
                        selectedSourceIds = if (current.sourceSelectionInitialized) current.selectedSourceIds.intersect(allSources.map { it.id }.toSet()) else selectedSourceIds,
                        sourceSelectionInitialized = true,
                    )
                }
                val sourceDispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLEL_SOURCES)
                val reconcileDispatcher = Dispatchers.IO.limitedParallelism(8)
                if (selectedSources.isEmpty()) {
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            completedSources = 0,
                            totalSources = 0,
                            status = if (allSources.isEmpty()) {
                                "No visible online extensions are available. Install or enable an extension, then refresh."
                            } else {
                                "No extensions selected. Choose at least one source, then tap Latest, Popular, or Refresh."
                            },
                        )
                    }
                    return@launch
                }
                mutableState.update {
                    it.copy(
                        totalSources = selectedSources.size,
                        status = "Loading ${mode.label.lowercase()} results… 0/${selectedSources.size} selected extensions checked",
                    )
                }
                val candidates = linkedMapOf<String, DiscoverEntry>()
                val candidatesMutex = Mutex()
                val sourceJobs = selectedSources.map { source ->
                    launch(sourceDispatcher) {
                        var failed = false
                        val remoteManga = try {
                            val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                try {
                                    if (mode == DiscoverMode.LATEST && source.supportsLatest) {
                                        source.getLatestUpdates(1)
                                    } else {
                                        source.getPopularManga(1)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    failed = true
                                    null
                                }
                            }
                            if (page == null) {
                                failed = true
                                emptyList()
                            } else {
                                page.mangas
                                    .take(MAX_ENTRIES_PER_SOURCE)
                                    .map { it.toDomainManga(source.id) }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        }
                        val entriesForSource = remoteManga.map { remoteMangaItem ->
                            async(reconcileDispatcher) {
                                val localManga = try {
                                    withTimeoutOrNull(3_000L) {
                                        try {
                                            mangaRepository.getMangaByUrlAndSourceId(remoteMangaItem.url, remoteMangaItem.source)
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                }
                                if (localManga?.favorite == true) {
                                    null
                                } else {
                                    DiscoverEntry(
                                        manga = localManga ?: remoteMangaItem,
                                        sourceName = sourceNames[remoteMangaItem.source] ?: "Source ${remoteMangaItem.source}",
                                    )
                                }
                            }
                        }.awaitAll().filterNotNull()
                        val newEntries = candidatesMutex.withLock {
                            entriesForSource.filter { entry ->
                                if (candidates.containsKey(entry.key)) {
                                    false
                                } else {
                                    candidates[entry.key] = entry
                                    true
                                }
                            }
                        }
                        if (generation != refreshGeneration) return@launch
                        mutableState.update { current ->
                            val completed = current.completedSources + 1
                            val failedCount = current.failedSources + if (failed) 1 else 0
                            val visibleItems = (current.items + newEntries).take(MAX_DISCOVER_ENTRIES)
                            current.copy(
                                items = visibleItems,
                                completedSources = completed,
                                totalSources = selectedSources.size,
                                failedSources = failedCount,
                                status = "${visibleItems.size} titles found • $completed/${selectedSources.size} selected extensions checked" +
                                    if (failedCount > 0) " • $failedCount failed or timed out" else "",
                            )
                        }
                    }
                }
                sourceJobs.joinAll()
                if (generation != refreshGeneration) return@launch
                mutableState.update { current ->
                    current.copy(
                        mode = mode,
                        isLoading = false,
                        status = when {
                                            current.items.isEmpty() && current.failedSources == current.totalSources -> "No results loaded. Every selected extension timed out or returned an error; tap Refresh to try again."
                            current.items.isEmpty() -> "No new titles found after checking ${current.totalSources} extensions."
                            current.failedSources > 0 -> "${current.items.size} titles from ${current.totalSources - current.failedSources}/${current.totalSources} selected extensions. ${current.failedSources} source(s) timed out or returned an error."
                            else -> "${current.items.size} titles from ${current.totalSources} selected installed extensions. Library items are hidden; results are capped only to keep the UI responsive."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != refreshGeneration) return@launch
                mutableState.update {
                    it.copy(
                        mode = mode,
                        isLoading = false,
                        status = "Could not load extensions: ${error.message?.take(120) ?: "unknown error"}. Tap Refresh to retry.",
                    )
                }
            }
        }
    }
}

class DoujinDiscoverScreen : Screen() {
    @Composable
    override fun Content() {
        DoujinDiscoverContent()
    }
}

@Composable
private fun DoujinDiscoverContent() {
    val navigator = LocalNavigator.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenModel = remember { DiscoverScreenModel() }
    val state: DiscoverState = screenModel.state.collectAsState().value
    val repository = remember { Injekt.get<MangaRepository>() }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAdding by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        screenModel.refresh()
    }

    val selectedEntries = state.items.filter { it.key in selectedKeys }

    fun selectAll() {
        selectedKeys = state.items.asSequence().map { it.key }.toSet()
    }

    fun addSelected() {
        if (selectedEntries.isEmpty() || isAdding) return
        isAdding = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val local = repository.insertNetworkManga(
                        selectedEntries.map { it.manga },
                        updateInfo = false,
                    )
                    val newFavorites = local.filterNot { it.favorite }
                    if (newFavorites.isNotEmpty()) {
                        repository.updateAll(newFavorites.map { MangaUpdate(id = it.id, favorite = true) })
                    }
                    newFavorites.size
                }
            }.onSuccess { count ->
                selectedKeys = emptySet()
                screenModel.refresh(state.mode)
                android.widget.Toast.makeText(context, "Added $count titles to Library", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                android.widget.Toast.makeText(context, "Could not add titles: ${error.message?.take(120) ?: "database error"}", android.widget.Toast.LENGTH_LONG).show()
            }
            isAdding = false
        }
    }

    if (showSourcePicker) {
        AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            title = { Text("Discover sources") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Select the installed extensions to include in this feed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = screenModel::selectAllSources) { Text("Select all") }
                        TextButton(onClick = screenModel::clearSourceSelection) { Text("Clear") }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(state.availableSources, key = { "discover-source-${it.id}" }) { source ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    screenModel.setSourceEnabled(source.id, source.id !in state.selectedSourceIds)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = source.id in state.selectedSourceIds,
                                    onCheckedChange = { enabled ->
                                        screenModel.setSourceEnabled(source.id, enabled)
                                    },
                                )
                                Text(source.name, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSourcePicker = false
                        selectedKeys = emptySet()
                        screenModel.refresh(state.mode, force = true)
                    },
                ) { Text("Apply & refresh") }
            },
            dismissButton = {
                TextButton(onClick = { showSourcePicker = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } },
                actions = {
                    IconButton(onClick = { screenModel.refresh(state.mode) }, enabled = !state.isLoading && !isAdding) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("All installed extensions", style = MaterialTheme.typography.titleMedium)
                    Text("Latest or popular doujins are combined into one feed. Items already in Library are excluded.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !isAdding,
                            onClick = {
                                selectedKeys = emptySet()
                                screenModel.refresh(DiscoverMode.LATEST)
                            },
                        ) { Text(if (state.mode == DiscoverMode.LATEST) "[Latest]" else "Latest") }
                        TextButton(
                            enabled = !isAdding,
                            onClick = {
                                selectedKeys = emptySet()
                                screenModel.refresh(DiscoverMode.POPULAR)
                            },
                        ) { Text(if (state.mode == DiscoverMode.POPULAR) "[Popular]" else "Popular") }
                        TextButton(enabled = state.items.isNotEmpty() && !state.isLoading, onClick = ::selectAll) { Text("Select all") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = state.availableSources.isNotEmpty() && !isAdding,
                            onClick = { showSourcePicker = true },
                        ) {
                            Text("Sources (${state.selectedSourceIds.size}/${state.availableSources.size})")
                        }
                        Text("Choose which extensions to include", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = selectedEntries.isNotEmpty() && !isAdding, onClick = ::addSelected) {
                            Text(if (isAdding) "Adding…" else "Add ${selectedEntries.size} to library")
                        }
                        TextButton(enabled = selectedKeys.isNotEmpty(), onClick = { selectedKeys = emptySet() }) { Text("Clear") }
                    }
                    if (state.isLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(state.status, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text(state.status, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            items(state.items, key = { "discover-${it.manga.source}-${it.manga.url}" }) { entry ->
                val manga = entry.manga
                val selected = entry.key in selectedKeys
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(enabled = manga.id != 0L) {
                        navigator.push(MangaScreen(manga.id))
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                selectedKeys = if (it) selectedKeys + entry.key else selectedKeys - entry.key
                            },
                        )
                        coil3.compose.AsyncImage(
                            model = manga.thumbnailUrl,
                            contentDescription = manga.title,
                            modifier = Modifier.size(72.dp),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(manga.title, style = MaterialTheme.typography.titleSmall)
                            Text(entry.sourceName, style = MaterialTheme.typography.labelMedium)
                            Text(manga.genre.orEmpty().take(4).joinToString(), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
