package exh.ui.reverse

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.workManager
import exh.ui.batchadd.BatchImportJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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

/** Searches every selected image concurrently, then imports only confident gallery matches. */
class ReverseSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    override suspend fun doWork(): Result {
        val selection = inputData.getString(KEY_SELECTION)?.let(::File) ?: return Result.failure()
        val images = discover(selection.readLines().map(Uri::parse)).distinct().take(MAX_IMAGES)
        if (images.isEmpty()) return Result.success(workDataOf(KEY_TOTAL to 0, KEY_FOUND to 0))
        val done = AtomicInteger(0)
        val found = coroutineScope {
            val gate = Semaphore(MAX_PARALLEL)
            images.map { uri -> async(Dispatchers.IO) {
                gate.withPermit {
                    if (isStopped) return@withPermit null
                    val url = withTimeoutOrNull(30_000) { searchSauceNao(uri) }
                    setProgress(workDataOf(KEY_PHASE to "Reverse searching ${done.incrementAndGet()}/${images.size}", KEY_COMPLETED to done.get(), KEY_TOTAL to images.size, KEY_FOUND to 0))
                    url
                }
            }}.awaitAll().filterNotNull().distinct()
        }
        if (found.isNotEmpty()) {
            // Reuse the app's rate-limited importer so source identity repair and library
            // insertion remain identical to normal Batch Add.
            BatchImportJob.start(applicationContext, found)
        }
        val result = workDataOf(KEY_PHASE to "Reverse search complete · ${found.size} confident match(es)", KEY_COMPLETED to images.size, KEY_TOTAL to images.size, KEY_FOUND to found.size)
        setProgress(result)
        return Result.success(result)
    }

    private suspend fun searchSauceNao(uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "image.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder().url("https://saucenao.com/search.php").post(body)
            .header("User-Agent", USER_AGENT).build()
        val html = client.newCall(request).execute().use { response -> if (!response.isSuccessful) return@withContext null else response.body.string() }
        val best = NHENTAI_REGEX.findAll(html).mapNotNull { match ->
            val context = html.substring((match.range.first - 700).coerceAtLeast(0), (match.range.last + 700).coerceAtMost(html.length))
            val score = SCORE_REGEX.find(context)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
            score to "https://nhentai.net/g/${match.groupValues[1]}/"
        }.maxByOrNull { it.first }
        best?.takeIf { it.first >= MIN_SCORE }?.second
    }

    private fun discover(seeds: List<Uri>): List<Uri> {
        val result = mutableListOf<Uri>(); val pending = ArrayDeque<Uri>()
        seeds.forEach { uri ->
            if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) pending += uri
            else if (applicationContext.contentResolver.getType(uri)?.startsWith("image/") == true) result += uri
        }
        while (pending.isNotEmpty() && result.size < MAX_IMAGES) {
            val tree = pending.removeFirst(); val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            applicationContext.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0); val mime = cursor.getString(1).orEmpty(); val child = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending += child else if (mime.startsWith("image/")) result += child
                }
            }
        }
        return result
    }

    override suspend fun getForegroundInfo() = ForegroundInfo(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT).setSmallIcon(R.drawable.ic_komikku).setContentTitle("Reverse Search").setContentText("Searching selected images").setOngoing(true).build())

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
        private const val MIN_SCORE = 60f
        private const val USER_AGENT = "Komikku Reverse Search/1.0"
        private val NHENTAI_REGEX = Regex("nhentai\\.net/g/(\\d{5,8})", RegexOption.IGNORE_CASE)
        private val SCORE_REGEX = Regex("(?:similarity|similar|match)[^%]{0,80}(\\d{1,3}(?:\\.\\d+)?)%", RegexOption.IGNORE_CASE)
        fun enqueue(context: Context, selections: List<Uri>): UUID {
            val id = UUID.randomUUID(); val dir = File(context.filesDir, "reverse-search").apply { mkdirs() }; val file = File(dir, "$id.selection")
            file.writeText(selections.joinToString("\n")); val request = OneTimeWorkRequestBuilder<ReverseSearchWorker>().setId(id).setInputData(workDataOf(KEY_SELECTION to file.absolutePath)).addTag(TAG).build()
            context.workManager.enqueueUniqueWork("komikku_reverse_search", androidx.work.ExistingWorkPolicy.REPLACE, request); return id
        }
    }
}
