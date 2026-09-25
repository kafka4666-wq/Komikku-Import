package exh.ui.batchimage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.database.Cursor
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs SAF-selected images through on-device ML Kit OCR and persists reviewable candidates. */
class BatchImageWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val selectionFile = inputData.getString(KEY_SELECTION_FILE)?.let(::File) ?: return Result.failure()
        val resultFile = inputData.getString(KEY_RESULT_FILE)?.let(::File) ?: return Result.failure()
        resultFile.parentFile?.mkdirs()
        resultFile.writeText("")
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        var completed = 0
        var total = 0
        var lastNotificationAt = 0L
        return try {
            setForeground(getForegroundInfo())
            setProgress(workDataOf(KEY_PHASE to "Finding images", KEY_COMPLETED to 0, KEY_TOTAL to 0, KEY_DISCOVERED to 0))
            val imageUris = discoverImages(selectionFile) { found ->
                if (found == 1 || found % 25 == 0) {
                    setProgress(workDataOf(KEY_PHASE to "Finding images", KEY_COMPLETED to 0, KEY_TOTAL to 0, KEY_DISCOVERED to found))
                }
            }.distinct()
            total = imageUris.size
            if (total == 0) {
                setProgress(workDataOf(KEY_PHASE to "No images found", KEY_COMPLETED to 0, KEY_TOTAL to 0))
                return Result.success()
            }
            updateProgress(0, total, "Scanning images")
            imageUris.forEachIndexed { index, uri ->
                if (isStopped) throw CancellationException("Batch Image scan cancelled")
                val recognized = runCatching {
                    val image = InputImage.fromFilePath(applicationContext, uri)
                    val latinText = latin.process(image).awaitResult()
                    val japaneseText = japanese.process(image).awaitResult()
                    BatchImageTextExtractor.extract(latinText, japaneseText, uri.toString())
                }.getOrNull()
                if (recognized != null) {
                    resultFile.appendText(BatchImageRecordCodec.encode(recognized) + "\n")
                }
                completed = index + 1
                updateProgress(completed, total, "Scanning images")
                val now = System.currentTimeMillis()
                if (completed == total || completed % 5 == 0 || now - lastNotificationAt > 2_000) {
                    lastNotificationAt = now
                    showProgressNotification(completed, total)
                }
            }
            setProgress(workDataOf(KEY_PHASE to "Review ready", KEY_COMPLETED to completed, KEY_TOTAL to total))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            setProgress(workDataOf(KEY_PHASE to (error.message ?: "OCR failed"), KEY_COMPLETED to completed, KEY_TOTAL to total))
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "OCR failed")))
        } finally {
            latin.close()
            japanese.close()
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        progressNotification(0, 0),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private suspend fun updateProgress(done: Int, total: Int, phase: String) {
        setProgress(workDataOf(KEY_PHASE to phase, KEY_COMPLETED to done, KEY_TOTAL to total))
    }

    private fun showProgressNotification(done: Int, total: Int) {
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, progressNotification(done, total))
    }

    private fun progressNotification(done: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT)
            .setSmallIcon(R.drawable.ic_komikku)
            .setContentTitle("Batch Image OCR")
            .setContentText(if (total <= 0) "Finding images…" else "Processing $done/$total…")
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(done < total || total == 0)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_close_24dp,
                "Cancel",
                applicationContext.workManager.createCancelPendingIntent(id),
            )
            .build()

    private suspend fun discoverImages(selectionFile: File, onDiscovered: suspend (Int) -> Unit): List<Uri> {
        val result = ArrayList<Uri>()
        val pending = ArrayDeque<Uri>()
        selectionFile.readLines().forEach { entry ->
            val split = entry.indexOf('|')
            if (split <= 0) return@forEach
            val kind = entry.substring(0, split)
            val uri = Uri.parse(entry.substring(split + 1))
            if (kind == "image") {
                if (isImage(applicationContext, uri)) result += uri
                onDiscovered(result.size)
            } else if (kind == "tree") {
                pending.addLast(uri)
                while (pending.isNotEmpty()) {
                    if (isStopped) throw CancellationException("Batch Image scan cancelled")
                    val folder = pending.removeLast()
                    val documentId = runCatching { DocumentsContract.getDocumentId(folder) }
                        .recoverCatching { DocumentsContract.getTreeDocumentId(folder) }
                        .getOrNull() ?: continue
                    val childrenUri = runCatching {
                        DocumentsContract.buildChildDocumentsUriUsingTree(folder, documentId)
                    }.getOrNull() ?: continue
                    queryChildren(folder, childrenUri).forEach { (child, mime) ->
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.addLast(child)
                        } else if (mime.startsWith("image/")) {
                            result += child
                            onDiscovered(result.size)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun queryChildren(treeUri: Uri, childrenUri: Uri): List<Pair<Uri, String>> {
        val children = ArrayList<Pair<Uri, String>>()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor: Cursor = applicationContext.contentResolver.query(childrenUri, projection, null, null, null) ?: return children
        cursor.use {
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIndex < 0 || mimeIndex < 0) return children
            while (it.moveToNext()) {
                val documentId = it.getString(idIndex) ?: continue
                val mime = it.getString(mimeIndex).orEmpty()
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                children += uri to mime
            }
        }
        return children
    }

    private fun isImage(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.getType(uri)?.startsWith("image/") == true
    }.getOrDefault(false)

    companion object {
        const val KEY_SELECTION_FILE = "selection_file"
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_PHASE = "phase"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_DISCOVERED = "discovered"
        const val KEY_ERROR = "error"
        const val NOTIFICATION_ID = -1805
        const val TAG = "batch_image_ocr"
        const val UNIQUE_WORK = "komikku_batch_image_ocr"
        private const val PREFS = "batch_image_scan"
        private const val PREF_JOB_ID = "job_id"
        private const val PREF_RESULT_FILE = "result_file"

        fun enqueue(context: Context, selections: List<Pair<String, Uri>>): UUID {
            val jobId = UUID.randomUUID()
            val directory = File(context.filesDir, "batch-image").apply { mkdirs() }
            val selectionFile = File(directory, "$jobId.selection")
            val resultFile = File(directory, "$jobId.results")
            selectionFile.writeText(selections.joinToString("\n") { (kind, uri) -> "$kind|$uri" })
            resultFile.writeText("")
            val request = OneTimeWorkRequestBuilder<BatchImageWorker>()
                .setId(jobId)
                .setInputData(workDataOf(KEY_SELECTION_FILE to selectionFile.absolutePath, KEY_RESULT_FILE to resultFile.absolutePath))
                .addTag(TAG)
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_JOB_ID, jobId.toString())
                .putString(PREF_RESULT_FILE, resultFile.absolutePath)
                .apply()
            context.workManager.enqueueUniqueWork(UNIQUE_WORK, androidx.work.ExistingWorkPolicy.REPLACE, request)
            return jobId
        }

        fun savedJobId(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_JOB_ID, null)

        fun readRecords(filePath: String?): List<BatchImageRecord> = runCatching {
            filePath?.let(::File)?.takeIf(File::exists)?.readLines()?.mapNotNull(BatchImageRecordCodec::decode).orEmpty()
        }.getOrDefault(emptyList())

        fun recordsFile(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_RESULT_FILE, null)

        fun clearSaved(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(PREF_RESULT_FILE, null)?.let(::File)?.delete()
            prefs.edit().clear().apply()
        }
    }
}

/** A single clean review candidate for one source screenshot. */
data class BatchImageRecord(
    val id: String,
    val title: String?,
    val artist: String?,
    val code: String?,
    val link: String?,
    val imageUri: String,
    val confidence: Int,
)

private object BatchImageRecordCodec {
    fun encode(record: BatchImageRecord): String = listOf(
        record.id, record.title.orEmpty(), record.artist.orEmpty(), record.code.orEmpty(),
        record.link.orEmpty(), record.imageUri, record.confidence.toString(),
    ).joinToString("\t") { android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) }

    fun decode(line: String): BatchImageRecord? = runCatching {
        val fields = line.split('\t').map { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        if (fields.size != 7) return null
        BatchImageRecord(fields[0], fields[1].ifBlank { null }, fields[2].ifBlank { null }, fields[3].ifBlank { null }, fields[4].ifBlank { null }, fields[5], fields[6].toIntOrNull() ?: 0)
    }.getOrNull()
}

/** Prioritizes source post titles, labeled titles/artists, and nhentai identifiers over UI text. */
object BatchImageTextExtractor {
    private val linkRegex = Regex("(?i)(?:https?://)?(?:www\\.)?nhentai\\.net/g/(\\d{5,7})/?")
    private val labeledCodeRegex = Regex("(?i)(?:nhentai(?:\\.net)?|n\\s*hentai|\\bcode\\b|\\bnh\\s*code\\b)\\s*[:#：=\\-]?\\s*(\\d{5,7})")
    private val labeledTitleRegex = Regex("(?i)^\\s*(?:judul|title)\\s*[:：]\\s*(.+)$")
    private val labeledArtistRegex = Regex("(?i)^\\s*(?:artist|artist name|pencipta)\\s*[:：]\\s*(.+)$")
    private val pureCodeRegex = Regex("^\\s*(\\d{5,7})\\s*$")
    private val trailingCreditRegex = Regex("(?i)\\s*\\[(?:tomiscans|scanlation|translation|translated|credits?|chapter|vol(?:ume)?\\s*\\d+)[^]]*]\\s*$")
    private val trailingArtistRegex = Regex("^(.{3,120}?)\\s*[\\[(]\\s*([^\\[\\]()]{2,55}?)\\s*[\\])]\\s*(?:(?:part|ch(?:apter)?)\\s*\\d+)?\\s*$", RegexOption.IGNORE_CASE)
    private val noiseRegex = Regex("(?i)(?:join the conversation|view more|see more|load more|show more|most relevant|\\bauthor\\b|upvote|downvote|comment|reply|share|like|follow|subscribe|\\bnsfw\\b|\\bjoin\\b|\\bviews?\\b|\\brank\\b|\\brating\\b|\\btop fan\\b|\\bmod\\b|\\bop\\b|\\breply\\b|\\bfollowing\\b)")
    private val usernameRegex = Regex("(?i)^\\s*(?:u/|r/|@)[a-z0-9_.-]+(?:\\s|$)")
    private val timeRegex = Regex("(?i)^\\s*(?:\\d{1,2}:\\d{2}|\\d+\\s*(?:s|m|h|d|w|mo|y)(?:\\s+ago)?|\\d+(?:\\.\\d+)?k?\\s*(?:views?|likes?))\\s*$")
    private val conversationalRegex = Regex("(?i)^\\s*(?:this was|that was|nice[.!?]|great[.!?]|if you want|sorry|i am|i'm|because you|the english title|thank you|thanks for)\\b")

    fun extract(latin: Text, japanese: Text, imageUri: String): BatchImageRecord? {
        return extractLines(
            latin.textBlocks.flatMap { block -> block.lines.map { it.text } } +
                japanese.textBlocks.flatMap { block -> block.lines.map { it.text } },
            imageUri,
        )
    }

    fun extractLines(rawLines: List<String>, imageUri: String): BatchImageRecord? {
        val lines = rawLines
            .map(::cleanLine)
            .filter(String::isNotBlank)
            .distinct()
        if (lines.isEmpty()) return null
        val joined = lines.joinToString("\n")
        val linkMatch = linkRegex.find(joined)
        val linkCode = linkMatch?.groupValues?.getOrNull(1)
        val codeMatch = labeledCodeRegex.find(joined)
        val explicitCode = codeMatch?.groupValues?.getOrNull(1)
        val pureCode = lines.firstNotNullOfOrNull { line ->
            if (noiseRegex.containsMatchIn(line) || timeRegex.matches(line)) null else pureCodeRegex.matchEntire(line)?.groupValues?.getOrNull(1)
        }
        val code = (linkCode ?: explicitCode ?: pureCode)?.takeIf { it.length in 5..7 }
        val link = linkMatch?.value?.let { found ->
            val id = linkRegex.find(found)?.groupValues?.getOrNull(1) ?: return@let null
            "https://nhentai.net/g/$id/"
        }

        val explicitTitle = lines.firstNotNullOfOrNull { line -> labeledTitleRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.let(::cleanTitle) }
        val structuredArtist = lines.firstNotNullOfOrNull { line -> labeledArtistRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.let(::cleanArtist) }
        val recTitle = lines.firstNotNullOfOrNull { line ->
            if (!line.startsWith("rec:", true) && !line.startsWith("rec ", true)) null else cleanTitle(line.replaceFirst(Regex("(?i)^rec\\s*[:：]?\\s*"), ""))
        }
        val candidateLine = lines.firstOrNull { isLikelyPostTitle(it) }
        val parsed = (explicitTitle ?: recTitle ?: candidateLine)?.let(::parseTitleAndArtist)
        val title = parsed?.first?.takeIf(String::isNotBlank)
        val artist = structuredArtist ?: parsed?.second
        if (title == null && code == null && link == null) return null
        val score = when {
            link != null -> 100
            explicitCode != null -> 96
            pureCode != null -> 90
            explicitTitle != null -> 95
            recTitle != null -> 92
            candidateLine != null -> 68
            else -> 55
        }
        return BatchImageRecord(
            id = imageUri.hashCode().toUInt().toString(16),
            title = title,
            artist = artist,
            code = code,
            link = link,
            imageUri = imageUri,
            confidence = score,
        )
    }

    private fun isLikelyPostTitle(value: String): Boolean {
        val line = cleanTitle(value)
        if (line.length !in 8..140 || usernameRegex.containsMatchIn(line) || timeRegex.matches(line)) return false
        if (noiseRegex.containsMatchIn(line) || conversationalRegex.containsMatchIn(line)) return false
        if (Regex("(?i)(?:·|•|\\s)\\d+\\s*(?:s|m|h|d|w|mo|y)\\b").containsMatchIn(line)) return false
        if (Regex("(?i)^\\s*(?:judul|title|artist|code|chapter|tags?|genre|home|share|comment|reply)\\s*[:：]?").containsMatchIn(line)) return false
        if (line.count { it.isLetter() } < 5 || line.split(Regex("\\s+")).size < 2) return false
        if (line.endsWith('.') || line.endsWith('?')) return false
        if (pureCodeRegex.matches(line)) return false
        return true
    }

    private fun parseTitleAndArtist(raw: String): Pair<String, String?> {
        var title = cleanTitle(raw).orEmpty()
        title = trailingCreditRegex.replace(title, "").trim()
        val suffix = trailingArtistRegex.matchEntire(title)
        if (suffix != null) {
            val possibleArtist = cleanArtist(suffix.groupValues[2])
            // All-uppercase site/source tags are credits, not creator names.
            if (possibleArtist != null && !possibleArtist.matches(Regex("[A-Z0-9 _-]{3,}"))) {
                return cleanTitle(suffix.groupValues[1]).orEmpty() to possibleArtist
            }
        }
        return title to null
    }

    private fun cleanTitle(value: String): String? {
        var cleaned = value.trim().trim('"', '\'', '“', '”', '•', '-', '—')
        cleaned = linkRegex.replace(cleaned, "").replace(Regex("\\s+"), " ").trim()
        cleaned = trailingCreditRegex.replace(cleaned, "").trim()
        return cleaned.takeIf { it.length >= 3 && it.any(Char::isLetter) }
    }

    private fun cleanArtist(value: String): String? = value.trim().trim('(', ')', '[', ']', ':', '：', '-', '—')
        .replace(Regex("\\s+"), " ")
        .takeIf { it.length in 2..60 && it.any(Char::isLetter) && !noiseRegex.containsMatchIn(it) }

    private fun cleanLine(value: String): String = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("ML Kit task cancelled")) }
}
