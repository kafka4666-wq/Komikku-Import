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
                }.getOrDefault(emptyList())
                recognized.forEach { record ->
                    resultFile.appendText(BatchImageRecordCodec.encode(record) + "\n")
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

internal object BatchImageRecordCodec {
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
    private val labeledCodeRegex = Regex("(?i)(?:nhentai(?:\\.net)?|n\\s*hentai|\\bcode\\b|\\bnh\\s*code\\b)\\s*[:#：=\\-]*\\s*[\\[(#=:\\-]*\\s*(\\d{5,7})")
    private val labeledTitleRegex = Regex("(?i)^\\s*(?:(?:english|japanese|jp)\\s+)?(?:doujin\\s+)?(?:judul|title|name)(?:\\s+name)?\\s*[:：=\\-]\\s*(.+)$")
    private val titleCueRegex = Regex("(?i)\\b(?:the\\s+)?(?:english\\s+|japanese\\s+|jp\\s+)?title\\s+(?:is|was)\\s*[:：\\-]?\\s*(.+)$")
    private val labeledArtistRegex = Regex("(?i)^\\s*(?:artist|artist name|pencipta|by|creator|author)\\s*[:：]\\s*(.+)$")
    private val pureCodeRegex = Regex("^\\s*[\\[(#-]*\\s*(\\d{5,7})\\s*[)\\]#.!-]*\\s*$")
    private val trailingCreditRegex = Regex("(?i)\\s*\\[(?:tomiscans|scanlation|translation|translated|credits?|chapter|vol(?:ume)?\\s*\\d+)[^]]*]\\s*$")
    private val trailingArtistRegex = Regex("^(.{3,120}?)\\s*[\\[(]\\s*([^\\[\\]()]{2,55}?)\\s*[\\])]\\s*(?:(?:part|ch(?:apter)?)\\s*\\d+)?\\s*$", RegexOption.IGNORE_CASE)
    private val trailingOpenArtistRegex = Regex("^(.{3,120}?)\\s*[\\[(]\\s*([^\\[\\]()]{2,55})\\s*$", RegexOption.IGNORE_CASE)
    private val saucePrefixRegex = Regex("(?i)^\\s*(?:full\\s+)?sauce\\s*[:：\\-]\\s*(.+)$")
    private val trailingSocialCountRegex = Regex("(?i)\\s*(?:\\+\\s*\\d+|[·•]\\s*\\d+\\s*(?:comments?|replies?|likes?))\\s*$")
    private val markdownLinkRegex = Regex("\\[([^]]+)]\\(https?://[^)]+\\)", RegexOption.IGNORE_CASE)
    private val genericUrlRegex = Regex("(?i)https?://\\S+")
    private val trailingByArtistRegex = Regex("(?i)^(.{3,120}?)\\s+by\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}'’ _.-]{1,50})\\.?\\s*$")
    private val leadingArtistRegex = Regex("^\\s*\\[([^]]{2,55})]\\s*(.{3,120})$")
    private val creatorDashTitleRegex = Regex("(?i)^\\s*(?:\\d+\\.\\s*)?([^:|\\-–—]{2,50}?)\\s+[-–—]\\s+(.{5,130})$")
    private val trailingCatalogTagsRegex = Regex("(?i)\\s*\\[(?:english|japanese|chinese|translated|translation|digital|raw|language|repack|complete)[^]]*]\\s*$")
    private val requestLineRegex = Regex("(?i)^\\s*(?:lf(?:\\s+doujinshi)?\\b|looking\\s+for\\b|sauce\\s+pls?\\b|can\\s+anyone\\b|does\\s+anyone\\b|anyone\\s+(?:know|have|remember)\\b|need\\s+help\\b|what\\s+is\\s+the\\s+title\\b|title\\s+of\\s+this\\b)")
    private val noiseRegex = Regex("(?i)(?:join the conversation|view more|see more|load more|show more|most relevant|posts you may have missed|ask about this image|\\bauthor\\b|\\bartist\\s*[:：]|upvote|downvote|\\bcomment\\b|\\breply\\b|\\bshare(?:s)?\\b|\\blike\\b|\\bfollow\\b|\\bsubscribe\\b|\\bnsfw\\b|\\bjoin\\b|\\bviews?\\b|\\brank\\b|\\brating\\b|\\btop fan\\b|\\bmod\\b|\\bop\\b|\\bfollowing\\b|\\brules?\\s*\\d*\\b|search image for|automoderator|subreddit wiki|full details|source please|source finder|result table|view \\d+ replies?|i am a bot|action was performed automatically|contact the moderators|moderators of this subreddit|please follow the community rules|enforced title format|reddit search|maybe sauce|sauce guys|anime meme|back to notifications|all comments|up next|for adult only|best artist|that's all for now|amazing work|cute couple|yo boy stood on business|see translation|view all|\\b2d\\s*author\\b|crotpedia project|nah gini loh|ngh gini loh|realita modern|de nandy estamos|nada del viernes|pacaran tinggal|doesn['’]?t\\s+matter|does not matter|for ad(?:u|l)t\\s*\\+\\s*only|\\.\\.\\.\\s*more\\s*$)")
    // Social app OCR often prepends a close/cross glyph to subreddit names (e.g. "X r/SauceSharingCommunity").
    private val communityRegex = Regex("(?i)^\\s*[x×✕✖✓✗•·\\-–—]*\\s*r\\s*[/l|]\\s*[\\p{L}\\p{N}_-]+(?:\\s.*)?$")
    private val usernameRegex = Regex("(?i)^\\s*[x×✕✖✓✗•·\\-–—]*\\s*(?:u\\s*/|@)[a-z0-9_.-]+(?:\\s|$)")
    private val timeRegex = Regex("(?i)^\\s*(?:\\d{1,2}:\\d{2}|\\d+\\s*(?:s|m|h|d|w|mo|months?|y)(?:\\s+ago)?|\\d+(?:\\.\\d+)?k?\\s*(?:views?|likes?))(?:\\s+\\d{2,4}x\\d{2,4})?\\s*$")
    private val commenterNameRegex = Regex("^[A-Z][\\p{L}'’.-]+\\s+[A-Z][\\p{L}'’.-]+$")
    private val trailingPlatformRegex = Regex("(?i)(?:\\s+[-–—|·•]?\\s*)(?:facebook|instagram|reddit|google lens|google|youtube|pinterest|tiktok|twitter|x)\\s*$")
    private val trailingCommunityRegex = Regex("(?i)\\s+[x×]?\\s*r\\s*[/l|]\\s*[\\p{L}\\p{N}_-]+\\s*$")
    private val platformBadgeRegex = Regex("(?i)^\\s*(?:o\\s+)?(?:facebook|instagram|reddit|google lens|google|youtube|pinterest|tiktok|twitter|x)\\s*$")
    private val socialReactionRegex = Regex("(?i)^\\s*(?:amazing work|nice work|great work|cute couple|that's all for now|all comments|best artist|for ad(?:u|l)t\\+? only|up next|back to notifications|yo boy stood on business|de nandy estamos|nah gini loh|neh gini loh)\\b")
    private val artistSentenceCueRegex = Regex("(?i)\\b(?:doesn['’]?t|don['’]?t|is|are|was|were|with|your|my|the|girl|boy|couple|matter)\\b")
    private val incompleteTailRegex = Regex("(?i)\\b(?:kimi\\s+no|to\\s+be|with\\s+the|and\\s+the|and\\s+then|of\\s+the|in\\s+the|on\\s+the|or\\s+the)$")
    private val conversationalRegex = Regex("(?i)^\\s*(?:this was|that was|this guy|this is peak|nice[.!?]|great[.!?]|if you want|sorry|i am|i'm|because you|the english title|thank you|thanks for|thanks\\b|found a good link|i think|i read|i saw|it was|it consists|the story|the doujin|the main girl|as i understand|one day|please remember|we recommend|you should check|don't worry|dont worry|i want such|in the end|and then)\\b")

    fun extract(latin: Text, japanese: Text, imageUri: String): List<BatchImageRecord> {
        return extractLines(
            latin.textBlocks.flatMap { block -> block.lines.map { it.text } } +
                japanese.textBlocks.flatMap { block -> block.lines.map { it.text } },
            imageUri,
        )
    }

    /** Compatibility helper for call sites that only want the top result. */
    fun extractFirstLines(rawLines: List<String>, imageUri: String): BatchImageRecord? =
        extractLines(rawLines, imageUri).firstOrNull()

    fun extractLines(rawLines: List<String>, imageUri: String): List<BatchImageRecord> {
        val lines = rawLines
            .map(::cleanLine)
            .filter(String::isNotBlank)
            .distinct()
        if (lines.isEmpty()) return emptyList()
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

        val structuredArtist = lines.firstNotNullOfOrNull { line -> labeledArtistRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.let(::cleanArtist) }
        val titleCandidates = LinkedHashMap<String, TitleCandidate>()
        var pendingArtist: String? = null
        var lastTitleKey: String? = null
        fun addCandidate(raw: String?, confidence: Int, suppliedArtist: String? = null) {
            val cleaned = raw?.let(::stripTitleCue)?.let(::cleanTitle) ?: return
            val (parsedTitle, parsedArtist) = parseTitleAndArtist(cleaned)
            val title = parsedTitle.trim()
            if (platformBadgeRegex.matches(title) || noiseRegex.containsMatchIn(title) || socialReactionRegex.containsMatchIn(title)) return
            if (title.length < 3 || !title.any(Char::isLetter)) return
            val key = normalizeTitle(title)
            if (key.isBlank()) return
            val incoming = TitleCandidate(title, suppliedArtist?.let(::cleanArtist) ?: pendingArtist ?: parsedArtist, confidence)
            val prior = titleCandidates[key]
            if (prior == null || incoming.confidence > prior.confidence) titleCandidates[key] = incoming
            lastTitleKey = key
            pendingArtist = null
        }

        val hasSocialChrome = lines.any { communityRegex.matches(it) || usernameRegex.containsMatchIn(it) || timeRegex.matches(it) || noiseRegex.containsMatchIn(it) }
        lines.forEachIndexed { index, line ->
            val artistMatch = labeledArtistRegex.matchEntire(line)
            if (artistMatch != null) {
                val artist = artistMatch.groupValues.getOrNull(1)?.let(::cleanArtist)
                if (artist != null) {
                    if (artist.split(Regex("\\s+")).size >= 3 && artistSentenceCueRegex.containsMatchIn(artist)) return@forEachIndexed
                    val previousKey = lastTitleKey
                    if (previousKey != null) {
                        titleCandidates[previousKey]?.let { titleCandidates[previousKey] = it.copy(artist = artist) }
                        lastTitleKey = null
                    } else {
                        pendingArtist = artist
                    }
                }
                return@forEachIndexed
            }
            labeledTitleRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.let { addCandidate(it, 100) }
            titleCueRegex.find(line)?.groupValues?.getOrNull(1)?.let { addCandidate(it, 98) }
            if (line.startsWith("rec:", true) || line.startsWith("rec ", true)) {
                addCandidate(line.replaceFirst(Regex("(?i)^rec\\s*[:：]?\\s*"), ""), 96)
            }
            saucePrefixRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.let { addCandidate(it, 94) }
            trailingByArtistRegex.matchEntire(line)?.let { addCandidate(it.groupValues[1], 96, it.groupValues[2]) }
            val leadingArtist = leadingArtistRegex.matchEntire(line)
            leadingArtist?.let { addCandidate(it.groupValues[2], 96, it.groupValues[1]) }
            creatorDashTitleRegex.matchEntire(line)?.let { addCandidate(it.groupValues[2], 90, it.groupValues[1]) }
            if (leadingArtist != null) return@forEachIndexed
            val formatted = trailingArtistRegex.matchEntire(line) != null || trailingOpenArtistRegex.matchEntire(line) != null
            titleCandidateScore(line, index, hasSocialChrome)?.let { addCandidate(line, if (formatted) maxOf(it, 88) else it) }
        }

        if (titleCandidates.isEmpty() && code == null && link == null) return emptyList()
        val imageKey = imageUri.hashCode().toUInt().toString(16)
        val orderedTitles = titleCandidates.values.toList()
        if (orderedTitles.isEmpty()) {
            val identifierScore = when {
                link != null -> 100
                explicitCode != null -> 96
                pureCode != null -> 90
                else -> 55
            }
            return listOf(BatchImageRecord(imageKey, null, pendingArtist ?: structuredArtist, code, link, imageUri, identifierScore))
        }
        return orderedTitles.mapIndexed { index, candidate ->
            BatchImageRecord(
                id = "$imageKey-${index + 1}-${normalizeTitle(candidate.title).hashCode().toUInt().toString(16)}",
                title = candidate.title,
                artist = candidate.artist,
                code = code,
                link = link,
                imageUri = imageUri,
                confidence = candidate.confidence,
            )
        }
    }

    private fun isLikelyPostTitle(value: String, hasSocialChrome: Boolean): Boolean {
        val line = cleanTitle(value) ?: return false
        if (line.length !in 8..160 || communityRegex.matches(line) || usernameRegex.containsMatchIn(line) || timeRegex.matches(line) || platformBadgeRegex.matches(line)) return false
        if (noiseRegex.containsMatchIn(line) || socialReactionRegex.containsMatchIn(line) || conversationalRegex.containsMatchIn(line)) return false
        if (incompleteTailRegex.containsMatchIn(line)) return false
        if (hasSocialChrome && commenterNameRegex.matches(line)) return false
        val firstLetter = line.firstOrNull(Char::isLetter)
        if (hasSocialChrome && firstLetter != null && firstLetter.isLowerCase() && firstLetter.code < 0x3000) return false
        if (requestLineRegex.containsMatchIn(line)) return false
        if (Regex("(?i)(?:·|•|\\s)\\d+\\s*(?:s|m|h|d|w|mo|y)\\b").containsMatchIn(line)) return false
        if (Regex("(?i)^\\s*(?:judul|title|name|artist|by|creator|author|code|chapter|tags?|genre|home|share|comment|reply)\\s*[:：]?").containsMatchIn(line)) return false
        if (Regex("(?i)\\b(?:artist|author|creator)\\s*[:：]").containsMatchIn(line)) return false
        val words = line.split(Regex("\\s+"))
        if (line.count { it.isLetter() } < 5 || words.size < 2) return false
        if (words.size == 2 && line.length < 12) return false
        if (words.size >= 3 && line.length < 15 && !line.any(Char::isDigit)) return false
        if (line.endsWith('.') || line.endsWith('?') || line.endsWith(',')) return false
        if (pureCodeRegex.matches(line)) return false
        if (line.matches(Regex("[A-Z0-9][A-Z0-9 &'’:_-]{1,20}")) && line.count { it.isLetter() } < 12) return false
        return true
    }

    private fun titleCandidateScore(value: String, index: Int, hasSocialChrome: Boolean): Int? {
        if (!isLikelyPostTitle(value, hasSocialChrome)) return null
        val line = cleanTitle(value) ?: return null
        val words = line.split(Regex("\\s+")).size
        var score = 60 + minOf(line.length / 12, 12) + minOf(words, 8)
        if (trailingArtistRegex.containsMatchIn(line) || trailingOpenArtistRegex.containsMatchIn(line)) score += 16
        if (line.any(Char::isDigit)) score += 4
        if (':' in line && words >= 4) score += 4
        if (line.count(Char::isLetter) >= 18) score += 4
        // Prefer specific title-like lines, but let a later title in a comment/reply beat a short UI label.
        score -= minOf(index, 20)
        return score
    }

    /** Search variants remove creator fragments and try the subtitle after a colon too. */
    fun searchQueries(title: String): List<String> {
        val parsed = parseTitleAndArtist(title).first
        val noDanglingCredit = trailingOpenArtistRegex.replace(parsed, "$1").trim()
        val clean = cleanTitle(noDanglingCredit) ?: noDanglingCredit.trim()
        val candidates = buildList {
            add(clean)
            clean.substringAfterLast(':', "").trim().takeIf { it.length >= 6 }?.let(::add)
            clean.substringAfterLast('—', "").trim().takeIf { it.length >= 6 }?.let(::add)
        }
        return candidates.map { it.trim().trim(':', '-', '—', '[', '(', ')', ']') }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
    }

    private fun parseTitleAndArtist(raw: String): Pair<String, String?> {
        var title = cleanTitle(raw).orEmpty()
        title = trailingCreditRegex.replace(title, "").trim()
        title = trailingCatalogTagsRegex.replace(title, "").trim()
        val suffix = trailingArtistRegex.matchEntire(title)
        if (suffix != null) {
            val possibleArtist = cleanArtist(suffix.groupValues[2])
            // All-uppercase site/source tags are credits, not creator names.
            if (possibleArtist != null && !possibleArtist.matches(Regex("[A-Z0-9 _-]{3,}"))) {
                return cleanTitle(suffix.groupValues[1]).orEmpty() to possibleArtist
            }
        }
        val openSuffix = trailingOpenArtistRegex.matchEntire(title)
        if (openSuffix != null) {
            val possibleArtist = cleanArtist(openSuffix.groupValues[2])
            if (possibleArtist != null && !possibleArtist.matches(Regex("[A-Z0-9 _-]{3,}"))) {
                return cleanTitle(openSuffix.groupValues[1]).orEmpty() to possibleArtist
            }
        }
        val bySuffix = trailingByArtistRegex.matchEntire(title)
        if (bySuffix != null) {
            val possibleArtist = cleanArtist(bySuffix.groupValues[2])
            if (possibleArtist != null) return cleanTitle(bySuffix.groupValues[1]).orEmpty() to possibleArtist
        }
        val leading = leadingArtistRegex.matchEntire(title)
        if (leading != null) return cleanTitle(leading.groupValues[2]).orEmpty() to cleanArtist(leading.groupValues[1])
        val creatorDash = creatorDashTitleRegex.matchEntire(title)
        if (creatorDash != null) return cleanTitle(creatorDash.groupValues[2]).orEmpty() to cleanArtist(creatorDash.groupValues[1])
        return title to null
    }

    private fun stripTitleCue(raw: String): String =
        saucePrefixRegex.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim() ?: raw

    private fun cleanTitle(value: String): String? {
        var cleaned = value.trim().trim('"', '\'', '“', '”', '•', '-', '—')
        cleaned = trailingPlatformRegex.replace(cleaned, "").trim()
        cleaned = trailingCommunityRegex.replace(cleaned, "").trim()
        cleaned = markdownLinkRegex.replace(cleaned, "$1")
        cleaned = genericUrlRegex.replace(cleaned, "")
        cleaned = linkRegex.replace(cleaned, "").replace(Regex("\\s+"), " ").trim()
        cleaned = trailingSocialCountRegex.replace(cleaned, "").trim()
        cleaned = trailingCreditRegex.replace(cleaned, "").trim()
        cleaned = trailingCatalogTagsRegex.replace(cleaned, "").trim()
        return cleaned.takeIf { it.length >= 3 && it.any(Char::isLetter) }
    }

    private fun cleanArtist(value: String): String? = value.trim().trim('(', ')', '[', ']', ':', '：', '-', '—', '.', '。')
        .replace(Regex("\\s+"), " ")
        .takeIf { it.length in 2..60 && it.any(Char::isLetter) && !noiseRegex.containsMatchIn(it) && !artistSentenceCueRegex.containsMatchIn(it) }

    private fun cleanLine(value: String): String = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun normalizeTitle(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private data class TitleCandidate(val title: String, val artist: String?, val confidence: Int)
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("ML Kit task cancelled")) }
}
