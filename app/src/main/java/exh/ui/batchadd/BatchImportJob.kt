package exh.ui.batchadd

import android.content.Context
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.BatchImportStatus
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.GalleryAddEvent
import exh.GalleryAdder
import exh.source.nHentaiSourceIds
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import com.hippo.unifile.UniFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A single process-wide gate shared by discovery and library insertion. */
object BatchImportRequestLimiter {
    private const val REQUEST_INTERVAL_MS = 4_000L
    private val mutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun await() {
        val waitFor = mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = (nextRequestAt - now).coerceAtLeast(0L)
            nextRequestAt = maxOf(now, nextRequestAt) + REQUEST_INTERVAL_MS
            wait
        }
        if (waitFor > 0) delay(waitFor)
    }
}

class BatchImportJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val status: BatchImportStatus = Injekt.get()
    private val storagePreferences: StoragePreferences = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val identityRepairLock = Mutex()
    private var identityRepairLoaded = false
    private val sourceByCanonicalUrl = mutableMapOf<String, Long>()
    private val mangaIdByCanonicalUrl = mutableMapOf<String, Long>()

    override suspend fun doWork(): Result {
        val inputPath = inputData.getString(INPUT_PATH) ?: return Result.failure()
        val inputFile = File(inputPath)
        val checkpointFile = File("$inputPath.progress")
        val failedFile = File("$inputPath.failed")
        val eventsFile = File("$inputPath.events")
        val doneFile = File("$inputPath.done")
        var urls = inputFile.readLinesSafely().toMutableList()
        if (urls.isEmpty() && !inputFile.exists()) return Result.failure()
        var nextIndex = checkpointFile.readLinesSafely().firstOrNull()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        var failedLinks = failedFile.readLinesSafely().toMutableList()
        var added = (nextIndex - failedLinks.size).coerceAtLeast(0)
        var failed = failedLinks.size.coerceAtMost(nextIndex)
        var announcedTotal = -1

        status.begin(urls.size, nextIndex.coerceAtMost(urls.size), added, failed, eventsFile.readLinesSafely())
        setForegroundSafely()
        showProgress(nextIndex, urls.size, added, failed)

        return try {
            while (true) {
                val available = inputFile.readLinesSafely()
                if (available.size > urls.size) {
                    urls.addAll(available.drop(urls.size))
                }
                if (urls.size != announcedTotal) {
                    status.begin(urls.size, nextIndex.coerceAtMost(urls.size), added, failed, eventsFile.readLinesSafely())
                    announcedTotal = urls.size
                    showProgress(nextIndex, urls.size, added, failed)
                }

                if (nextIndex < urls.size) {
                    val url = urls[nextIndex]
                    repairExistingNhentaiIdentity(url)
                    val result = addGalleryRateLimited(url)
                    val wasAdded = result is GalleryAddEvent.Success
                    val detail = if (wasAdded) null else (result as? GalleryAddEvent.Fail.Error)?.logMessage
                    if (wasAdded) {
                        added++
                    } else {
                        failed++
                        failedLinks += url
                        failedFile.appendLineSafely(url)
                    }
                    val event = if (wasAdded) "[ADDED] $url" else "[FAILED] $url${detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}"
                    eventsFile.appendLineSafely(event)
                    nextIndex++
                    checkpointFile.writeText(nextIndex.toString())
                    status.record(url, wasAdded, detail)
                    showProgress(nextIndex, urls.size, added, failed)
                    continue
                }

                if (doneFile.exists()) break
                delay(PAUSE_POLL_MS)
            }

            writeLinksFile("batch_import_failed_links", failedLinks)
            showComplete(nextIndex, urls.size, added, failed)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val currentUrls = inputFile.readLinesSafely()
                writeUnprocessedLinks(currentUrls, nextIndex, failedLinks)
                status.restore(currentUrls.size, nextIndex.coerceAtMost(currentUrls.size), added, failed, eventsFile.readLinesSafely(), running = false)
            }
            throw cancelled
        } catch (error: Throwable) {
            xLogE("Batch import error", error)
            withContext(NonCancellable) {
                val currentUrls = inputFile.readLinesSafely()
                writeUnprocessedLinks(currentUrls, nextIndex, failedLinks)
                status.restore(currentUrls.size, nextIndex.coerceAtMost(currentUrls.size), added, failed, eventsFile.readLinesSafely(), running = false)
            }
            Result.retry()
        } finally {
            context.cancelNotification(Notifications.ID_BATCH_IMPORT_PROGRESS)
            if (!isStopped && doneFile.exists() && nextIndex >= inputFile.readLinesSafely().size) {
                inputFile.delete()
                checkpointFile.delete()
                failedFile.delete()
                eventsFile.delete()
                doneFile.delete()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_BATCH_IMPORT_PROGRESS,
        buildProgressNotification(0, 1, 0, 0),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    /**
     * The source grid marks a result as present by querying (manga.url, manga.source).
     * Older importer builds could resolve the same nhentai gallery through a different
     * delegated language/source ID. Repair that key before GalleryAdder looks it up,
     * preserving the existing row, favorite state, chapters, and categories.
     */
    private suspend fun repairExistingNhentaiIdentity(url: String) {
        val identity = runCatching { GalleryAdder().canonicalMangaIdentity(url) }.getOrNull() ?: return
        val canonicalUrl = identity.second.trimEnd('/').ifBlank { return }
        val targetSource = identity.first
        identityRepairLock.withLock {
            if (!identityRepairLoaded) {
                getLibraryManga.await().forEach { libraryManga ->
                    val manga = libraryManga.manga
                    val normalizedUrl = manga.url.trimEnd('/').ifBlank { return@forEach }
                    val sourceIsNhentai = nHentaiSourceIds.isEmpty() || manga.source in nHentaiSourceIds
                    if (sourceIsNhentai && normalizedUrl.startsWith("/g/")) {
                        sourceByCanonicalUrl[normalizedUrl] = manga.source
                        mangaIdByCanonicalUrl[normalizedUrl] = manga.id
                    }
                }
                identityRepairLoaded = true
            }

            val existingSource = sourceByCanonicalUrl[canonicalUrl] ?: return
            if (existingSource == targetSource) return
            val mangaId = mangaIdByCanonicalUrl[canonicalUrl] ?: return
            if (updateManga.await(MangaUpdate(id = mangaId, source = targetSource))) {
                sourceByCanonicalUrl[canonicalUrl] = targetSource
            }
        }
    }

    private suspend fun addGalleryRateLimited(url: String): GalleryAddEvent {
        awaitResume()
        var result: GalleryAddEvent = GalleryAddEvent.Fail.Error(url, "Rate limit retry exhausted")
        for (attempt in 0 until RATE_LIMIT_RETRIES) {
            awaitResume()
            BatchImportRequestLimiter.await()
            result = GalleryAdder().addGallery(context = context, url = url, fav = true, retry = 1)
            if (!isRateLimited(result)) return result
            if (attempt < RATE_LIMIT_RETRIES - 1) {
                delay((RATE_LIMIT_COOLDOWN_MS * (1L shl attempt)).coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS))
            }
        }
        return result
    }

    private suspend fun awaitResume() {
        while (isPaused(context)) delay(PAUSE_POLL_MS)
    }

    private fun isRateLimited(result: GalleryAddEvent): Boolean =
        result is GalleryAddEvent.Fail.Error && result.logMessage.lowercase().let {
            "429" in it || "too many request" in it || "rate limit" in it || "rate-limit" in it
        }

    private fun buildProgressNotification(completed: Int, total: Int, added: Int, failed: Int) =
        context.notificationBuilder(Notifications.CHANNEL_BATCH_IMPORT_PROGRESS) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Adding manga")
            setContentText("${if (total == 0) 0 else completed * 100 / total}% • $completed/$total processed • $added added • $failed failed")
            setProgress(total.coerceAtLeast(1), completed, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setAutoCancel(false)
            setSubText("${if (total == 0) 0 else completed * 100 / total}%")
            addAction(R.drawable.ic_pause_24dp, "Pause", NotificationReceiver.pauseBatchImportPendingBroadcast(context))
            addAction(R.drawable.ic_play_arrow_24dp, "Resume", NotificationReceiver.resumeBatchImportPendingBroadcast(context))
            addAction(R.drawable.ic_close_24dp, "Cancel", NotificationReceiver.cancelBatchImportPendingBroadcast(context))
        }.build()

    private fun showProgress(completed: Int, total: Int, added: Int, failed: Int) {
        context.notify(Notifications.ID_BATCH_IMPORT_PROGRESS, buildProgressNotification(completed, total, added, failed))
    }

    private fun showComplete(completed: Int, total: Int, added: Int, failed: Int) {
        context.cancelNotification(Notifications.ID_BATCH_IMPORT_PROGRESS)
        context.notificationBuilder(Notifications.CHANNEL_BATCH_IMPORT_COMPLETE) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Manga adding complete")
            setContentText("100% • $completed/$total processed • $added added • $failed failed")
            setAutoCancel(true)
        }.also { context.notify(Notifications.ID_BATCH_IMPORT_COMPLETE, it.build()) }
    }

    private fun writeUnprocessedLinks(urls: List<String>, nextIndex: Int, failedLinks: List<String>) {
        val pending = urls.drop(nextIndex)
        writeLinksFile("batch_import_unprocessed_links", (failedLinks + pending).distinct())
    }

    private fun writeLinksFile(prefix: String, links: List<String>) {
        if (links.isEmpty()) return
        runCatching {
            val directory = UniFile.fromUri(context, Uri.parse(storagePreferences.baseStorageDirectory().get())) ?: return
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = directory.createFile("${prefix}_$timestamp.txt") ?: return
            file.openOutputStream().bufferedWriter().use { writer -> links.distinct().forEach(writer::appendLine) }
        }.onFailure { xLogE("Batch-link export error", it) }
    }

    private fun File.readLinesSafely(): List<String> = runCatching { if (exists()) readLines().filter(String::isNotBlank) else emptyList() }.getOrDefault(emptyList())

    private fun File.appendLineSafely(value: String) {
        parentFile?.mkdirs()
        appendText(value + "\n")
    }

    companion object {
        private const val TAG = "BatchImport"
        private const val INPUT_PATH = "input_path"
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        private const val MAX_RATE_LIMIT_COOLDOWN_MS = 10 * 60_000L
        private const val RATE_LIMIT_RETRIES = 3
        private const val PAUSE_POLL_MS = 500L
        private const val PREFS_NAME = "batch_import_controls"
        private const val PREFS_PAUSED = "paused"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fun isPaused(context: Context): Boolean = prefs(context).getBoolean(PREFS_PAUSED, false)
        fun pause(context: Context) { prefs(context).edit().putBoolean(PREFS_PAUSED, true).apply() }
        fun resume(context: Context) { prefs(context).edit().putBoolean(PREFS_PAUSED, false).apply() }

        fun start(context: Context, urls: List<String>) {
            val input = File(context.cacheDir, "batch-import-${System.currentTimeMillis()}.txt")
            input.writeText(urls.joinToString("\n"))
            File("${input.absolutePath}.done").writeText("done")
            enqueue(context, input)
        }

        fun startFromFile(context: Context, input: File) {
            input.parentFile?.mkdirs()
            if (!input.exists()) input.createNewFile()
            enqueue(context, input)
        }

        private fun enqueue(context: Context, input: File) {
            val request = OneTimeWorkRequestBuilder<BatchImportJob>()
                .setInputData(workDataOf(INPUT_PATH to input.absolutePath))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        fun cancel(context: Context) {
            resume(context)
            stop(context)
        }
    }
}
