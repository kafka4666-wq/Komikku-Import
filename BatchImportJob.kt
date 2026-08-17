package exh.ui.batchadd

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.BatchImportStatus
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.GalleryAddEvent
import exh.GalleryAdder
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import com.hippo.unifile.UniFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BatchImportJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val status: BatchImportStatus = Injekt.get()
    private val storagePreferences: StoragePreferences = Injekt.get()
    private val requestGate = Mutex()
    private var nextRequestAt = 0L

    override suspend fun doWork(): Result {
        val inputPath = inputData.getString(INPUT_PATH) ?: return Result.failure()
        val inputFile = File(inputPath)
        val urls = runCatching { inputFile.readLines().filter(String::isNotBlank) }.getOrElse {
            xLogE("Batch import input read error", it)
            return Result.failure()
        }
        if (urls.isEmpty()) return Result.success()

        val checkpointFile = File("$inputPath.progress")
        val failedFile = File("$inputPath.failed")
        val eventsFile = File("$inputPath.events")
        var nextIndex = checkpointFile.readLinesSafely().firstOrNull()?.trim()?.toIntOrNull()?.coerceIn(0, urls.size) ?: 0
        val failedLinks = failedFile.readLinesSafely().toMutableList()
        val events = eventsFile.readLinesSafely()
        val initialFailed = failedLinks.size.coerceAtMost(nextIndex)
        var added = (nextIndex - initialFailed).coerceAtLeast(0)
        var failed = initialFailed

        status.begin(urls.size, nextIndex, added, failed, events)
        setForegroundSafely()
        showProgress(nextIndex, urls.size, added, failed)

        return try {
            while (nextIndex < urls.size) {
                val url = urls[nextIndex]
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
            }
            writeLinksFile("batch_import_failed_links", failedLinks)
            showComplete(nextIndex, urls.size, added, failed)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                writeUnprocessedLinks(urls, nextIndex, failedLinks)
                status.restore(urls.size, nextIndex, added, failed, eventsFile.readLinesSafely(), running = false)
            }
            throw cancelled
        } catch (error: Throwable) {
            xLogE("Batch import error", error)
            withContext(NonCancellable) {
                writeUnprocessedLinks(urls, nextIndex, failedLinks)
                status.restore(urls.size, nextIndex, added, failed, eventsFile.readLinesSafely(), running = false)
            }
            Result.retry()
        } finally {
            context.cancelNotification(Notifications.ID_BATCH_IMPORT_PROGRESS)
            if (!isStopped && nextIndex >= urls.size) {
                inputFile.delete()
                checkpointFile.delete()
                failedFile.delete()
                eventsFile.delete()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_BATCH_IMPORT_PROGRESS,
        buildProgressNotification(0, 1, 0, 0),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private suspend fun addGalleryRateLimited(url: String): GalleryAddEvent {
        var result: GalleryAddEvent = GalleryAddEvent.Fail.Error(url, "Rate limit retry exhausted")
        for (attempt in 0 until RATE_LIMIT_RETRIES) {
            awaitRequestSlot()
            result = GalleryAdder().addGallery(context = context, url = url, fav = true, retry = 1)
            if (!isRateLimited(result)) return result
            if (attempt < RATE_LIMIT_RETRIES - 1) {
                delay((RATE_LIMIT_COOLDOWN_MS * (1L shl attempt)).coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS))
            }
        }
        return result
    }

    private suspend fun awaitRequestSlot() {
        val waitFor = requestGate.withLock {
            val now = System.currentTimeMillis()
            val wait = (nextRequestAt - now).coerceAtLeast(0L)
            nextRequestAt = maxOf(now, nextRequestAt) + REQUEST_INTERVAL_MS
            wait
        }
        if (waitFor > 0) delay(waitFor)
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
        private const val REQUEST_INTERVAL_MS = 4_000L
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        private const val MAX_RATE_LIMIT_COOLDOWN_MS = 10 * 60_000L
        private const val RATE_LIMIT_RETRIES = 3

        fun start(context: Context, urls: List<String>) {
            val input = File(context.cacheDir, "batch-import-${System.currentTimeMillis()}.txt")
            input.writeText(urls.joinToString("\n"))
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
    }
}
