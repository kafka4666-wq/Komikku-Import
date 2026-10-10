package exh.ui.reverse

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.workManager
import exh.ui.batchadd.BatchImportJob
import exh.ui.batchadd.BatchTitleSearchWorker
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Searches selected images in the background and imports only confident matches. */
class ReverseSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result {
        // Explicitly promote the worker. getForegroundInfo() alone does not keep it alive after
        // the screen closes or the app leaves the foreground.
        setForeground(getForegroundInfo())
        safeUpdateNotification(0, 0, "Preparing selected images", 0)

        val selectionFile = inputData.getString(KEY_SELECTION)?.let(::File) ?: return Result.failure()
        val seeds = runCatching { selectionFile.readLines().map(Uri::parse) }.getOrElse {
            safeUpdateNotification(0, 0, "Could not read the selected folder; retrying", 0)
            return Result.retry()
        }
        val images = runCatching { discover(seeds).distinct().take(MAX_IMAGES) }.getOrElse {
            safeUpdateNotification(0, 0, "Could not scan the selected folder; retrying", 0)
            return Result.retry()
        }
        if (images.isEmpty()) {
            val result = workDataOf(KEY_PHASE to "No images found", KEY_COMPLETED to 0, KEY_TOTAL to 0, KEY_FOUND to 0)
            safeSetProgress(result)
            safeUpdateNotification(0, 0, "No images found", 0)
            return Result.success(result)
        }

        val completed = AtomicInteger(0)
        val foundCount = AtomicInteger(0)
        val outcomes = supervisorScope {
            val gate = Semaphore(MAX_PARALLEL)
            images.map { uri ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (isStopped) return@withPermit null
                        // A timeout, HTTP error, malformed response, or unreadable image only
                        // skips that image instead of cancelling the entire album job.
                        var outcome: ReverseOutcome? = null
                        try {
                            val reverse = withTimeoutOrNull(45_000L) { searchSauceNao(uri) }
                            outcome = reverse?.let { ReverseOutcome(it.directUrl, it.titles) }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            // Bad URI, provider response, or network failure: skip only this image.
                        } finally {
                            val done = completed.incrementAndGet()
                            if (outcome?.directUrl != null || !outcome?.titles.isNullOrEmpty()) foundCount.incrementAndGet()
                            val phase = "Reverse searched $done/${images.size} images"
                            val progress = workDataOf(
                                KEY_PHASE to phase,
                                KEY_COMPLETED to done,
                                KEY_TOTAL to images.size,
                                KEY_FOUND to foundCount.get(),
                            )
                            safeSetProgress(progress)
                            safeUpdateNotification(done, images.size, "$phase · ${foundCount.get()} matches", foundCount.get())
                        }
                        outcome
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (isStopped) return Result.failure()
        val directUrls = outcomes.mapNotNull { it.directUrl }.distinct()
        val titles = outcomes.flatMap { it.titles }.distinct().take(MAX_TITLES)
        if (directUrls.isNotEmpty()) {
            // Library insertion continues through the existing rate-limited batch importer.
            runCatching { BatchImportJob.start(applicationContext, directUrls) }
        }
        if (titles.isNotEmpty()) {
            // Use the exact worker behind Batch Add's "Find titles from TXT in all enabled
            // sources" action. It searches enabled sources and automatically queues its
            // candidates through the normal title-match importer.
            runCatching { BatchTitleSearchWorker.enqueueTitles(applicationContext, titles, autoImport = true) }
        }

        val result = workDataOf(
            KEY_PHASE to "Reverse search complete · ${directUrls.size} direct match(es), ${titles.size} title(s) sent to Batch Add",
            KEY_COMPLETED to images.size,
            KEY_TOTAL to images.size,
            KEY_FOUND to directUrls.size + titles.size,
        )
        safeSetProgress(result)
        safeUpdateNotification(images.size, images.size, "Complete · ${directUrls.size} direct matches; ${titles.size} titles sent to Batch Add", directUrls.size + titles.size)
        return Result.success(result)
    }

    private suspend fun safeSetProgress(data: androidx.work.Data) {
        try {
            setProgress(data)
        } catch (_: Throwable) {
            // WorkManager progress is diagnostic only; it must never abort the search.
        }
    }

    private fun safeUpdateNotification(completed: Int, total: Int, detail: String, found: Int) {
        runCatching { updateNotification(completed, total, detail, found) }
    }

    private suspend fun searchSauceNao(uri: Uri): ReverseResult? = withContext(Dispatchers.IO) {
        val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "image.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://saucenao.com/search.php")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body.string()
        }
        val best = NHENTAI_REGEX.findAll(html).mapNotNull { match ->
            val context = html.substring(
                (match.range.first - 700).coerceAtLeast(0),
                (match.range.last + 700).coerceAtMost(html.length),
            )
            val score = SCORE_REGEX.find(context)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
            score to "https://nhentai.net/g/${match.groupValues[1]}/"
        }.maxByOrNull { it.first }
        val titles = TITLE_REGEX.findAll(html)
            .map { stripHtml(it.groupValues[1]) }
            .filter { it.length >= 4 }
            .distinct()
            .take(MAX_TITLES)
            .toList()
        if (best == null && titles.isEmpty()) return@withContext null
        ReverseResult(best?.takeIf { it.first >= MIN_SCORE }?.second, titles)
    }

    private fun stripHtml(value: String): String = value.replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&(?:amp|quot|#39|lt|gt);"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun discover(seeds: List<Uri>): List<Uri> {
        val result = mutableListOf<Uri>()
        val pending = ArrayDeque<Uri>()
        seeds.forEach { uri ->
            runCatching {
                if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) pending += uri
                else if (applicationContext.contentResolver.getType(uri)?.startsWith("image/") == true) result += uri
            }
        }
        while (pending.isNotEmpty() && result.size < MAX_IMAGES) {
            val tree = pending.removeFirst()
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            runCatching {
                applicationContext.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && result.size < MAX_IMAGES) {
                        val childId = cursor.getString(0)
                        val mime = cursor.getString(1).orEmpty()
                        val child = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending += child
                        else if (mime.startsWith("image/")) result += child
                    }
                }
            }
        }
        return result
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        applicationContext.notificationBuilder(Notifications.CHANNEL_KOMIKKU_IMPORT) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Reverse Search")
            setContentText("Searching selected images")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
        }.build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun updateNotification(completed: Int, total: Int, detail: String, found: Int) {
        NotificationManagerCompat.from(applicationContext).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT)
                .setSmallIcon(R.drawable.ic_komikku)
                .setContentTitle("Reverse Search")
                .setContentText(detail)
                .setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total.coerceAtLeast(1)), false)
                .setSubText(if (found > 0) "$found match(es)" else null)
                .setOngoing(completed < total || total == 0)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_close_24dp, "Cancel", applicationContext.workManager.createCancelPendingIntent(id))
                .build(),
        )
    }

    companion object {
        const val KEY_SELECTION = "selection"
        const val KEY_PHASE = "phase"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_FOUND = "found"
        const val NOTIFICATION_ID = -1810
        const val TAG = "komikku_reverse_search"
        private const val MAX_IMAGES = 2_000
        private const val MAX_PARALLEL = 4
        private const val MAX_TITLES = 4
        private const val MIN_SCORE = 60f
        private const val USER_AGENT = "Komikku Reverse Search/1.0"
        private val NHENTAI_REGEX = Regex("nhentai\\.net/g/(\\d{5,8})", RegexOption.IGNORE_CASE)
        private val SCORE_REGEX = Regex("(?:similarity|similar|match)[^%]{0,80}(\\d{1,3}(?:\\.\\d+)?)%", RegexOption.IGNORE_CASE)
        private val TITLE_REGEX = Regex(
            "class\\s*=\\s*[\\\"'](?:resulttitle|result-title)[\\\"'][^>]*>(.*?)</(?:div|a)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun enqueue(context: Context, selections: List<Uri>): UUID {
            val id = UUID.randomUUID()
            val dir = File(context.filesDir, "reverse-search").apply { mkdirs() }
            val file = File(dir, "$id.selection")
            file.writeText(selections.joinToString("\n"))
            val request = OneTimeWorkRequestBuilder<ReverseSearchWorker>()
                .setId(id)
                .setInputData(workDataOf(KEY_SELECTION to file.absolutePath))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(
                "komikku_reverse_search",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
            return id
        }
    }

    private data class ReverseResult(val directUrl: String?, val titles: List<String>)
    private data class ReverseOutcome(val directUrl: String?, val titles: List<String>)
}
