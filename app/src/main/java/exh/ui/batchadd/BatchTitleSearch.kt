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

class BatchTitleSearchScreen(
    private val inputUriString: String,
    private val existingJobId: String? = null,
    private val speedPresetName: String = TitleSearchSpeedPreset.FAST.name,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        val speedPreset = TitleSearchSpeedPreset.fromName(speedPresetName)
        var jobId by rememberSaveable(inputUriString, existingJobId) { mutableStateOf(existingJobId) }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        var results by remember { mutableStateOf<List<TitleSearchCandidate>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val inputUri = remember(inputUriString) { Uri.parse(inputUriString) }

        LaunchedEffect(inputUriString, existingJobId, speedPresetName) {
            if (jobId != null) return@LaunchedEffect
            if (existingJobId != null) {
                jobId = existingJobId
                return@LaunchedEffect
            }
            runCatching { context.contentResolver.takePersistableUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            jobId = BatchTitleSearchWorker.enqueue(context.applicationContext, inputUri, speedPreset).toString()
        }
        LaunchedEffect(jobId) {
            val id = jobId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collect { workInfo ->
                info = workInfo
                if (workInfo?.state?.isFinished == true) {
                    val resultPath = workInfo.outputData.getString(BatchTitleSearchWorker.KEY_RESULT_FILE)
                        ?: BatchTitleSearchWorker.partialResultsPath(context, id.toString())
                    results = BatchTitleSearchWorker.readResults(context, resultPath)
                }
            }
        }
        LaunchedEffect(jobId, info?.state) {
            if (info?.state != WorkInfo.State.RUNNING && info?.state != WorkInfo.State.ENQUEUED) return@LaunchedEffect
            val id = jobId ?: return@LaunchedEffect
            val path = BatchTitleSearchWorker.partialResultsPath(context, id)
            while (true) {
                results = BatchTitleSearchWorker.readResults(context, path)
                delay(2_500)
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
        val paused = BatchTitleSearchWorker.isPaused(context)

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
                            Button(onClick = { jobId?.let { BatchTitleSearchWorker.setPaused(context, true) } }) { Text("Pause") }
                            Button(onClick = { BatchTitleSearchWorker.setPaused(context, false) }) { Text("Resume") }
                            TextButton(onClick = { jobId?.let { manager.cancelWorkById(UUID.fromString(it)) } }) { Text("Cancel") }
                        }
                        if (paused) Text("Paused · no new source searches will start", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (results.isNotEmpty()) {
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
                } else if (!running && info?.state?.isFinished == true) {
                    Text(
                        "No matching titles were found across the enabled installed sources.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (!running) {
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
        "Fastest: up to 64 source requests at once; may trigger rate limits on some sites.",
        TitleSearchConcurrencyLimits(40, 64, 8, 8_000L),
        TitleSearchConcurrencyLimits(12, 24, 4, 10_000L),
    ),
    BALANCED(
        "Balanced",
        "Moderate parallelism for a better balance between speed and source stability.",
        TitleSearchConcurrencyLimits(28, 48, 6, 9_000L),
        TitleSearchConcurrencyLimits(10, 20, 3, 12_000L),
    ),
    GENTLE(
        "Gentle",
        "Fewer simultaneous requests to reduce device and source load; usually slower.",
        TitleSearchConcurrencyLimits(16, 24, 3, 12_000L),
        TitleSearchConcurrencyLimits(8, 16, 2, 15_000L),
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
        val checkpointCandidates = resultDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".row") }
            .sortedBy { it.name }
            .flatMap { it.readLines() }
            .mapNotNull(::decode)
            .distinctBy { it.id }
        val checkpointResults = TitleSearchCandidateDeduplicator.collapse(checkpointCandidates)
        val completed = AtomicInteger(titles.indices.count { rowFile(it).isFile })
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
                KEY_PAUSED to isPaused(applicationContext),
            ),
        )
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
                                        limits.timeoutMillis,
                                    )
                                }
                            }.awaitAll().flatten()
                        }
                        val outputFile = rowFile(index)
                        val tempFile = File(resultDirectory, ".${outputFile.name}.tmp")
                        val titleCandidates = TitleSearchCandidateDeduplicator.collapse(candidates)
                        val rows = titleCandidates.joinToString("\n") { encode(it) }
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
                        setProgress(
                            workDataOf(
                                KEY_COMPLETED to done,
                                KEY_TOTAL to titles.size,
                                KEY_FOUND to found.get(),
                                KEY_FOUND_STRONG to foundStrong.get(),
                                KEY_FOUND_WEAK to foundWeak.get(),
                                KEY_ETA_SECONDS to etaSeconds,
                                KEY_SPEED_PRESET to speedPreset.name,
                                KEY_PAUSED to isPaused(applicationContext),
                            ),
                        )
                        updateNotification(done, titles.size, "Finished $done/${titles.size} · ${found.get()} matches")
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
    ): List<TitleSearchCandidate> {
        return try {
            awaitNotPaused()
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
    private suspend fun awaitNotPaused() {
        while (isPaused(applicationContext) && !isStopped) {
            updateNotification(0, 0, "Paused · waiting to resume")
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
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_PAUSED = "paused"
        const val KEY_FOUND = "found"
        const val KEY_FOUND_STRONG = "found_strong"
        const val KEY_FOUND_WEAK = "found_weak"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_SPEED_PRESET = "speed_preset"
        const val NOTIFICATION_ID = -1810
        const val ACTION_OPEN_BATCH_TITLE_SEARCH = "com.manus.komikku.OPEN_BATCH_TITLE_SEARCH"
        const val EXTRA_WORK_ID = "batch_title_search_work_id"
        private const val PREFS = "batch_title_search_controls"
        private const val PAUSED = "paused"
        fun isPaused(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSED, false)
        fun setPaused(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSED, value).apply() }
        fun partialResultsPath(context: Context, workId: String): String =
            File(context.filesDir, "batch-title-search-$workId.result-rows").absolutePath

        internal suspend fun enqueue(
            context: Context,
            uri: Uri,
            speedPreset: TitleSearchSpeedPreset = TitleSearchSpeedPreset.FAST,
        ): UUID = withContext(Dispatchers.IO) {
            val workManager = context.workManager
            val uniqueName = uniqueUriWorkName(uri)
            val activeWork = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }
            }.getOrNull()
            if (activeWork != null) return@withContext activeWork.id

            setPaused(context, false)
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_SPEED_PRESET to speedPreset.name))
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
                .setInputData(
                    workDataOf(
                        KEY_TITLES_FILE to file.absolutePath,
                        KEY_AUTO_IMPORT to autoImport,
                        KEY_SPEED_PRESET to TitleSearchSpeedPreset.FAST.name,
                    ),
                )
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
        private fun candidateId(title: String, sourceId: Long, url: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$title\u0000$sourceId\u0000$url".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
