package exh.ui.batchadd

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.workManager
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import android.util.AtomicFile
import android.util.Base64
import coil3.compose.AsyncImage

class BatchTitleSearchScreen(
    private val inputUriString: String,
    private val existingJobId: String? = null,
    private val speedPresetName: String = TitleSearchSpeedPreset.FAST.name,
    private val autoImport: Boolean = false,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        val speedPreset = TitleSearchSpeedPreset.fromName(speedPresetName)
        var jobId by rememberSaveable(inputUriString, existingJobId) {
            mutableStateOf(existingJobId ?: inputUriString.takeIf(String::isBlank)?.let { BatchTitleSearchWorker.savedJobId(context.applicationContext) })
        }
        var sourceSelectionConfirmed by rememberSaveable(inputUriString, existingJobId) {
            mutableStateOf(inputUriString.isBlank() || existingJobId != null)
        }
        var selectedSourceIds by remember(inputUriString, existingJobId) { mutableStateOf<Set<Long>>(emptySet()) }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val availableSources = remember {
            sourceManager.getVisibleSources()
                .filterIsInstance<HttpSource>()
                .filter { it.lang.equals("en", ignoreCase = true) || it.lang.equals("all", ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
        }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        var results by remember { mutableStateOf<List<TitleSearchCandidate>>(emptyList()) }
        var inputTitles by remember { mutableStateOf<List<String>>(emptyList()) }
        var importInfo by remember { mutableStateOf<WorkInfo?>(null) }
        var importedRows by remember { mutableStateOf<List<AutoImportedTitleResult>>(emptyList()) }
        var searchResultsLoaded by remember(jobId) { mutableStateOf(false) }
        var fallbackImportWorkId by remember(jobId) {
            mutableStateOf(jobId?.let { BatchTitleSearchWorker.savedAutoImportWorkId(context, it) })
        }
        val selected = remember { mutableStateListOf<String>() }
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val inputUri = remember(inputUriString) { Uri.parse(inputUriString) }

        LaunchedEffect(jobId, autoImport) {
            if (autoImport) jobId?.let { BatchTitleSearchWorker.saveAutoImportRequested(context.applicationContext, it, true) }
        }
        LaunchedEffect(inputUriString, existingJobId, speedPresetName, sourceSelectionConfirmed, selectedSourceIds) {
            if (jobId != null) return@LaunchedEffect
            if (inputUriString.isBlank() || !sourceSelectionConfirmed || selectedSourceIds.isEmpty()) return@LaunchedEffect
            runCatching { context.contentResolver.takePersistableUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            jobId = BatchTitleSearchWorker.enqueue(
                context.applicationContext,
                inputUri,
                selectedSourceIds,
                speedPreset,
                autoImport = autoImport,
            ).toString()
        }
        LaunchedEffect(jobId) {
            val id = jobId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collect { workInfo ->
                info = workInfo
                if (workInfo?.state?.isFinished == true) {
                    val resultPath = workInfo.outputData.getString(BatchTitleSearchWorker.KEY_RESULT_FILE)
                        ?: BatchTitleSearchWorker.partialResultsPath(context, id.toString())
                    results = withContext(Dispatchers.IO) {
                        BatchTitleSearchWorker.readResults(context, resultPath)
                    }
                    inputTitles = withContext(Dispatchers.IO) {
                        BatchTitleSearchWorker.readInputTitles(
                            context,
                            workInfo.outputData.getString(BatchTitleSearchWorker.KEY_TITLES_RESULT_FILE),
                        )
                    }
                    searchResultsLoaded = true
                }
            }
        }
        val autoImportRequested = autoImport ||
            jobId?.let { BatchTitleSearchWorker.isAutoImportRequested(context, it) } == true ||
            info?.progress?.getBoolean(BatchTitleSearchWorker.KEY_AUTO_IMPORT, false) == true ||
            info?.outputData?.getBoolean(BatchTitleSearchWorker.KEY_AUTO_IMPORT, false) == true ||
            fallbackImportWorkId != null
        val importWorkId = info?.outputData?.getString(BatchTitleSearchWorker.KEY_IMPORT_WORK_ID) ?: fallbackImportWorkId
        LaunchedEffect(jobId, importWorkId, fallbackImportWorkId, info?.state, results, inputTitles, autoImportRequested) {
            if (!autoImportRequested || info?.state != WorkInfo.State.SUCCEEDED) return@LaunchedEffect
            var resolvedImportWorkId = importWorkId
            val searchWorkId = jobId
            if (resolvedImportWorkId == null && searchResultsLoaded && results.isNotEmpty() && searchWorkId != null) {
                val urls = TitleSearchCandidateDeduplicator.bestPerInputPreferringStrong(results)
                    .map { it.url }.distinctBy(::canonicalImportUrl)
                if (urls.isNotEmpty()) {
                    val started = withContext(Dispatchers.IO) {
                        BatchImportJob.startTitleMatches(context.applicationContext, urls, titleSearchWorkId = searchWorkId)
                    }
                    resolvedImportWorkId = started.toString()
                    BatchTitleSearchWorker.saveAutoImportWorkId(context.applicationContext, searchWorkId, resolvedImportWorkId)
                    fallbackImportWorkId = resolvedImportWorkId
                }
            }
            val importId = resolvedImportWorkId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (importId == null) {
                importInfo = null
                importedRows = buildAutomaticTitleResults(inputTitles, results, emptyMap())
                return@LaunchedEffect
            }
            manager.getWorkInfoByIdFlow(importId).collect { importWork ->
                importInfo = importWork
                if (importWork?.state?.isFinished == true) {
                    val resultFile = importWork.outputData.getString(BatchImportJob.KEY_TITLE_IMPORT_RESULTS_FILE)
                        ?: File(context.filesDir, "batch-title-import-results-$importId.tsv").absolutePath
                    val attempts = withContext(Dispatchers.IO) { readTitleImportAttempts(resultFile) }
                    importedRows = buildAutomaticTitleResults(inputTitles, results, attempts)
                }
            }
        }
        LaunchedEffect(jobId, info?.state, autoImportRequested) {
            if (autoImportRequested) return@LaunchedEffect
            if (info?.state != WorkInfo.State.RUNNING && info?.state != WorkInfo.State.ENQUEUED) return@LaunchedEffect
            val id = jobId ?: return@LaunchedEffect
            val path = BatchTitleSearchWorker.partialResultsPath(context, id)
            while (true) {
                val nextResults = withContext(Dispatchers.IO) {
                    BatchTitleSearchWorker.readResults(context, path)
                }
                if (nextResults.size != results.size) results = nextResults
                delay(4_000)
            }
        }
        val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        val progress = info?.progress
        val completed = progress?.getInt(BatchTitleSearchWorker.KEY_COMPLETED, 0) ?: 0
        val total = progress?.getInt(BatchTitleSearchWorker.KEY_TOTAL, 0) ?: 0
        val found = progress?.getInt(BatchTitleSearchWorker.KEY_FOUND, results.size) ?: results.size
        val foundStrong = progress?.getInt(
            BatchTitleSearchWorker.KEY_FOUND_STRONG,
            results.count { TitleMatchQuality.isStrong(it) },
        ) ?: results.count { TitleMatchQuality.isStrong(it) }
        val foundWeak = progress?.getInt(
            BatchTitleSearchWorker.KEY_FOUND_WEAK,
            results.count { !TitleMatchQuality.isStrong(it) },
        ) ?: results.count { !TitleMatchQuality.isStrong(it) }
        val etaSeconds = progress?.getInt(BatchTitleSearchWorker.KEY_ETA_SECONDS, -1) ?: -1
        val activeSpeedPreset = progress?.getString(BatchTitleSearchWorker.KEY_SPEED_PRESET)
            ?.let { TitleSearchSpeedPreset.fromName(it) } ?: speedPreset
        var paused by remember(jobId) { mutableStateOf(false) }
        LaunchedEffect(jobId, info?.state) {
            while (isActive) {
                paused = BatchTitleSearchWorker.isPaused(context, jobId)
                delay(500)
            }
        }

        val strongResults = remember(results) { results.filter { TitleMatchQuality.isStrong(it) } }
        val weakResults = remember(results) { results.filter { !TitleMatchQuality.isStrong(it) } }
        val visibleResults = if (selectedTab == 0) strongResults else weakResults
        val searchFinished = info?.state?.isFinished == true
        val searchSucceeded = info?.state == WorkInfo.State.SUCCEEDED
        val fallbackImportPending = autoImportRequested && searchSucceeded && searchResultsLoaded && importWorkId == null &&
            (results.isNotEmpty() || (inputTitles.isNotEmpty() && importedRows.isEmpty()))
        val autoImportPending = autoImportRequested && searchSucceeded &&
            (!searchResultsLoaded || (importWorkId != null && importInfo?.state?.isFinished != true) || fallbackImportPending)
        val autoImportComplete = autoImportRequested && searchSucceeded && searchResultsLoaded &&
            (importWorkId == null || importInfo?.state?.isFinished == true) && !fallbackImportPending
        val visibleImportRows = if (selectedTab == 0) importedRows.filter { it.added } else importedRows.filterNot { it.added }
        val selectedUrls = results.filter { it.id in selected }.map { it.url }.distinct()

        Scaffold(topBar = { scroll ->
            AppBar(title = if (autoImportComplete) "Title search results" else "Find titles in enabled sources", navigateUp = { navigator?.pop() }, scrollBehavior = scroll)
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val selectingSources = inputUriString.isNotBlank() && existingJobId == null && jobId == null && !sourceSelectionConfirmed
                if (selectingSources) {
                    Text(
                        "Choose the installed sources to search. Selecting fewer sources reduces data use, time, and device load.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { selectedSourceIds = availableSources.map { it.id }.toSet() }) {
                            Text("Select all")
                        }
                        TextButton(onClick = { selectedSourceIds = emptySet() }) {
                            Text("Clear")
                        }
                        Text(
                            "${selectedSourceIds.size}/${availableSources.size}",
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(availableSources, key = { it.id }) { source ->
                            val checked = source.id in selectedSourceIds
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        selectedSourceIds = if (value) selectedSourceIds + source.id else selectedSourceIds - source.id
                                    },
                                )
                                Column {
                                    Text(source.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(source.lang.toString(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Button(
                        enabled = selectedSourceIds.isNotEmpty(),
                        onClick = { sourceSelectionConfirmed = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text("Start search with ${selectedSourceIds.size} source${if (selectedSourceIds.size == 1) "" else "s"}")
                    }
                }
                if (!selectingSources && running) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Searching all enabled sources with the ${activeSpeedPreset.label} preset. Results stay grouped by input title, and partial matches appear below as titles finish.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text(if (total > 0) "Searching $completed/$total titles…" else "Searching enabled sources…")
                        }
                        if (total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Text("Found $found matches · Strong $foundStrong · Weak $foundWeak", style = MaterialTheme.typography.titleSmall)
                        if (etaSeconds >= 0 && completed < total) {
                            Text("Estimated time remaining: ${formatTitleSearchEta(etaSeconds)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { BatchTitleSearchWorker.setPaused(context, true, jobId) }) { Text("Pause") }
                            Button(onClick = { BatchTitleSearchWorker.setPaused(context, false, jobId) }) { Text("Resume") }
                            TextButton(onClick = { jobId?.let { BatchTitleSearchWorker.clearSavedJob(context, it); manager.cancelWorkById(UUID.fromString(it)) } }) { Text("Cancel") }
                        }
                        if (paused) Text("Paused · no new source searches will start", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!selectingSources && autoImportComplete) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Added (${importedRows.count { it.added }})") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Not added (${importedRows.count { !it.added }})") })
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                if (selectedTab == 0) "Best available matches successfully added to your library."
                                else "These titles were not added. They either had no search match or the source rejected/failed the add.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (visibleImportRows.isEmpty()) {
                            item {
                                val emptyMessage = when {
                                    importedRows.isEmpty() -> "No input titles were provided."
                                    selectedTab == 0 -> "No titles were added."
                                    else -> "All input titles were added."
                                }
                                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(visibleImportRows, key = { it.inputTitle.lowercase() }) { row ->
                            if (row.candidate != null) {
                                CandidateCard(candidate = row.candidate, checked = false, onCheckedChange = {}, showCheckbox = false)
                            } else {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(row.inputTitle, style = MaterialTheme.typography.titleSmall)
                                        Text(row.detail ?: "No matching title was found.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (!row.added && row.candidate != null && !row.detail.isNullOrBlank()) {
                                Text(row.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
                            }
                        }
                    }
                } else if (!selectingSources && autoImportPending) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Search complete. Adding the best strong match per title, or the best weak match when no strong match exists.", style = MaterialTheme.typography.bodyMedium)
                        val importProgress = importInfo?.progress
                        val done = importProgress?.getInt(BatchImportJob.KEY_COMPLETED, 0) ?: 0
                        val total = importProgress?.getInt(BatchImportJob.KEY_TOTAL, 0) ?: 0
                        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Text(if (total > 0) "Adding titles: $done/$total" else "Preparing library additions…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!selectingSources && results.isNotEmpty() && !autoImportRequested) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Strong (${strongResults.size})") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Weak (${weakResults.size})") },
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                if (selectedTab == 0) {
                                    "Strong matches: exact or close title hits. Prefer these for bulk add."
                                } else {
                                    "Weak matches: looser search hits kept for manual review. They stay available but are not pre-selected by “best per title”."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${visibleResults.size} on this tab · ${selected.size} selected overall",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        val ids = visibleResults.map { it.id }
                                        if (ids.all { it in selected }) {
                                            selected.removeAll(ids.toSet())
                                        } else {
                                            ids.forEach { id -> if (id !in selected) selected.add(id) }
                                        }
                                    },
                                ) { Text("Select all on tab") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        selected.clear()
                                        val candidates = if (selectedTab == 0) strongResults else weakResults
                                        TitleSearchCandidateDeduplicator.bestPerInput(candidates).forEach { selected.add(it.id) }
                                    },
                                ) {
                                    Text(if (selectedTab == 0) "Select best strong per title" else "Select closest weak per title")
                                }
                                TextButton(onClick = { selected.clear() }) { Text("Clear selection") }
                            }
                            Button(
                                enabled = selectedUrls.isNotEmpty(),
                                onClick = {
                                    BatchImportJob.startTitleMatches(context.applicationContext, selectedUrls)
                                    navigator?.pop()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Add selected confirmed titles (${selectedUrls.size})") }
                        }
                        items(visibleResults, key = { it.id }) { candidate ->
                            CandidateCard(
                                candidate = candidate,
                                checked = candidate.id in selected,
                                onCheckedChange = { checked ->
                                    if (checked) selected.add(candidate.id) else selected.remove(candidate.id)
                                },
                            )
                        }
                    }
                } else if (!selectingSources && autoImportRequested && searchSucceeded && !autoImportComplete) {
                    Text("Preparing title search results…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                } else if (!selectingSources && !running && info?.state?.isFinished == true) {
                    Text(
                        when (info?.state) {
                            WorkInfo.State.FAILED -> "Title search failed before completing; no titles were added automatically."
                            WorkInfo.State.CANCELLED -> "Title search was cancelled; no titles were added automatically."
                            else -> "No matching titles were found across the enabled installed sources."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (!selectingSources && !running) {
                    Text(
                        "Preparing title search…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: TitleSearchCandidate,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showCheckbox: Boolean = true,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            if (showCheckbox) Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            AsyncImage(
                model = candidate.thumbnailUrl,
                contentDescription = candidate.matchedTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(candidate.matchedTitle, style = MaterialTheme.typography.titleSmall)
                Text("${candidate.sourceName} · from: ${candidate.inputTitle}", style = MaterialTheme.typography.bodySmall)
                Text(candidate.url, style = MaterialTheme.typography.labelSmall)
                if (TitleMatchQuality.isExact(candidate)) {
                    Text("Exact title", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (TitleMatchQuality.isStrong(candidate)) {
                    Text("Strong match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

data class AutoImportedTitleResult(
    val inputTitle: String,
    val candidate: TitleSearchCandidate?,
    val added: Boolean,
    val detail: String?,
)

private data class TitleImportAttempt(val added: Boolean, val detail: String?)

private fun readTitleImportAttempts(path: String?): Map<String, TitleImportAttempt> = runCatching {
    val file = path?.let(::File) ?: return@runCatching emptyMap()
    if (!file.isFile) return@runCatching emptyMap()
    file.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 3) return@mapNotNull null
        val url = runCatching { String(Base64.decode(fields[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
        val detail = runCatching { String(Base64.decode(fields[2], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()?.ifBlank { null }
        canonicalImportUrl(url) to TitleImportAttempt(fields[0] == "ADDED", detail)
    }.toMap()
}.getOrDefault(emptyMap())

private fun buildAutomaticTitleResults(
    inputTitles: List<String>,
    candidates: List<TitleSearchCandidate>,
    attempts: Map<String, TitleImportAttempt>,
): List<AutoImportedTitleResult> {
    val titles = inputTitles.ifEmpty { candidates.map { it.inputTitle }.distinctBy { it.trim().lowercase() } }
    return titles.distinctBy { it.trim().lowercase() }.map { title ->
        val best = TitleSearchCandidateDeduplicator.bestPerInputPreferringStrong(
            candidates.filter { it.inputTitle.trim().equals(title.trim(), ignoreCase = true) },
        ).firstOrNull()
        val attempt = best?.let { attempts[canonicalImportUrl(it.url)] }
        AutoImportedTitleResult(
            inputTitle = title,
            candidate = best,
            added = attempt?.added == true,
            detail = when {
                best == null -> "No matching title was found in the selected sources."
                attempt == null -> "The title was not added."
                attempt.added -> null
                else -> attempt.detail ?: "The source could not add this title."
            },
        )
    }
}

private fun canonicalImportUrl(value: String): String = value.trim().trimEnd('/').lowercase()

data class TitleSearchCandidate(
    val id: String,
    val inputTitle: String,
    val matchedTitle: String,
    val sourceName: String,
    val url: String,
    val thumbnailUrl: String?,
)

/** Classifies search hits so the UI can split strong vs weak tabs. */
internal object TitleMatchQuality {
    fun isExact(candidate: TitleSearchCandidate): Boolean =
        candidate.matchedTitle.trim().equals(candidate.inputTitle.trim(), ignoreCase = true)

    fun isStrong(candidate: TitleSearchCandidate): Boolean {
        if (isExact(candidate)) return true
        val input = TitleSearchCandidateDeduplicator.normalize(candidate.inputTitle)
        val matched = TitleSearchCandidateDeduplicator.normalize(candidate.matchedTitle)
        if (input.isEmpty() || matched.isEmpty()) return false
        if (input == matched) return true
        if (matched.contains(input) || input.contains(matched)) return true
        val inputTokens = input.chunkedTokens()
        val matchedTokens = matched.chunkedTokens().toSet()
        if (inputTokens.isEmpty()) return false
        val hits = inputTokens.count { it in matchedTokens }
        return hits * 2 >= inputTokens.size && hits >= 2
    }

    /** Stable 0..2000 score used to choose the closest result for a required title. */
    fun similarityScore(candidate: TitleSearchCandidate): Int {
        val input = TitleSearchCandidateDeduplicator.normalize(candidate.inputTitle)
        val matched = TitleSearchCandidateDeduplicator.normalize(candidate.matchedTitle)
        if (input.isEmpty() || matched.isEmpty()) return 0
        val maxLength = maxOf(input.length, matched.length)
        val editScore = ((maxLength - levenshteinDistance(input, matched)) * 1000 / maxLength).coerceAtLeast(0)
        val inputTokens = candidate.inputTitle.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val matchedTokens = candidate.matchedTitle.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()
        val tokenScore = if (inputTokens.isEmpty()) 0 else inputTokens.count { it in matchedTokens } * 1000 / inputTokens.size
        return editScore + tokenScore
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun String.chunkedTokens(): List<String> {
        if (length < 3) return emptyList()
        return Regex("[a-z0-9]{3,}").findAll(this).map { it.value }.toList()
            .ifEmpty { listOf(this) }
    }
}

/**
 * Per-input-title collapse only: keep every unique URL for a given input title,
 * and never merge different input titles together.
 */
internal object TitleSearchCandidateDeduplicator {
    fun collapse(candidates: List<TitleSearchCandidate>): List<TitleSearchCandidate> {
        return candidates
            .groupBy { it.inputTitle.trim().lowercase() }
            .values
            .flatMap { group ->
                group
                    .distinctBy { it.url.trim().trimEnd('/').lowercase() }
                    .sortedWith(
                        compareByDescending<TitleSearchCandidate> { TitleMatchQuality.isExact(it) }
                            .thenByDescending { TitleMatchQuality.isStrong(it) }
                            .thenBy { it.sourceName.lowercase() }
                            .thenBy { it.url.lowercase() },
                    )
            }
    }

    fun bestPerInputPreferringStrong(candidates: List<TitleSearchCandidate>): List<TitleSearchCandidate> =
        candidates.groupBy { it.inputTitle.trim().lowercase() }.values.mapNotNull { group ->
            val strong = group.filter(TitleMatchQuality::isStrong)
            bestPerInput(if (strong.isNotEmpty()) strong else group).singleOrNull()
        }

    fun bestPerInput(candidates: List<TitleSearchCandidate>): List<TitleSearchCandidate> {
        return candidates
            .groupBy { it.inputTitle.trim().lowercase() }
            .values
            .map { group ->
                group.minWith(
                    compareByDescending<TitleSearchCandidate> { TitleMatchQuality.isExact(it) }
                        .thenByDescending { TitleMatchQuality.isStrong(it) }
                        .thenByDescending { TitleMatchQuality.similarityScore(it) }
                        .thenBy { it.sourceName.lowercase() }
                        .thenBy { it.url.lowercase() },
                )
            }
    }

    fun normalize(value: String): String = Normalizer
        .normalize(value.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " "), Normalizer.Form.NFKD)
        .lowercase()
        .filter { it.isLetterOrDigit() }
}

internal object TitleSearchCheckpoint {
    private const val PREFIX = "#batch-title-search-complete:"

    fun marker(title: String): String = PREFIX + Base64.encodeToString(title.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun isMarker(line: String): Boolean = line.startsWith(PREFIX)

    fun isComplete(lines: List<String>, title: String, decodedCandidates: List<TitleSearchCandidate>): Boolean {
        val firstLine = lines.firstOrNull()
        if (firstLine != null && isMarker(firstLine)) return firstLine == marker(title)
        // Legacy checkpoints had no marker. Only accept them when a result explicitly
        // belongs to this input title; a row for another index/title must be searched.
        return decodedCandidates.any { it.inputTitle.trim().equals(title.trim(), ignoreCase = true) }
    }
}

internal data class TitleSearchConcurrencyLimits(
    val parallelTitles: Int,
    val globalSourceSearches: Int,
    val perSourceSearches: Int,
    val timeoutMillis: Long,
)

internal enum class TitleSearchSpeedPreset(
    val label: String,
    val description: String,
    private val normalLimits: TitleSearchConcurrencyLimits,
    private val throttledLimits: TitleSearchConcurrencyLimits,
) {
    FAST(
        "Fast",
        "Fast while keeping the app responsive: up to 16 source requests at once.",
        TitleSearchConcurrencyLimits(2, 16, 3, 8_000L),
        TitleSearchConcurrencyLimits(1, 10, 2, 10_000L),
    ),
    BALANCED(
        "Balanced",
        "Moderate parallelism for a better balance between speed and source stability.",
        TitleSearchConcurrencyLimits(2, 12, 2, 9_000L),
        TitleSearchConcurrencyLimits(1, 8, 2, 12_000L),
    ),
    GENTLE(
        "Gentle",
        "Fewer simultaneous requests to reduce source and device load; usually slower.",
        TitleSearchConcurrencyLimits(2, 8, 1, 12_000L),
        TitleSearchConcurrencyLimits(1, 6, 1, 15_000L),
    );

    fun concurrencyLimits(throttled: Boolean): TitleSearchConcurrencyLimits =
        if (throttled) throttledLimits else normalLimits

    companion object {
        fun fromName(value: String?): TitleSearchSpeedPreset =
            values().firstOrNull { it.name == value } ?: FAST
    }
}

private fun formatTitleSearchEta(seconds: Int): String {
    val remaining = seconds.coerceAtLeast(0)
    return when {
        remaining < 60 -> "$remaining sec"
        remaining < 3_600 -> "${remaining / 60} min"
        else -> "${remaining / 3_600} hr ${(remaining % 3_600) / 60} min"
    }
}

class BatchTitleSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
    override suspend fun doWork(): Result {
        val workId = id.toString()
        val resultDirectory = File(applicationContext.filesDir, "batch-title-search-${id}.result-rows")
        val finalResultFile = File(applicationContext.filesDir, "batch-title-search-${id}.final-results")
        val titles = runCatching {
            inputData.getString(KEY_TITLES_FILE)?.let { File(it).readLines() }
                ?: inputData.getString(KEY_URI)?.let(Uri::parse)?.let { uri ->
                    applicationContext.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines -> lines.toList() }.orEmpty()
                }
                ?: emptyList()
        }.map { lines ->
            lines.map(::cleanTitle).map(String::trim).filter(String::isNotBlank).distinct()
        }.getOrElse { xLogE("Title file read failed", it); return Result.failure() }
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabled = sourcePreferences.disabledSources().get()
        val requestedSourceIds = inputData.getLongArray(KEY_SOURCE_IDS)?.toSet()
        val sources = sourceManager.getVisibleSources()
            .filterIsInstance<HttpSource>()
            .filter { requestedSourceIds?.contains(it.id) ?: (it.lang in enabledLanguages && it.id.toString() !in disabled) }
        if (!resultDirectory.exists() && !resultDirectory.mkdirs()) return Result.failure()
        resultDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(".") && it.name.endsWith(".tmp") }
            .forEach { it.delete() }
        fun rowFile(index: Int) = File(resultDirectory, "${index.toString().padStart(8, '0')}.row")
        val checkpointCandidates = titles.indices
            .filter { index -> isCompletedCheckpoint(rowFile(index), titles[index]) }
            .flatMap { index -> rowFile(index).readLines().filterNot(TitleSearchCheckpoint::isMarker) }
            .mapNotNull(::decode)
            .distinctBy { it.id }
        val checkpointResults = TitleSearchCandidateDeduplicator.collapse(checkpointCandidates)
        val completed = AtomicInteger(titles.indices.count { isCompletedCheckpoint(rowFile(it), titles[it]) })
        val initialCompleted = completed.get()
        val startedAtMillis = System.currentTimeMillis()
        val found = AtomicInteger(checkpointResults.size)
        val foundStrong = AtomicInteger(checkpointResults.count { TitleMatchQuality.isStrong(it) })
        val foundWeak = AtomicInteger(checkpointResults.count { !TitleMatchQuality.isStrong(it) })
        val speedPreset = TitleSearchSpeedPreset.fromName(inputData.getString(KEY_SPEED_PRESET))
        val limits = searchConcurrencyLimits(speedPreset)
        val titleSemaphore = Semaphore(limits.parallelTitles)
        val sourceSearchSemaphore = Semaphore(limits.globalSourceSearches)
        val perSourceSemaphores = sources.associate { it.id to Semaphore(limits.perSourceSearches) }
        setForeground(getForegroundInfo())
        setProgress(
            workDataOf(
                KEY_COMPLETED to completed.get(),
                KEY_TOTAL to titles.size,
                KEY_FOUND to found.get(),
                KEY_FOUND_STRONG to foundStrong.get(),
                KEY_FOUND_WEAK to foundWeak.get(),
                KEY_ETA_SECONDS to -1,
                KEY_SPEED_PRESET to speedPreset.name,
                KEY_AUTO_IMPORT to inputData.getBoolean(KEY_AUTO_IMPORT, false),
                KEY_PAUSED to isPaused(applicationContext, workId),
            ),
        )
        updateNotification(completed.get(), titles.size, "Searching ${sources.size} enabled sources · ${limits.globalSourceSearches} concurrent requests")
        coroutineScope {
            // Keep the number of waiting coroutines bounded. Creating one coroutine per
            // input title made large files consume memory and made pause feel ineffective.
            titles.indices.chunked(limits.parallelTitles).forEach { batch ->
                batch.mapNotNull { index ->
                    val title = titles[index]
                    if (isCompletedCheckpoint(rowFile(index), title)) return@mapNotNull null
                    async(Dispatchers.IO) {
                        titleSemaphore.withPermit {
                            awaitNotPaused(workId)
                            val candidates = coroutineScope {
                                // Do not create one coroutine for every installed source at once.
                                // A small source batch keeps the worker responsive on large source lists.
                                buildList {
                                    for (sourceBatch in sources.chunked(8)) {
                                        addAll(
                                            sourceBatch.map { source ->
                                                async {
                                                    searchSource(
                                                        source,
                                                        title,
                                                        sourceSearchSemaphore,
                                                        perSourceSemaphores.getValue(source.id),
                                                        limits.timeoutMillis,
                                                        workId,
                                                    )
                                                }
                                            }.awaitAll().flatten(),
                                        )
                                    }
                                }
                            }
                            val outputFile = rowFile(index)
                            val tempFile = File(resultDirectory, ".${outputFile.name}.tmp")
                            val titleCandidates = TitleSearchCandidateDeduplicator.collapse(candidates)
                            val rows = buildString {
                                append(TitleSearchCheckpoint.marker(title))
                                if (titleCandidates.isNotEmpty()) {
                                    append('\n')
                                    append(titleCandidates.joinToString("\n") { encode(it) })
                                }
                            }
                            try {
                                FileOutputStream(tempFile).use { stream ->
                                    if (rows.isNotEmpty()) stream.write(rows.toByteArray(Charsets.UTF_8))
                                    stream.fd.sync()
                                }
                                if (!tempFile.renameTo(outputFile)) throw IOException("Unable to commit title-search checkpoint ${outputFile.name}")
                            } catch (error: Throwable) {
                                tempFile.delete()
                                throw error
                            }
                            val done = completed.incrementAndGet()
                            found.addAndGet(titleCandidates.size)
                            foundStrong.addAndGet(titleCandidates.count { TitleMatchQuality.isStrong(it) })
                            foundWeak.addAndGet(titleCandidates.count { !TitleMatchQuality.isStrong(it) })
                            val completedThisRun = done - initialCompleted
                            val elapsedMillis = System.currentTimeMillis() - startedAtMillis
                            val etaSeconds = if (completedThisRun >= 5) {
                                (((titles.size - done).coerceAtLeast(0).toLong() * elapsedMillis) / completedThisRun / 1_000L)
                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt()
                            } else {
                                -1
                            }
                            if (done == titles.size || done % 5 == 0) {
                                setProgress(
                                    workDataOf(
                                        KEY_COMPLETED to done,
                                        KEY_TOTAL to titles.size,
                                        KEY_FOUND to found.get(),
                                        KEY_FOUND_STRONG to foundStrong.get(),
                                        KEY_FOUND_WEAK to foundWeak.get(),
                                        KEY_ETA_SECONDS to etaSeconds,
                                        KEY_SPEED_PRESET to speedPreset.name,
                                        KEY_AUTO_IMPORT to inputData.getBoolean(KEY_AUTO_IMPORT, false),
                                        KEY_PAUSED to isPaused(applicationContext, workId),
                                    ),
                                )
                                updateNotification(done, titles.size, "Finished $done/${titles.size} · ${found.get()} matches")
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val uniqueTitleCount = writeConsolidatedResults(resultDirectory, finalResultFile, titles)
        updateNotification(titles.size, titles.size, "Search complete · $uniqueTitleCount unique title matches")
        val autoImport = inputData.getBoolean(KEY_AUTO_IMPORT, false)
        val titlesResultFile = File(applicationContext.filesDir, "batch-title-search-$id.titles")
        titlesResultFile.writeText(titles.joinToString("\n") { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) })
        val importWorkId = if (autoImport) {
            val urls = TitleSearchCandidateDeduplicator.bestPerInputPreferringStrong(
                readResults(applicationContext, finalResultFile.absolutePath),
            ).map { it.url }.distinctBy(::canonicalImportUrl)
            if (urls.isEmpty()) null else runCatching {
                BatchImportJob.startTitleMatches(applicationContext, urls, titleSearchWorkId = workId).toString()
            }.onFailure { xLogE("Automatic title import could not be queued", it) }.getOrNull()
        } else null
        clearSavedJob(applicationContext, workId)
        val output = Data.Builder()
            .putString(KEY_RESULT_FILE, finalResultFile.absolutePath)
            .putString(KEY_TITLES_RESULT_FILE, titlesResultFile.absolutePath)
            .putInt(KEY_COMPLETED, titles.size)
            .putInt(KEY_TOTAL, titles.size)
            .putBoolean(KEY_AUTO_IMPORT, autoImport)
            .apply { if (importWorkId != null) putString(KEY_IMPORT_WORK_ID, importWorkId) }
            .build()
        return Result.success(output)
    }

    private fun writeConsolidatedResults(resultDirectory: File, finalResultFile: File, titles: List<String>): Int {
        val validTitleKeys = titles.map { it.trim().lowercase() }.toSet()
        val candidates = resultDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".row") }
            .sortedBy { it.name }
            .flatMap { it.readLines() }
            .mapNotNull(::decode)
            .filter { it.inputTitle.trim().lowercase() in validTitleKeys }
            .distinctBy { it.id }
        val uniqueCandidates = TitleSearchCandidateDeduplicator.collapse(candidates)
        val atomicFile = AtomicFile(finalResultFile)
        val output = atomicFile.startWrite()
        try {
            val payload = uniqueCandidates.joinToString("\n") { encode(it) }
            if (payload.isNotEmpty()) output.write(payload.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
        return uniqueCandidates.size
    }

    private fun searchConcurrencyLimits(speedPreset: TitleSearchSpeedPreset): TitleSearchConcurrencyLimits {
        val powerManager = applicationContext.getSystemService(PowerManager::class.java)
        val powerSave = powerManager?.isPowerSaveMode == true
        val thermallyConstrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            (powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_MODERATE
        }.getOrDefault(false)
        return speedPreset.concurrencyLimits(powerSave || thermallyConstrained)
    }

    private suspend fun searchSource(
        source: Source,
        title: String,
        globalSemaphore: Semaphore,
        sourceSemaphore: Semaphore,
        timeoutMillis: Long,
        workId: String,
    ): List<TitleSearchCandidate> {
        return try {
            awaitNotPaused(workId)
            suspend fun query() =
                sourceSemaphore.withPermit {
                    globalSemaphore.withPermit {
                        withTimeoutOrNull(timeoutMillis) { source.getSearchManga(1, title.sanitize(), source.getFilterList()) }
                    }
                }
            val page = query()
                ?: return emptyList()
            page.mangas.map { manga ->
                val local = manga.toDomainManga(source.id)
                val mangaUrl = absoluteUrl(source, manga.url)
                TitleSearchCandidate(
                    id = candidateId(title, source.id, mangaUrl),
                    inputTitle = title,
                    matchedTitle = manga.title,
                    sourceName = source.name,
                    url = mangaUrl,
                    thumbnailUrl = local.thumbnailUrl?.let { absoluteUrl(source, it) },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }
    private suspend fun awaitNotPaused(workId: String) {
        while (isPaused(applicationContext, workId) && !isStopped) {
            delay(750)
        }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        applicationContext.notificationBuilder(Notifications.CHANNEL_KOMIKKU_IMPORT) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Finding titles in enabled sources")
            setContentText("Searching enabled sources")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
        }.build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
    private fun updateNotification(completed: Int, total: Int, detail: String) {
        val openIntent = Intent(applicationContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java).apply {
            action = ACTION_OPEN_BATCH_TITLE_SEARCH
            putExtra(EXTRA_WORK_ID, id.toString())
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(applicationContext, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(applicationContext).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT)
                .setSmallIcon(R.drawable.ic_komikku)
                .setContentTitle("Finding titles in enabled sources")
                .setContentText(detail)
                .setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total), false)
                .setOngoing(completed < total)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(false)
                .addAction(R.drawable.ic_close_24dp, "Cancel", applicationContext.workManager.createCancelPendingIntent(id))
                .build(),
        )
    }
    companion object {
        const val KEY_URI = "title_uri"
        const val KEY_TITLES_FILE = "title_file"
        const val KEY_AUTO_IMPORT = "auto_import"
        const val KEY_TITLES_RESULT_FILE = "titles_result_file"
        const val KEY_IMPORT_WORK_ID = "import_work_id"
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_PAUSED = "paused"
        const val KEY_FOUND = "found"
        const val KEY_FOUND_STRONG = "found_strong"
        const val KEY_FOUND_WEAK = "found_weak"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_SPEED_PRESET = "speed_preset"
        const val KEY_SOURCE_IDS = "source_ids"
        const val NOTIFICATION_ID = -1810
        const val ACTION_OPEN_BATCH_TITLE_SEARCH = "com.manus.komikku.OPEN_BATCH_TITLE_SEARCH"
        const val EXTRA_WORK_ID = "batch_title_search_work_id"
        private const val PREFS = "batch_title_search_controls"
        private const val PAUSED = "paused"
        private const val ACTIVE_JOB_ID = "active_job_id"
        private const val AUTO_IMPORT_JOB_PREFIX = "auto_import_job:"
        private const val AUTO_IMPORT_REQUESTED_PREFIX = "auto_import_requested:"
        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private fun pauseKey(workId: String?) = "$PAUSED:${workId ?: "legacy"}"
        fun isPaused(context: Context, workId: String? = null): Boolean = prefs(context).getBoolean(pauseKey(workId), false)
        fun setPaused(context: Context, value: Boolean, workId: String? = null) { prefs(context).edit().putBoolean(pauseKey(workId), value).apply() }
        fun savedJobId(context: Context): String? = prefs(context).getString(ACTIVE_JOB_ID, null)
        fun savedAutoImportWorkId(context: Context, titleSearchWorkId: String): String? =
            prefs(context).getString(AUTO_IMPORT_JOB_PREFIX + titleSearchWorkId, null)
        fun isAutoImportRequested(context: Context, titleSearchWorkId: String): Boolean =
            prefs(context).getBoolean(AUTO_IMPORT_REQUESTED_PREFIX + titleSearchWorkId, false)
        fun saveAutoImportRequested(context: Context, titleSearchWorkId: String, requested: Boolean) {
            prefs(context).edit().putBoolean(AUTO_IMPORT_REQUESTED_PREFIX + titleSearchWorkId, requested).apply()
        }
        fun saveAutoImportWorkId(context: Context, titleSearchWorkId: String, importWorkId: String) {
            prefs(context).edit().putString(AUTO_IMPORT_JOB_PREFIX + titleSearchWorkId, importWorkId).apply()
        }
        fun activeSavedJobId(context: Context): String? {
            val value = savedJobId(context) ?: return null
            val id = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
            val info = runCatching { context.workManager.getWorkInfoById(id).get() }.getOrNull()
            if (info != null && !info.state.isFinished) return value
            clearSavedJob(context, value)
            return null
        }
        private fun saveJobId(context: Context, workId: String) { prefs(context).edit().putString(ACTIVE_JOB_ID, workId).apply() }
        internal fun clearSavedJob(context: Context, workId: String) {
            if (savedJobId(context) == workId) prefs(context).edit().remove(ACTIVE_JOB_ID).apply()
        }
        fun partialResultsPath(context: Context, workId: String): String =
            File(context.filesDir, "batch-title-search-$workId.result-rows").absolutePath

        internal suspend fun enqueue(
            context: Context,
            uri: Uri,
            sourceIds: Set<Long>,
            speedPreset: TitleSearchSpeedPreset = TitleSearchSpeedPreset.FAST,
            autoImport: Boolean = false,
        ): UUID = withContext(Dispatchers.IO) {
            val workManager = context.workManager
            val uniqueName = uniqueUriWorkName(uri)
            val activeWork = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }
            }.getOrNull()
            if (activeWork != null) {
                saveJobId(context, activeWork.id.toString())
                if (autoImport) saveAutoImportRequested(context, activeWork.id.toString(), true)
                return@withContext activeWork.id
            }

            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URI to uri.toString(),
                        KEY_SOURCE_IDS to sourceIds.toLongArray(),
                        KEY_SPEED_PRESET to speedPreset.name,
                        KEY_AUTO_IMPORT to autoImport,
                    ),
                )
                .addTag("batch_title_search")
                .build()
            setPaused(context, false, request.id.toString())
            saveAutoImportRequested(context, request.id.toString(), autoImport)
            saveJobId(context, request.id.toString())
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request).result.get()
            val enqueuedId = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }?.id
            }.getOrNull() ?: request.id
            saveJobId(context, enqueuedId.toString())
            enqueuedId
        }
        fun enqueueTitles(context: Context, titles: List<String>, autoImport: Boolean = false): UUID {
            val id = UUID.randomUUID()
            val file = File(context.filesDir, "batch-title-search-$id.txt")
            file.writeText(titles.joinToString("\n"))
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setId(id)
                .setInputData(
                    workDataOf(
                        KEY_TITLES_FILE to file.absolutePath,
                        KEY_AUTO_IMPORT to autoImport,
                        KEY_SPEED_PRESET to TitleSearchSpeedPreset.FAST.name,
                    ),
                )
                .addTag("batch_title_search")
                .build()
            setPaused(context, false, id.toString())
            saveAutoImportRequested(context, id.toString(), autoImport)
            saveJobId(context, id.toString())
            context.workManager.enqueue(request)
            return id
        }
        fun readInputTitles(context: Context, path: String?): List<String> = runCatching {
            val file = path?.let(::File) ?: return@runCatching emptyList()
            if (!file.isFile) return@runCatching emptyList()
            file.readLines().mapNotNull { line ->
                runCatching { String(Base64.decode(line, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()?.takeIf(String::isNotBlank)
            }
        }.getOrDefault(emptyList())

        fun readResults(context: Context, path: String?): List<TitleSearchCandidate> = runCatching {
            val result = path?.let(::File) ?: return@runCatching emptyList()
            val lines = if (result.isDirectory) {
                result.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".row") }
                    .sortedBy { it.name }
                    .flatMap { it.readLines() }
            } else if (result.isFile) {
                result.readLines()
            } else {
                emptyList()
            }
            TitleSearchCandidateDeduplicator.collapse(lines.mapNotNull(::decode).distinctBy { it.id })
        }.getOrDefault(emptyList())
        private fun uniqueUriWorkName(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "batch_title_search_uri_$key"
        }
        private fun candidateId(title: String, sourceId: Long, url: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$title\u0000$sourceId\u0000$url".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun encode(value: TitleSearchCandidate): String = listOf(value.id, value.inputTitle, value.matchedTitle, value.sourceName, value.url, value.thumbnailUrl.orEmpty()).joinToString("\t") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        private fun decode(line: String): TitleSearchCandidate? = runCatching { val v = line.split('\t').map { String(Base64.decode(it, Base64.DEFAULT)) }; TitleSearchCandidate(v[0], v[1], v[2], v[3], v[4], v.getOrNull(5)?.ifBlank { null }) }.getOrNull()
        private fun isCompletedCheckpoint(file: File, title: String): Boolean = runCatching {
            if (!file.isFile) return@runCatching false
            val lines = file.readLines()
            val decodedCandidates = lines.mapNotNull(::decode)
            TitleSearchCheckpoint.isComplete(lines, title, decodedCandidates)
        }.getOrDefault(false)
        private fun cleanTitle(value: String): String = value.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun absoluteUrl(source: Source, value: String): String {
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
            val baseUrl = (source as? HttpSource)?.baseUrl?.trimEnd('/') ?: return value
            return "$baseUrl/${value.trimStart('/')}"
        }
    }
}
