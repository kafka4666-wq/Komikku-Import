package eu.kanade.presentation.more

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.DoujinFeatureEngine
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TagLibraryScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()
        val repository = remember { Injekt.get<MangaRepository>() }
        var tag by remember { mutableStateOf("") }
        var results by remember { mutableStateOf(emptyList<Manga>()) }
        var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
        var selectedTab by remember { mutableStateOf(0) }
        var hasSearched by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var removing by remember { mutableStateOf(false) }
        var showRemoveConfirmation by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }

        fun searchTag() {
            val query = DoujinFeatureEngine.normalize(tag)
            if (query.isBlank() || loading) return
            loading = true
            hasSearched = true
            results = emptyList()
            selectedIds = emptySet()
            selectedTab = 0
            status = "Searching your library…"
            scope.launch {
                val response = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.getFavorites().filter { manga ->
                            manga.genre.orEmpty().any { DoujinFeatureEngine.normalize(it) == query } ||
                                DoujinFeatureEngine.personalTags(context, manga.id).any {
                                    DoujinFeatureEngine.normalize(it) == query
                                }
                        }.sortedBy { it.title.lowercase() }
                    }
                }
                loading = false
                response.onSuccess { matches ->
                    results = matches
                    status = if (matches.isEmpty()) {
                        "No library books matched the tag “$tag”."
                    } else {
                        "${matches.size} book${if (matches.size == 1) "" else "s"} matched “$tag”."
                    }
                }.onFailure { error ->
                    results = emptyList()
                    status = "Tag search failed: ${error.message ?: "library unavailable"}"
                }
            }
        }

        fun toggleSelection(mangaId: Long) {
            selectedIds = if (mangaId in selectedIds) selectedIds - mangaId else selectedIds + mangaId
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Tag Library") },
                    navigationIcon = {
                        TextButton(onClick = { navigator?.pop() }) { Text("Back") }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Search the tags on books in your library. Matches include source tags and your personal tags.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Tag (for example, yaoi)") },
                        singleLine = true,
                        enabled = !loading,
                    )
                    Button(onClick = ::searchTag, enabled = tag.isNotBlank() && !loading) {
                        Text(if (loading) "Searching" else "Search")
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (hasSearched) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Results (${results.size})") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Selected (${selectedIds.size})") },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedTab == 0) {
                            OutlinedButton(
                                enabled = results.isNotEmpty() && !loading && !removing,
                                onClick = {
                                    val allIds = results.mapTo(linkedSetOf()) { it.id }
                                    selectedIds = if (selectedIds.containsAll(allIds)) emptySet() else allIds
                                },
                            ) { Text(if (results.isNotEmpty() && selectedIds.containsAll(results.map { it.id })) "Clear selection" else "Select all") }
                        } else {
                            Text("${selectedIds.size} selected", modifier = Modifier.weight(1f))
                        }
                        if (selectedTab == 0) Spacer(Modifier.weight(1f))
                        Button(
                            enabled = selectedIds.isNotEmpty() && !removing,
                            onClick = { showRemoveConfirmation = true },
                        ) {
                            Text(if (removing) "Removing…" else "Remove from library (${selectedIds.size})")
                        }
                    }
                    val visibleResults = if (selectedTab == 0) results else results.filter { it.id in selectedIds }
                    if (visibleResults.isEmpty()) {
                        Text(
                            if (selectedTab == 1) "Select books from Results to see them here." else "No books to show.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(visibleResults, key = { it.id }) { manga ->
                                TagLibraryResultRow(
                                    manga = manga,
                                    checked = manga.id in selectedIds,
                                    enabled = !removing,
                                    onToggle = { toggleSelection(manga.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showRemoveConfirmation) {
            val count = selectedIds.size
            AlertDialog(
                onDismissRequest = { if (!removing) showRemoveConfirmation = false },
                title = { Text("Remove selected books from your library?") },
                text = {
                    Text(
                        "This will remove $count selected book${if (count == 1) "" else "s"} from the Library list. Manga records, covers, downloads, reading history, and tags are kept. This does not delete downloaded files.",
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !removing,
                        onClick = {
                            val idsToRemove = selectedIds.toSet()
                            val updates = idsToRemove.map { MangaUpdate(id = it, favorite = false) }
                            removing = true
                            scope.launch {
                                val removed = withContext(Dispatchers.IO) {
                                    runCatching { repository.updateAll(updates) }
                                }
                                removing = false
                                showRemoveConfirmation = false
                                removed.onSuccess { success ->
                                    if (success) {
                                        results = results.filterNot { it.id in idsToRemove }
                                        selectedIds = emptySet()
                                        status = "$count book${if (count == 1) "" else "s"} removed from the library. Downloads and manga records were kept."
                                        Toast.makeText(context, "Removed $count from library", Toast.LENGTH_SHORT).show()
                                    } else {
                                        status = "Could not update the library. No books were removed."
                                    }
                                }.onFailure { error ->
                                    status = "Removal failed: ${error.message ?: "database unavailable"}"
                                }
                            }
                        },
                    ) { Text("Remove from library") }
                },
                dismissButton = {
                    TextButton(enabled = !removing, onClick = { showRemoveConfirmation = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun TagLibraryResultRow(
    manga: Manga,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = MangaCoverData(
                mangaId = manga.id,
                sourceId = manga.source,
                isMangaFavorite = manga.favorite,
                ogUrl = manga.thumbnailUrl,
                lastModified = manga.coverLastModified,
            ),
            contentDescription = "Cover for ${manga.title}",
            modifier = Modifier.size(width = 58.dp, height = 82.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall)
            Text(
                manga.genre.orEmpty().joinToString().ifBlank { "Personal tag" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}
