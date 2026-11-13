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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
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

class BatchTitleSearchScreen(private val inputUriString: String, private val existingJobId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        var jobId by rememberSaveable(inputUriString, existingJobId) { mutableStateOf(existingJobId) }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        var results by remember { mutableStateOf<List<TitleSearchCandidate>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val inputUri = remember(inputUriString) { Uri.parse(inputUriString) }

        LaunchedEffect(inputUriString, existingJobId) {
            if (jobId != null) return@LaunchedEffect
            if (existingJobId != null) {
                jobId = existingJobId
                return@LaunchedEffect
            }
            runCatching { context.contentResolver.takePersistableUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            jobId = BatchTitleSearchWorker.enqueue(context.applicationContext, inputUri).toString()
        }
        LaunchedEffect(jobId) {
            val id = jobId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collect { workInfo ->
                info = workInfo
                if (workInfo?.state?.isFinished == true) {
                    results = BatchTitleSearchWorker.readResults(context, workInfo.outputData.getString(BatchTitleSearchWorker.KEY_RESULT_FILE))
                }
            }
        }
        val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        val progress = info?.progress
        val completed = progress?.getInt(BatchTitleSearchWorker.KEY_COMPLETED, 0) ?: 0
        val total = progress?.getInt(BatchTitleSearchWorker.KEY_TOTAL, 0) ?: 0
        val paused = progress?.getBoolean(BatchTitleSearchWorker.KEY_PAUSED, false) ?: BatchTitleSearchWorker.isPaused(context)

        val strongResults = remember(results) { results.filter { TitleMatchQuality.isStrong(it) } }
        val weakResults = remember(results) { results.filter { !TitleMatchQuality.isStrong(it) } }
        val visibleResults = if (selectedTab == 0) strongResults else weakResults
        val selectedUrls = results.filter { it.id in selected }.map { it.url }.distinct()

        Scaffold(topBar = { scroll ->
            AppBar(title = "Find titles in enabled sources", navigateUp = { navigator?.pop() }, scrollBehavior = scroll)
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (running) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "All enabled sources are searched for each title. Results are kept per input title (no cross-title collapse). Strong and weak matches are split into tabs when finished.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text(if (total > 0) "Searching $completed/$total titles…" else "Searching enabled sources…")
                        }
                        if (total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { jobId?.let { BatchTitleSearchWorker.setPaused(context, true) } }) { Text("Pause") }
                            Button(onClick = { BatchTitleSearchWorker.setPaused(context, false) }) { Text("Resume") }
                            TextButton(onClick = { jobId?.let { manager.cancelWorkById(UUID.fromString(it)) } }) { Text("Cancel") }
                        }
                        if (paused) Text("Paused · no new source searches will start", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (results.isNotEmpty()) {
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
                        modifier = Modifier.fillMaxSize(),
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
                                        TitleSearchCandidateDeduplicator.bestPerInput(strongResults).forEach { selected.add(it.id) }
                                        selectedTab = 0
                                    },
                                ) { Text("Select best strong per title") }
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
                } else if (info?.state?.isFinished == true) {
                    Text(
                        "No matching titles were found across the enabled installed sources.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
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
) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
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

    fun bestPerInput(candidates: List<TitleSearchCandidate>): List<TitleSearchCandidate> {
        return candidates
            .groupBy { it.inputTitle.trim().lowercase() }
            .values
            .map { group ->
                group.minWith(
                    compareByDescending<TitleSearchCandidate> { TitleMatchQuality.isExact(it) }
                        .thenByDescending { TitleMatchQuality.isStrong(it) }
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

class BatchTitleSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
    override suspend fun doWork(): Result {
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
        val sources = sourceManager.getVisibleSources()
            .filterIsInstance<HttpSource>()
            .filter { it.lang in enabledLanguages && it.id.toString() !in disabled }
        if (!resultDirectory.exists() && !resultDirectory.mkdirs()) return Result.failure()
        resultDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(".") && it.name.endsWith(".tmp") }
            .forEach { it.delete() }
        fun rowFile(index: Int) = File(resultDirectory, "${index.toString().padStart(8, '0')}.row")
        val completed = AtomicInteger(titles.indices.count { rowFile(it).isFile })
        val limits = searchConcurrencyLimits()
        val titleSemaphore = Semaphore(limits.parallelTitles)
        val sourceSearchSemaphore = Semaphore(limits.globalSourceSearches)
        val perSourceSemaphores = sources.associate { it.id to Semaphore(limits.perSourceSearches) }
        setForeground(getForegroundInfo())
        setProgress(workDataOf(KEY_COMPLETED to completed.get(), KEY_TOTAL to titles.size))
        updateNotification(completed.get(), titles.size, "Searching ${sources.size} enabled sources · ${limits.globalSourceSearches} concurrent requests")
        coroutineScope {
            titles.mapIndexedNotNull { index, title ->
                if (rowFile(index).isFile) return@mapIndexedNotNull null
                async(Dispatchers.IO) {
                    titleSemaphore.withPermit {
                        awaitNotPaused()
                        val candidates = coroutineScope {
                            sources.map { source ->
                                async {
                                    searchSource(
                                        source,
                                        title,
                                        sourceSearchSemaphore,
                                        perSourceSemaphores.getValue(source.id),
                                    )
                                }
                            }.awaitAll().flatten()
                        }
                        val outputFile = rowFile(index)
                        val tempFile = File(resultDirectory, ".${outputFile.name}.tmp")
                        val rows = candidates.distinctBy { it.url }.joinToString("\n") { encode(it) }
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
                        setProgress(workDataOf(KEY_COMPLETED to done, KEY_TOTAL to titles.size, KEY_PAUSED to isPaused(applicationContext)))
                        updateNotification(done, titles.size, "Finished $done/${titles.size}: ${title.take(48)}")
                    }
                }
            }.awaitAll()
        }
        val uniqueTitleCount = writeConsolidatedResults(resultDirectory, finalResultFile)
        updateNotification(titles.size, titles.size, "Search complete · $uniqueTitleCount unique title matches")
        if (inputData.getBoolean(KEY_AUTO_IMPORT, false)) {
            val urls = readResults(applicationContext, finalResultFile.absolutePath)
                .filter { TitleMatchQuality.isStrong(it) }
                .let { TitleSearchCandidateDeduplicator.bestPerInput(it) }
                .map { it.url }
                .distinct()
            if (urls.isNotEmpty()) runCatching { BatchImportJob.startTitleMatches(applicationContext, urls) }
        }
        return Result.success(workDataOf(KEY_RESULT_FILE to finalResultFile.absolutePath, KEY_COMPLETED to titles.size, KEY_TOTAL to titles.size))
    }

    private fun writeConsolidatedResults(resultDirectory: File, finalResultFile: File): Int {
        val candidates = resultDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".row") }
            .sortedBy { it.name }
            .flatMap { it.readLines() }
            .mapNotNull(::decode)
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

    private data class SearchConcurrencyLimits(
        val parallelTitles: Int,
        val globalSourceSearches: Int,
        val perSourceSearches: Int,
    )

    private fun searchConcurrencyLimits(): SearchConcurrencyLimits {
        val powerManager = applicationContext.getSystemService(PowerManager::class.java)
        val powerSave = powerManager?.isPowerSaveMode == true
        val thermallyConstrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            (powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_MODERATE
        }.getOrDefault(false)
        return if (powerSave || thermallyConstrained) {
            SearchConcurrencyLimits(
                parallelTitles = THROTTLED_PARALLEL_TITLES,
                globalSourceSearches = THROTTLED_GLOBAL_SOURCE_SEARCHES,
                perSourceSearches = THROTTLED_PER_SOURCE_SEARCHES,
            )
        } else {
            SearchConcurrencyLimits(
                parallelTitles = NORMAL_PARALLEL_TITLES,
                globalSourceSearches = NORMAL_GLOBAL_SOURCE_SEARCHES,
                perSourceSearches = NORMAL_PER_SOURCE_SEARCHES,
            )
        }
    }

    private suspend fun searchSource(
        source: Source,
        title: String,
        globalSemaphore: Semaphore,
        sourceSemaphore: Semaphore,
    ): List<TitleSearchCandidate> {
        return try {
            awaitNotPaused()
            suspend fun query() =
                sourceSemaphore.withPermit {
                    globalSemaphore.withPermit {
                        withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.getSearchManga(1, title.sanitize(), source.getFilterList()) }
                    }
                }
            val page = query()
                ?: return emptyList()
            page.mangas.map { manga ->
                val local = manga.toDomainManga(source.id)
                TitleSearchCandidate(
                    id = "${title.hashCode()}-${source.id}-${manga.url.hashCode()}",
                    inputTitle = title,
                    matchedTitle = manga.title,
                    sourceName = source.name,
                    url = absoluteUrl(source, manga.url),
                    thumbnailUrl = local.thumbnailUrl?.let { absoluteUrl(source, it) },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }
    private suspend fun awaitNotPaused() {
        while (isPaused(applicationContext) && !isStopped) {
            setProgress(workDataOf(KEY_PAUSED to true))
            updateNotification(0, 0, "Paused · waiting to resume")
            delay(750)
        }
        if (!isStopped) setProgress(workDataOf(KEY_PAUSED to false))
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
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_PAUSED = "paused"
        const val NOTIFICATION_ID = -1810
        const val ACTION_OPEN_BATCH_TITLE_SEARCH = "com.manus.komikku.OPEN_BATCH_TITLE_SEARCH"
        const val EXTRA_WORK_ID = "batch_title_search_work_id"
        private const val PREFS = "batch_title_search_controls"
        private const val PAUSED = "paused"
        private const val THROTTLED_PARALLEL_TITLES = 12
        private const val THROTTLED_GLOBAL_SOURCE_SEARCHES = 24
        private const val THROTTLED_PER_SOURCE_SEARCHES = 4
        private const val NORMAL_PARALLEL_TITLES = 40
        private const val NORMAL_GLOBAL_SOURCE_SEARCHES = 64
        private const val NORMAL_PER_SOURCE_SEARCHES = 8
        private const val SEARCH_TIMEOUT_MS = 8_000L
        fun isPaused(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSED, false)
        fun setPaused(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSED, value).apply() }
        suspend fun enqueue(context: Context, uri: Uri): UUID = withContext(Dispatchers.IO) {
            val workManager = context.workManager
            val uniqueName = uniqueUriWorkName(uri)
            val activeWork = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }
            }.getOrNull()
            if (activeWork != null) return@withContext activeWork.id

            setPaused(context, false)
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString()))
                .addTag("batch_title_search")
                .build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request).result.get()
            runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }?.id
            }.getOrNull() ?: request.id
        }
        fun enqueueTitles(context: Context, titles: List<String>, autoImport: Boolean = false): UUID {
            setPaused(context, false)
            val id = UUID.randomUUID()
            val file = File(context.filesDir, "batch-title-search-$id.txt")
            file.writeText(titles.joinToString("\n"))
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setId(id)
                .setInputData(workDataOf(KEY_TITLES_FILE to file.absolutePath, KEY_AUTO_IMPORT to autoImport))
                .addTag("batch_title_search")
                .build()
            context.workManager.enqueue(request)
            return id
        }
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
        private fun encode(value: TitleSearchCandidate): String = listOf(value.id, value.inputTitle, value.matchedTitle, value.sourceName, value.url, value.thumbnailUrl.orEmpty()).joinToString("\t") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        private fun decode(line: String): TitleSearchCandidate? = runCatching { val v = line.split('\t').map { String(Base64.decode(it, Base64.DEFAULT)) }; TitleSearchCandidate(v[0], v[1], v[2], v[3], v[4], v.getOrNull(5)?.ifBlank { null }) }.getOrNull()
        private fun cleanTitle(value: String): String = value.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun absoluteUrl(source: Source, value: String): String {
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
            val baseUrl = (source as? HttpSource)?.baseUrl?.trimEnd('/') ?: return value
            return "$baseUrl/${value.trimStart('/')}"
        }
    }
}
