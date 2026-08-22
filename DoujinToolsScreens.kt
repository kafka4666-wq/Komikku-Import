package eu.kanade.presentation.more

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.domain.ui.DoujinFeatureEngine
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.GetRemoteManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

private const val PAGE_SIZE = 100

class DoujinToolsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val context = LocalContext.current
        val repository = remember { Injekt.get<MangaRepository>() }
        val scope = rememberCoroutineScope()
        var message by remember { mutableStateOf("All actions are bounded, local-first, and review-only by default.") }
        var loading by remember { mutableStateOf(false) }
        var duplicateGroups by remember { mutableStateOf<List<List<DoujinFeatureEngine.DuplicateCandidate>>>(emptyList()) }
        var metadataIssues by remember { mutableStateOf<List<DoujinFeatureEngine.MetadataIssue>>(emptyList()) }
        var galleryIssues by remember { mutableStateOf<List<DoujinFeatureEngine.GalleryIssue>>(emptyList()) }
        var random by remember { mutableStateOf<Manga?>(null) }
        var repairQueue by remember { mutableStateOf(DoujinFeatureEngine.metadataRepairQueue(context)) }
        var offset by remember { mutableStateOf(0) }

        fun launchBounded(block: suspend () -> Unit) {
            if (loading) return
            scope.launch {
                loading = true
                try { block() } catch (error: Exception) { message = "Stopped safely: ${error.message ?: "operation failed"}" }
                finally { loading = false }
            }
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text("Doujin Tools") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("A compact doujin catalog layer for very large libraries. No scanner deletes, merges, overwrites personal data, or changes WebDAV data automatically.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                item { ToolCard("Discovery workspace", "Paged cover-first browsing with latest, popular, random, recommended, fuzzy, tag, creator, source, and masonry modes.") { Button(onClick = { navigator.push(DoujinDiscoveryScreen()) }) { Text("Open Discovery") } } }
                item { ToolCard("Duplicate Scanner", "Find real library entries using source link, normalized/alternate titles, creator, tags, and metadata similarity.") {
                    Button(enabled = !loading, onClick = { launchBounded {
                        val entries = withContext(Dispatchers.IO) { repository.getDuplicateLibraryEntries(500) }
                        val weights = DoujinFeatureEngine.parseTagWeights("")
                        duplicateGroups = entries.groupBy { DoujinFeatureEngine.titleKey(it) }.values.filter { it.size > 1 }.map { group ->
                            group.map { manga ->
                                val peer = group.firstOrNull { it.id != manga.id }
                                val scored = peer?.let { DoujinFeatureEngine.duplicateCandidate(manga, it, weights) }
                                DoujinFeatureEngine.DuplicateCandidate(manga, scored?.confidence ?: 100, scored?.signals ?: listOf("same normalized title"))
                            }
                        }.take(200)
                        message = "Found ${duplicateGroups.size} bounded review groups. Compare, keep, ignore, or mark not-duplicate manually."
                    }) { Text(if (loading) "Scanning…" else "Scan library") }
                } }
                items(duplicateGroups) { group ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Possible duplicate group", style = MaterialTheme.typography.titleSmall)
                        group.forEach { candidate ->
                            TextButton(onClick = { navigator.push(MangaScreen(candidate.manga.id)) }) {
                                Text("${candidate.confidence}% · ${candidate.manga.title} · ${candidate.signals.joinToString()}")
                            }
                        }
                        Text("Review only. No automatic delete or merge is performed.", style = MaterialTheme.typography.labelSmall)
                    } }
                }
                item { ToolCard("Gallery Integrity Scanner", "Inspect local gallery files in small batches for missing, zero-byte, unreadable, unsupported, corrupt, and duplicate page files.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !loading, onClick = { launchBounded { galleryIssues = withContext(Dispatchers.IO) { DoujinFeatureEngine.scanLocalGallery(context, 500) }; message = "Found ${galleryIssues.size} reviewable local-file issues." } }) { Text("Scan files") }
                        Button(onClick = { galleryIssues = emptyList(); message = "Issue list cleared; files were not changed." }) { Text("Clear") }
                    }
                } }
                items(galleryIssues) { issue ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("${issue.kind}: ${issue.detail}", style = MaterialTheme.typography.bodySmall); Text(issue.path, style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { DoujinFeatureEngine.ignoreGalleryIssue(context, issue); galleryIssues = galleryIssues - issue }) { Text("Ignore") }
                    }
                }
                item { ToolCard("Metadata Scanner + safe repair queue", "Find incomplete entries, select or queue them for a later explicit source refresh, and preserve notes, personal tags, progress, categories, and favorites.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !loading, onClick = { launchBounded {
                            val page = withContext(Dispatchers.IO) { DoujinFeatureEngine.favoriteWindow(repository, PAGE_SIZE, offset) }
                            metadataIssues = page.map(DoujinFeatureEngine::metadataIssues).filter { it.missing.isNotEmpty() }
                            message = "Found ${metadataIssues.size} incomplete entries in window offset $offset."
                        } }) { Text("Scan window") }
                        Button(enabled = !loading, onClick = { offset += PAGE_SIZE; message = "Next bounded window: offset $offset" }) { Text("Next window") }
                        Button(enabled = metadataIssues.isNotEmpty(), onClick = { DoujinFeatureEngine.requestMetadataRepair(context, metadataIssues.map { it.manga.id }); repairQueue = DoujinFeatureEngine.metadataRepairQueue(context); message = "Queued ${metadataIssues.size} entries for explicit repair review." }) { Text("Queue selected") }
                        TextButton(enabled = repairQueue.isNotEmpty(), onClick = { DoujinFeatureEngine.clearMetadataRepairQueue(context); repairQueue = emptySet(); message = "Repair queue cleared; no metadata was changed." }) { Text("Clear queue") }
                    }
                    Text("Repair queue: ${repairQueue.size} entries. Applying a repair must remain an explicit, user-confirmed source refresh.", style = MaterialTheme.typography.labelSmall)
                } }
                items(metadataIssues) { issue ->
                    TextButton(onClick = { navigator.push(MangaScreen(issue.manga.id)) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("${issue.manga.title}: missing ${issue.missing.joinToString()}") }
                }
                item { ToolCard("Smart Random", "Pick from a bounded window with tag weighting and minimum/maximum constraints represented by the current local metadata.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !loading, onClick = { launchBounded { val page = withContext(Dispatchers.IO) { DoujinFeatureEngine.favoriteWindow(repository, PAGE_SIZE, offset) }; random = DoujinFeatureEngine.selectRandom(page, weights = DoujinFeatureEngine.parseTagWeights("")); message = "Random selection uses a bounded window and never loads the whole library." } }) { Text("Roll") }
                        random?.let { TextButton(onClick = { navigator.push(MangaScreen(it.id)) }) { Text(it.title.take(32)) } }
                    }
                } }
                item { ToolCard("More complete workflows", "Open creators, similarity, source-agnostic grouping, personal tags, tag weights, bookmarks, and the reading heatmap.") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { navigator.push(DoujinCreatorScreen()) }) { Text("Artists / circles / groups") }
                        Button(onClick = { navigator.push(DoujinSourceGroupsScreen()) }) { Text("Source-agnostic work groups") }
                        Button(onClick = { navigator.push(DoujinPersonalTagsScreen()) }) { Text("Personal tags") }
                        Button(onClick = { navigator.push(DoujinTagWeightsScreen()) }) { Text("Tag preferences") }
                        Button(onClick = { navigator.push(DoujinBookmarksScreen()) }) { Text("Page bookmarks") }
                        Button(onClick = { navigator.push(DoujinHeatmapScreen()) }) { Text("Reading heatmap") }
                    }
                } }
                item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)); Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

class DoujinDiscoveryScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val context = LocalContext.current
        val repository = remember { Injekt.get<MangaRepository>() }
        val prefs = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var include by remember { mutableStateOf(prefs.includeTags().get()) }
        var exclude by remember { mutableStateOf(prefs.excludeTags().get()) }
        var optional by remember { mutableStateOf("") }
        var source by remember { mutableStateOf("") }
        var exact by remember { mutableStateOf(prefs.exactTagMatching().get()) }
        var mode by remember { mutableStateOf(prefs.discoveryMode().get()) }
        var results by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var offset by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Discovery is local, paged, deduplicated, and source-filtered locally when needed.") }
        var sidePanel by remember { mutableStateOf<Manga?>(null) }
        var combinationName by remember { mutableStateOf("") }
        val weights = DoujinFeatureEngine.parseTagWeights(prefs.tagWeights().get())
        var saved by remember { mutableStateOf(DoujinFeatureEngine.savedTagCombinations(context)) }

        fun load(reset: Boolean) {
            if (loading) return
            scope.launch {
                loading = true
                try {
                    val pageOffset = if (reset) 0 else offset
                    val page = withContext(Dispatchers.IO) { DoujinFeatureEngine.favoriteWindow(repository, 100, pageOffset) }
                    val seen = DoujinFeatureEngine.seenIds(context)
                    val required = DoujinFeatureEngine.parseTags(include)
                    val filtered = page.asSequence()
                        .filter { source.isBlank() || it.source.toString() == source.trim() }
                        .filter { DoujinFeatureEngine.tagExpressionMatches(it, include, exclude, exact, optional, DoujinFeatureEngine.personalTags(context, it.id)) }
                        .filter { query.isBlank() || DoujinFeatureEngine.fuzzyScore(query, it) >= 0.32f }
                        .filter { it.id !in seen || !prefs.rememberSeen().get() }
                        .sortedByDescending { when (mode) { "oldest" -> -it.dateAdded; "alphabetical" -> -DoujinFeatureEngine.normalize(it.title).hashCode(); "random" -> kotlin.random.Random.nextInt(); else -> DoujinFeatureEngine.discoveryScore(it, query, required, weights) } }
                        .toList()
                    results = if (reset) filtered.distinctBy { it.id }.take(300) else (results + filtered).distinctBy { it.id }.take(300)
                    offset = pageOffset + page.size
                    status = "Loaded ${filtered.size} local results from window $pageOffset. ${if (source.isBlank()) "Remote source filtering is not claimed." else "Source filter: $source"}"
                } catch (error: Exception) { status = "Discovery stopped safely: ${error.message ?: "database error"}" }
                finally { loading = false }
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Doujin Discovery") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Latest · Popular · Updated · Random · Rated · Added · Recommended", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Fuzzy title, alternate title, or creator") })
                    OutlinedTextField(include, { include = it }, Modifier.fillMaxWidth(), label = { Text("Required tags: A + B") })
                    OutlinedTextField(optional, { optional = it }, Modifier.fillMaxWidth(), label = { Text("Optional tags") })
                    OutlinedTextField(exclude, { exclude = it }, Modifier.fillMaxWidth(), label = { Text("Excluded tags: C + D") })
                    OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), label = { Text("Source ID (optional; native feed uses this)") })
                    val nativeSourceId = source.trim().toLongOrNull()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { exact = !exact }) { Text(if (exact) "Exact tags" else "Partial tags") }
                        TextButton(onClick = { mode = when (mode) { "latest" -> "popular"; "popular" -> "updated"; "updated" -> "rated"; "rated" -> "added"; "added" -> "random"; "random" -> "recommended"; else -> "latest" } }) { Text("Mode: $mode") }
                        Button(enabled = !loading, onClick = { offset = 0; results = emptyList(); load(true) }) { Text("Refresh") }
                        Button(enabled = !loading, onClick = { load(false) }) { Text("Next page") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = nativeSourceId != null, onClick = { nativeSourceId?.let { navigator.push(BrowseSourceScreen(it, GetRemoteManga.QUERY_LATEST)) } }) { Text("Native latest") }
                        TextButton(enabled = nativeSourceId != null, onClick = { nativeSourceId?.let { navigator.push(BrowseSourceScreen(it, GetRemoteManga.QUERY_POPULAR)) } }) { Text("Native popular") }
                        TextButton(enabled = nativeSourceId != null && query.isNotBlank(), onClick = { nativeSourceId?.let { navigator.push(BrowseSourceScreen(it, query.trim())) } }) { Text("Native search") }
                    }
                    OutlinedTextField(combinationName, { combinationName = it }, Modifier.fillMaxWidth(), label = { Text("Name this tag combination") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = combinationName.isNotBlank(), onClick = { DoujinFeatureEngine.saveTagCombination(context, DoujinFeatureEngine.SavedTagCombination(combinationName, include, exclude, optional, source.ifBlank { "all" })); saved = DoujinFeatureEngine.savedTagCombinations(context); combinationName = "" }) { Text("Save combination") }
                        if (saved.isNotEmpty()) TextButton(onClick = { val current = saved.last(); include = current.include; exclude = current.exclude; optional = current.optional; source = current.source.takeUnless { it == "all" }.orEmpty() }) { Text("Load latest") }
                    }
                    if (saved.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { saved.take(6).forEach { combination -> TextButton(onClick = { include = combination.include; exclude = combination.exclude; optional = combination.optional; source = combination.source.takeUnless { it == "all" }.orEmpty() }) { Text(combination.name) }; TextButton(onClick = { DoujinFeatureEngine.deleteTagCombination(context, combination.name); saved = DoujinFeatureEngine.savedTagCombinations(context) }) { Text("×") } } }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                } }
                if (prefs.masonryLayout().get()) {
                    val columns = prefs.masonryColumns().get().toIntOrNull()?.coerceIn(2, 5) ?: 3
                    items(results.chunked(columns)) { row -> Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { manga -> DiscoveryCard(manga, Modifier.weight(1f), onOpen = { DoujinFeatureEngine.rememberSeen(context, listOf(manga.id)); navigator.push(MangaScreen(manga.id)) }, onInfo = { sidePanel = manga }) }; repeat(columns - row.size) { Column(Modifier.weight(1f)) {} } } }
                } else {
                    items(results, key = { it.id }) { manga -> DiscoveryCard(manga, Modifier.fillMaxWidth().padding(horizontal = 16.dp), onOpen = { DoujinFeatureEngine.rememberSeen(context, listOf(manga.id)); navigator.push(MangaScreen(manga.id)) }, onInfo = { sidePanel = manga }) }
                }
                item { Text("The feed keeps only a bounded result window in memory and preserves the current screen state while loading another page.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall) }
            }
        }
        sidePanel?.let { manga -> MetadataPanel(manga, context, onDismiss = { sidePanel = null }, onCreator = { sidePanel = null; navigator.push(DoujinCreatorScreen(DoujinFeatureEngine.creatorName(manga))) }) }
    }
}

class DoujinCreatorScreen(private val creatorQuery: String = "") : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val repository = remember { Injekt.get<MangaRepository>() }
        val scope = rememberCoroutineScope()
        var creator by remember { mutableStateOf(creatorQuery) }
        var entries by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var creatorOffset by remember { mutableStateOf(0) }
        var sort by remember { mutableStateOf("newest") }
        var status by remember { mutableStateOf("Enter an artist, circle, or group; results remain bounded.") }
        fun search(reset: Boolean = false) { scope.launch { val pageOffset = if (reset) 0 else creatorOffset; val page = withContext(Dispatchers.IO) { DoujinFeatureEngine.favoriteWindow(repository, 200, pageOffset) }; val filtered = page.filter { creator.isBlank() || DoujinFeatureEngine.creatorKey(it).contains(DoujinFeatureEngine.normalize(creator)) }.let { list -> when (sort) { "oldest" -> list.sortedBy { it.dateAdded }; "alphabetical" -> list.sortedBy { it.title.lowercase() }; "pages" -> list.sortedByDescending { it.lastUpdate }; else -> list.sortedByDescending { it.lastUpdate } } }; entries = if (reset) filtered else (entries + filtered).distinctBy { it.id }.take(600); creatorOffset = pageOffset + page.size; status = "${entries.size} works loaded across bounded windows; no full-library materialization is used." } }
        Scaffold(topBar = { TopAppBar(title = { Text("Artists / Circles / Groups") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding ->
                                LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(creator, { creator = it }, Modifier.fillMaxWidth(), label = { Text("Creator / circle / group") }); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { sort = when (sort) { "newest" -> "oldest"; "oldest" -> "popular"; "popular" -> "pages"; "pages" -> "alphabetical"; else -> "newest" }; search(true) }) { Text("Sort: $sort") }; Button(onClick = { search(true) }) { Text("Find works") }; Button(onClick = { search(false) }) { Text("Next window") } }; Text(status, style = MaterialTheme.typography.bodySmall) } }

                items(entries, key = { it.id }) { manga -> ResultRow(manga, onClick = { navigator.push(MangaScreen(manga.id)) }) }
            }
        }
    }
}

class DoujinSimilarityScreen(private val seedId: Long) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val repository = remember { Injekt.get<MangaRepository>() }
        var seed by remember { mutableStateOf<Manga?>(null) }
        var results by remember { mutableStateOf<List<Pair<Manga, Int>>>(emptyList()) }
        var message by remember { mutableStateOf("Comparing bounded local metadata…") }
        LaunchedEffect(seedId) { runCatching { val current = withContext(Dispatchers.IO) { repository.getMangaById(seedId) }; val page = withContext(Dispatchers.IO) { DoujinFeatureEngine.favoriteWindow(repository, 200, 0) }; seed = current; results = page.asSequence().filter { it.id != current.id }.map { it to (DoujinFeatureEngine.similarityScore(current, it) * 100).toInt() }.sortedByDescending { it.second }.take(40).toList(); message = "${results.size} ranked local matches; no network is required." }.onFailure { message = "Similarity stopped safely: ${it.message}" } }
        Scaffold(topBar = { TopAppBar(title = { Text("Find Similar") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(6.dp)) { item { Text("${seed?.title ?: "Loading…"}\n$message", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }; items(results, key = { it.first.id }) { (manga, score) -> ResultRow(manga, "Similarity: $score%", onClick = { navigator.push(MangaScreen(manga.id)) }) } }
        }
    }
}

class DoujinSourceGroupsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val context = LocalContext.current
        val repository = remember { Injekt.get<MangaRepository>() }
        var groups by remember { mutableStateOf<List<DoujinFeatureEngine.SourceWorkGroup>>(emptyList()) }
        var message by remember { mutableStateOf("Grouping bounded library window by normalized title…") }
        LaunchedEffect(Unit) { runCatching { val page = withContext(Dispatchers.IO) { DoujinFeatureEngine.favoriteWindow(repository, 300, 0) }; groups = DoujinFeatureEngine.sourceWorkGroups(page, context); message = "${groups.size} multi-source logical work groups. Preferred-source selection is stored locally." }.onFailure { message = "Grouping stopped safely: ${it.message}" } }
        Scaffold(topBar = { TopAppBar(title = { Text("Source-Agnostic Works") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("$message\nEntries remain separate internally; nothing is merged automatically.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }; items(groups) { group -> Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Column(Modifier.padding(12.dp)) { Text(group.title, style = MaterialTheme.typography.titleSmall); group.entries.forEach { entry -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { navigator.push(MangaScreen(entry.id)) }) { Text("Source ${entry.source} · open") }; TextButton(onClick = { DoujinFeatureEngine.setPreferredSource(context, group.entries.first().id, entry.source) }) { Text(if (group.preferredId == entry.source) "Preferred" else "Prefer") } } } } } } }
        }
    }
}

class DoujinPersonalTagsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val context = LocalContext.current
        var names by remember { mutableStateOf(DoujinFeatureEngine.personalTagNames(context)) }
        var newTag by remember { mutableStateOf("") }
        var mangaId by remember { mutableStateOf("") }
        var selectedTag by remember { mutableStateOf("") }
        var renameFrom by remember { mutableStateOf("") }
        var renameTo by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf("") }
        var bulkIds by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("Personal tags are offline and separate from source metadata.") }
        Scaffold(topBar = { TopAppBar(title = { Text("Personal Tags") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding ->
                                LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(message); OutlinedTextField(newTag, { newTag = it }, Modifier.fillMaxWidth(), label = { Text("Create tag") }); Button(onClick = { if (DoujinFeatureEngine.createPersonalTag(context, newTag)) { names = DoujinFeatureEngine.personalTagNames(context); newTag = ""; message = "Tag created." } }) { Text("Create") }; OutlinedTextField(renameFrom, { renameFrom = it }, Modifier.fillMaxWidth(), label = { Text("Rename from") }); OutlinedTextField(renameTo, { renameTo = it }, Modifier.fillMaxWidth(), label = { Text("Rename to") }); Button(onClick = { if (DoujinFeatureEngine.renamePersonalTag(context, renameFrom, renameTo)) { names = DoujinFeatureEngine.personalTagNames(context); renameFrom = ""; renameTo = ""; message = "Tag renamed without changing source metadata." } }) { Text("Rename") }; OutlinedTextField(mangaId, { mangaId = it }, Modifier.fillMaxWidth(), label = { Text("Manga ID for assignment") }); OutlinedTextField(selectedTag, { selectedTag = it }, Modifier.fillMaxWidth(), label = { Text("Tag to assign/remove") }); Button(onClick = { val id = mangaId.toLongOrNull(); if (id != null && selectedTag.isNotBlank()) { val added = DoujinFeatureEngine.togglePersonalTag(context, id, selectedTag); message = if (added) "Assigned ${DoujinFeatureEngine.normalize(selectedTag)} to $id." else "Removed ${DoujinFeatureEngine.normalize(selectedTag)} from $id." } }) { Text("Toggle assignment") }; OutlinedTextField(bulkIds, { bulkIds = it }, Modifier.fillMaxWidth(), label = { Text("Bulk manga IDs (comma-separated, max 100)") }); Button(onClick = { val ids = bulkIds.split(',', ' ', '\n').mapNotNull { it.toLongOrNull() }.distinct().take(100); if (ids.isNotEmpty() && selectedTag.isNotBlank()) { ids.forEach { id -> DoujinFeatureEngine.setPersonalTags(context, id, DoujinFeatureEngine.personalTags(context, id) + DoujinFeatureEngine.normalize(selectedTag)) }; message = "Assigned the tag to ${ids.size} entries." } }) { Text("Bulk assign") }; OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter tag list") }); Text("Available tags", style = MaterialTheme.typography.titleSmall) }; names.filter { filter.isBlank() || it.contains(filter, true) }.forEach { name -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); TextButton(onClick = { DoujinFeatureEngine.deletePersonalTag(context, name); names = DoujinFeatureEngine.personalTagNames(context) }) { Text("Delete") } } } } }

        }
    }
}

class DoujinTagWeightsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val prefs = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
        var weights by remember { mutableStateOf(prefs.tagWeights().get()) }
        var message by remember { mutableStateOf("Use tag=weight entries from -10 to +10; weights affect discovery ranking only.") }
        Scaffold(topBar = { TopAppBar(title = { Text("Tag Preferences") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(message); OutlinedTextField(weights, { weights = it }, Modifier.fillMaxWidth(), label = { Text("best art=5,reference=2,avoid=-5") }); Button(onClick = { prefs.tagWeights().set(weights); message = "Saved locally; source metadata was not changed." }) { Text("Save weights") }; Button(onClick = { weights = ""; prefs.tagWeights().set(""); message = "Tag weights reset." }) { Text("Reset") } } }
    }
}

class DoujinBookmarksScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val context = LocalContext.current
        var query by remember { mutableStateOf("") }
        var bookmarks by remember { mutableStateOf(DoujinFeatureEngine.bookmarks(context)) }
        Scaffold(topBar = { TopAppBar(title = { Text("Page Bookmarks") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(6.dp)) { item { OutlinedTextField(query, { query = it; bookmarks = DoujinFeatureEngine.bookmarks(context, query = it) }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Search notes or page") }) }; items(bookmarks) { bookmark -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { navigator.push(MangaScreen(bookmark.mangaId)) }) { Text("Page ${bookmark.page}${if (bookmark.note.isBlank()) "" else " · ${bookmark.note}"}") }; TextButton(onClick = { DoujinFeatureEngine.deleteBookmark(context, bookmark); bookmarks = DoujinFeatureEngine.bookmarks(context, query = query) }) { Text("Delete") } } } } }
        }
    }
}

class DoujinHeatmapScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current ?: return
        val context = LocalContext.current
        var stats by remember { mutableStateOf(DoujinFeatureEngine.heatmapStats(context)) }
        var period by remember { mutableStateOf("day") }
        Scaffold(topBar = { TopAppBar(title = { Text("Reading Activity") }, navigationIcon = { TextButton(onClick = navigator::pop) { Text("Back") } }) }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Local GitHub-style activity summary", style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("day", "week", "month", "year").forEach { value -> TextButton(onClick = { period = value }) { Text(if (period == value) "[$value]" else value) } } }; Text("${period.replaceFirstChar { it.uppercase() }} activity: ${if (period == "day") stats.byDay[LocalDate.now().toString()] ?: 0 else DoujinFeatureEngine.periodActivity(context, period)} pages"); Text("Total activity: ${stats.total}"); Text("Active days: ${stats.activeDays}"); Text("Current streak: ${stats.currentStreak} days"); Text("Longest streak: ${stats.longestStreak} days"); Button(onClick = { DoujinFeatureEngine.markReading(context); stats = DoujinFeatureEngine.heatmapStats(context) }) { Text("Record today") }; Text("The compact view stores up to one year of local daily counters, keeps personal activity on-device, and exposes day/week/month/year aggregation.", style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun DiscoveryCard(manga: Manga, modifier: Modifier, onOpen: () -> Unit, onInfo: () -> Unit) {
    Card(modifier.clickable(onClick = onOpen)) { Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { AsyncImage(model = manga.thumbnailUrl, contentDescription = manga.title, modifier = Modifier.fillMaxWidth().size(120.dp)); Text(manga.title, maxLines = 2, style = MaterialTheme.typography.titleSmall); Text("${DoujinFeatureEngine.creatorName(manga)} · ${manga.genre.orEmpty().take(3).joinToString()}", maxLines = 2, style = MaterialTheme.typography.labelSmall); TextButton(onClick = onInfo) { Text("Metadata") } } }
}

@Composable
private fun ResultRow(manga: Manga, suffix: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { AsyncImage(model = manga.thumbnailUrl, contentDescription = manga.title, modifier = Modifier.size(60.dp)); Column(Modifier.weight(1f)) { Text(manga.title, style = MaterialTheme.typography.titleSmall); Text("${DoujinFeatureEngine.creatorName(manga)}${if (suffix.isBlank()) "" else " · $suffix"}", style = MaterialTheme.typography.bodySmall); Text(manga.genre.orEmpty().take(5).joinToString(), style = MaterialTheme.typography.labelSmall) } }
}

@Composable
private fun MetadataPanel(manga: Manga, context: Context, onDismiss: () -> Unit, onCreator: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Metadata") }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("${manga.title}\nSource: ${manga.source}\nURL: ${manga.url}"); Text("Artist / circle: ${DoujinFeatureEngine.creatorName(manga)}"); Text("Tags: ${manga.genre.orEmpty().joinToString()}"); Text("Personal tags: ${DoujinFeatureEngine.personalTags(context, manga.id).joinToString().ifBlank { "none" }}"); Text("Reading state and page data remain in Komikku’s existing reader/database.") } }, confirmButton = { TextButton(onClick = onCreator) { Text("Open creator") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun ToolCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall); content() } }
}
