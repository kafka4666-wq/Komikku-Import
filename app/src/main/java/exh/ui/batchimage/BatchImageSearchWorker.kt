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
import kotlinx.coroutines.withContext
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
        val records = selectionPath.readLines().mapNotNull(BatchImageRecordCodec::decode)
        if (records.isEmpty()) return Result.success(workDataOf(KEY_COMPLETED to 0, KEY_TOTAL to 0))
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabledSources = sourcePreferences.disabledSources().get()
        val sources = sourceManager.getVisibleOnlineSources()
            .filter { it.lang in enabledLanguages && it.id.toString() !in disabledSources }
            .sortedWith(compareByDescending<HttpSource> { sourcePriority(it.name) }.thenBy { it.name.lowercase() })
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
                val queryArtist = record.artist?.takeIf(String::isNotBlank)
                // An artist-only query may return an entire catalog. Leave those candidates
                // for manual review instead of auto-favoriting unrelated works.
                if (queryTitle == null) {
                    unmatched++
                    completed = index + 1
                    setProgress(progressData(completed, records.size, added, alreadyPresent, unmatched, "No title or artist to search"))
                    return@forEachIndexed
                }
                val query = listOfNotNull(queryTitle, queryArtist).joinToString(" ")
                val titleQueries = BatchImageTextExtractor.searchQueries(queryTitle!!)
                setProgress(progressData(completed, records.size, added, alreadyPresent, unmatched, "Searching ${index + 1}/${records.size}: $query"))

                var best: Candidate? = null
                for (source in sources) {
                    if (isStopped) throw CancellationException("Source search cancelled")
                    for (sourceQuery in titleQueries) {
                        val matches = runCatching {
                            withContext(Dispatchers.IO) {
                                source.getSearchManga(1, sourceQuery.sanitize(), source.getFilterList()).mangas
                                    .take(MAX_RESULTS_PER_SOURCE)
                                    .map { it.toDomainManga(source.id) }
                            }
                        }.getOrDefault(emptyList())
                        for (manga in matches) {
                            val score = titleQueries.maxOfOrNull { matchScore(it, queryArtist, manga) } ?: 0
                            if (score >= MINIMUM_MATCH_SCORE && (best == null || score > best!!.score)) {
                                best = Candidate(manga, source.name, score)
                            }
                        }
                        if ((best?.score ?: 0) >= EARLY_EXIT_SCORE) break
                    }
                    // Search known doujin sources first; an exact title there is strong enough to stop.
                    if ((best?.score ?: 0) >= EARLY_EXIT_SCORE) break
                }

                val candidate = best
                if (candidate == null) {
                    unmatched++
                } else {
                    val local = networkToLocalManga(candidate.manga, updateInfo = false)
                    if (local.favorite) {
                        alreadyPresent++
                    } else if (updateManga.awaitUpdateFavorite(local.id, true)) {
                        added++
                    } else {
                        unmatched++
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

    private fun matchScore(title: String?, artist: String?, manga: Manga): Int {
        val target = title ?: artist ?: return 0
        val candidateText = if (title != null) manga.title else listOfNotNull(manga.artist, manga.author).joinToString(" ")
        val targetNormalized = normalize(target)
        val candidateNormalized = normalize(candidateText)
        if (targetNormalized.isBlank() || candidateNormalized.isBlank()) return 0
        val titleScore = when {
            targetNormalized == candidateNormalized -> 100
            candidateNormalized.contains(targetNormalized) || targetNormalized.contains(candidateNormalized) -> 88
            else -> {
                val targetTokens = targetNormalized.split(' ').filter(String::isNotBlank).toSet()
                val candidateTokens = candidateNormalized.split(' ').filter(String::isNotBlank).toSet()
                if (targetTokens.isEmpty() || candidateTokens.isEmpty()) 0
                else (100.0 * targetTokens.intersect(candidateTokens).size / maxOf(targetTokens.size, candidateTokens.size)).toInt()
            }
        }
        if (titleScore == 0) return 0
        val creatorScore = if (artist != null && normalize(listOfNotNull(manga.artist, manga.author).joinToString(" ")) == normalize(artist)) 8 else 0
        return (titleScore + creatorScore).coerceAtMost(100)
    }

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
        const val NOTIFICATION_ID = -1806
        const val TAG = "batch_image_source_search"
        const val UNIQUE_WORK = "komikku_batch_image_source_search"
        private const val MAX_RESULTS_PER_SOURCE = 30
        private const val MINIMUM_MATCH_SCORE = 86
        private const val EARLY_EXIT_SCORE = 92
        private const val PREFS = "batch_image_source_search"
        private const val PREF_JOB_ID = "job_id"

        fun enqueue(context: Context, records: List<BatchImageRecord>): UUID {
            val jobId = UUID.randomUUID()
            val directory = File(context.filesDir, "batch-image").apply { mkdirs() }
            val selectionFile = File(directory, "$jobId-search.selection")
            selectionFile.writeText(records.joinToString("\n") { BatchImageRecordCodec.encode(it) })
            val request = OneTimeWorkRequestBuilder<BatchImageSearchWorker>()
                .setId(jobId)
                .setInputData(workDataOf(KEY_SELECTION_FILE to selectionFile.absolutePath, KEY_TOTAL_HINT to records.size.toString()))
                .addTag(TAG)
                .build()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_JOB_ID, jobId.toString()).apply()
            context.workManager.enqueueUniqueWork(UNIQUE_WORK, androidx.work.ExistingWorkPolicy.REPLACE, request)
            return jobId
        }

        fun savedJobId(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_JOB_ID, null)
    }

    private data class Candidate(val manga: Manga, val sourceName: String, val score: Int)
}
