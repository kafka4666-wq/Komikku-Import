package eu.kanade.presentation.more

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.ui.DoujinFeatureEngine
import eu.kanade.domain.ui.KomikkuExtendedFeatureStore
import eu.kanade.domain.ui.KomikkuFeatureStore
import eu.kanade.domain.ui.LibraryCompanionRules
import eu.kanade.domain.ui.LibraryReleaseReminder
import eu.kanade.domain.ui.LibraryStorageWatch
import eu.kanade.domain.ui.LibraryCompanionStore
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.main.KomikkuHomeShortcuts
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import exh.source.MERGED_SOURCE_ID
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CompanionBook(
    val library: LibraryManga,
    val sourceName: String,
    val downloaded: Boolean,
    val personal: LibraryCompanionStore.PersonalRecord,
) {
    val manga: Manga get() = library.manga
}

private data class MetadataFieldChange(val label: String, val current: String, val offered: String)
private data class MetadataComparison(val book: CompanionBook, val remote: SManga, val changes: List<MetadataFieldChange>)

/** A working home for private Library tools; all personal fields remain local to this install. */
class LibraryCompanionScreen(
    private val initialTab: Int = 0,
    private val initialRule: String = "",
    private val launchRandom: Boolean = false,
    private val launchContinueLatest: Boolean = false,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()
        val repository = remember { Injekt.get<MangaRepository>() }
        val downloadManager = remember { Injekt.get<DownloadManager>() }
        var books by remember { mutableStateOf<List<CompanionBook>>(emptyList()) }
        var history by remember { mutableStateOf<List<HistoryWithRelations>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
        var search by remember { mutableStateOf("") }
        var rule by remember { mutableStateOf(initialRule) }
        var surpriseRule by remember { mutableStateOf(LibraryCompanionStore.setting(context, "surprise_filter")) }
        var launchActionHandled by remember { mutableStateOf(false) }
        var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var groupEditions by remember { mutableStateOf(false) }
        var sortByBundle by remember { mutableStateOf(false) }
        var sortByRank by remember { mutableStateOf(false) }
        var collapsedEditionGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
        var selectedBook by remember { mutableStateOf<CompanionBook?>(null) }
        var readingWarningBook by remember { mutableStateOf<CompanionBook?>(null) }
        var randomPick by remember { mutableStateOf<CompanionBook?>(null) }
        var ambientEffectName by remember { mutableStateOf(LibraryCompanionStore.setting(context, "ambient_effect", KomikkuAmbientEffect.INK_AURORA.name)) }
        var activeViewName by remember { mutableStateOf("") }
        var viewName by remember { mutableStateOf("") }
        var bulkTag by remember { mutableStateOf("") }
        var removeBulkTag by remember { mutableStateOf(false) }
        var showBulkPreview by remember { mutableStateOf(false) }
        var showCleanup by remember { mutableStateOf(false) }
        var cleanupReport by remember { mutableStateOf("") }
        var cleanupCandidates by remember { mutableStateOf<List<CleanupCandidate>>(emptyList()) }
        var cleanupDismissedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showStorage by remember { mutableStateOf(false) }
        var storageReport by remember { mutableStateOf("") }
        var showBackup by remember { mutableStateOf(false) }
        var backupName by remember { mutableStateOf("Personal backup") }
        var backupIncludeNotes by remember { mutableStateOf(true) }
        var backupIncludeTags by remember { mutableStateOf(true) }
        var backupIncludeRatings by remember { mutableStateOf(true) }
        var backupIncludeStatuses by remember { mutableStateOf(true) }
        var backupIncludeQueue by remember { mutableStateOf(true) }
        var backupIncludeGoals by remember { mutableStateOf(true) }
        var backupIncludeProgress by remember { mutableStateOf(true) }
        var backupIncludeCategories by remember { mutableStateOf(false) }
        var backupIncludeSettings by remember { mutableStateOf(false) }
        var backupPreview by remember { mutableStateOf("") }
        var showGoalDialog by remember { mutableStateOf(false) }
        var chapterGoalText by remember { mutableStateOf("") }
        var bookGoalText by remember { mutableStateOf("") }
        var showImportProfile by remember { mutableStateOf(false) }
        var importProfileName by remember { mutableStateOf("") }
        var importThreshold by remember { mutableIntStateOf(80) }
        var importStrongOnly by remember { mutableStateOf(true) }
        var importSources by remember { mutableStateOf("") }
        var showFollowedCreators by remember { mutableStateOf(false) }
        var creatorFilter by remember { mutableStateOf(false) }
        var sourceEvents by remember { mutableStateOf(emptyList<String>()) }
        var showSourceHealth by remember { mutableStateOf(false) }
        var sourceHealthCheckRunning by remember { mutableStateOf(false) }
        var activity by remember { mutableStateOf(emptyList<LibraryCompanionStore.ActivityEntry>()) }
        var releaseIntervalText by remember { mutableStateOf("14") }
        var offlinePackCount by remember { mutableStateOf("5") }
        var offlinePackSelectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var offlinePackPreview by remember { mutableStateOf("") }
        var showOfflinePackReview by remember { mutableStateOf(false) }
        var catchUpMinimumUnread by remember { mutableStateOf("10") }
        var catchUpLimit by remember { mutableStateOf("5") }
        var storageThresholdText by remember { mutableStateOf("5") }
        var showStorageWatch by remember { mutableStateOf(false) }
        var showCompare by remember { mutableStateOf(false) }
        var compareResult by remember { mutableStateOf("") }
        var metadataComparison by remember { mutableStateOf<MetadataComparison?>(null) }
        var queueEntries by remember { mutableStateOf(emptyList<LibraryCompanionStore.QueueEntry>()) }
        var savedViews by remember { mutableStateOf(emptyList<LibraryCompanionStore.SavedView>()) }
        var followedCreators by remember { mutableStateOf(emptyList<String>()) }
        var releaseRemindersEnabled by remember { mutableStateOf(LibraryReleaseReminder.enabled(context)) }
        val releaseReminderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                releaseRemindersEnabled = true
                scope.launch(Dispatchers.IO) { LibraryReleaseReminder.setEnabled(context, true) }
            } else {
                Toast.makeText(context, "Notification permission is needed for release reminders", Toast.LENGTH_LONG).show()
            }
        }
        val ambientEffect = KomikkuAmbientEffect.entries.firstOrNull { it.name == ambientEffectName } ?: KomikkuAmbientEffect.INK_AURORA

        fun refreshSidecars() {
            queueEntries = LibraryCompanionStore.queue(context)
            savedViews = LibraryCompanionStore.views(context)
            followedCreators = LibraryCompanionStore.followedCreators(context)
            sourceEvents = KomikkuExtendedFeatureStore.sourceEvents(context)
            activity = LibraryCompanionStore.activity(context, 100)
        }

        suspend fun loadLibrary() {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sourceManager = Injekt.get<SourceManager>()
                    val records = LibraryCompanionStore.getRecords(context)
                    val libraryRows = Injekt.get<GetLibraryManga>().await()
                    LibraryCompanionStore.recordLibrarySnapshot(
                        context,
                        libraryRows.mapTo(linkedSetOf()) { it.manga.id },
                        libraryRows.associate { it.manga.id to it.categories.toSet() },
                    )
                    libraryRows.map { library ->
                        val manga = library.manga
                        CompanionBook(
                            library = library,
                            sourceName = sourceManager.get(manga.source)?.name ?: "Source ${manga.source}",
                            downloaded = downloadManager.getDownloadCount(manga) > 0,
                            personal = records[manga.id] ?: LibraryCompanionStore.PersonalRecord(manga.id),
                        )
                    }.sortedBy { it.manga.title.lowercase(Locale.ROOT) } to
                        Injekt.get<GetHistory>().subscribe("", null, null, null).first()
                }
            }
            loading = false
            result.onSuccess { (loadedBooks, loadedHistory) ->
                books = loadedBooks
                history = loadedHistory
                refreshSidecars()
            }.onFailure {
                Toast.makeText(context, "Could not load Library data: ${it.message ?: "database unavailable"}", Toast.LENGTH_LONG).show()
            }
        }

        fun updateBookRecord(
            book: CompanionBook,
            updated: LibraryCompanionStore.PersonalRecord,
            action: String = "Personal details updated",
        ) {
            scope.launch {
                withContext(Dispatchers.IO) { LibraryCompanionStore.saveRecord(context, updated, action) }
                books = books.map { if (it.manga.id == book.manga.id) it.copy(personal = updated) else it }
                refreshSidecars()
            }
        }

        fun openReader(book: CompanionBook, chapterId: Long? = null) {
            scope.launch {
                val chapter = withContext(Dispatchers.IO) {
                    if (chapterId != null) {
                        val chapters = if (book.manga.source == MERGED_SOURCE_ID) {
                            Injekt.get<GetMergedChaptersByMangaId>().await(book.manga.id, applyFilter = false)
                        } else {
                            Injekt.get<GetChaptersByMangaId>().await(book.manga.id, applyFilter = false)
                        }
                        return@withContext chapters.firstOrNull { it.id == chapterId }
                    }
                    val merged = Injekt.get<GetMergedMangaById>().await(book.manga.id).associateBy { it.id }
                    val chapters = if (book.manga.source == MERGED_SOURCE_ID) {
                        Injekt.get<GetMergedChaptersByMangaId>().await(book.manga.id, applyFilter = true)
                    } else {
                        Injekt.get<GetChaptersByMangaId>().await(book.manga.id, applyFilter = true)
                    }
                    chapters.getNextUnread(book.manga, downloadManager, merged)
                }
                if (chapter?.id == null) {
                    Toast.makeText(context, "No unread chapter is available for ${book.manga.title}", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                context.startActivity(ReaderActivity.newIntent(context, book.manga.id, chapter.id))
            }
        }

        LaunchedEffect(Unit) { loadLibrary() }

        val currentMonth = remember {
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        }
        val monthHistory = history.filter { item ->
            item.readAt?.let { SimpleDateFormat("yyyy-MM", Locale.US).format(it) == currentMonth } == true
        }
        val chaptersReadThisMonth = monthHistory.map { it.chapterId }.distinct().size
        val booksReadThisMonth = monthHistory.map { it.mangaId }.distinct().size
        val avgChapterSeconds = history.asSequence()
            .filter { it.readDuration in 1_000L..7_200_000L }
            .map { it.readDuration.toDouble() / 1_000.0 }
            .take(200)
            .toList()
            .average()
            .takeIf { it.isFinite() && it > 0.0 } ?: 0.0

        fun subject(book: CompanionBook) = LibraryCompanionRules.Subject(
            title = book.manga.title,
            source = book.sourceName,
            tags = book.manga.genre.orEmpty().toSet() + book.personal.tags,
            status = book.personal.status,
            unreadCount = book.library.unreadCount.toInt().coerceAtLeast(0),
            rating = book.personal.rating,
            downloaded = book.downloaded,
            addedAt = book.manga.dateAdded,
            lastUpdatedAt = book.library.latestUpload,
        )

        val activeBooks = books.filterNot { book ->
            LibraryCompanionRules.isSnoozed(
                book.personal.snoozedUntil,
                book.personal.snoozeAtUnreadCount,
                book.library.unreadCount.toInt().coerceAtLeast(0),
                book.personal.status,
                book.personal.snoozeUntilStatus,
            )
        }
        val query = listOf(search, rule).filter(String::isNotBlank).joinToString(" ")
        val baseFiltered = activeBooks.filter { book ->
            (search.isBlank() || book.manga.title.contains(search, ignoreCase = true) || book.sourceName.contains(search, ignoreCase = true)) &&
                (!creatorFilter || followedCreators.any { name ->
                    book.manga.author.orEmpty().contains(name, ignoreCase = true) || book.manga.artist.orEmpty().contains(name, ignoreCase = true)
                }) && LibraryCompanionRules.matches(rule, subject(book))
        }
        val pickerCandidates = baseFiltered.filter {
            it.library.unreadCount > 0 && System.currentTimeMillis() - it.personal.lastSkippedAt > 7L * 24 * 60 * 60 * 1000
        }
        val sortedBooks = when {
            sortByBundle -> baseFiltered.sortedWith(compareBy<CompanionBook> { it.personal.bundleName.isBlank() }.thenBy { it.personal.bundleName.lowercase(Locale.ROOT) }.thenBy { it.manga.title.lowercase(Locale.ROOT) })
            sortByRank -> baseFiltered.sortedByDescending { it.personal.favoriteRank }
            else -> baseFiltered
        }

        LaunchedEffect(loading, books, history) {
            if (loading || launchActionHandled) return@LaunchedEffect
            when {
                launchRandom -> {
                    launchActionHandled = true
                    val now = System.currentTimeMillis()
                    val candidates = books.filter { book ->
                        book.library.unreadCount > 0 &&
                            !LibraryCompanionRules.isSnoozed(book.personal.snoozedUntil, book.personal.snoozeAtUnreadCount, book.library.unreadCount.toInt(), book.personal.status, book.personal.snoozeUntilStatus) &&
                            now - book.personal.lastSkippedAt > 7L * 24 * 60 * 60 * 1000 &&
                            LibraryCompanionRules.matches(surpriseRule, subject(book))
                    }
                    val choice = candidates.randomOrNull()
                    if (choice == null) Toast.makeText(context, "No unread book matches your saved Surprise Me filters", Toast.LENGTH_LONG).show()
                    else if (choice.personal.warning.isBlank()) openReader(choice) else readingWarningBook = choice
                }
                launchContinueLatest -> {
                    launchActionHandled = true
                    val last = history.firstOrNull()
                    if (last == null) Toast.makeText(context, "No recent reading history is available", Toast.LENGTH_LONG).show()
                    else context.startActivity(ReaderActivity.newIntent(context, last.mangaId, last.chapterId))
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            KomikkuAmbientBackdrop(effect = ambientEffect, modifier = Modifier.fillMaxSize())
            androidx.compose.material3.Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Library Companion") },
                    navigationIcon = { TextButton(onClick = { navigator?.pop() }) { Text("Back") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Moving background", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    BoxMenu(
                        value = ambientEffect.title,
                        options = KomikkuAmbientEffect.entries.map { it.title },
                        onSelect = { title ->
                            val selected = KomikkuAmbientEffect.entries.firstOrNull { it.title == title } ?: KomikkuAmbientEffect.INK_AURORA
                            ambientEffectName = selected.name
                            LibraryCompanionStore.setSetting(context, "ambient_effect", selected.name)
                        },
                    )
                }
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("Library", "Lists", "Reading", "Tools").forEachIndexed { index, title ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                    }
                }
                if (loading) {
                    Text("Loading your Library…", modifier = Modifier.padding(16.dp))
                } else when (selectedTab) {
                    0 -> {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            label = { Text("Search title or source") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = rule,
                            onValueChange = { rule = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            label = { Text("Smart filter") },
                            supportingText = { Text("AND terms, | for OR; e.g. unread tag:romance rating>=4") },
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = creatorFilter, onClick = { creatorFilter = !creatorFilter }, label = { Text("Followed creators") })
                            Text("${baseFiltered.size} shown · ${selectedIds.size} selected", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { selectedIds = if (baseFiltered.all { it.manga.id in selectedIds } && baseFiltered.isNotEmpty()) emptySet() else baseFiltered.mapTo(linkedSetOf()) { it.manga.id } }) { Text("Select all") }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            FilterChip(selected = groupEditions, onClick = { groupEditions = !groupEditions }, label = { Text("Collapse duplicate editions") })
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = sortByBundle, onClick = { sortByBundle = !sortByBundle; if (sortByBundle) sortByRank = false }, label = { Text("Series bundles") })
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = sortByRank, onClick = { sortByRank = !sortByRank; if (sortByRank) sortByBundle = false }, label = { Text("Rank") })
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = bulkTag, onValueChange = { bulkTag = it }, modifier = Modifier.weight(1f), label = { Text("Bulk personal tag") }, singleLine = true)
                            TextButton(onClick = { removeBulkTag = !removeBulkTag }) { Text(if (removeBulkTag) "Remove" else "Add") }
                            Button(onClick = { showBulkPreview = true }, enabled = selectedIds.isNotEmpty() && bulkTag.isNotBlank()) { Text("Preview") }
                        }
                        val tagColors = LibraryCompanionStore.tagColors(context)
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (!groupEditions) {
                                items(sortedBooks, key = { it.manga.id }) { book ->
                                    CompanionBookRow(
                                        book = book,
                                        checked = book.manga.id in selectedIds,
                                        tagColors = tagColors,
                                        onToggle = { selectedIds = if (book.manga.id in selectedIds) selectedIds - book.manga.id else selectedIds + book.manga.id },
                                        onEdit = { selectedBook = book },
                                        onQueue = {
                                            val currentChapterId = history.firstOrNull { it.mangaId == book.manga.id }?.chapterId
                                            LibraryCompanionStore.addToQueue(context, book.manga.id, currentChapterId = currentChapterId)
                                            refreshSidecars()
                                            Toast.makeText(context, "Added to Reading Queue", Toast.LENGTH_SHORT).show()
                                        },
                                        onRead = { if (book.personal.warning.isBlank()) openReader(book) else readingWarningBook = book },
                                        onSkip = { updateBookRecord(book, book.personal.copy(lastSkippedAt = System.currentTimeMillis()), "Skipped in random discovery") },
                                    )
                                }
                            } else {
                                val groups = sortedBooks.groupBy { LibraryCompanionRules.normalizeTitle(it.manga.title) }.toList()
                                items(groups, key = { it.first }) { (groupKey, group) ->
                                    Column {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${group.first().manga.title} · ${group.size} edition${if (group.size == 1) "" else "s"}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                            TextButton(onClick = {
                                                collapsedEditionGroups = if (groupKey in collapsedEditionGroups) collapsedEditionGroups - groupKey else collapsedEditionGroups + groupKey
                                            }) { Text(if (groupKey in collapsedEditionGroups) "Expand" else "Collapse") }
                                        }
                                        if (groupKey !in collapsedEditionGroups || group.size == 1) {
                                            group.forEach { book ->
                                                CompanionBookRow(
                                                    book = book,
                                                    checked = book.manga.id in selectedIds,
                                                    tagColors = tagColors,
                                                    onToggle = { selectedIds = if (book.manga.id in selectedIds) selectedIds - book.manga.id else selectedIds + book.manga.id },
                                                    onEdit = { selectedBook = book },
                                                    onQueue = { LibraryCompanionStore.addToQueue(context, book.manga.id); refreshSidecars() },
                                                    onRead = { if (book.personal.warning.isBlank()) openReader(book) else readingWarningBook = book },
                                                    onSkip = { updateBookRecord(book, book.personal.copy(lastSkippedAt = System.currentTimeMillis()), "Skipped in random discovery") },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Live lists update from your current Library data. Use filters such as unread, status:Reading, tag:romance, downloaded, added:month, source:Nhentai, rating>=4, or updated:recent.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = viewName, onValueChange = { viewName = it }, modifier = Modifier.weight(1f), label = { Text("View name") }, singleLine = true)
                                Button(onClick = {
                                    LibraryCompanionStore.saveView(context, viewName, rule.ifBlank { query })
                                    viewName = ""
                                    refreshSidecars()
                                    Toast.makeText(context, "Live view saved", Toast.LENGTH_SHORT).show()
                                }, enabled = viewName.isNotBlank() && rule.isNotBlank()) { Text("Save view") }
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                items(savedViews, key = { it.name }) { view ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).clickable {
                                            activeViewName = view.name
                                            rule = view.query
                                            selectedTab = 0
                                        }.padding(8.dp)) {
                                            Text(view.name, style = MaterialTheme.typography.titleSmall)
                                            Text(view.query, style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { LibraryCompanionStore.deleteView(context, view.name); refreshSidecars() }) { Text("Delete") }
                                    }
                                }
                            }
                            Text("Library Inbox · recently added and not yet reviewed", style = MaterialTheme.typography.titleMedium)
                            val inbox = books.filter { !it.personal.inboxReviewed && it.manga.dateAdded >= System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 }.sortedByDescending { it.manga.dateAdded }
                            if (inbox.isEmpty()) Text("No unreviewed books added in the last 30 days.")
                            inbox.take(20).forEach { book ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(book.manga.title, Modifier.weight(1f), maxLines = 1)
                                    TextButton(onClick = { LibraryCompanionStore.markInboxReviewed(context, book.manga.id); scope.launch { loadLibrary() } }) { Text("Reviewed") }
                                }
                            }
                            Text("Followed creators", style = MaterialTheme.typography.titleMedium)
                            Text(followedCreators.joinToString().ifBlank { "None followed yet; follow an author/artist from a book’s details." })
                            TextButton(onClick = { showFollowedCreators = true }) { Text("Manage creator follows") }
                            Text("Series Bundles and source grouping use the private bundle name on each book. Edit a book to assign its bundle; duplicate normalized titles remain separate editions.")
                            TextButton(onClick = { sortByBundle = true; sortByRank = false; selectedTab = 0 }) { Text("Group by bundle") }
                            TextButton(onClick = { sortByRank = true; sortByBundle = false; selectedTab = 0 }) { Text("Sort by personal favorites ranking") }
                            val snoozed = books.filter { book -> LibraryCompanionRules.isSnoozed(book.personal.snoozedUntil, book.personal.snoozeAtUnreadCount, book.library.unreadCount.toInt(), book.personal.status, book.personal.snoozeUntilStatus) }
                            Text("Snoozed books: ${snoozed.size}. They are hidden from the active Library Companion list until their date or unread-count condition expires.")
                            snoozed.take(10).forEach { book -> Text("• ${book.manga.title} · until ${when { book.personal.snoozedUntil > 0 -> Date(book.personal.snoozedUntil).toString(); book.personal.snoozeUntilStatus.isNotBlank() -> "status changes from ${book.personal.snoozeUntilStatus}"; else -> "unread chapters decrease" }}") }
                        }
                    }
                    2 -> {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Reading goals for $currentMonth: $chaptersReadThisMonth chapters · $booksReadThisMonth books")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = releaseRemindersEnabled,
                                    onClick = {
                                        if (releaseRemindersEnabled) {
                                            releaseRemindersEnabled = false
                                            scope.launch(Dispatchers.IO) { LibraryReleaseReminder.setEnabled(context, false) }
                                        } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                            releaseReminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            releaseRemindersEnabled = true
                                            scope.launch(Dispatchers.IO) { LibraryReleaseReminder.setEnabled(context, true) }
                                        }
                                    },
                                    label = { Text(if (releaseRemindersEnabled) "New chapter reminders: On" else "New chapter reminders: Off") },
                                )
                                Text("Checks every 12 hours for series marked Reading or Ongoing", style = MaterialTheme.typography.bodySmall)
                            }
                            val goal = LibraryCompanionStore.goal(context, currentMonth)
                            Text("Targets: ${goal.chapterTarget} chapters, ${goal.bookTarget} books")
                            Button(onClick = {
                                chapterGoalText = goal.chapterTarget.toString()
                                bookGoalText = goal.bookTarget.toString()
                                showGoalDialog = true
                            }) { Text("Set monthly goal") }
                            val releaseForecasts = books.mapNotNull { book ->
                                LibraryCompanionRules.predictedReleaseAt(book.library.latestUpload, book.personal.expectedReleaseDays)
                                    ?.takeIf { it in System.currentTimeMillis()..(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000) }
                                    ?.let { it to book }
                            }.sortedBy { it.first }
                            Text("Chapter Release Calendar · next 30 days", style = MaterialTheme.typography.titleMedium)
                            if (releaseForecasts.isEmpty()) Text("No upcoming estimates yet. Set an expected release interval in a book’s personal details.", style = MaterialTheme.typography.bodySmall)
                            releaseForecasts.take(12).forEach { (at, book) -> Text("${SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(at))} · ${book.manga.title}", style = MaterialTheme.typography.bodySmall) }
                            val queueBooks = queueEntries.mapNotNull { entry -> books.firstOrNull { it.manga.id == entry.mangaId } }
                            Text("Reading Queue · ${queueBooks.size} prioritized books", style = MaterialTheme.typography.titleMedium)
                            if (queueBooks.isEmpty()) Text("Use Add to Queue on a Library book to build a continue-later or catch-up queue.")
                            queueBooks.forEachIndexed { index, book ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${index + 1}. ${book.manga.title}", style = MaterialTheme.typography.titleSmall)
                                        Text("${book.library.unreadCount} unread · cap ${queueEntries.first { it.mangaId == book.manga.id }.unreadLimit} · priority ${queueEntries.first { it.mangaId == book.manga.id }.priority}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { val old = queueEntries.first { it.mangaId == book.manga.id }; LibraryCompanionStore.applyQueuePriority(context, book.manga.id, old.priority + 1); refreshSidecars() }) { Text("P+") }
                                    TextButton(onClick = { val old = queueEntries.first { it.mangaId == book.manga.id }; LibraryCompanionStore.applyQueuePriority(context, book.manga.id, old.priority - 1); refreshSidecars() }) { Text("P−") }
                                    TextButton(onClick = { LibraryCompanionStore.moveQueueItem(context, book.manga.id, -1); refreshSidecars() }) { Text("↑") }
                                    TextButton(onClick = { LibraryCompanionStore.moveQueueItem(context, book.manga.id, 1); refreshSidecars() }) { Text("↓") }
                                    TextButton(onClick = { LibraryCompanionStore.removeFromQueue(context, book.manga.id); refreshSidecars() }) { Text("Remove") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    queueBooks.firstOrNull()?.let { book ->
                                        val entry = queueEntries.firstOrNull { it.mangaId == book.manga.id }
                                        openReader(book, entry?.currentChapterId)
                                        }
                                    }, enabled = queueBooks.isNotEmpty()) { Text("Continue Queue") }
                                OutlinedButton(onClick = {
                                    LibraryCompanionStore.shuffleQueue(context)
                                    refreshSidecars()
                                }, enabled = queueBooks.size > 1) { Text("Shuffle within priorities") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        var queued = 0
                                        queueEntries.forEach { entry ->
                                            val book = books.firstOrNull { it.manga.id == entry.mangaId } ?: return@forEach
                                            val chapters = withContext(Dispatchers.IO) {
                                                if (book.manga.source == MERGED_SOURCE_ID) {
                                                    Injekt.get<GetMergedChaptersByMangaId>().await(book.manga.id, applyFilter = true)
                                                } else {
                                                    Injekt.get<GetChaptersByMangaId>().await(book.manga.id, applyFilter = true)
                                                }
                                            }.filterNot { it.read }.sortedBy { it.dateUpload }.take(entry.unreadLimit)
                                            if (book.manga.source == MERGED_SOURCE_ID) {
                                                val merged = withContext(Dispatchers.IO) { Injekt.get<GetMergedMangaById>().await(book.manga.id).associateBy { it.id } }
                                                chapters.groupBy { it.mangaId }.forEach { (mangaId, rows) ->
                                                    merged[mangaId]?.let { downloadManager.downloadChapters(it, rows); queued += rows.size }
                                                }
                                            } else {
                                                downloadManager.downloadChapters(book.manga, chapters)
                                                queued += chapters.size
                                            }
                                        }
                                        Toast.makeText(context, "Queued $queued chapters using per-book limits", Toast.LENGTH_SHORT).show()
                                    }
                                }, enabled = queueBooks.isNotEmpty()) { Text("Download capped chapters") }
                                OutlinedButton(onClick = {
                                    val ordered = queueBooks.sortedByDescending { queueEntries.firstOrNull { q -> q.mangaId == it.manga.id }?.priority ?: 0 }
                                    val activeDownloads = downloadManager.queueState.value
                                    val queueOrder = ordered.mapIndexed { i, book -> book.manga.id to i }.toMap()
                                    downloadManager.reorderQueue(activeDownloads.sortedBy { d -> queueOrder[d.manga.id] ?: Int.MAX_VALUE })
                                    Toast.makeText(context, "Active download queue reordered by your reading priorities", Toast.LENGTH_SHORT).show()
                                }, enabled = queueBooks.isNotEmpty()) { Text("Apply priorities to downloads") }
                            }
                            Text("Offline Reading Packs · choose titles, review the estimate, then queue the pack", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = offlinePackCount,
                                onValueChange = { offlinePackCount = it.filter(Char::isDigit).take(3) },
                                label = { Text("Unread chapters per book") },
                                singleLine = true,
                            )
                            Column(Modifier.heightIn(max = 180.dp)) {
                                books.filter { it.library.unreadCount > 0 }.take(16).forEach { book ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = book.manga.id in offlinePackSelectedIds, onCheckedChange = { checked ->
                                            offlinePackSelectedIds = if (checked) offlinePackSelectedIds + book.manga.id else offlinePackSelectedIds - book.manga.id
                                        })
                                        Text("${book.manga.title} · ${book.library.unreadCount} unread", maxLines = 1)
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    val count = offlinePackCount.toIntOrNull()?.coerceIn(1, 100) ?: 5
                                    val selected = books.filter { it.manga.id in offlinePackSelectedIds }
                                    scope.launch {
                                        offlinePackPreview = buildOfflinePackEstimate(context, selected, count)
                                        showOfflinePackReview = true
                                    }
                                },
                                enabled = offlinePackSelectedIds.isNotEmpty(),
                            ) { Text("Review offline pack") }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = catchUpMinimumUnread, onValueChange = { catchUpMinimumUnread = it.filter(Char::isDigit).take(5) }, label = { Text("Minimum unread") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = catchUpLimit, onValueChange = { catchUpLimit = it.filter(Char::isDigit).take(3) }, label = { Text("Per-book cap") }, modifier = Modifier.weight(1f), singleLine = true)
                                Button(onClick = {
                                    val minimum = catchUpMinimumUnread.toIntOrNull()?.coerceAtLeast(1) ?: 10
                                    val cap = catchUpLimit.toIntOrNull()?.coerceIn(1, 100) ?: 5
                                    val candidates = activeBooks.filter { it.library.unreadCount >= minimum }
                                    candidates.forEach { book -> LibraryCompanionStore.addToQueue(context, book.manga.id, unreadLimit = cap) }
                                    refreshSidecars()
                                    Toast.makeText(context, "Added ${candidates.size} catch-up titles (cap $cap each)", Toast.LENGTH_SHORT).show()
                                }) { Text("Build") }
                            }
                            Text("Where Did I Leave Off? · ${history.size} chapter records · estimated remaining time uses your recent reading durations.")
                            val recent = history.take(18)
                            LazyColumn(Modifier.weight(1f)) {
                                items(recent, key = { it.chapterId }) { entry ->
                                    val remaining = books.firstOrNull { it.manga.id == entry.mangaId }?.library?.unreadCount?.toInt() ?: 0
                                    val mins = LibraryCompanionRules.estimatedMinutes(avgChapterSeconds, remaining)
                                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Row(Modifier.fillMaxWidth().clickable { context.startActivity(ReaderActivity.newIntent(context, entry.mangaId, entry.chapterId)) }) {
                                            Text("${entry.title} · ch ${entry.chapterNumber} · ${entry.readAt ?: "not read yet"} · ~$mins min remaining", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { LibraryCompanionStore.addToQueue(context, entry.mangaId, currentChapterId = entry.chapterId); refreshSidecars() }) { Text("Save this chapter for later") }
                                    }
                                }
                            }
                            val currentGoalProgress = if (goal.chapterTarget > 0) (chaptersReadThisMonth * 100 / goal.chapterTarget).coerceAtMost(100) else 0
                            val streak = readingStreakDays(history)
                            Text("Goal progress: $currentGoalProgress% · current reading streak: $streak day${if (streak == 1) "" else "s"}.")
                            val finishedCount = books.count { it.personal.status.equals("Finished", true) || it.library.unreadCount <= 0 }
                            val ongoingCount = books.count { it.personal.status.equals("Ongoing", true) }
                            val abandonedCount = books.count { it.personal.status.equals("Abandoned", true) || it.personal.status.equals("Dropped", true) }
                            Text("Series Completion Tracker · finished $finishedCount · ongoing $ongoingCount · dropped/abandoned $abandonedCount", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text("Tools act on current Library/database data. Destructive actions always show a review and confirmation first.") }
                            item {
                                OutlinedTextField(value = surpriseRule, onValueChange = { surpriseRule = it }, label = { Text("Surprise Me filter (blank = any unread)") }, supportingText = { Text("Uses the same live filter syntax: tag:romance source:Nhentai status:Reading") })
                                Button(onClick = {
                                    LibraryCompanionStore.setSetting(context, "surprise_filter", surpriseRule.trim())
                                    Toast.makeText(context, "Surprise Me filter saved for the widget and shortcut", Toast.LENGTH_SHORT).show()
                                }) { Text("Save Surprise Me filters") }
                            }
                            item {
                                Text("Custom Home Shortcuts", style = MaterialTheme.typography.titleMedium)
                                Text("Pin only the actions you want; shortcuts open working app destinations.", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { Toast.makeText(context, if (KomikkuHomeShortcuts.pinShortcut(context, KomikkuHomeShortcuts.ID_SURPRISE)) "Surprise Me shortcut pinned" else "Pinning is not supported by this launcher", Toast.LENGTH_SHORT).show() }) { Text("Pin Surprise") }
                                    TextButton(onClick = { Toast.makeText(context, if (KomikkuHomeShortcuts.pinShortcut(context, KomikkuHomeShortcuts.ID_CONTINUE)) "Continue shortcut pinned" else "Pinning is not supported by this launcher", Toast.LENGTH_SHORT).show() }) { Text("Pin Continue") }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { Toast.makeText(context, if (KomikkuHomeShortcuts.pinShortcut(context, KomikkuHomeShortcuts.ID_SEARCH_TAG)) "Tag search shortcut pinned" else "Pinning is not supported by this launcher", Toast.LENGTH_SHORT).show() }) { Text("Pin Tag Search") }
                                    TextButton(onClick = { Toast.makeText(context, if (KomikkuHomeShortcuts.pinShortcut(context, KomikkuHomeShortcuts.ID_DOWNLOADS)) "Downloads shortcut pinned" else "Pinning is not supported by this launcher", Toast.LENGTH_SHORT).show() }) { Text("Pin Downloads") }
                                }
                            }
                            item { Button(onClick = {
                                scope.launch {
                                    val (report, candidates, dismissed) = withContext(Dispatchers.IO) {
                                        val dismissed = LibraryCompanionStore.dismissedCleanupKeys(context)
                                        Triple(
                                            buildCleanupReport(context, books),
                                            buildCleanupCandidates(context, books).filterNot { it.key in dismissed },
                                            dismissed,
                                        )
                                    }
                                    cleanupReport = report
                                    cleanupCandidates = candidates
                                    cleanupDismissedKeys = dismissed
                                    showCleanup = true
                                }
                            }) { Text("Library Health Report / Cleanup Review") } }
                            item { OutlinedButton(onClick = {
                                scope.launch {
                                    val report = withContext(Dispatchers.IO) { buildStorageReport(context, books) }
                                    storageReport = report
                                    showStorage = true
                                }
                            }) { Text("Download Space Planner") } }
                            item { Button(onClick = {
                                randomPick = pickerCandidates.randomOrNull()
                                if (randomPick == null) Toast.makeText(context, "No unread book matches the current filters", Toast.LENGTH_SHORT).show()
                            }) { Text("What Should I Read? / Random Discovery") } }
                            item { OutlinedButton(onClick = { showBackup = true }) { Text("Flexible Backup Profile / Export") } }
                            item { Button(onClick = {
                                val snapshot = books.joinToString("\n") { book ->
                                    listOf(book.manga.title, book.sourceName, book.manga.genre.orEmpty().joinToString(","), "${book.library.readCount}/${book.library.totalChapters}", "${book.personal.rating}/5", book.personal.status, book.personal.tags.joinToString(",")).joinToString("\t")
                                }
                                KomikkuFeatureStore.shareText(context, "Komikku Library Snapshot", "Title\tSource\tTags\tProgress\tRating\tStatus\tPersonal tags\n$snapshot")
                            }) { Text("Export Library Snapshot") } }
                            item { Button(onClick = { showImportProfile = true }) { Text("Import Profiles") } }
                            item { OutlinedButton(onClick = {
                                sourceEvents = KomikkuExtendedFeatureStore.sourceEvents(context)
                                showSourceHealth = true
                            }) { Text("Source Reliability Dashboard") } }
                            item {
                                OutlinedTextField(value = storageThresholdText, onValueChange = { storageThresholdText = it }, label = { Text("Low-storage alert threshold (GB)") }, singleLine = true)
                                Button(onClick = {
                                    val bytes = (storageThresholdText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 1_000.0).times(1024.0 * 1024.0 * 1024.0).toLong()
                                    LibraryCompanionStore.setStorageThresholdBytes(context, bytes)
                                    LibraryStorageWatch.schedule(context)
                                    showStorageWatch = true
                                }, enabled = (storageThresholdText.toDoubleOrNull() ?: 0.0) > 0.0) { Text("Enable Daily Low-Storage Watch") }
                            }
                            item { Text("Personal content warnings appear before reading from this screen and from the standard reader activity. Ratings, notes, labels, tags, creator follows, and warning text remain private and local.") }
                            item { Text("Reading modes use the native per-title viewer preference; release calendar predictions require a personal expected interval set in book details.") }
                            item { Text("Surprise Me widget and pinned shortcut are available from the Android home-screen widget picker and the Surprise Me action after installation.") }
                            item { Text("Activity log entries: ${activity.size} · source events: ${sourceEvents.size} · saved import profiles: ${LibraryCompanionStore.importProfiles(context).size} · backup profiles: ${LibraryCompanionStore.backupProfiles(context).size}") }
                            item { TextButton(onClick = {
                                val items = downloadManager.queueState.value
                                val priorities = queueEntries.associate { it.mangaId to it.priority }
                                downloadManager.reorderQueue(items.sortedWith(compareByDescending { priorities[it.manga.id] ?: 0 }))
                                Toast.makeText(context, "Queued chapters ordered by saved priority", Toast.LENGTH_SHORT).show()
                            }) { Text("Reorder active chapter queue") } }
                            item { TextButton(onClick = {
                                val rows = LibraryCompanionStore.activity(context)
                                val report = rows.joinToString("\n") { "${Date(it.timestamp)}\t${it.action}\t${it.mangaId ?: ""}\t${it.details}" }
                                KomikkuFeatureStore.shareText(context, "Komikku Library Change Log", report.ifBlank { "No Library Companion actions recorded yet." })
                            }) { Text("Export Library Change Log") } }
                            item { TextButton(onClick = {
                                val undone = LibraryCompanionStore.undoLastPersonalEdit(context)
                                if (undone == null) Toast.makeText(context, "No reversible personal change is available", Toast.LENGTH_SHORT).show()
                                else { Toast.makeText(context, "Restored personal fields for manga ${undone.mangaId}", Toast.LENGTH_SHORT).show(); scope.launch { loadLibrary() } }
                            }) { Text("Undo last personal edit") } }
                            item { TextButton(onClick = { refreshSidecars(); showFollowedCreators = true }) { Text("Manage followed creators and tag colors") } }
                            item { OutlinedButton(onClick = { scope.launch { loadLibrary() } }) { Text("Refresh Library data") } }
                        }
                    }
                }
            }
        }
        }

        selectedBook?.let { book ->
            BookPersonalEditor(
                book = book,
                onDismiss = { selectedBook = null },
                onCompare = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val source = Injekt.get<SourceManager>().get(book.manga.source)
                                    ?: error("This source is not installed or is unavailable")
                                source.getMangaUpdate(
                                    manga = book.manga.toSManga(),
                                    chapters = emptyList(),
                                    fetchDetails = true,
                                    fetchChapters = false,
                                ).manga
                            }
                        }.onSuccess { remote ->
                            val changes = listOf(
                                MetadataFieldChange("Title", book.manga.title, remote.title),
                                MetadataFieldChange("Author", book.manga.author.orEmpty(), remote.author.orEmpty()),
                                MetadataFieldChange("Artist", book.manga.artist.orEmpty(), remote.artist.orEmpty()),
                                MetadataFieldChange("Description", book.manga.description.orEmpty(), remote.description.orEmpty()),
                                MetadataFieldChange("Tags", book.manga.genre.orEmpty().joinToString(", "), remote.genre.orEmpty()),
                                MetadataFieldChange("Cover URL", book.manga.thumbnailUrl.orEmpty(), remote.thumbnail_url.orEmpty()),
                            ).filter { it.current != it.offered }
                            metadataComparison = MetadataComparison(book, remote, changes)
                        }.onFailure { error ->
                            Toast.makeText(context, "Metadata comparison failed: ${error.message ?: "source error"}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onSave = { record, mode ->
                    selectedBook = null
                    updateBookRecord(book, record)
                    if (mode != null && mode != ReadingMode.DEFAULT) {
                        scope.launch {
                            val updatedFlags = (book.manga.viewerFlags and ReadingMode.MASK.toLong().inv()) or mode.flagValue.toLong()
                            repository.update(MangaUpdate(id = book.manga.id, viewerFlags = updatedFlags))
                        }
                    }
                    refreshSidecars()
                },
                onFollowCreator = { name, follow ->
                    LibraryCompanionStore.followCreator(context, name, follow)
                    refreshSidecars()
                },
                onSetTagColor = { tag, color -> LibraryCompanionStore.setTagColor(context, tag, color) },
            )
        }
        readingWarningBook?.let { book ->
            AlertDialog(
                onDismissRequest = { readingWarningBook = null },
                title = { Text("Personal content warning") },
                text = { Text(book.personal.warning) },
                confirmButton = { TextButton(onClick = { readingWarningBook = null; openReader(book) }) { Text("Continue reading") } },
                dismissButton = { TextButton(onClick = { readingWarningBook = null }) { Text("Cancel") } },
            )
        }
        randomPick?.let { book ->
            val alternatives = pickerCandidates.filterNot { it.manga.id == book.manga.id }
            AlertDialog(
                onDismissRequest = { randomPick = null },
                title = { Text("What should I read?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!book.manga.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(model = book.manga.thumbnailUrl, contentDescription = "Cover of ${book.manga.title}", modifier = Modifier.size(104.dp), contentScale = ContentScale.Crop)
                        }
                        Text(book.manga.title, style = MaterialTheme.typography.titleMedium)
                        Text("${book.sourceName} · ${book.library.unreadCount} unread chapters · rated ${book.personal.rating}/5", style = MaterialTheme.typography.bodySmall)
                        Text("This pick follows your current search and smart-list filters. Recently skipped books are held back for seven days.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    randomPick = null
                    if (book.personal.warning.isBlank()) openReader(book) else readingWarningBook = book
                }) { Text("Read this book") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            updateBookRecord(book, book.personal.copy(lastSkippedAt = System.currentTimeMillis()), action = "Skipped random recommendation")
                            randomPick = alternatives.randomOrNull()
                        }, enabled = alternatives.isNotEmpty()) { Text("Pick another") }
                        TextButton(onClick = { randomPick = null }) { Text("Close") }
                    }
                },
            )
        }
        if (showBulkPreview) {
            val selectedBooks = books.filter { it.manga.id in selectedIds }
            AlertDialog(
                onDismissRequest = { showBulkPreview = false },
                title = { Text("Review bulk tag change") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp)) {
                        Text("${if (removeBulkTag) "Remove" else "Add"} personal tag “${bulkTag.trim()}” from ${selectedBooks.size} selected books. Source-provided tags are not changed.")
                        LazyColumn { items(selectedBooks, key = { it.manga.id }) { Text("• ${it.manga.title}") } }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    val tag = bulkTag.trim()
                    selectedBooks.forEach { book ->
                        val tags = if (removeBulkTag) book.personal.tags.filterNot { it.equals(tag, true) } else (book.personal.tags + tag).distinct()
                        updateBookRecord(book, book.personal.copy(tags = tags), action = if (removeBulkTag) "Bulk tag removed" else "Bulk tag added")
                    }
                    selectedIds = emptySet()
                    showBulkPreview = false
                    Toast.makeText(context, "Personal tags updated", Toast.LENGTH_SHORT).show()
                }) { Text("Apply to ${selectedBooks.size} books") } },
                dismissButton = { TextButton(onClick = { showBulkPreview = false }) { Text("Cancel") } },
            )
        }
        if (showGoalDialog) {
            AlertDialog(
                onDismissRequest = { showGoalDialog = false },
                title = { Text("Monthly reading goals") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = chapterGoalText, onValueChange = { chapterGoalText = it.filter(Char::isDigit).take(6) }, label = { Text("Chapters") }, singleLine = true)
                        OutlinedTextField(value = bookGoalText, onValueChange = { bookGoalText = it.filter(Char::isDigit).take(5) }, label = { Text("Books") }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    LibraryCompanionStore.saveGoal(context, LibraryCompanionStore.Goal(currentMonth, chapterGoalText.toIntOrNull() ?: 0, bookGoalText.toIntOrNull() ?: 0))
                    showGoalDialog = false
                }) { Text("Save goal") } },
                dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("Cancel") } },
            )
        }
        if (showCleanup) {
            AlertDialog(
                onDismissRequest = { showCleanup = false },
                title = { Text("Library Health Report · ${cleanupCandidates.size} findings") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp)) {
                        Text(cleanupReport.lineSequence().take(5).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        if (cleanupCandidates.isEmpty()) Text("All findings have been reviewed or dismissed.", modifier = Modifier.padding(vertical = 12.dp))
                        if (cleanupDismissedKeys.isNotEmpty()) {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { LibraryCompanionStore.clearCleanupDismissals(context) }
                                    cleanupCandidates = withContext(Dispatchers.IO) { buildCleanupCandidates(context, books) }
                                    cleanupDismissedKeys = emptySet()
                                }
                            }) { Text("Show ${cleanupDismissedKeys.size} dismissed findings again") }
                        }
                        LazyColumn {
                            items(cleanupCandidates.take(120), key = { it.key }) { finding ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Text(finding.book.manga.title, style = MaterialTheme.typography.titleSmall)
                                    Text(finding.issue, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (finding.canCompareSource) {
                                            TextButton(onClick = {
                                                selectedBook = finding.book
                                                showCleanup = false
                                            }) { Text("Compare / Fix") }
                                        } else if (finding.key.endsWith(":duplicate")) {
                                            TextButton(onClick = {
                                                search = finding.book.manga.title
                                                groupEditions = true
                                                selectedTab = 0
                                                showCleanup = false
                                            }) { Text("Review editions") }
                                        }
                                        TextButton(onClick = {
                                            cleanupCandidates = cleanupCandidates.filterNot { it.key == finding.key }
                                            cleanupDismissedKeys = cleanupDismissedKeys + finding.key
                                            scope.launch(Dispatchers.IO) { LibraryCompanionStore.dismissCleanupFinding(context, finding.key) }
                                        }) { Text("Dismiss") }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCleanup = false }) { Text("Done") } },
            )
        }
        if (showStorage) {
            AlertDialog(onDismissRequest = { showStorage = false }, title = { Text("Download Space Planner") }, text = { Text(storageReport) }, confirmButton = { TextButton(onClick = { showStorage = false }) { Text("Done") } })
        }
        if (showOfflinePackReview) {
            AlertDialog(
                onDismissRequest = { showOfflinePackReview = false },
                title = { Text("Review Offline Reading Pack") },
                text = { Text(offlinePackPreview) },
                confirmButton = { TextButton(onClick = {
                    val count = offlinePackCount.toIntOrNull()?.coerceIn(1, 100) ?: 5
                    val selected = books.filter { it.manga.id in offlinePackSelectedIds }
                    showOfflinePackReview = false
                    scope.launch {
                        val queued = queueOfflinePack(context, selected, count)
                        Toast.makeText(context, "Queued $queued unread chapters for offline reading", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Queue pack") } },
                dismissButton = { TextButton(onClick = { showOfflinePackReview = false }) { Text("Cancel") } },
            )
        }
        if (showSourceHealth) {
            val outcomes = sourceEvents.mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size < 4) null else fields[1] to (fields[3] == "FAIL")
            }.groupBy({ it.first }, { it.second })
            val latestBySource = linkedMapOf<String, Pair<String, Boolean>>()
            sourceEvents.forEach { line ->
                val fields = line.split('\t')
                if (fields.size >= 4 && fields[1] !in latestBySource) latestBySource[fields[1]] = fields[2] to (fields[3] == "FAIL")
            }
            AlertDialog(
                onDismissRequest = { showSourceHealth = false },
                title = { Text("Source Reliability Dashboard") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp)) {
                        Text("Recent outcomes come from real searches, imports, and optional chapter-update health checks. The manual check tests one library title per source in sequence, records results locally, and isolates failures; it does not apply metadata or stop other sources.", style = MaterialTheme.typography.bodySmall)
                        if (outcomes.isEmpty()) Text("No source operation outcomes have been recorded yet.")
                        outcomes.forEach { (name, failures) ->
                            val failed = failures.count { it }
                            val latest = latestBySource[name]
                            val health = when {
                                latest?.second == true -> " · temporarily unreliable (last: ${latest.first})"
                                latest != null -> " · last: ${latest.first} OK"
                                else -> ""
                            }
                            Text("$name · ${failures.size} recorded operations · $failed failed$health", style = MaterialTheme.typography.bodyMedium)
                        }
                        sourceEvents.take(16).forEach { Text(it.replace("\t", " · "), style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { sourceEvents = KomikkuExtendedFeatureStore.sourceEvents(context) }) { Text("Refresh") }
                        TextButton(onClick = {
                            scope.launch {
                                sourceHealthCheckRunning = true
                                val checked = books.filterNot { it.manga.source == MERGED_SOURCE_ID }.distinctBy { it.manga.source }.take(24).size
                                try {
                                    sourceEvents = checkLibrarySourceUpdateHealth(context, books)
                                    Toast.makeText(context, "Checked $checked library sources; each result was recorded independently", Toast.LENGTH_LONG).show()
                                } catch (error: Exception) {
                                    Toast.makeText(context, "Source checks could not finish: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                                } finally {
                                    sourceHealthCheckRunning = false
                                }
                            }
                        }, enabled = !sourceHealthCheckRunning) { Text(if (sourceHealthCheckRunning) "Checking…" else "Check updates") }
                    }
                },
                dismissButton = { TextButton(onClick = { showSourceHealth = false }) { Text("Close") } },
            )
        }
        if (showBackup) {
            AlertDialog(
                onDismissRequest = { showBackup = false },
                title = { Text("Flexible backup profile") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(value = backupName, onValueChange = { backupName = it }, label = { Text("Profile name") }, singleLine = true)
                        ToggleLine("Notes and warnings", backupIncludeNotes) { backupIncludeNotes = it }
                        ToggleLine("Personal tags and labels", backupIncludeTags) { backupIncludeTags = it }
                        ToggleLine("Ratings and ranking", backupIncludeRatings) { backupIncludeRatings = it }
                        ToggleLine("Statuses, snooze, bundles", backupIncludeStatuses) { backupIncludeStatuses = it }
                        ToggleLine("Reading queue", backupIncludeQueue) { backupIncludeQueue = it }
                        ToggleLine("Reading goals", backupIncludeGoals) { backupIncludeGoals = it }
                        ToggleLine("Reading progress", backupIncludeProgress) { backupIncludeProgress = it }
                        ToggleLine("Library categories", backupIncludeCategories) { backupIncludeCategories = it }
                        ToggleLine("Companion tool settings", backupIncludeSettings) { backupIncludeSettings = it }
                        LibraryCompanionStore.backupProfiles(context).forEach { saved ->
                            TextButton(onClick = {
                                backupName = saved.name
                                backupIncludeNotes = saved.includeNotes
                                backupIncludeTags = saved.includeTags
                                backupIncludeRatings = saved.includeRatings
                                backupIncludeStatuses = saved.includeStatuses
                                backupIncludeQueue = saved.includeQueues
                                backupIncludeGoals = saved.includeGoals
                                backupIncludeProgress = saved.includeProgress
                                backupIncludeCategories = saved.includeCategories
                                backupIncludeSettings = saved.includeSettings
                            }) { Text("Load profile: ${saved.name}") }
                        }
                        Text("Preview is generated from your current Library data. Manga records and source metadata are not modified.", style = MaterialTheme.typography.bodySmall)
                        if (backupPreview.isNotBlank()) Text(backupPreview.take(1_200), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            val profile = currentBackupProfile(backupName, backupIncludeNotes, backupIncludeTags, backupIncludeRatings, backupIncludeStatuses, backupIncludeQueue, backupIncludeGoals, backupIncludeProgress, backupIncludeCategories, backupIncludeSettings)
                            backupPreview = LibraryCompanionStore.exportJson(context, profile, makeLibrarySnapshot(books))
                        }) { Text("Preview") }
                        TextButton(onClick = {
                            val profile = currentBackupProfile(backupName, backupIncludeNotes, backupIncludeTags, backupIncludeRatings, backupIncludeStatuses, backupIncludeQueue, backupIncludeGoals, backupIncludeProgress, backupIncludeCategories, backupIncludeSettings)
                            LibraryCompanionStore.saveBackupProfile(context, profile)
                            backupPreview = LibraryCompanionStore.exportJson(context, profile, makeLibrarySnapshot(books))
                            showBackup = false
                            KomikkuFeatureStore.shareText(context, "Komikku backup · ${profile.name}", backupPreview)
                        }) { Text("Save & export") }
                    }
                },
                dismissButton = { TextButton(onClick = { showBackup = false }) { Text("Cancel") } },
            )
        }
        if (showImportProfile) {
            AlertDialog(
                onDismissRequest = { showImportProfile = false },
                title = { Text("Reusable import profile") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = importProfileName, onValueChange = { importProfileName = it }, label = { Text("Profile name") }, singleLine = true)
                        OutlinedTextField(value = importSources, onValueChange = { importSources = it }, label = { Text("Source IDs, comma-separated") }, singleLine = true)
                        OutlinedTextField(value = importThreshold.toString(), onValueChange = { importThreshold = it.toIntOrNull()?.coerceIn(0, 100) ?: importThreshold }, label = { Text("Match threshold (0–100)") }, singleLine = true)
                        ToggleLine("Only auto-add strong matches", importStrongOnly) { importStrongOnly = it }
                        Text("Active profile: ${LibraryCompanionStore.activeImportProfile(context)?.name ?: "None"}")
                        LibraryCompanionStore.importProfiles(context).forEach { profile ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${profile.name} · ${profile.sourceIds.size} sources · threshold ${profile.matchThreshold}% · strong only=${profile.strongOnly}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { LibraryCompanionStore.setActiveImportProfile(context, profile.name); Toast.makeText(context, "${profile.name} will be applied to the next quick title search", Toast.LENGTH_SHORT).show() }) { Text("Use") }
                            }
                        }
                        TextButton(onClick = { LibraryCompanionStore.setActiveImportProfile(context, null) }) { Text("Clear active profile") }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    LibraryCompanionStore.saveImportProfile(context, LibraryCompanionStore.ImportProfile(importProfileName, importSources.split(',').mapNotNull { it.trim().toLongOrNull() }, importThreshold, importStrongOnly))
                    LibraryCompanionStore.setActiveImportProfile(context, importProfileName)
                    showImportProfile = false
                    Toast.makeText(context, "Import profile saved locally", Toast.LENGTH_SHORT).show()
                }) { Text("Save profile") } },
                dismissButton = { TextButton(onClick = { showImportProfile = false }) { Text("Close") } },
            )
        }
        if (showFollowedCreators) {
            AlertDialog(
                onDismissRequest = { showFollowedCreators = false },
                title = { Text("Followed creators and tag colors") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp)) {
                        Text("Followed creators are stored privately; enable the Followed creators filter on the Library tab.")
                        followedCreators.forEach { name -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(name, Modifier.weight(1f)); TextButton(onClick = { LibraryCompanionStore.followCreator(context, name, false); refreshSidecars() }) { Text("Unfollow") } } }
                        Text("Tag colors are edited from each book's personal details. They are decorative labels only and never replace source tags.")
                    }
                },
                confirmButton = { TextButton(onClick = { showFollowedCreators = false }) { Text("Done") } },
            )
        }
        if (showStorageWatch) {
            AlertDialog(onDismissRequest = { showStorageWatch = false }, title = { Text("Low-storage watch enabled") }, text = { Text("A daily background check will notify you when app downloads reach the saved threshold of $storageThresholdText GB. It will not delete files automatically.") }, confirmButton = { TextButton(onClick = { showStorageWatch = false }) { Text("OK") } })
        }
        metadataComparison?.let { preview ->
            AlertDialog(
                onDismissRequest = { metadataComparison = null },
                title = { Text("Source metadata comparison") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp)) {
                        Text("Current local values are compared with the live source response. Nothing changes until you choose Apply.")
                        if (preview.changes.isEmpty()) Text("No metadata differences were returned.")
                        preview.changes.forEach { change ->
                            Text(change.label, style = MaterialTheme.typography.titleSmall)
                            Text("Current: ${change.current.ifBlank { "(empty)" }}", style = MaterialTheme.typography.bodySmall)
                            Text("Source: ${change.offered.ifBlank { "(empty)" }}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    val remote = preview.remote
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                repository.update(
                                    MangaUpdate(
                                        id = preview.book.manga.id,
                                        title = remote.title,
                                        author = remote.author,
                                        artist = remote.artist,
                                        description = remote.description,
                                        genre = remote.genre?.split(",")?.map(String::trim)?.filter(String::isNotBlank),
                                        thumbnailUrl = remote.thumbnail_url,
                                        status = remote.status.toLong(),
                                        updateStrategy = remote.update_strategy,
                                        initialized = true,
                                    ),
                                )
                            }
                        }.onSuccess {
                            metadataComparison = null
                            Toast.makeText(context, "Source metadata applied", Toast.LENGTH_SHORT).show()
                            loadLibrary()
                        }.onFailure { error -> Toast.makeText(context, "Could not apply metadata: ${error.message}", Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Apply source metadata") } },
                dismissButton = { TextButton(onClick = { metadataComparison = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun CompanionBookRow(
    book: CompanionBook,
    checked: Boolean,
    tagColors: Map<String, String>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onQueue: () -> Unit,
    onRead: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AsyncImage(
            model = MangaCoverData(book.manga.id, book.manga.source, book.manga.favorite, book.manga.thumbnailUrl, book.manga.coverLastModified),
            contentDescription = "Cover for ${book.manga.title}",
            modifier = Modifier.size(width = 54.dp, height = 76.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(book.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text("${book.sourceName} · ${book.library.unreadCount} unread · ${book.personal.status} · ${book.personal.rating}/5", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (book.personal.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    book.personal.tags.take(3).forEach { tag ->
                        val color = tagColors.entries.firstOrNull { it.key.equals(tag, true) }?.value?.let { runCatching { Color(AndroidColor.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.secondaryContainer
                        Surface(color = color.copy(alpha = 0.42f), contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(4.dp)) {
                            Text(tag, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (book.personal.note.isNotBlank()) Text(book.personal.note, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onRead, enabled = book.library.unreadCount > 0) { Text("Read") }
            TextButton(onClick = onQueue) { Text("Queue") }
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun BookPersonalEditor(
    book: CompanionBook,
    onDismiss: () -> Unit,
    onCompare: () -> Unit,
    onSave: (LibraryCompanionStore.PersonalRecord, ReadingMode?) -> Unit,
    onFollowCreator: (String, Boolean) -> Unit,
    onSetTagColor: (String, String) -> Unit,
) {
    var note by remember(book.manga.id) { mutableStateOf(book.personal.note) }
    var tagText by remember(book.manga.id) { mutableStateOf(book.personal.tags.joinToString(", ")) }
    var labelText by remember(book.manga.id) { mutableStateOf(book.personal.labels.joinToString(", ")) }
    var warning by remember(book.manga.id) { mutableStateOf(book.personal.warning) }
    var status by remember(book.manga.id) { mutableStateOf(book.personal.status) }
    var bundle by remember(book.manga.id) { mutableStateOf(book.personal.bundleName) }
    var interval by remember(book.manga.id) { mutableStateOf(book.personal.expectedReleaseDays.toString()) }
    var rank by remember(book.manga.id) { mutableIntStateOf(book.personal.favoriteRank) }
    var rating by remember(book.manga.id) { mutableFloatStateOf(book.personal.rating.toFloat()) }
    var tagColorHex by remember(book.manga.id) { mutableStateOf("#80CBC4") }
    var snoozeMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var selectedMode by remember(book.manga.id) { mutableStateOf<ReadingMode?>(null) }
    var snoozeUntil by remember(book.manga.id) { mutableStateOf(book.personal.snoozedUntil) }
    var snoozeUnread by remember(book.manga.id) { mutableIntStateOf(book.personal.snoozeAtUnreadCount) }
    var snoozeUntilStatus by remember(book.manga.id) { mutableStateOf(book.personal.snoozeUntilStatus) }
    val modes = ReadingMode.entries

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.manga.title) },
        text = {
            Column(Modifier.heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${book.sourceName} · ${book.library.readCount}/${book.library.totalChapters} read · ${book.library.unreadCount} unread")
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Private note") }, minLines = 2)
                OutlinedTextField(value = tagText, onValueChange = { tagText = it }, label = { Text("Personal tags, comma-separated") })
                OutlinedTextField(value = labelText, onValueChange = { labelText = it }, label = { Text("Custom labels") })
                Text("Personal rating: ${rating.toInt()}/5")
                Slider(value = rating, onValueChange = { rating = it }, valueRange = 0f..5f, steps = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Status")
                    BoxMenu(value = status, options = listOf("Want to read", "Reading", "On hold", "Finished", "Dropped", "Ongoing", "Abandoned"), onSelect = { status = it })
                }
                OutlinedTextField(value = warning, onValueChange = { warning = it }, label = { Text("Personal content warning") }, minLines = 2)
                OutlinedButton(onClick = onCompare, modifier = Modifier.fillMaxWidth()) { Text("Compare current source metadata") }
                OutlinedTextField(value = bundle, onValueChange = { bundle = it }, label = { Text("Series bundle name") }, singleLine = true)
                OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit).take(3) }, label = { Text("Expected release interval (days)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Personal favorites rank: $rank")
                    TextButton(onClick = { rank = (rank - 1).coerceAtLeast(0) }) { Text("−") }
                    TextButton(onClick = { rank = (rank + 1).coerceAtMost(100) }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reading mode")
                    BoxMenu(value = selectedMode?.name?.replace('_', ' ') ?: "Keep current", options = listOf("Keep current") + modes.filterNot { it == ReadingMode.DEFAULT }.map { it.name.replace('_', ' ') }, onSelect = { label -> selectedMode = modes.firstOrNull { it.name.replace('_', ' ').equals(label, true) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { snoozeUntil = System.currentTimeMillis() + 24L * 60 * 60 * 1000; snoozeUnread = -1; snoozeUntilStatus = "" }) { Text("Snooze 1 day") }
                    TextButton(onClick = { snoozeUntil = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000; snoozeUnread = -1; snoozeUntilStatus = "" }) { Text("Snooze 7 days") }
                    TextButton(onClick = { snoozeUntil = 0L; snoozeUnread = book.library.unreadCount.toInt().coerceAtLeast(0); snoozeUntilStatus = "" }) { Text("Until catch-up") }
                    TextButton(onClick = { snoozeUntil = 0L; snoozeUnread = -1; snoozeUntilStatus = status }) { Text("Until status changes") }
                }
                Text("Tag-based colors")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = tagColorHex, onValueChange = { tagColorHex = it.take(24) }, label = { Text("Hex color") }, singleLine = true, modifier = Modifier.weight(1f))
                    val firstTag = tagText.split(',').firstOrNull()?.trim().orEmpty()
                    TextButton(onClick = {
                        if (firstTag.isNotBlank() && runCatching { AndroidColor.parseColor(tagColorHex) }.isSuccess) onSetTagColor(firstTag, tagColorHex)
                    }, enabled = firstTag.isNotBlank()) { Text("Apply") }
                }
                listOf(book.manga.author, book.manga.artist).filterNotNull().filter(String::isNotBlank).distinct().forEach { creator ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Follow $creator", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onFollowCreator(creator, true) }) { Text("Follow") }
                        TextButton(onClick = { onFollowCreator(creator, false) }) { Text("Unfollow") }
                    }
                }
                val predicted = LibraryCompanionRules.predictedReleaseAt(book.library.latestUpload, interval.toIntOrNull() ?: 0)
                if (predicted != null) Text("Next expected update: ${Date(predicted)}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = {
            val tags = tagText.split(',').map(String::trim).filter(String::isNotBlank).distinct()
            val labels = labelText.split(',').map(String::trim).filter(String::isNotBlank).distinct()
            val record = book.personal.copy(
                note = note,
                rating = rating.toInt(),
                tags = tags,
                labels = labels,
                status = status,
                warning = warning,
                snoozedUntil = snoozeUntil,
                snoozeAtUnreadCount = snoozeUnread,
                snoozeUntilStatus = snoozeUntilStatus,
                favoriteRank = rank,
                bundleName = bundle,
                readingMode = selectedMode?.flagValue ?: book.personal.readingMode,
                expectedReleaseDays = interval.toIntOrNull()?.coerceIn(0, 365) ?: 0,
            )
            onSave(record, selectedMode)
        }) { Text("Save personal details") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(book.personal.copy(snoozedUntil = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000, snoozeAtUnreadCount = -1, snoozeUntilStatus = ""), null) }) { Text("Snooze 30 days") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun BoxMenu(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text(value) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private data class CleanupCandidate(
    val key: String,
    val book: CompanionBook,
    val issue: String,
    val canCompareSource: Boolean,
)

private fun buildCleanupCandidates(context: android.content.Context, books: List<CompanionBook>): List<CleanupCandidate> {
    val sourceManager = Injekt.get<SourceManager>()
    val now = System.currentTimeMillis()
    return buildList {
        books.filter { it.manga.thumbnailUrl.isNullOrBlank() }.forEach { add(CleanupCandidate("${it.manga.id}:cover", it, "Missing cover", sourceManager.get(it.manga.source) != null)) }
        books.filter { sourceManager.get(it.manga.source) == null }.forEach { add(CleanupCandidate("${it.manga.id}:source", it, "Source unavailable", false)) }
        books.filter { it.library.latestUpload <= 0L || now - it.library.latestUpload > 180L * 24 * 60 * 60 * 1000 }.forEach { add(CleanupCandidate("${it.manga.id}:stale", it, "No update in 180+ days (or timestamp unavailable)", sourceManager.get(it.manga.source) != null)) }
        books.groupBy { LibraryCompanionRules.normalizeTitle(it.manga.title) }
            .filter { it.key.isNotBlank() && it.value.size > 1 }
            .values.forEach { group ->
                group.forEach { book ->
                    val others = group.filterNot { it.manga.id == book.manga.id }.joinToString { "${it.manga.title} [${it.sourceName}]" }
                    add(CleanupCandidate("${book.manga.id}:duplicate", book, "Possible duplicate edition · also: $others", false))
                }
            }
    }.distinctBy { it.key }
}

private suspend fun loadOfflinePackChapters(selected: List<CompanionBook>, limitPerBook: Int): List<Pair<Manga, List<Chapter>>> = withContext(Dispatchers.IO) {
    buildList {
        selected.forEach { book ->
            val chapters = if (book.manga.source == MERGED_SOURCE_ID) {
                Injekt.get<GetMergedChaptersByMangaId>().await(book.manga.id, applyFilter = true)
            } else {
                Injekt.get<GetChaptersByMangaId>().await(book.manga.id, applyFilter = true)
            }.filterNot { it.read }.sortedBy { it.dateUpload }.take(limitPerBook.coerceIn(1, 100))
            if (book.manga.source == MERGED_SOURCE_ID) {
                val merged = Injekt.get<GetMergedMangaById>().await(book.manga.id).associateBy { it.id }
                chapters.groupBy { it.mangaId }.forEach { (mangaId, rows) -> merged[mangaId]?.let { add(it to rows) } }
            } else if (chapters.isNotEmpty()) {
                add(book.manga to chapters)
            }
        }
    }
}

private suspend fun buildOfflinePackEstimate(context: android.content.Context, selected: List<CompanionBook>, limitPerBook: Int): String {
    val chapterGroups = loadOfflinePackChapters(selected, limitPerBook)
    val provider = Injekt.get<DownloadProvider>()
    val sourceManager = Injekt.get<SourceManager>()
    val downloadManager = Injekt.get<DownloadManager>()
    val fallbackBytesPerChapter = 4L * 1024 * 1024
    val estimatedBytes = chapterGroups.sumOf { (manga, chapters) ->
        val directory = provider.findMangaDir(manga.ogTitle, sourceManager.getOrStub(manga.source))
        val bytes = directory?.let { dir ->
            fun total(file: com.hippo.unifile.UniFile): Long = if (file.isDirectory) file.listFiles().orEmpty().sumOf(::total) else file.length().coerceAtLeast(0L)
            total(dir)
        } ?: 0L
        val existingCount = downloadManager.getDownloadCount(manga)
        val average = if (bytes > 0L && existingCount > 0) bytes / existingCount else fallbackBytesPerChapter
        average * chapters.size
    }
    return buildString {
        appendLine("Selected books: ${selected.size}")
        chapterGroups.forEach { (manga, chapters) -> appendLine("• ${manga.title}: ${chapters.size} unread chapter(s)") }
        appendLine("Total chapters to queue: ${chapterGroups.sumOf { it.second.size }}")
        appendLine("Estimated storage: ${formatBytes(estimatedBytes)}")
        append("Estimate uses actual downloaded bytes per existing chapter when available; otherwise it assumes about 4 MB per chapter. The queue starts only after you confirm.")
    }
}

private suspend fun queueOfflinePack(context: android.content.Context, selected: List<CompanionBook>, limitPerBook: Int): Int {
    val groups = loadOfflinePackChapters(selected, limitPerBook)
    val downloadManager = Injekt.get<DownloadManager>()
    groups.forEach { (manga, chapters) -> downloadManager.downloadChapters(manga, chapters) }
    return groups.sumOf { it.second.size }
}

private fun makeLibrarySnapshot(books: List<CompanionBook>): org.json.JSONArray = org.json.JSONArray().also { rows ->
    books.forEach { book ->
        rows.put(
            org.json.JSONObject()
                .put("mangaId", book.manga.id)
                .put("readCount", book.library.readCount)
                .put("totalChapters", book.library.totalChapters)
                .put("categoryIds", org.json.JSONArray(book.library.categories)),
        )
    }
}

private fun currentBackupProfile(
    name: String,
    includeNotes: Boolean,
    includeTags: Boolean,
    includeRatings: Boolean,
    includeStatuses: Boolean,
    includeQueues: Boolean,
    includeGoals: Boolean,
    includeProgress: Boolean,
    includeCategories: Boolean,
    includeSettings: Boolean,
) = LibraryCompanionStore.BackupProfile(
    name = name,
    includeNotes = includeNotes,
    includeTags = includeTags,
    includeRatings = includeRatings,
    includeStatuses = includeStatuses,
    includeQueues = includeQueues,
    includeGoals = includeGoals,
    includeProgress = includeProgress,
    includeCategories = includeCategories,
    includeSettings = includeSettings,
)

private fun readingStreakDays(history: List<HistoryWithRelations>): Int {
    val zone = java.time.ZoneId.systemDefault()
    val readDates = history.mapNotNull { it.readAt?.toInstant()?.atZone(zone)?.toLocalDate() }.toSet()
    val today = java.time.LocalDate.now(zone)
    var day = if (today in readDates) today else today.minusDays(1)
    var streak = 0
    while (day in readDates) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

private suspend fun buildCleanupReport(context: android.content.Context, books: List<CompanionBook>): String {
    val sourceManager = Injekt.get<SourceManager>()
    val duplicates = books.groupBy { LibraryCompanionRules.normalizeTitle(it.manga.title) }.filter { it.key.isNotBlank() && it.value.size > 1 }
    val noCover = books.filter { it.manga.thumbnailUrl.isNullOrBlank() }
    val missingSource = books.filter { sourceManager.get(it.manga.source) == null }
    val stale = books.filter { it.library.latestUpload <= 0L || System.currentTimeMillis() - it.library.latestUpload > 180L * 24 * 60 * 60 * 1000 }
    return buildString {
        appendLine("Scanned ${books.size} current Library titles. No record or file was changed.")
        appendLine("Missing covers: ${noCover.size}")
        appendLine("Missing/unavailable source registrations: ${missingSource.size}")
        appendLine("No chapter update in 180+ days or no update timestamp: ${stale.size}")
        appendLine("Duplicate title groups: ${duplicates.size}")
        duplicates.entries.take(30).forEach { (title, group) -> appendLine("• $title — ${group.joinToString { "${it.manga.title} [${it.sourceName}]" }}") }
        noCover.take(20).forEach { appendLine("• Missing cover: ${it.manga.title}") }
        missingSource.take(20).forEach { appendLine("• Source unavailable: ${it.manga.title} (ID ${it.manga.source})") }
        stale.take(20).forEach { appendLine("• Stale metadata candidate: ${it.manga.title}") }
        append("Review candidates here; duplicates are never removed automatically.")
    }
}

private suspend fun buildStorageReport(context: android.content.Context, books: List<CompanionBook>): String {
    val storage = Injekt.get<StorageManager>()
    val root = storage.getDownloadsDirectory()
    fun size(file: com.hippo.unifile.UniFile): Long = if (file.isDirectory) file.listFiles().orEmpty().sumOf(::size) else file.length().coerceAtLeast(0L)
    val totalBytes = root?.let(::size) ?: 0L
    val provider = Injekt.get<DownloadProvider>()
    val sourceManager = Injekt.get<SourceManager>()
    val largest = books.mapNotNull { book ->
        val source = sourceManager.getOrStub(book.manga.source)
        provider.findMangaDir(book.manga.ogTitle, source)?.let { it.name.orEmpty() to size(it) }
    }.sortedByDescending { it.second }.take(10)
    val queueBytes = books.filter { it.library.unreadCount > 0 }.take(50).sumOf { book ->
        val dirs = provider.findMangaDir(book.manga.ogTitle, sourceManager.getOrStub(book.manga.source))
        val currentSize = dirs?.let(::size) ?: 0L
        val downloaded = downloadManagerForEstimate(context).getDownloadCount(book.manga).coerceAtLeast(1)
        if (currentSize > 0L) currentSize / downloaded else 0L
    }
    return buildString {
        appendLine("Downloads directory total: ${formatBytes(totalBytes)}")
        appendLine("Approximate size for one unread chapter across the first 50 library candidates: ${formatBytes(queueBytes)}")
        appendLine("Largest downloaded series:")
        if (largest.isEmpty()) appendLine("• No downloaded series found or storage access is unavailable.")
        largest.forEach { appendLine("• ${it.first}: ${formatBytes(it.second)}") }
        append("No files will be removed. Space estimates are based on actual download-directory files and are approximate for future chapters.")
    }
}

private fun downloadManagerForEstimate(context: android.content.Context): DownloadManager = Injekt.get()
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private suspend fun checkLibrarySourceUpdateHealth(
    context: android.content.Context,
    books: List<CompanionBook>,
): List<String> = withContext(Dispatchers.IO) {
    val sourceManager = Injekt.get<SourceManager>()
    val samples = books.filterNot { it.manga.source == MERGED_SOURCE_ID }
        .distinctBy { it.manga.source }
        .take(24)
    samples.forEachIndexed { index, book ->
        val startedAt = System.currentTimeMillis()
        val source = runCatching { sourceManager.get(book.manga.source) }.getOrNull()
        if (source == null) {
            KomikkuExtendedFeatureStore.recordSourceEvent(context, book.sourceName, "library-update-check", false, System.currentTimeMillis() - startedAt, "Source is not installed or available")
        } else {
            val result = runCatching {
                source.getMangaUpdate(
                    manga = book.manga.toSManga(),
                    chapters = emptyList(),
                    fetchDetails = false,
                    fetchChapters = true,
                ).manga
            }
            val elapsed = System.currentTimeMillis() - startedAt
            result.onSuccess {
                KomikkuExtendedFeatureStore.recordSourceEvent(context, source.name, "library-update-check", true, elapsed)
            }.onFailure { error ->
                KomikkuExtendedFeatureStore.recordSourceEvent(context, source.name, "library-update-check", false, elapsed, error.message ?: error.javaClass.simpleName)
            }
        }
        if (index < samples.lastIndex) delay(180)
    }
    KomikkuExtendedFeatureStore.sourceEvents(context)
}
