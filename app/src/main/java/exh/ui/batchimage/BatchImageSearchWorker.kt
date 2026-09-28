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
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

/** Searches only visible, enabled online sources and favorites one unambiguous best match per OCR item. */
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
        if (records.isEmpty()) return Result.success(workDataOf(KEY_COMPLETED to 0, KEY_TOTAL to 0))
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabledSources = sourcePreferences.disabledSources().get()
        val sources = sourceManager.getVisibleOnlineSources()
            .filter { it.lang in enabledLanguages && it.id.toString() !in disabledSources }
            .sortedWith(compareByDescending<HttpSource> { sourcePriority(it.name) }.thenBy { it.name.lowercase() })
        val prioritizedSources = sources.filter { sourcePriority(it.name) > 0 }
        val remainingSources = sources.filter { sourcePriority(it.name) == 0 }
        var completed = 0
        var added = 0
        var alreadyPresent = 0
        var unmatched = 0
        return try {
            setForeground(getForegroundInfo())
            setProgress(progressData(0, records.size, added, alreadyPresent, unmatched, "Preparing installed sources"))
            records.forEachIndexed { index, record ->
                if (isStopped) throw CancellationException("Source search cancelled")
                val queryTitle = record.title?.takeIf(String::isNotBlank)
                // An artist-only query may return an entire catalog. Leave those candidates
                // for manual review instead of auto-favoriting unrelated works.
                if (queryTitle == null) {
                    unmatched++
                    appendOutcome(outcomeFile, BatchImageSearchOutcome(record.id, "no_title", "No title to search"))
                    completed = index + 1
                    setProgress(progressData(completed, records.size, added, alreadyPresent, unmatched, "No title or artist to search"))
                    return@forEachIndexed
                }
                val titleQueries = BatchImageTextExtractor.searchQueries(queryTitle!!)
                val candidatePool = LinkedHashMap<String, Candidate>()
                val permits = Semaphore(MAX_CONCURRENT_SOURCE_SEARCHES)
                for ((queryIndex, sourceQuery) in titleQueries.withIndex()) {
                    if (isStopped) throw CancellationException("Source search cancelled")
                    setProgress(progressData(completed, records.size, added, alreadyPresent, unmatched, "Searching keywords ${queryIndex + 1}/${titleQueries.size} for ${index + 1}/${records.size}"))
                    fun addMatches(matches: List<Candidate>) {
                        for (candidate in matches) {
                            val score = titleQueries.maxOfOrNull { BatchImageTitleMatcher.score(it, candidate.manga.title) } ?: 0
                            if (score < MINIMUM_MATCH_SCORE) continue
                            val key = normalize(candidate.manga.title)
                            val current = candidatePool[key]
                            if (current == null || score > current.score || score == current.score && sourcePriority(candidate.sourceName) > sourcePriority(current.sourceName)) {
                                candidatePool[key] = candidate.copy(score = score)
                            }
                        }
                    }
                    searchAcrossSources(prioritizedSources, sourceQuery, permits) { matches ->
                        addMatches(matches)
                        resolveCandidate(candidatePool.values.toList()).candidate?.score == EXACT_TITLE_SCORE
                    }
                    // A unique strong doujin-source result avoids waiting on unrelated/general catalogs.
                    if (resolveCandidate(candidatePool.values.toList()).candidate == null) {
                        searchAcrossSources(remainingSources, sourceQuery, permits) { matches ->
                            addMatches(matches)
                            resolveCandidate(candidatePool.values.toList()).candidate?.score == EXACT_TITLE_SCORE
                        }
                    }
                    // Exact OCR-title matches are sufficient. Otherwise, try the shorter keyword variants.
                    if (resolveCandidate(candidatePool.values.toList()).candidate?.score == EXACT_TITLE_SCORE) break
                }

                val resolution = resolveCandidate(candidatePool.values.toList())
                val candidate = resolution.candidate
                if (candidate == null) {
                    unmatched++
                    val outcome = if (resolution.ambiguous) {
                        BatchImageSearchOutcome(record.id, "ambiguous", "Several similar source results; kept for review")
                    } else {
                        BatchImageSearchOutcome(record.id, "not_found", "No confident match in enabled sources; kept for review")
                    }
                    appendOutcome(outcomeFile, outcome)
                } else {
                    try {
                        val local = networkToLocalManga(candidate.manga, updateInfo = false)
                        if (local.favorite) {
                            alreadyPresent++
                            appendOutcome(outcomeFile, BatchImageSearchOutcome(record.id, "already", "Already in library · ${candidate.manga.title} · ${candidate.sourceName}"))
                        } else if (updateManga.awaitUpdateFavorite(local.id, true)) {
                            added++
                            appendOutcome(outcomeFile, BatchImageSearchOutcome(record.id, "added", "Added · ${candidate.manga.title} · ${candidate.sourceName}"))
                        } else {
                            unmatched++
                            appendOutcome(outcomeFile, BatchImageSearchOutcome(record.id, "add_failed", "Source match found but library add failed · ${candidate.manga.title}"))
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        unmatched++
                        appendOutcome(outcomeFile, BatchImageSearchOutcome(record.id, "add_failed", "Library add failed · ${error.message ?: error.javaClass.simpleName}"))
                    }
                }
                completed = index + 1
                setProgress(progressData(completed, records.size, added, alreadyPresent, unmatched, "Searched ${index + 1}/${records.size}"))
                if (completed % 5 == 0 || completed == records.size) {
                    NotificationManagerCompat.from(applicationContext).notify(
                        NOTIFICATION_ID,
                        notification(completed, records.size),
                    )
                }
            }
            val output = workDataOf(
                KEY_COMPLETED to completed,
                KEY_TOTAL to records.size,
                KEY_ADDED to added,
                KEY_ALREADY_PRESENT to alreadyPresent,
                KEY_UNMATCHED to unmatched,
                KEY_PHASE to "Search and library add complete",
            )
            setProgress(output)
            Result.success(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(
                workDataOf(
                    KEY_COMPLETED to completed,
                    KEY_TOTAL to records.size,
                    KEY_ADDED to added,
                    KEY_ALREADY_PRESENT to alreadyPresent,
                    KEY_UNMATCHED to unmatched,
                    KEY_ERROR to (error.message ?: "Source search failed"),
                ),
            )
        } finally {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification(0, inputData.getString(KEY_TOTAL_HINT)?.toIntOrNull() ?: 1),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun notification(completed: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT)
            .setSmallIcon(R.drawable.ic_komikku)
            .setContentTitle("Batch Image source search")
            .setContentText("Searching and adding $completed/$total confident matches…")
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

    private fun progressData(completed: Int, total: Int, added: Int, already: Int, unmatched: Int, phase: String) =
        workDataOf(
            KEY_COMPLETED to completed,
            KEY_TOTAL to total,
            KEY_ADDED to added,
            KEY_ALREADY_PRESENT to already,
            KEY_UNMATCHED to unmatched,
            KEY_PHASE to phase,
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

    private suspend fun searchAcrossSources(
        sources: List<HttpSource>,
        query: String,
        permits: Semaphore,
        shouldStop: (List<Candidate>) -> Boolean,
    ): List<Candidate> = coroutineScope {
        val collected = mutableListOf<Candidate>()
        for (sourceGroup in sources.chunked(MAX_CONCURRENT_SOURCE_SEARCHES)) {
            if (isStopped) throw CancellationException("Source search cancelled")
            val batch = sourceGroup.map { source ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        if (isStopped) throw CancellationException("Source search cancelled")
                        try {
                            source.getSearchManga(1, query.sanitize(), source.getFilterList()).mangas
                                .take(MAX_RESULTS_PER_SOURCE)
                                .map { Candidate(it.toDomainManga(source.id), source.name, 0) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
            collected += batch
            if (shouldStop(batch)) break
        }
        collected
    }

    private fun resolveCandidate(candidates: List<Candidate>): Resolution {
        val distinctWorks = candidates
            .groupBy { normalize(it.manga.title) }
            .values
            .mapNotNull { sameTitle -> sameTitle.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { sourcePriority(it.sourceName) }) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { sourcePriority(it.sourceName) })
        val best = distinctWorks.firstOrNull()?.takeIf { it.score >= MINIMUM_MATCH_SCORE } ?: return Resolution(null, false)
        val second = distinctWorks.getOrNull(1)
        if (second != null && best.score - second.score < MINIMUM_MATCH_MARGIN) return Resolution(null, true)
        return Resolution(best, false)
    }

    private fun appendOutcome(file: File, outcome: BatchImageSearchOutcome) {
        file.appendText(BatchImageSearchOutcomeCodec.encode(outcome) + "\n")
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
        const val NOTIFICATION_ID = -1806
        const val TAG = "batch_image_source_search"
        const val UNIQUE_WORK = "komikku_batch_image_source_search"
        private const val MAX_RESULTS_PER_SOURCE = 30
        private const val MAX_CONCURRENT_SOURCE_SEARCHES = 4
        private const val EXACT_TITLE_SCORE = 100
        private const val MINIMUM_MATCH_SCORE = 92
        private const val MINIMUM_MATCH_MARGIN = 6
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
                .setInputData(workDataOf(KEY_SELECTION_FILE to selectionFile.absolutePath, KEY_OUTCOME_FILE to outcomeFile.absolutePath, KEY_TOTAL_HINT to records.size.toString()))
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
}

data class BatchImageSearchOutcome(val recordId: String, val status: String, val message: String)

internal object BatchImageTitleMatcher {
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

private object BatchImageSearchOutcomeCodec {
    fun encode(outcome: BatchImageSearchOutcome): String = listOf(outcome.recordId, outcome.status, outcome.message)
        .joinToString("\t") { android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) }

    fun decode(line: String): BatchImageSearchOutcome? = runCatching {
        val values = line.split('\t').map { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        if (values.size != 3) return null
        BatchImageSearchOutcome(values[0], values[1], values[2])
    }.getOrNull()
}
