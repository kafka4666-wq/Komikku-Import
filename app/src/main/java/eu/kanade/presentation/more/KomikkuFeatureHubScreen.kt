package eu.kanade.presentation.more

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.KomikkuFeatureStore
import eu.kanade.domain.ui.KomikkuExtendedFeatureStore
import eu.kanade.domain.ui.KomikkuRuntimeFeatureEngine
import eu.kanade.domain.ui.KomikkuFullFeatureEngine
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.DoujinDiscoveryScreen
import eu.kanade.presentation.more.DoujinToolsScreen
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import exh.ui.batchadd.BatchImportJob
import exh.ui.batchadd.BatchAddScreen
import exh.ui.nhentaidate.NhentaiDateImportScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One bounded, reviewable duplicate candidate from the actual library table. */
data class DuplicateReviewEntry(
    val mangaId: Long,
    val title: String,
    val url: String,
    val sourceId: Long,
)

/** A direct action center for workflows that should not be hidden in Settings. */
class KomikkuFeatureHubScreen(
    private val initialText: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        // This screen can be opened from the root navigator (for example through
        // Android share intents), so it must not require a tab composition local.
        val coroutineScope = rememberCoroutineScope()
        val backPress = LocalBackPress.current
        var input by remember { mutableStateOf(initialText.orEmpty()) }
        var recipeName by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<List<String>?>(null) }
        var showHistory by remember { mutableStateOf(false) }
        var showRecipes by remember { mutableStateOf(false) }
        var showStorage by remember { mutableStateOf(false) }
        var showSavedSearches by remember { mutableStateOf(false) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var showFullFeatureStatus by remember { mutableStateOf(false) }
        var showSourceHealth by remember { mutableStateOf(false) }
        var showRecovery by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        var showStatistics by remember { mutableStateOf(false) }
        var showUndo by remember { mutableStateOf(false) }
        var showCommandPalette by remember { mutableStateOf(false) }
        var showOfflinePolicy by remember { mutableStateOf(false) }
        var duplicateReview by remember { mutableStateOf<List<List<DuplicateReviewEntry>>?>(null) }
        var removalCandidate by remember { mutableStateOf<DuplicateReviewEntry?>(null) }
        var duplicateScanRunning by remember { mutableStateOf(false) }
        var commandQuery by remember { mutableStateOf("") }
        var savedSearchName by remember { mutableStateOf("") }
        var savedSearchQuery by remember { mutableStateOf("") }
        val links = KomikkuFeatureStore.extractLinks(input)
        val failedLinks = remember { KomikkuFeatureStore.lastFailedLinks(context) }
        val history = remember { KomikkuFeatureStore.history(context) }
        val recipes = remember { KomikkuFeatureStore.recipes(context) }

        fun openLibrary() {
            navigator?.pop()
                ?: Toast.makeText(context, "Open Library from the main tabs", Toast.LENGTH_SHORT).show()
        }

        fun scanDuplicateLibrary() {
            if (duplicateScanRunning) return
            duplicateScanRunning = true
            Toast.makeText(context, "Scanning up to $MAX_DUPLICATE_SCAN_ENTRIES library entries…", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val repository = Injekt.get<MangaRepository>()
                        val indexedCandidates = repository
                            .getDuplicateLibraryEntries(limit = MAX_DUPLICATE_GROUPS * 8L)
                        val pagedCandidates = buildList {
                            var offset = 0L
                            while (size < MAX_DUPLICATE_SCAN_ENTRIES) {
                                val page = repository.getFavoriteMangaPage(
                                    limit = DUPLICATE_PAGE_SIZE,
                                    offset = offset,
                                )
                                if (page.isEmpty()) break
                                addAll(page.take(MAX_DUPLICATE_SCAN_ENTRIES - size))
                                if (page.size < DUPLICATE_PAGE_SIZE) break
                                offset += page.size
                            }
                        }
                        (indexedCandidates + pagedCandidates)
                            .asSequence()
                            .distinctBy { it.id }
                            .groupBy { manga ->
                                KomikkuFullFeatureEngine.duplicateFingerprint(
                                    manga.title,
                                    manga.author,
                                    manga.artist,
                                )
                            }
                            .values
                            .asSequence()
                            .filter { it.size > 1 }
                            .sortedByDescending { it.size }
                            .take(MAX_DUPLICATE_GROUPS)
                            .map { group ->
                                group.map { manga ->
                                    DuplicateReviewEntry(
                                        mangaId = manga.id,
                                        title = manga.title.replace(Regex("\\s+"), " ").trim().take(160),
                                        url = manga.url.take(320),
                                        sourceId = manga.source,
                                    )
                                }
                            }
                            .toList()
                    }
                }
                duplicateScanRunning = false
                result.onSuccess { groups ->
                    KomikkuFullFeatureEngine.recordRecovery(context, "duplicate-review", "${groups.size} groups", "opened")
                    duplicateReview = groups
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        "Duplicate scan failed: ${error.message?.take(120) ?: "database unavailable"}",
                        Toast.LENGTH_LONG,
                    ).show()
                    KomikkuFullFeatureEngine.recordRecovery(context, "duplicate-review", "scan-failed", error::class.simpleName.orEmpty())
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Komikku Action Center",
                    navigateUp = { backPress?.invoke() ?: navigator?.pop() },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "New workflows",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = MaterialTheme.padding.small),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Paste links or text") },
                    supportingText = { Text("Nhentai, E-Hentai, ExHentai, and gallery links are detected automatically") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { input = KomikkuFeatureStore.clipboardText(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clipboard")
                    }
                    Button(
                        onClick = { preview = links },
                        enabled = links.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Preview ${links.size}")
                    }
                }
                TextPreferenceWidget(
                    title = "Start clipboard/text import",
                    subtitle = "Validate, deduplicate, and add recognized links in the background",
                    icon = Icons.Outlined.ContentPaste,
                    onPreferenceClick = {
                        if (links.isEmpty()) {
                            Toast.makeText(context, "No supported links found", Toast.LENGTH_SHORT).show()
                        } else {
                            BatchImportJob.start(context.applicationContext, links)
                            KomikkuFeatureStore.recordImport(context, "Clipboard/text import", links.size, 0, 0, "started")
                            Toast.makeText(context, "Started ${links.size} links", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TextPreferenceWidget(
                    title = "Open Import Wizard",
                    subtitle = "Use the full Batch Add screen with file selection and live controls",
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    onPreferenceClick = {
                        if (navigator != null) navigator.push(BatchAddScreen())
                        else Toast.makeText(context, "Navigation is not ready yet", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Nhentai Book Import",
                    subtitle = "Import by date range with tag filtering and background progress",
                    icon = Icons.Outlined.Search,
                    onPreferenceClick = {
                        if (navigator != null) navigator.push(NhentaiDateImportScreen())
                        else Toast.makeText(context, "Navigation is not ready yet", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Doujin Tools",
                    subtitle = "Bounded duplicate, metadata, integrity, random, bookmark, and heatmap workflows",
                    icon = Icons.Outlined.Build,
                    onPreferenceClick = {
                        if (navigator != null) navigator.push(DoujinToolsScreen())
                        else Toast.makeText(context, "Navigation is not ready yet", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Doujin Discovery",
                    subtitle = "Paged fuzzy search, advanced tag combinations, creator grouping, and seen-item memory",
                    icon = Icons.Outlined.Explore,
                    onPreferenceClick = {
                        if (navigator != null) navigator.push(DoujinDiscoveryScreen())
                        else Toast.makeText(context, "Navigation is not ready yet", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Retry failed only",
                    subtitle = if (failedLinks.isEmpty()) "No saved failed links" else "Retry ${failedLinks.size} saved failed links",
                    icon = Icons.Outlined.Refresh,
                    onPreferenceClick = {
                        if (failedLinks.isEmpty()) {
                            Toast.makeText(context, "No failed links to retry", Toast.LENGTH_SHORT).show()
                        } else {
                            BatchImportJob.start(context.applicationContext, failedLinks)
                            KomikkuFeatureStore.recordImport(context, "Retry failed only", failedLinks.size, 0, 0, "started")
                            Toast.makeText(context, "Retry started", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TextPreferenceWidget(
                    title = "Resume current import",
                    subtitle = "Resume the persisted queue without reopening the importer",
                    icon = Icons.Outlined.PlayArrow,
                    onPreferenceClick = {
                        BatchImportJob.resume(context.applicationContext)
                        Toast.makeText(context, "Import resumed", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Save current links as recipe",
                    subtitle = "Reuse this filtered link set later from the Action Center",
                    icon = Icons.Outlined.Save,
                    onPreferenceClick = {
                        val name = recipeName.ifBlank { "Recipe ${history.size + 1}" }
                        if (links.isNotEmpty()) {
                            KomikkuFeatureStore.saveRecipe(context, name, links)
                            recipeName = ""
                            Toast.makeText(context, "Recipe saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Add links before saving a recipe", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Recipe name (optional)") },
                )

                Text(
                    text = "Library and recovery",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = MaterialTheme.padding.medium, bottom = MaterialTheme.padding.small),
                )
                TextPreferenceWidget(
                    title = "Import history",
                    subtitle = "Review recent jobs, counts, and status",
                    icon = Icons.Outlined.History,
                    onPreferenceClick = { showHistory = true },
                )
                TextPreferenceWidget(
                    title = "Saved import recipes",
                    subtitle = if (recipes.isEmpty()) "No recipes saved yet" else "${recipes.size} reusable recipes",
                    icon = Icons.Outlined.Rule,
                    onPreferenceClick = { showRecipes = true },
                )
                TextPreferenceWidget(
                    title = "Smart collections",
                    subtitle = "Open live views for recently added, unread, downloaded, and imported books",
                    icon = Icons.Outlined.LibraryBooks,
                    onPreferenceClick = { openLibrary() },
                )
                TextPreferenceWidget(
                    title = "Cleanup and duplicate review",
                    subtitle = "Review storage, failed covers, and possible duplicates without automatic deletion",
                    icon = Icons.Outlined.CleaningServices,
                    onPreferenceClick = { showStorage = true },
                )
                TextPreferenceWidget(
                    title = "Random unread / reading queue",
                    subtitle = "Jump to the Library and choose what to read next",
                    icon = Icons.Outlined.Shuffle,
                    onPreferenceClick = { openLibrary() },
                )
                TextPreferenceWidget(
                    title = "Export or share current links",
                    subtitle = "Send the detected links to another app as plain text",
                    icon = Icons.Outlined.Share,
                    onPreferenceClick = {
                        KomikkuFeatureStore.shareText(context, "Komikku links", links.joinToString("\n"))
                    },
                )
                TextPreferenceWidget(
                    title = "Storage dashboard",
                    subtitle = "Review app files, cache, and saved failed-link counts",
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = { showStorage = true },
                )
                TextPreferenceWidget(
                    title = "Sync health",
                    subtitle = "Review local activity before opening WebDAV sync settings",
                    icon = Icons.Outlined.Sync,
                    onPreferenceClick = { showHistory = true },
                )
                TextPreferenceWidget(
                    title = "Download and offline actions",
                    subtitle = "Pause, resume, reorder, clear, and inspect the existing download queue",
                    icon = Icons.Outlined.GetApp,
                    onPreferenceClick = { showQueue = true },
                )
                TextPreferenceWidget(
                    title = "Reading statistics",
                    subtitle = "View incremental reading counters without scanning the full library",
                    icon = Icons.Outlined.History,
                    onPreferenceClick = { showStatistics = true },
                )
                TextPreferenceWidget(
                    title = "Undo last local action",
                    subtitle = "Review and reverse the last reversible queue or recovery action",
                    icon = Icons.Outlined.Refresh,
                    onPreferenceClick = { showUndo = true },
                )
                TextPreferenceWidget(
                    title = "Saved reports",
                    subtitle = "Share an import history or storage report for troubleshooting",
                    icon = Icons.Outlined.Description,
                    onPreferenceClick = {
                        val report = buildString {
                            appendLine("Komikku activity report")
                            appendLine(KomikkuFeatureStore.storageSummary(context))
                            history.forEach { appendLine(it) }
                        }
                        KomikkuFeatureStore.shareText(context, "Komikku report", report)
                    },
                )
                TextPreferenceWidget(
                    title = "Compare library entries",
                    subtitle = "Scan real library entries before merging or deleting possible duplicates",
                    icon = Icons.Outlined.Difference,
                    onPreferenceClick = { scanDuplicateLibrary() },
                )
                TextPreferenceWidget(
                    title = "Saved searches and filter presets",
                    subtitle = "Save a reusable title, tag, source, read-state, download, favorite, and date query",
                    icon = Icons.Outlined.Search,
                    onPreferenceClick = { showSavedSearches = true },
                )
                TextPreferenceWidget(
                    title = "Duplicate review",
                    subtitle = "Review possible duplicates without automatic deletion or merging",
                    icon = Icons.Outlined.Difference,
                    onPreferenceClick = { scanDuplicateLibrary() },
                )
                TextPreferenceWidget(
                    title = "Source diagnostics",
                    subtitle = "Manually record source checks and inspect recent health events",
                    icon = Icons.Outlined.Refresh,
                    onPreferenceClick = { showSourceHealth = true },
                )
                TextPreferenceWidget(
                    title = "Offline and local-data safety",
                    subtitle = if (KomikkuFullFeatureEngine.offlineModeEnabled(context)) "Offline mode is enabled; network commands are blocked" else "Online with local cache; tap to configure offline-first behavior",
                    icon = Icons.Outlined.CloudOff,
                    onPreferenceClick = { showOfflinePolicy = true },
                )
                TextPreferenceWidget(
                    title = "Recovery center",
                    subtitle = "Review interrupted operations and safe retry/checkpoint state",
                    icon = Icons.Outlined.Refresh,
                    onPreferenceClick = { showRecovery = true },
                )
                TextPreferenceWidget(
                    title = "Command palette",
                    subtitle = "Search and execute commands: ${KomikkuFullFeatureEngine.commandNames().size} available",
                    icon = Icons.Outlined.PlayArrow,
                    onPreferenceClick = { showCommandPalette = true },
                )
                TextPreferenceWidget(
                    title = "Privacy-safe diagnostics",
                    subtitle = "Copy or share app, memory, queue, source, reader, and recovery status without credentials",
                    icon = Icons.Outlined.Description,
                    onPreferenceClick = { showDiagnostics = true },
                )
                TextPreferenceWidget(
                    title = "Full runtime feature status",
                    subtitle = "Inspect all 47 feature flags, adaptive layout, performance, recovery, and compatibility state",
                    icon = Icons.Outlined.Rule,
                    onPreferenceClick = { showFullFeatureStatus = true },
                )
                TextPreferenceWidget(
                    title = "Import timeline",
                    subtitle = "See recent activity without scanning the full library",
                    icon = Icons.Outlined.ImportExport,
                    onPreferenceClick = { showHistory = true },
                )
            }
        }

        duplicateReview?.let { groups ->
            AlertDialog(
                onDismissRequest = { duplicateReview = null },
                title = {
                    Text("Duplicate review · ${groups.size} groups")
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (groups.isEmpty()) {
                            Text("No duplicate candidates were found in this bounded scan of up to $MAX_DUPLICATE_SCAN_ENTRIES library entries.")
                            Text("The scan compares normalized title, creator, and artist fingerprints. Entries outside the scan window or with substantially different metadata may require another pass after sorting the library.")
                        } else {
                            Text("Found ${groups.size} candidate groups from real favorite/library entries. No entry is removed automatically. Review each row before using Remove from library; that action unfavorites the entry and keeps its manga record, downloads, history, notes, and tags.")
                        }
                        groups.take(12).forEachIndexed { index, group ->
                            Text("Group ${index + 1}", style = MaterialTheme.typography.titleSmall)
                            group.take(4).forEach { entry ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                    Text("Source ${entry.sourceId} · ID ${entry.mangaId}", style = MaterialTheme.typography.bodySmall)
                                    Text(entry.url, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            KomikkuFullFeatureEngine.recordDuplicateDecision(context, "keep", entry.mangaId.toString(), entry.title)
                                            Toast.makeText(context, "Kept in library", Toast.LENGTH_SHORT).show()
                                        }) { Text("Keep") }
                                        TextButton(onClick = {
                                            KomikkuFullFeatureEngine.recordDuplicateDecision(context, "ignore", entry.mangaId.toString(), entry.title)
                                            Toast.makeText(context, "Ignored for this review", Toast.LENGTH_SHORT).show()
                                        }) { Text("Ignore") }
                                        TextButton(onClick = { removalCandidate = entry }) { Text("Remove from library") }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { duplicateReview = null }) { Text("Done") } },
            )
        }
        removalCandidate?.let { entry ->
            AlertDialog(
                onDismissRequest = { removalCandidate = null },
                title = { Text("Remove duplicate from library?") },
                text = { Text("${entry.title} will be unfavorited and removed from the Library list. Its manga record, downloads, reading history, notes, and tags are preserved.") },
                confirmButton = {
                    TextButton(onClick = {
                        val candidate = entry
                        removalCandidate = null
                        coroutineScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    Injekt.get<MangaRepository>().update(MangaUpdate(id = candidate.mangaId, favorite = false))
                                }
                            }.onSuccess {
                                duplicateReview = duplicateReview
                                    ?.map { group -> group.filterNot { it.mangaId == candidate.mangaId } }
                                    ?.filter { it.size > 1 }
                                KomikkuFullFeatureEngine.recordDuplicateDecision(context, "remove-from-library", candidate.mangaId.toString(), candidate.title)
                                Toast.makeText(context, "Removed from Library; data preserved", Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                Toast.makeText(context, "Could not remove entry: ${error.message?.take(100) ?: "database unavailable"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removalCandidate = null }) { Text("Cancel") } },
            )
        }
        preview?.let { items ->
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text("Import preview") },
                text = {
                    Column {
                        Text("Recognized: ${items.size}")
                        Text("Duplicates removed before queueing")
                        Text("Rate limit: one request every four seconds")
                        Text("Permanent failures remain available in recovery")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        BatchImportJob.start(context.applicationContext, items)
                        KomikkuFeatureStore.recordImport(context, "Previewed import", items.size, 0, 0, "started")
                        preview = null
                        Toast.makeText(context, "Import started", Toast.LENGTH_SHORT).show()
                    }) { Text("Start") }
                },
                dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
            )
        }
        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text("Import history") },
                text = {
                    Column {
                        if (history.isEmpty()) Text("No import jobs recorded yet")
                        history.take(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                },
                confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } },
            )
        }
        if (showRecipes) {
            AlertDialog(
                onDismissRequest = { showRecipes = false },
                title = { Text("Saved recipes") },
                text = {
                    Column {
                        if (recipes.isEmpty()) Text("No recipes saved yet")
                        recipes.take(12).forEach { (name, recipeLinks) ->
                            TextPreferenceWidget(
                                title = name,
                                subtitle = "${recipeLinks.size} links",
                                icon = Icons.Outlined.Rule,
                                onPreferenceClick = {
                                    BatchImportJob.start(context.applicationContext, recipeLinks)
                                    KomikkuFeatureStore.recordImport(context, name, recipeLinks.size, 0, 0, "started")
                                    showRecipes = false
                                    Toast.makeText(context, "Recipe started", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showRecipes = false }) { Text("Close") } },
            )
        }
        if (showStorage) {
            AlertDialog(
                onDismissRequest = { showStorage = false },
                title = { Text("Storage and cleanup review") },
                text = { Text(KomikkuFeatureStore.storageSummary(context)) },
                confirmButton = { TextButton(onClick = { showStorage = false }) { Text("Close") } },
            )
        }
        if (showSavedSearches) {
            AlertDialog(
                onDismissRequest = { showSavedSearches = false },
                title = { Text("Saved searches") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Save an executable tokenized query. Tap a saved query to compile it for the supported search workflow.")
                        OutlinedTextField(value = savedSearchName, onValueChange = { savedSearchName = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(value = savedSearchQuery, onValueChange = { savedSearchQuery = it }, label = { Text("Query") }, singleLine = true)
                        KomikkuExtendedFeatureStore.savedSearches(context).take(8).forEach { (name, query) ->
                            TextPreferenceWidget(
                                title = name,
                                subtitle = query,
                                icon = Icons.Outlined.Search,
                                onPreferenceClick = {
                                    savedSearchQuery = query
                                    val tokenCount = KomikkuFullFeatureEngine.compileSavedSearch(query).size
                                    if (navigator != null && query.isNotBlank()) {
                                        showSavedSearches = false
                                        navigator.push(GlobalSearchScreen(query))
                                    } else {
                                        Toast.makeText(context, "Saved query compiled with $tokenCount terms", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        KomikkuFullFeatureEngine.saveSearch(context, savedSearchName, savedSearchQuery)
                        KomikkuExtendedFeatureStore.saveSearch(context, savedSearchName, savedSearchQuery)
                        savedSearchName = ""
                        savedSearchQuery = ""
                        Toast.makeText(context, "Search saved", Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSavedSearches = false }) { Text("Close") } },
            )
        }
        if (showSourceHealth) {
            AlertDialog(
                onDismissRequest = { showSourceHealth = false },
                title = { Text("Source health") },
                text = {
                    Column {
                        Text("Diagnostics are manual and bounded; sources are not continuously pinged.")
                        if (KomikkuExtendedFeatureStore.sourceEvents(context).isEmpty()) Text("No source checks recorded yet")
                        KomikkuExtendedFeatureStore.sourceEvents(context).take(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        KomikkuExtendedFeatureStore.recordSourceEvent(context, "manual", "diagnostic", true)
                        Toast.makeText(context, "Manual diagnostic recorded", Toast.LENGTH_SHORT).show()
                    }) { Text("Record check") }
                },
                dismissButton = { TextButton(onClick = { showSourceHealth = false }) { Text("Close") } },
            )
        }
        if (showRecovery) {
            AlertDialog(
                onDismissRequest = { showRecovery = false },
                title = { Text("Recovery center") },
                text = { Text(KomikkuExtendedFeatureStore.recoverySummary(context)) },
                confirmButton = {
                    TextButton(onClick = {
                        KomikkuExtendedFeatureStore.recordRecovery(context, "manual-review", "User opened recovery center")
                        KomikkuFullFeatureEngine.startupSelfCheck(context)
                        Toast.makeText(context, "Recovery checkpoint preserved", Toast.LENGTH_SHORT).show()
                    }) { Text("Record review") }
                },
                dismissButton = { TextButton(onClick = { showRecovery = false }) { Text("Close") } },
            )
        }
        if (showQueue) {
            val queue = KomikkuRuntimeFeatureEngine.queueSummary(context)
            AlertDialog(
                onDismissRequest = { showQueue = false },
                title = { Text("Download queue") },
                text = { Text(queue.asText()) },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { KomikkuRuntimeFeatureEngine.resumeDownloads(context) }) { Text("Resume") }
                        TextButton(onClick = { KomikkuRuntimeFeatureEngine.pauseDownloads(context) }) { Text("Pause") }
                        TextButton(onClick = { KomikkuRuntimeFeatureEngine.prioritizeLastQueued(context) }) { Text("Prioritize") }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { KomikkuRuntimeFeatureEngine.clearQueuedDownloads(context) }) { Text("Clear queue") }
                        TextButton(onClick = { showQueue = false }) { Text("Close") }
                    }
                },
            )
        }
        if (showStatistics) {
            AlertDialog(
                onDismissRequest = { showStatistics = false },
                title = { Text("Reading statistics") },
                text = { Text(KomikkuRuntimeFeatureEngine.readingStatistics(context)) },
                confirmButton = { TextButton(onClick = { showStatistics = false }) { Text("Close") } },
            )
        }
        if (showUndo) {
            val entries = KomikkuFullFeatureEngine.undoEntries(context)
            AlertDialog(
                onDismissRequest = { showUndo = false },
                title = { Text("Undo and recovery") },
                text = {
                    Column {
                        Text(KomikkuFullFeatureEngine.undoEntries(context).lastOrNull()?.let { "${it.action}: ${it.payload}" } ?: "No reversible local action is recorded.")
                        if (entries.isNotEmpty()) {
                            Text("\nHistory:", style = MaterialTheme.typography.labelMedium)
                            entries.takeLast(5).reversed().forEach { Text("- ${it.action}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                val result = KomikkuFullFeatureEngine.executeUndoAsync(context)
                                Toast.makeText(context, result.detail, Toast.LENGTH_LONG).show()
                                if (result.success) showUndo = false
                            }
                        },
                        enabled = entries.isNotEmpty()
                    ) { Text("Undo last") }
                },
                dismissButton = { TextButton(onClick = { showUndo = false }) { Text("Close") } },
            )
        }
        if (showDiagnostics) {
            val report = KomikkuRuntimeFeatureEngine.diagnostics(context)
            AlertDialog(
                onDismissRequest = { showDiagnostics = false },
                title = { Text("Privacy-safe diagnostics") },
                text = { Text(report, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { KomikkuFeatureStore.shareText(context, "Komikku diagnostics", report) }) { Text("Share") }
                        TextButton(onClick = { KomikkuFeatureStore.shareText(context, "Komikku diagnostics", report) }) { Text("Copy/share") }
                    }
                },
                dismissButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } },
            )
        }
        if (showFullFeatureStatus) {
            val fullStatus = KomikkuFullFeatureEngine.diagnostics(context)
            AlertDialog(
                onDismissRequest = { showFullFeatureStatus = false },
                title = { Text("Full runtime feature status") },
                text = { Text(fullStatus, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { KomikkuFullFeatureEngine.setPerformance(context, KomikkuFullFeatureEngine.Performance.PERFORMANCE) }) { Text("Performance") }
                        TextButton(onClick = { KomikkuFullFeatureEngine.setPerformance(context, KomikkuFullFeatureEngine.Performance.BATTERY_SAVER) }) { Text("Battery saver") }
                        TextButton(onClick = { KomikkuFeatureStore.shareText(context, "Komikku full feature status", fullStatus) }) { Text("Share") }
                    }
                },
                dismissButton = { TextButton(onClick = { showFullFeatureStatus = false }) { Text("Close") } },
            )
        }
        if (showOfflinePolicy) {
            val offline = KomikkuFullFeatureEngine.offlineModeEnabled(context)
            AlertDialog(
                onDismissRequest = { showOfflinePolicy = false },
                title = { Text("Offline and local-data safety") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (offline) "Offline mode is enabled. Local library, reading progress, history, downloaded files, and cached metadata remain available where supported. Network sync and update commands are blocked." else "Online mode is enabled with local-cache fallback. You can force offline mode to prevent network requests while browsing local data.")
                        Text("Current policy: ${KomikkuFullFeatureEngine.offlinePolicy(context)}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        KomikkuFullFeatureEngine.setOfflineMode(context, !offline)
                        showOfflinePolicy = false
                        Toast.makeText(context, if (offline) "Offline mode disabled" else "Offline mode enabled", Toast.LENGTH_SHORT).show()
                    }) { Text(if (offline) "Use online mode" else "Enable offline mode") }
                },
                dismissButton = { TextButton(onClick = { showOfflinePolicy = false }) { Text("Close") } },
            )
        }
        if (showCommandPalette) {
            val commands = KomikkuFullFeatureEngine.commandNames()
            val filtered = commands.filter { it.contains(commandQuery, ignoreCase = true) }
            AlertDialog(
                onDismissRequest = { showCommandPalette = false },
                title = { Text("Command palette") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = commandQuery,
                            onValueChange = { commandQuery = it },
                            label = { Text("Search commands") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Column {
                            filtered.take(6).forEach { cmd ->
                                TextPreferenceWidget(
                                    title = cmd,
                                    subtitle = "Execute runtime action",
                                    icon = Icons.Outlined.PlayArrow,
                                    onPreferenceClick = {
                                        val result = KomikkuFullFeatureEngine.executeCommand(context, cmd)
                                        Toast.makeText(context, result.detail, Toast.LENGTH_SHORT).show()
                                        showCommandPalette = false
                                        commandQuery = ""
                                    }
                                )
                            }
                            if (filtered.isEmpty()) Text("No matching commands found", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCommandPalette = false }) { Text("Close") } },
            )
        }
    }

    private companion object {
        const val MAX_DUPLICATE_GROUPS = 50
        const val DUPLICATE_PAGE_SIZE = 200L
        const val MAX_DUPLICATE_SCAN_ENTRIES = 5_000
    }
}
