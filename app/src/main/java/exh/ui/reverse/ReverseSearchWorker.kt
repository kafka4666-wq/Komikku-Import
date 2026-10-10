package exh.ui.reverse

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Yandex-only reverse search. Imports only a canonical nH/eH page whose returned
 * source image passes a strict perceptual comparison against the selected image. */
class ReverseSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
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

        val completed = AtomicInteger(0)
        val foundCount = AtomicInteger(0)
        val outcomes = supervisorScope {
            val gate = Semaphore(MAX_PARALLEL)
            images.map { uri ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (isStopped) return@withPermit null
                        var outcome: ReverseOutcome? = null
                        try {
                            outcome = withTimeoutOrNull(60_000L) { searchYandex(uri) }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            // A Yandex block, timeout, malformed result, or unreadable image skips only this image.
                        } finally {
                            val done = completed.incrementAndGet()
                            if (outcome?.directUrl != null) foundCount.incrementAndGet()
                            val phase = "Yandex searched $done/${images.size} images"
                            val progress = workDataOf(KEY_PHASE to phase, KEY_COMPLETED to done, KEY_TOTAL to images.size, KEY_FOUND to foundCount.get())
                            safeSetProgress(progress)
                            safeUpdateNotification(done, images.size, "$phase · ${foundCount.get()} verified matches", foundCount.get())
                        }
                        outcome
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (isStopped) return Result.failure()
        val directUrls = outcomes.mapNotNull { it.directUrl }.distinct()
        val titles = outcomes.mapNotNull { it.title }.distinct()
        if (directUrls.isNotEmpty()) runCatching { BatchImportJob.start(applicationContext, directUrls) }
        val result = workDataOf(
            KEY_PHASE to "Yandex complete · ${titles.size} verified title(s); ${directUrls.size} canonical gallery import(s)",
            KEY_COMPLETED to images.size,
            KEY_TOTAL to images.size,
            KEY_FOUND to directUrls.size,
        )
        safeSetProgress(result)
        safeUpdateNotification(images.size, images.size, "Complete · ${titles.size} verified titles; ${directUrls.size} imports", directUrls.size)
        return Result.success(result)
    }

    private suspend fun safeSetProgress(data: androidx.work.Data) { runCatching { setProgress(data) } }
    private fun safeUpdateNotification(completed: Int, total: Int, detail: String, found: Int) { runCatching { updateNotification(completed, total, detail, found) } }

    private suspend fun searchYandex(uri: Uri): ReverseOutcome? = withContext(Dispatchers.IO) {
        val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val requestJson = "{\"blocks\":[{\"block\":\"b-page_type_search-by-image__link\"}]}"
        val uploadUrl = "https://yandex.com/images/search?rpt=imageview&format=json&request=${URLEncoder.encode(requestJson, "UTF-8")}"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("upfile", "image.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("image_content", "")
            .build()
        val upload = Request.Builder().url(uploadUrl).post(body).header("User-Agent", USER_AGENT).build()
        val uploadJson = client.newCall(upload).execute().use { response -> if (!response.isSuccessful) return@withContext null else response.body.string() }
        val query = extractYandexQuery(uploadJson) ?: return@withContext null
        val resultUrl = "https://yandex.com/images/search?$query"
        val resultHtml = client.newCall(Request.Builder().url(resultUrl).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null else response.body.string()
        }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val candidates = parseYandexSites(resultHtml)
        val match = candidates.asSequence().mapNotNull { candidate ->
            val imageUrl = candidate.originalImage ?: return@mapNotNull null
            val remote = downloadBitmap(imageUrl) ?: return@mapNotNull null
            if (!sameImage(source, remote)) return@mapNotNull null
            VerifiedYandexResult(canonicalGallery(candidate.pageUrl), candidate.title)
        }.firstOrNull() ?: return@withContext null
        ReverseOutcome(match.galleryUrl, match.title)
    }

    private fun extractYandexQuery(json: String): String? = runCatching {
        val root = JSONObject(json)
        val params = root.getJSONArray("blocks").getJSONObject(0).getJSONObject("params")
        val cbirId = params.optString("cbirId").takeIf { it.isNotBlank() } ?: return@runCatching null
        "rpt=imageview&cbir_id=${URLEncoder.encode(cbirId, "UTF-8")}"
    }.getOrNull()

    private data class YandexSite(val title: String, val pageUrl: String, val originalImage: String?)
    private data class VerifiedYandexResult(val galleryUrl: String?, val title: String)

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

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        val safeUrl = if (url.startsWith("//")) "https:$url" else url
        client.newCall(Request.Builder().url(safeUrl).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }.getOrNull()

    /** Strict normalized pixel comparison: rejects visually similar but different pages. */
    private fun sameImage(a: Bitmap, b: Bitmap): Boolean {
        val size = 32
        val aa = Bitmap.createScaledBitmap(a, size, size, true)
        val bb = Bitmap.createScaledBitmap(b, size, size, true)
        var error = 0.0
        var hashDistance = 0
        val av = IntArray(size * size)
        val bv = IntArray(size * size)
        aa.getPixels(av, 0, size, 0, 0, size, size)
        bb.getPixels(bv, 0, size, 0, 0, size, size)
        var meanA = 0.0
        var meanB = 0.0
        for (i in av.indices) { meanA += luminance(av[i]); meanB += luminance(bv[i]) }
        meanA /= av.size; meanB /= bv.size
        for (i in av.indices) {
            val da = luminance(av[i]) - meanA
            val db = luminance(bv[i]) - meanB
            error += (da - db) * (da - db)
            if ((luminance(av[i]) > meanA) != (luminance(bv[i]) > meanB)) hashDistance++
        }
        return error / av.size / (255.0 * 255.0) <= MAX_NORMALIZED_ERROR && hashDistance <= MAX_HASH_DISTANCE
    }

    private fun luminance(pixel: Int): Double = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)

    private fun discover(seeds: List<Uri>): List<Uri> {
        val result = mutableListOf<Uri>(); val pending = ArrayDeque<Uri>(); seeds.forEach { uri -> runCatching { if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) pending += uri else if (applicationContext.contentResolver.getType(uri)?.startsWith("image/") == true) result += uri } }
        while (pending.isNotEmpty() && result.size < MAX_IMAGES) { val tree = pending.removeFirst(); val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue; val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id); runCatching { applicationContext.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor -> while (cursor.moveToNext() && result.size < MAX_IMAGES) { val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)); val mime = cursor.getString(1).orEmpty(); if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending += child else if (mime.startsWith("image/")) result += child } } } }
        return result
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(NOTIFICATION_ID, applicationContext.notificationBuilder(Notifications.CHANNEL_KOMIKKU_IMPORT) { setSmallIcon(R.drawable.ic_komikku); setContentTitle("Reverse Search"); setContentText("Searching selected images with Yandex"); setOngoing(true); setOnlyAlertOnce(true); setPriority(NotificationCompat.PRIORITY_LOW) }.build(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

    private fun updateNotification(completed: Int, total: Int, detail: String, found: Int) { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT).setSmallIcon(R.drawable.ic_komikku).setContentTitle("Reverse Search").setContentText(detail).setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total.coerceAtLeast(1)), false).setSubText(if (found > 0) "$found verified match(es)" else null).setOngoing(completed < total || total == 0).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).addAction(R.drawable.ic_close_24dp, "Cancel", applicationContext.workManager.createCancelPendingIntent(id)).build()) }

    companion object {
        const val KEY_SELECTION = "selection"; const val KEY_PHASE = "phase"; const val KEY_COMPLETED = "completed"; const val KEY_TOTAL = "total"; const val KEY_FOUND = "found"; const val NOTIFICATION_ID = -1810; const val TAG = "komikku_reverse_search"
        private const val MAX_IMAGES = 2_000; private const val MAX_PARALLEL = 2; private const val MAX_NORMALIZED_ERROR = 0.10; private const val MAX_HASH_DISTANCE = 130; private const val USER_AGENT = "Komikku Reverse Search/1.0"
        private val GALLERY_REGEX = Regex("(?:https?://)?((?:www\\.)?(?:nhentai\\.net|exhentai\\.org))/g/(\\d{5,8})", RegexOption.IGNORE_CASE)
        fun enqueue(context: Context, selections: List<Uri>): UUID { val id = UUID.randomUUID(); val dir = File(context.filesDir, "reverse-search").apply { mkdirs() }; File(dir, "$id.selection").writeText(selections.joinToString("\\n")); val request = OneTimeWorkRequestBuilder<ReverseSearchWorker>().setId(id).setInputData(workDataOf(KEY_SELECTION to File(dir, "$id.selection").absolutePath)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build(); context.workManager.enqueueUniqueWork("komikku_reverse_search", androidx.work.ExistingWorkPolicy.REPLACE, request); return id }
    }
    private data class ReverseOutcome(val directUrl: String?, val title: String?)
}
