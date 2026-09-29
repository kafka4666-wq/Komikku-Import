package exh.ui.batchimage

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal object BatchImageSourceDiagnostics {
    fun sourceIssueNote(samples: List<String>): String = samples.asSequence()
        .map { it.replace(Regex("https?://\\S+"), "[URL]").replace(Regex("\\s+"), " ").trim().take(96) }
        .filter(String::isNotBlank)
        .distinct()
        .take(3)
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = " Sources: ", separator = "; ")
        ?.take(200)
        .orEmpty()
}

/** Mirrors Browse Global Search across all visible, enabled installed sources and adds only a clear title match. */
class BatchImageSearchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()

    override suspend fun doWork(): Result {
        val selectionPath = inputData.getString(KEY_SELECTION_FILE)?.let(::File) ?: return Result.failure()
        val outcomeFile = inputData.getString(KEY_OUTCOME_FILE)?.let(::File) ?: File("$selectionPath.outcomes")
        outcomeFile.parentFile?.mkdirs()
        val records = selectionPath.readLines().mapNotNull(BatchImageRecordCodec::decode)
        val groups = BatchImageTitleMatcher.groupRecords(records)
        if (groups.isEmpty()) return Result.success(workDataOf(KEY_COMPLETED to 0, KEY_TOTAL to 0, KEY_ADDED to 0, KEY_UNMATCHED to 0))

        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabledSources = sourcePreferences.disabledSources().get()
        val sources = sourceManager.getVisibleSources()
            .filter { it.lang in enabledLanguages && it.id.toString() !in disabledSources }
            .sortedWith(compareByDescending<Source> { sourcePriority(it.name) }.thenBy { it.name.lowercase() })
        val requestSemaphore = Semaphore(MAX_TOTAL_PARALLEL_SOURCE_REQUESTS)
        val titleSemaphore = Semaphore(MAX_CONCURRENT_TITLE_SEARCHES)
        val progressMutex = Mutex()
        val libraryMutex = Mutex()
        val candidateMutex = Mutex()
        val outcomeMutex = Mutex()
        val completed = AtomicInteger()
        val added = AtomicInteger()
        val alreadyPresent = AtomicInteger()
        val unmatched = AtomicInteger()
        val timedOutSources = AtomicInteger()
        val failedSources = AtomicInteger()

        return try {
            setForeground(getForegroundInfo())
            setProgress(progressData(0, groups.size, 0, 0, 0, "Global search · ${sources.size} enabled installed sources"))
            if (sources.isEmpty()) {
                appendOutcomes(outcomeFile, groups.flatMap { group ->
                    group.records.map { BatchImageSearchOutcome(it.id, "no_sources", "No enabled installed sources are available. Enable an installed source and retry.") }
                }, outcomeMutex)
                val output = workDataOf(KEY_COMPLETED to 0, KEY_TOTAL to groups.size, KEY_ADDED to 0, KEY_ALREADY_PRESENT to 0, KEY_UNMATCHED to groups.size, KEY_PHASE to "No enabled installed sources")
                setProgress(output)
                return Result.success(output)
            }

            coroutineScope {
                groups.mapIndexed { index, group ->
                    async(Dispatchers.IO) {
                        titleSemaphore.withPermit {
                            if (isStopped) throw CancellationException("Global title search cancelled")
                            val titleQueries = BatchImageTextExtractor.searchQueries(group.title).take(MAX_SEARCH_QUERY_VARIANTS)
                            val searchedQueries = mutableListOf<String>()
                            val candidatePool = LinkedHashMap<String, Candidate>()
                            val itemTimeouts = AtomicInteger()
                            val itemFailures = AtomicInteger()
                            val sourceIssueSamples = mutableListOf<String>()
                            for ((queryIndex, sourceQuery) in titleQueries.withIndex()) {
                                if (isStopped) throw CancellationException("Global title search cancelled")
                                searchedQueries += sourceQuery
                                searchAllEnabledSources(sources, sourceQuery, requestSemaphore) { checked, result ->
                                    if (result.timedOut) {
                                        itemTimeouts.incrementAndGet()
                                        timedOutSources.incrementAndGet()
                                    }
                                    if (result.failed) {
                                        itemFailures.incrementAndGet()
                                        failedSources.incrementAndGet()
                                    }
                                    candidateMutex.withLock {
                                        if ((result.timedOut || result.failed) && sourceIssueSamples.size < MAX_SOURCE_ISSUE_SAMPLES) {
                                            result.issueSummary?.let(sourceIssueSamples::add)
                                        }
                                        result.candidates.forEach { candidate ->
                                            val score = searchedQueries.maxOfOrNull { BatchImageTitleMatcher.score(it, candidate.manga.title) } ?: 0
                                            if (score >= BatchImageTitleMatcher.MINIMUM_MATCH_SCORE) {
                                                val key = normalize(candidate.manga.title)
                                                val current = candidatePool[key]
                                                if (current == null || score > current.score || score == current.score && sourcePriority(candidate.sourceName) > sourcePriority(current.sourceName)) {
                                                    candidatePool[key] = candidate.copy(score = score)
                                                }
                                            }
                                        }
                                    }
                                    if (checked == sources.size || checked % PROGRESS_SOURCE_INTERVAL == 0) {
                                        val detail = "Global search · title ${index + 1}/${groups.size} · keyword ${queryIndex + 1}/${titleQueries.size} · $checked/${sources.size} sources answered · ${group.title.take(44)}"
                                        progressMutex.withLock {
                                            setProgress(progressData(completed.get(), groups.size, added.get(), alreadyPresent.get(), unmatched.get(), detail, timedOutSources.get(), failedSources.get()))
                                            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification(completed.get(), groups.size, detail))
                                        }
                                    }
                                }
                                val resolution = resolveCandidate(candidatePool.values.toList())
                                // This keyword was searched across all sources. A high-confidence,
                                // unambiguous match avoids spending time on broader fallback queries.
                                if (resolution.candidate != null) break
                            }

                            val resolution = resolveCandidate(candidatePool.values.toList())
                            val candidate = resolution.candidate
                            if (candidate == null) {
                                unmatched.incrementAndGet()
                                val status = when {
                                    resolution.ambiguous -> "ambiguous"
                                    itemTimeouts.get() > 0 -> "timed_out"
                                    itemFailures.get() > 0 -> "source_error"
                                    else -> "not_found"
                                }
                                val message = when (status) {
                                    "ambiguous" -> "Global search found competing similar titles; kept for review${timeoutNote(itemTimeouts.get())}"
                                    "timed_out" -> "No confident match after global search; ${itemTimeouts.get()} request(s) timed out and ${itemFailures.get()} failed.${BatchImageSourceDiagnostics.sourceIssueNote(sourceIssueSamples)} Kept for review."
                                    "source_error" -> "No confident match after global search; ${itemFailures.get()} request(s) failed.${BatchImageSourceDiagnostics.sourceIssueNote(sourceIssueSamples)} Kept for review."
                                    else -> "No confident match across enabled sources; kept for review"
                                }
                                appendOutcomes(outcomeFile, group.records.map { BatchImageSearchOutcome(it.id, status, message) }, outcomeMutex)
                            } else {
                                try {
                                    val result = libraryMutex.withLock {
                                        val local = networkToLocalManga(candidate.manga, updateInfo = false)
                                        val isAlreadyPresent = local.favorite
                                        val didAdd = !isAlreadyPresent && updateManga.awaitUpdateFavorite(local.id, true)
                                        isAlreadyPresent to didAdd
                                    }
                                    val (isAlreadyPresent, didAdd) = result
                                    when {
                                        isAlreadyPresent -> alreadyPresent.incrementAndGet()
                                        didAdd -> added.incrementAndGet()
                                        else -> unmatched.incrementAndGet()
                                    }
                                    val status = if (isAlreadyPresent) "already" else if (didAdd) "added" else "add_failed"
                                    val label = if (isAlreadyPresent) "Already in library" else if (didAdd) "Added" else "Library add failed"
                                    val issueNote = if (itemTimeouts.get() + itemFailures.get() > 0) " (${itemTimeouts.get()} timed out, ${itemFailures.get()} failed source requests)" else ""
                                    appendOutcomes(outcomeFile, group.records.map { BatchImageSearchOutcome(it.id, status, "$label · ${candidate.manga.title} · ${candidate.sourceName}$issueNote") }, outcomeMutex)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    unmatched.incrementAndGet()
                                    appendOutcomes(outcomeFile, group.records.map { BatchImageSearchOutcome(it.id, "add_failed", "Library add failed · ${error.message ?: error.javaClass.simpleName}") }, outcomeMutex)
                                }
                            }

                            val finished = completed.incrementAndGet()
                            progressMutex.withLock {
                                val detail = "Finished global title search $finished/${groups.size} · ${added.get()} added · ${alreadyPresent.get()} already present"
                                setProgress(progressData(finished, groups.size, added.get(), alreadyPresent.get(), unmatched.get(), detail, timedOutSources.get(), failedSources.get()))
                                NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification(finished, groups.size, detail))
                            }
                        }
                    }
                }.awaitAll()
            }

            val output = workDataOf(
                KEY_COMPLETED to completed.get(),
                KEY_TOTAL to groups.size,
                KEY_ADDED to added.get(),
                KEY_ALREADY_PRESENT to alreadyPresent.get(),
                KEY_UNMATCHED to unmatched.get(),
                KEY_TIMED_OUT_SOURCES to timedOutSources.get(),
                KEY_FAILED_SOURCES to failedSources.get(),
                KEY_PHASE to if (timedOutSources.get() + failedSources.get() > 0) "Global search complete · ${timedOutSources.get()} timed out · ${failedSources.get()} failed; unmatched titles remain reviewable" else "Global search and library add complete",
            )
            setProgress(output)
            Result.success(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(workDataOf(KEY_COMPLETED to completed.get(), KEY_TOTAL to groups.size, KEY_ADDED to added.get(), KEY_ALREADY_PRESENT to alreadyPresent.get(), KEY_UNMATCHED to unmatched.get(), KEY_TIMED_OUT_SOURCES to timedOutSources.get(), KEY_FAILED_SOURCES to failedSources.get(), KEY_ERROR to (error.message ?: "Global source search failed")))
        } finally {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification(0, inputData.getString(KEY_TOTAL_HINT)?.toIntOrNull() ?: 1),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun notification(completed: Int, total: Int, phase: String? = null) =
        NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT)
            .setSmallIcon(R.drawable.ic_komikku)
            .setContentTitle("Batch Image Global Search")
            .setContentText(phase ?: "$completed/$total unique titles processed")
            .setProgress(total.coerceAtLeast(1), completed.coerceAtMost(total), false)
            .setOngoing(completed < total)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_close_24dp,
                "Cancel",
                applicationContext.workManager.createCancelPendingIntent(id),
            )
            .build()

    private fun progressData(
        completed: Int,
        total: Int,
        added: Int,
        already: Int,
        unmatched: Int,
        phase: String,
        timedOutSources: Int = 0,
        failedSources: Int = 0,
    ) =
        workDataOf(
            KEY_COMPLETED to completed,
            KEY_TOTAL to total,
            KEY_ADDED to added,
            KEY_ALREADY_PRESENT to already,
            KEY_UNMATCHED to unmatched,
            KEY_PHASE to phase,
            KEY_TIMED_OUT_SOURCES to timedOutSources,
            KEY_FAILED_SOURCES to failedSources,
        )

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun sourcePriority(name: String): Int =
        if (name.contains("nhentai", ignoreCase = true) || name.contains("hentai", ignoreCase = true) ||
            name.contains("doujin", ignoreCase = true) || name.contains("e-hentai", ignoreCase = true) ||
            name.contains("pururin", ignoreCase = true)
        ) 1 else 0

    private suspend fun searchAllEnabledSources(
        sources: List<Source>,
        query: String,
        requestSemaphore: Semaphore,
        onSourceResult: suspend (checked: Int, result: SourceSearchResult) -> Unit,
    ) = coroutineScope {
        val answered = AtomicInteger()
        sources.map { source ->
            async(Dispatchers.IO) {
                if (isStopped) throw CancellationException("Global source search cancelled")
                val result = try {
                    val mangas = requestSemaphore.withPermit {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            source.getSearchManga(1, query.sanitize(), source.getFilterList()).mangas
                                .distinctBy { it.url }
                                .map { Candidate(it.toDomainManga(source.id), source.name, 0) }
                        }
                    }
                    if (mangas == null) {
                        SourceSearchResult(source.name, emptyList(), timedOut = true, failed = false, issueSummary = "${source.name}: exceeded ${SOURCE_TIMEOUT_MS / 1_000}s")
                    } else {
                        SourceSearchResult(source.name, mangas, timedOut = false, failed = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val detail = error.message.orEmpty()
                        .replace(Regex("https?://\\S+"), "[URL]")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(72)
                        .ifBlank { error.javaClass.simpleName }
                    SourceSearchResult(source.name, emptyList(), timedOut = false, failed = true, issueSummary = "${source.name}: $detail")
                }
                val checked = answered.incrementAndGet()
                onSourceResult(checked, result)
            }
        }.awaitAll()
    }

        private fun timeoutNote(count: Int): String = if (count > 0) " ($count source request(s) timed out)" else ""

    private fun resolveCandidate(candidates: List<Candidate>): Resolution {
        val distinctWorks = candidates
            .groupBy { normalize(it.manga.title) }
            .values
            .mapNotNull { sameTitle -> sameTitle.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { sourcePriority(it.sourceName) }) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { sourcePriority(it.sourceName) })
        val best = distinctWorks.firstOrNull() ?: return Resolution(null, false)
        val second = distinctWorks.getOrNull(1)
        if (!BatchImageTitleMatcher.isConfidentMatch(best.score, second?.score)) {
            return Resolution(null, best.score >= BatchImageTitleMatcher.MINIMUM_MATCH_SCORE)
        }
        return Resolution(best, false)
    }

    private suspend fun appendOutcomes(file: File, outcomes: List<BatchImageSearchOutcome>, mutex: Mutex) {
        if (outcomes.isEmpty()) return
        mutex.withLock {
            file.appendText(outcomes.joinToString("\n", postfix = "\n", transform = BatchImageSearchOutcomeCodec::encode))
        }
    }

    companion object {
        const val KEY_SELECTION_FILE = "selection_file"
        const val KEY_TOTAL_HINT = "total_hint"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_ADDED = "added"
        const val KEY_ALREADY_PRESENT = "already_present"
        const val KEY_UNMATCHED = "unmatched"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "error"
        const val KEY_OUTCOME_FILE = "outcome_file"
        const val KEY_TIMED_OUT_SOURCES = "timed_out_sources"
        const val KEY_FAILED_SOURCES = "failed_sources"
        const val NOTIFICATION_ID = -1806
        const val TAG = "batch_image_source_search"
        const val UNIQUE_WORK = "komikku_batch_image_source_search"
        private const val MAX_TOTAL_PARALLEL_SOURCE_REQUESTS = 5
        private const val MAX_CONCURRENT_TITLE_SEARCHES = 2
        private const val MAX_SEARCH_QUERY_VARIANTS = 3
        private const val MAX_SOURCE_ISSUE_SAMPLES = 3
        private const val PROGRESS_SOURCE_INTERVAL = 8
        private const val SOURCE_TIMEOUT_MS = 45_000L
        private const val PREFS = "batch_image_source_search"
        private const val PREF_JOB_ID = "job_id"
        private const val PREF_OUTCOME_FILE = "outcome_file"

        fun enqueue(context: Context, records: List<BatchImageRecord>): UUID {
            val jobId = UUID.randomUUID()
            val directory = File(context.filesDir, "batch-image").apply { mkdirs() }
            val selectionFile = File(directory, "$jobId-search.selection")
            val outcomeFile = File(directory, "$jobId-search.outcomes")
            selectionFile.writeText(records.joinToString("\n") { BatchImageRecordCodec.encode(it) })
            outcomeFile.writeText(readOutcomes(context).values.joinToString("\n", postfix = "\n", transform = BatchImageSearchOutcomeCodec::encode))
            val request = OneTimeWorkRequestBuilder<BatchImageSearchWorker>()
                .setId(jobId)
                .setInputData(workDataOf(KEY_SELECTION_FILE to selectionFile.absolutePath, KEY_OUTCOME_FILE to outcomeFile.absolutePath, KEY_TOTAL_HINT to BatchImageTitleMatcher.groupRecords(records).size.toString()))
                .addTag(TAG)
                .build()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_JOB_ID, jobId.toString())
                .putString(PREF_OUTCOME_FILE, outcomeFile.absolutePath)
                .apply()
            context.workManager.enqueueUniqueWork(UNIQUE_WORK, androidx.work.ExistingWorkPolicy.REPLACE, request)
            return jobId
        }

        fun savedJobId(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_JOB_ID, null)

        fun readOutcomes(context: Context): Map<String, BatchImageSearchOutcome> = runCatching {
            val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_OUTCOME_FILE, null)
            path?.let(::File)?.takeIf(File::exists)?.readLines()?.mapNotNull(BatchImageSearchOutcomeCodec::decode)?.associateBy { it.recordId }.orEmpty()
        }.getOrDefault(emptyMap())

        fun clearOutcome(context: Context, recordId: String) {
            runCatching {
                val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_OUTCOME_FILE, null)
                val file = path?.let(::File)?.takeIf(File::exists) ?: return
                val remaining = file.readLines().mapNotNull(BatchImageSearchOutcomeCodec::decode).filterNot { it.recordId == recordId }
                file.writeText(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n", transform = BatchImageSearchOutcomeCodec::encode))
            }
        }
    }

    private data class Candidate(val manga: Manga, val sourceName: String, val score: Int)
    private data class Resolution(val candidate: Candidate?, val ambiguous: Boolean)
    private data class SourceSearchResult(val sourceName: String, val candidates: List<Candidate>, val timedOut: Boolean, val failed: Boolean, val issueSummary: String? = null)
}

data class BatchImageSearchOutcome(val recordId: String, val status: String, val message: String)

internal object BatchImageTitleMatcher {
    const val MINIMUM_MATCH_SCORE = 95
    const val MINIMUM_MATCH_MARGIN = 8

    fun isConfidentMatch(bestScore: Int, secondScore: Int?): Boolean =
        bestScore >= MINIMUM_MATCH_SCORE &&
            (secondScore == null || bestScore - secondScore >= MINIMUM_MATCH_MARGIN)

    fun groupRecords(records: List<BatchImageRecord>): List<BatchImageTitleGroup> = records
        .filter { !it.title.isNullOrBlank() }
        .groupBy { record ->
            val title = record.title.orEmpty()
            val query = BatchImageTextExtractor.searchQueries(title).firstOrNull().orEmpty().ifBlank { title }
            normalize(query)
        }
        .filterKeys(String::isNotBlank)
        .values
        .map { matches ->
            val representative = matches.maxBy { it.confidence }
            BatchImageTitleGroup(representative.title.orEmpty(), matches)
        }

    fun score(title: String?, candidateTitle: String): Int {
        val targetNormalized = normalize(title.orEmpty())
        val candidateNormalized = normalize(candidateTitle)
        if (targetNormalized.isBlank() || candidateNormalized.isBlank()) return 0
        return when {
            targetNormalized == candidateNormalized -> 100
            candidateNormalized.contains(" $targetNormalized ") || candidateNormalized.startsWith("$targetNormalized ") || candidateNormalized.endsWith(" $targetNormalized") -> if (targetNormalized.split(' ').size >= 3) 95 else 90
            targetNormalized.contains(" $candidateNormalized ") || targetNormalized.startsWith("$candidateNormalized ") || targetNormalized.endsWith(" $candidateNormalized") -> if (candidateNormalized.split(' ').size >= 3) 95 else 90
            else -> {
                val targetTokens = targetNormalized.split(' ').filter { it.length > 1 }.toSet()
                val candidateTokens = candidateNormalized.split(' ').filter { it.length > 1 }.toSet()
                val common = targetTokens.intersect(candidateTokens).size
                if (targetTokens.size < 3 || candidateTokens.size < 3 || common < 3) 0 else {
                    val recall = common.toDouble() / targetTokens.size
                    val precision = common.toDouble() / candidateTokens.size
                    if (recall < 0.8 || precision < 0.6) 0 else (100 * 2 * recall * precision / (recall + precision)).toInt()
                }
            }
        }.coerceIn(0, 100)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

internal data class BatchImageTitleGroup(val title: String, val records: List<BatchImageRecord>)

internal data class BatchImageSelectionPlan(
    val titleRows: List<BatchImageRecord>,
    val titleGroups: List<BatchImageTitleGroup>,
    val codeRows: List<BatchImageRecord>,
    val codeUrls: List<String>,
    val otherRows: List<BatchImageRecord>,
) {
    companion object {
        fun from(records: List<BatchImageRecord>): BatchImageSelectionPlan {
            val codeRows = records.filter { !it.code.isNullOrBlank() }
            val titleRows = records.filter { !it.title.isNullOrBlank() && it.code.isNullOrBlank() }
            val otherRows = records.filter { it.code.isNullOrBlank() && it.title.isNullOrBlank() }
            return BatchImageSelectionPlan(
                titleRows = titleRows,
                titleGroups = BatchImageTitleMatcher.groupRecords(titleRows),
                codeRows = codeRows,
                codeUrls = codeRows.mapNotNull { record -> record.code?.let { "https://nhentai.net/g/$it/" } }.distinct(),
                otherRows = otherRows,
            )
        }
    }
}

private object BatchImageSearchOutcomeCodec {
    fun encode(outcome: BatchImageSearchOutcome): String = listOf(outcome.recordId, outcome.status, outcome.message)
        .joinToString("\t") { android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) }

    fun decode(line: String): BatchImageSearchOutcome? = runCatching {
        val values = line.split('\t').map { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        if (values.size != 3) return null
        BatchImageSearchOutcome(values[0], values[1], values[2])
    }.getOrNull()
}
