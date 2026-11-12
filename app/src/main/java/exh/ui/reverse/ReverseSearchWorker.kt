package exh.ui.reverse

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.app.PendingIntent
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
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.workManager
import exh.ui.batchadd.BatchImportJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Yandex-only reverse search. Imports only a canonical nH/eH page whose returned
 * source image passes a strict perceptual comparison against the selected image. */
class ReverseSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val candidateImageGate = Semaphore(MAX_PARALLEL_CANDIDATE_IMAGES)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result {
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
        if (images.isEmpty()) return Result.success(workDataOf(KEY_PHASE to "No images found", KEY_COMPLETED to 0, KEY_TOTAL to 0, KEY_FOUND to 0))

        val journalFile = File(applicationContext.filesDir, "reverse-search/${id}.results.jsonl").apply { parentFile?.mkdirs() }
        val previous = readJournal(journalFile)
        val previousIds = previous.map { it.originalUri }.toHashSet()
        val pendingImages = images.filterNot { it.toString() in previousIds }
        val completed = AtomicInteger(previous.size)
        val foundCount = AtomicInteger(previous.count { !it.title.isNullOrBlank() })
        safeSetProgress(workDataOf(KEY_PHASE to "Resuming ${pendingImages.size} unfinished image(s)", KEY_COMPLETED to completed.get(), KEY_TOTAL to images.size, KEY_FOUND to foundCount.get(), KEY_REVIEW_FILE to journalFile.absolutePath))
        if (pendingImages.isEmpty()) return finishReview(images, previous, journalFile)
        val outcomes = supervisorScope {
            val gate = Semaphore(MAX_PARALLEL)
            pendingImages.map { uri ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (isStopped) return@withPermit null
                        awaitNotPaused()
                        var outcome: ReverseOutcome? = null
                        var safeToJournal = false
                        try {
                            outcome = searchYandexWithTransientRetry(uri)
                            safeToJournal = true
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            // A Yandex block, timeout, malformed result, or unreadable image skips only this image.
                            safeToJournal = true
                        } finally {
                            // WorkManager cancellation is not a search result. Leave this URI
                            // absent from the journal so a resumed worker will try it again.
                            if (safeToJournal && !isStopped) {
                                appendJournal(journalFile, ReverseOutcome(uri.toString(), outcome?.directUrl, outcome?.title, outcome?.matchedImage))
                                val done = completed.incrementAndGet()
                                if (outcome?.title != null) foundCount.incrementAndGet()
                                val phase = "Yandex searched $done/${images.size} images"
                                val progress = workDataOf(KEY_PHASE to phase, KEY_COMPLETED to done, KEY_TOTAL to images.size, KEY_FOUND to foundCount.get(), KEY_PAUSED to isPaused(applicationContext), KEY_REVIEW_FILE to journalFile.absolutePath)
                                safeSetProgress(progress)
                                safeUpdateNotification(done, images.size, "$phase · ${foundCount.get()} verified matches", foundCount.get())
                            }
                        }
                        outcome
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (isStopped) return Result.failure()
        return finishReview(images, readJournal(journalFile), journalFile)
    }

    private suspend fun finishReview(images: List<Uri>, completedRows: List<ReverseOutcome>, journalFile: File): Result {
        val reviewedUris = completedRows.map { it.originalUri }.toSet()
        val reviewRows = completedRows + images.filter { it.toString() !in reviewedUris }.map { ReverseOutcome(it.toString(), null, null, null) }
        val reviewFile = File(applicationContext.filesDir, "reverse-search/${id}.review.jsonl").apply {
            parentFile?.mkdirs()
            writeText(reviewRows.joinToString("\n") { it.toJson().toString() })
        }
        val titles = completedRows.mapNotNull { it.title }.distinct()
        val result = workDataOf(KEY_PHASE to "Yandex complete · ${titles.size} verified result(s) ready for review", KEY_COMPLETED to images.size, KEY_TOTAL to images.size, KEY_FOUND to titles.size, KEY_REVIEW_FILE to reviewFile.absolutePath)
        safeSetProgress(result)
        safeUpdateNotification(images.size, images.size, "Complete · ${titles.size} verified results ready for review", titles.size)
        return Result.success(result)
    }

    private fun readJournal(file: File): List<ReverseOutcome> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { line -> runCatching { ReverseOutcome.fromJson(JSONObject(line)) }.getOrNull() }.distinctBy { it.originalUri }
    }.getOrDefault(emptyList())

    private fun appendJournal(file: File, outcome: ReverseOutcome) = synchronized(JOURNAL_LOCK) {
        file.appendText(outcome.toJson().toString() + "\n")
    }

    /** A genuine no-match is final; only timeouts and transport/server failures retry. */
    private suspend fun searchYandexWithTransientRetry(uri: Uri): ReverseOutcome? {
        repeat(MAX_ATTEMPTS_PER_IMAGE) { attempt ->
            try {
                return withTimeout(SEARCH_ATTEMPT_TIMEOUT_MS) { searchYandex(uri) }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (attempt == MAX_ATTEMPTS_PER_IMAGE - 1) return null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                if (attempt == MAX_ATTEMPTS_PER_IMAGE - 1) return null
            }
            delay(TRANSIENT_RETRY_DELAY_MS)
        }
        return null
    }

    private suspend fun safeSetProgress(data: androidx.work.Data) { runCatching { setProgress(data) } }
    private fun safeUpdateNotification(completed: Int, total: Int, detail: String, found: Int) { runCatching { updateNotification(completed, total, detail, found) } }
    private suspend fun awaitNotPaused() {
        while (isPaused(applicationContext) && !isStopped) {
            safeSetProgress(workDataOf(KEY_PAUSED to true))
            safeUpdateNotification(0, 0, "Paused · waiting to resume", 0)
            delay(750)
        }
        if (!isStopped) safeSetProgress(workDataOf(KEY_PAUSED to false))
    }

    private suspend fun searchYandex(uri: Uri): ReverseOutcome? = withContext(Dispatchers.IO) {
        var bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val source = decodeForComparison(bytes) ?: return@withContext null
        val sourceSignature = try {
            imageSignature(source)
        } finally {
            source.recycle()
        }
        val uploadJson = uploadImageToYandex(bytes) ?: return@withContext null
        // The multipart body is out of scope now; don't retain a full-resolution photo
        // while waiting for Yandex's result page and remote candidate downloads.
        bytes = ByteArray(0)
        val query = extractYandexQuery(uploadJson) ?: return@withContext null
        val resultUrl = "https://yandex.com/images/search?$query"
        val resultHtml = client.newCall(Request.Builder().url(resultUrl).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (response.isSuccessful) response.body.string()
            else if (response.code == HTTP_TOO_MANY_REQUESTS || response.code >= 500) throw IOException("Transient Yandex result response ${response.code}")
            else return@withContext null
        }
        // Yandex can repeat one image under several site/title records. Fetch each
        // unique source image only once, while preserving the full candidate list.
        val candidates = parseYandexSites(resultHtml).distinctBy { candidate ->
            candidate.originalImage?.let(::normalizedImageUrl) ?: "${candidate.pageUrl}|${candidate.title}"
        }
        val match = findVerifiedCandidate(uri, sourceSignature, candidates) ?: return@withContext null
        ReverseOutcome(match.originalUri, match.galleryUrl, match.title, match.matchedImage)
    }

    private suspend fun findVerifiedCandidate(
        uri: Uri,
        sourceSignature: ImageSignature,
        candidates: List<YandexSite>,
    ): VerifiedYandexResult? = coroutineScope {
        val candidatesWithImages = candidates.filter { !it.originalImage.isNullOrBlank() }
        for (batch in candidatesWithImages.chunked(CANDIDATE_BATCH_SIZE)) {
            val matches = batch.map { candidate ->
                async(Dispatchers.IO) {
                    val imageUrl = candidate.originalImage ?: return@async null
                    val remote = candidateImageGate.withPermit { downloadBitmap(imageUrl) } ?: return@async null
                    val same = try {
                        sameImage(sourceSignature, remote)
                    } finally {
                        remote.recycle()
                    }
                    if (!same) return@async null
                    VerifiedYandexResult(uri.toString(), canonicalGallery(candidate.pageUrl), candidate.title, imageUrl)
                }
            }.awaitAll()
            matches.firstOrNull { it != null }?.let { return@coroutineScope it }
        }
        null
    }

    private fun uploadImageToYandex(bytes: ByteArray): String? {
        val requestJson = "{\"blocks\":[{\"block\":\"b-page_type_search-by-image__link\"}]}"
        val uploadUrl = "https://yandex.com/images/search?rpt=imageview&format=json&request=${URLEncoder.encode(requestJson, "UTF-8")}"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("upfile", "image.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("image_content", "")
            .build()
        val upload = Request.Builder().url(uploadUrl).post(body).header("User-Agent", USER_AGENT).build()
        return client.newCall(upload).execute().use { response ->
            if (response.isSuccessful) response.body.string()
            else if (response.code == HTTP_TOO_MANY_REQUESTS || response.code >= 500) throw IOException("Transient Yandex upload response ${response.code}")
            else null
        }
    }

    private fun extractYandexQuery(json: String): String? = runCatching {
        val root = JSONObject(json)
        val params = root.getJSONArray("blocks").getJSONObject(0).getJSONObject("params")
        val cbirId = params.optString("cbirId").takeIf { it.isNotBlank() } ?: return@runCatching null
        "rpt=imageview&cbir_id=${URLEncoder.encode(cbirId, "UTF-8")}"
    }.getOrNull()

    private data class YandexSite(val title: String, val pageUrl: String, val originalImage: String?)
    private data class VerifiedYandexResult(val originalUri: String, val galleryUrl: String?, val title: String, val matchedImage: String)

    private fun parseYandexSites(html: String): List<YandexSite> {
        val result = mutableListOf<YandexSite>()
        val attr = Regex("""data-state="([^"]{200,})""" ).findAll(html)
        for (match in attr) {
            val decoded = match.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
            runCatching { collectSites(JSONObject(decoded), result) }
        }
        val pageTitles = result.filter { it.pageUrl.isNotBlank() && it.originalImage != null }
            .associateBy { imageKey(it.originalImage) }
        return result.map { candidate ->
            if (candidate.pageUrl.isBlank()) {
                pageTitles[imageKey(candidate.originalImage)]?.let { page -> candidate.copy(title = page.title) } ?: candidate
            } else candidate
        }.distinctBy { "${it.pageUrl}|${it.originalImage}|${it.title}" }
    }

    private fun collectSites(value: Any?, result: MutableList<YandexSite>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key == "sites" && child is JSONArray) {
                        for (i in 0 until child.length()) {
                            val site = child.optJSONObject(i) ?: continue
                            val page = site.optString("url")
                            val title = site.optString("title")
                            val original = site.optJSONObject("originalImage")?.optString("url")
                            if (page.isNotBlank() && title.isNotBlank()) result += YandexSite(title, page, original)
                        }
                    }
                    if (key == "small_dups" || key == "medium_dups" || key == "large_dups") {
                        if (child is JSONArray) for (i in 0 until child.length()) {
                            val item = child.optJSONObject(i) ?: continue
                            val image = item.optString("url")
                            val title = titleFromImageUrl(image)
                            if (image.isNotBlank() && title.isNotBlank()) result += YandexSite(title, "", image)
                        }
                    }
                    collectSites(child, result)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectSites(value.opt(i), result)
        }
    }

    private fun titleFromImageUrl(url: String): String = runCatching {
        val path = URLDecoder.decode(url.substringBefore('?').substringBefore('#'), "UTF-8")
        val file = path.substringAfterLast('/').substringBeforeLast('.', path.substringAfterLast('/'))
        file.replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim()
    }.getOrDefault("")

    private fun imageKey(url: String?): String = url.orEmpty().substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()

    private fun canonicalGallery(url: String): String? {
        val match = GALLERY_REGEX.find(url) ?: return null
        val host = match.groupValues[1].lowercase()
        val id = match.groupValues[2]
        return if (host.contains("exhentai")) "https://exhentai.org/g/$id/" else "https://nhentai.net/g/$id/"
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val safeUrl = if (url.startsWith("//")) "https:$url" else url
        val request = runCatching { Request.Builder().url(safeUrl).header("User-Agent", USER_AGENT).build() }.getOrNull() ?: return null
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.bytes().let(::decodeForComparison)
            else if (response.code == HTTP_TOO_MANY_REQUESTS || response.code >= 500) throw IOException("Transient Yandex image response ${response.code}")
            else null
        }
    }

    /** Decode only enough pixels for the 32x32 perceptual check; huge phone images
     * otherwise waste CPU and memory despite being immediately scaled down. */
    private fun decodeForComparison(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > COMPARISON_DECODE_MAX_DIMENSION) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    /** Strict normalized pixel comparison: rejects visually similar but different pages. */
    private data class ImageSignature(val luminance: DoubleArray, val mean: Double, val bits: BooleanArray)

    private fun imageSignature(bitmap: Bitmap): ImageSignature {
        val size = 32
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        val values = DoubleArray(pixels.size) { luminance(pixels[it]) }
        val mean = values.average()
        val bits = BooleanArray(values.size) { values[it] > mean }
        return ImageSignature(values, mean, bits)
    }

    private fun sameImage(source: ImageSignature, candidate: Bitmap): Boolean {
        val other = imageSignature(candidate)
        var error = 0.0
        var hashDistance = 0
        for (i in source.luminance.indices) {
            val da = source.luminance[i] - source.mean
            val db = other.luminance[i] - other.mean
            error += (da - db) * (da - db)
            if (source.bits[i] != other.bits[i]) hashDistance++
        }
        return error / source.luminance.size / (255.0 * 255.0) <= MAX_NORMALIZED_ERROR && hashDistance <= MAX_HASH_DISTANCE
    }

    private fun luminance(pixel: Int): Double = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)

    private fun discover(seeds: List<Uri>): List<Uri> {
        val result = mutableListOf<Uri>(); val pending = ArrayDeque<Uri>(); seeds.forEach { uri -> runCatching { if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) pending += uri else if (applicationContext.contentResolver.getType(uri)?.startsWith("image/") == true) result += uri } }
        while (pending.isNotEmpty() && result.size < MAX_IMAGES) { val tree = pending.removeFirst(); val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue; val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id); runCatching { applicationContext.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor -> while (cursor.moveToNext() && result.size < MAX_IMAGES) { val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)); val mime = cursor.getString(1).orEmpty(); if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending += child else if (mime.startsWith("image/")) result += child } } } }
        return result
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(NOTIFICATION_ID, applicationContext.notificationBuilder(Notifications.CHANNEL_KOMIKKU_IMPORT) { setSmallIcon(R.drawable.ic_komikku); setContentTitle("Reverse Search"); setContentText("Searching selected images with Yandex"); setOngoing(true); setOnlyAlertOnce(true); setPriority(NotificationCompat.PRIORITY_LOW) }.build(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

    private fun updateNotification(completed: Int, total: Int, detail: String, found: Int) { val openIntent = Intent(applicationContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java).apply { action = ACTION_OPEN_REVERSE_SEARCH; putExtra(EXTRA_WORK_ID, id.toString()); flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }; val openPendingIntent = PendingIntent.getActivity(applicationContext, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT).setSmallIcon(R.drawable.ic_komikku).setContentTitle("Reverse Search").setContentText(detail).setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total.coerceAtLeast(1)), false).setSubText(if (found > 0) "$found verified match(es)" else null).setOngoing(completed < total || total == 0).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(openPendingIntent).setAutoCancel(false).addAction(R.drawable.ic_pause_24dp, "Pause", NotificationReceiver.pauseReverseSearchPendingBroadcast(applicationContext)).addAction(R.drawable.ic_play_arrow_24dp, "Resume", NotificationReceiver.resumeReverseSearchPendingBroadcast(applicationContext)).addAction(R.drawable.ic_close_24dp, "Cancel", applicationContext.workManager.createCancelPendingIntent(id)).build()) }

    companion object {
        const val KEY_SELECTION = "selection"; const val KEY_PHASE = "phase"; const val KEY_COMPLETED = "completed"; const val KEY_TOTAL = "total"; const val KEY_FOUND = "found"; const val KEY_REVIEW_FILE = "review_file"; const val KEY_PAUSED = "paused"; const val ACTION_OPEN_REVERSE_SEARCH = "com.manus.komikku.OPEN_REVERSE_SEARCH"; const val EXTRA_WORK_ID = "reverse_search_work_id"; const val NOTIFICATION_ID = -1812; const val TAG = "komikku_reverse_search"
        private const val PREFS = "reverse_search_controls"; private const val PAUSED = "paused"; private const val ACTIVE_WORK_ID = "active_work_id"
        private val JOURNAL_LOCK = Any()
        fun isPaused(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSED, false)
        fun setPaused(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSED, value).apply() }
        fun activeWorkId(context: Context): UUID? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_WORK_ID, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        private const val MAX_IMAGES = 2_000; private const val MAX_PARALLEL = 8; private const val MAX_PARALLEL_CANDIDATE_IMAGES = 8; private const val CANDIDATE_BATCH_SIZE = 4; private const val MAX_ATTEMPTS_PER_IMAGE = 2; private const val SEARCH_ATTEMPT_TIMEOUT_MS = 60_000L; private const val TRANSIENT_RETRY_DELAY_MS = 2_000L; private const val HTTP_TOO_MANY_REQUESTS = 429; private const val COMPARISON_DECODE_MAX_DIMENSION = 512; private const val MAX_NORMALIZED_ERROR = 0.10; private const val MAX_HASH_DISTANCE = 130; private const val USER_AGENT = "Komikku Reverse Search/1.0"
        private val GALLERY_REGEX = Regex("(?:https?://)?((?:www\\.)?(?:nhentai\\.net|exhentai\\.org))/g/(\\d{5,8})", RegexOption.IGNORE_CASE)
        private fun normalizedImageUrl(url: String): String = url.substringBefore('#').trim()
        fun enqueue(context: Context, selections: List<Uri>): UUID { setPaused(context, false); val id = UUID.randomUUID(); val dir = File(context.filesDir, "reverse-search").apply { mkdirs() }; File(dir, "$id.selection").writeText(selections.joinToString("\\n")); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE_WORK_ID, id.toString()).apply(); val request = OneTimeWorkRequestBuilder<ReverseSearchWorker>().setId(id).setInputData(workDataOf(KEY_SELECTION to File(dir, "$id.selection").absolutePath)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build(); context.workManager.enqueueUniqueWork("komikku_reverse_search", androidx.work.ExistingWorkPolicy.REPLACE, request); return id }
    }
    private data class ReverseOutcome(
        val originalUri: String,
        val directUrl: String?,
        val title: String?,
        val matchedImage: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("originalUri", originalUri)
            put("galleryUrl", directUrl ?: JSONObject.NULL)
            put("title", title ?: JSONObject.NULL)
            put("matchedImage", matchedImage ?: JSONObject.NULL)
        }
        companion object { fun fromJson(json: JSONObject) = ReverseOutcome(json.getString("originalUri"), json.optString("galleryUrl").takeIf { it.isNotBlank() && it != "null" }, json.optString("title").takeIf { it.isNotBlank() && it != "null" }, json.optString("matchedImage").takeIf { it.isNotBlank() && it != "null" }) }
    }
}
