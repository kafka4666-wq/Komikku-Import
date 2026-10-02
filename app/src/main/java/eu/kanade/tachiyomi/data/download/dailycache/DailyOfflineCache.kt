package eu.kanade.tachiyomi.data.download.dailycache

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.ui.KodamiTreasuryPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object DailyOfflineCacheJob {
    private const val CACHE_WORK_PREFIX = "komikku-daily-cache-"

    fun enqueue(context: Context): Boolean {
        val today = LocalDate.now().toString()
        val request = OneTimeWorkRequestBuilder<DailyOfflineCacheWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$CACHE_WORK_PREFIX$today",
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }
}

class DailyOfflineCacheWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val result = DailyOfflineCacheManager(applicationContext).cacheToday()
        Result.success(
            workDataOf(
                OUTPUT_TITLE_COUNT to result.titleCount,
                OUTPUT_CHAPTER_COUNT to result.newlyQueuedChapterCount,
            ),
        )
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val OUTPUT_TITLE_COUNT = "title_count"
        const val OUTPUT_CHAPTER_COUNT = "new_chapter_count"
    }
}

class DailyOfflineCacheCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        DailyOfflineCacheManager(applicationContext).expireIfNeeded()
        scheduleNext(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        private const val CLEANUP_WORK_PREFIX = "komikku-daily-cache-expiry-"

        /** WorkManager is persisted across process death and reboot; expireIfNeeded also runs on app resume. */
        fun scheduleNext(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            val nextDate = now.toLocalDate().plusDays(1)
            val nextMidnight = nextDate.atStartOfDay(zone)
            val delayMillis = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L)
            val request = OneTimeWorkRequestBuilder<DailyOfflineCacheCleanupWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$CLEANUP_WORK_PREFIX$nextDate",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

class DailyOfflineCacheManager(
    private val context: Context,
    private val preferences: KodamiTreasuryPreferences = KodamiTreasuryPreferences(Injekt.get()),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {
    private val recordsFile: File
        get() = File(context.filesDir, "komikku-daily-cache-owned-chapters.txt")

    suspend fun cacheToday(): CacheResult = withContext(Dispatchers.IO) {
        expireIfNeeded()
        val today = LocalDate.now().toString()
        val existingDate = preferences.dailyCacheDate().get()
        val existingIds = parseIds(preferences.dailyCacheMangaIds().get())
        if (existingDate == today && existingIds.isNotEmpty()) {
            preferences.dailyCacheStatus().set("Today's cache is already ready (${existingIds.size} titles).")
            return@withContext CacheResult(existingIds.size, 0)
        }

        val selected = mutableListOf<CachedTitle>()
        val library: List<LibraryManga> = getLibraryManga.await()
        for (entry in library.filter { it.totalChapters > 0 }.shuffled()) {
            if (selected.size >= TITLES_PER_DAY) break
            val manga = entry.manga
            if (sourceManager.get(manga.source) !is HttpSource) continue
            val chapters = getChaptersByMangaId.await(manga.id, applyFilter = false)
                .filter { it.id > 0L }
            if (chapters.isNotEmpty()) selected += CachedTitle(manga, chapters)
        }
        if (selected.isEmpty()) {
            preferences.dailyCacheStatus().set("No eligible library titles with available chapters were found.")
            return@withContext CacheResult(0, 0)
        }

        val newlyQueued = mutableListOf<Pair<Long, Long>>()
        selected.forEach { title ->
            val queued = downloadManager.downloadChaptersTracked(title.manga, title.chapters)
            queued.forEach { chapter -> newlyQueued += title.manga.id to chapter.id }
        }

        // Persist ownership before starting the queue. At midnight, only chapters newly
        // admitted by this job are deleted; favorited titles are retained explicitly.
        writeRecords(newlyQueued)
        val retainedIds = readRecords().map { it.first }.distinct()
        preferences.dailyCacheMangaIds().set((retainedIds + selected.map { it.manga.id }).distinct().joinToString(","))
        preferences.dailyCacheDate().set(today)
        downloadManager.startDownloads()
        preferences.dailyCacheStatus().set(
            "Prepared ${selected.size} titles; queued ${newlyQueued.size} new chapters.",
        )
        CacheResult(selected.size, newlyQueued.size)
    }

    suspend fun expireIfNeeded() = withContext(Dispatchers.IO) {
        val cacheDate = preferences.dailyCacheDate().get()
        val today = LocalDate.now().toString()
        if (cacheDate.isBlank() || cacheDate >= today) return@withContext

        val retained = mutableListOf<Pair<Long, Long>>()
        readRecords().groupBy { it.first }.forEach { (mangaId, records) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            if (manga.favorite) {
                retained += records
                return@forEach
            }
            val source = sourceManager.get(manga.source) ?: return@forEach
            val wantedChapterIds = records.mapTo(hashSetOf()) { it.second }
            val ownedChapters = getChaptersByMangaId.await(mangaId, applyFilter = false)
                .filter { it.id in wantedChapterIds }
            if (ownedChapters.isNotEmpty()) {
                downloadManager.deleteChaptersNow(
                    chapters = ownedChapters,
                    manga = manga,
                    source = source,
                    ignoreCategoryExclusion = true,
                )
            }
        }

        if (retained.isEmpty()) recordsFile.delete() else writeRecords(retained, replace = true)
        preferences.dailyCacheMangaIds().set(retained.map { it.first }.distinct().joinToString(","))
        preferences.dailyCacheChapterRecords().set("")
        preferences.dailyCacheDate().set("")
        preferences.dailyCacheStatus().set("")
    }

    private fun writeRecords(records: List<Pair<Long, Long>>, replace: Boolean = false) {
        val merged = if (replace) records.distinct() else (readRecords() + records).distinct()
        val tempFile = File(context.filesDir, "komikku-daily-cache-owned-chapters.tmp")
        tempFile.writeText(merged.joinToString("\n") { (mangaId, chapterId) -> "$mangaId:$chapterId" })
        if (recordsFile.exists() && !recordsFile.delete()) error("Could not replace daily-cache ownership records")
        if (!tempFile.renameTo(recordsFile)) error("Could not save daily-cache ownership records")
    }

    private fun readRecords(): List<Pair<Long, Long>> {
        if (!recordsFile.exists()) return emptyList()
        return recordsFile.useLines { lines ->
            lines.mapNotNull { line ->
                val split = line.split(':', limit = 2)
                val mangaId = split.getOrNull(0)?.toLongOrNull()
                val chapterId = split.getOrNull(1)?.toLongOrNull()
                if (mangaId != null && chapterId != null) mangaId to chapterId else null
            }.toList()
        }
    }

    private fun parseIds(value: String): List<Long> = value
        .split(',')
        .mapNotNull(String::toLongOrNull)
        .distinct()

    data class CacheResult(
        val titleCount: Int,
        val newlyQueuedChapterCount: Int,
    )

    private data class CachedTitle(
        val manga: Manga,
        val chapters: List<Chapter>,
    )

    private companion object {
        const val TITLES_PER_DAY = 30
    }
}
