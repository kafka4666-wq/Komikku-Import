package exh.ui.batchadd

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.workManager
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import android.util.AtomicFile
import android.util.Base64
import coil3.compose.AsyncImage

class BatchTitleSearchScreen(private val inputUriString: String, private val existingJobId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        var jobId by rememberSaveable(inputUriString, existingJobId) { mutableStateOf(existingJobId) }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        var results by remember { mutableStateOf<List<TitleSearchCandidate>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }
        val inputUri = remember(inputUriString) { Uri.parse(inputUriString) }

        LaunchedEffect(inputUriString, existingJobId) {
            if (jobId != null) return@LaunchedEffect
            if (existingJobId != null) {
                jobId = existingJobId
                return@LaunchedEffect
            }
            runCatching { context.contentResolver.takePersistableUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            jobId = BatchTitleSearchWorker.enqueue(context.applicationContext, inputUri).toString()
        }
        LaunchedEffect(jobId) {
            val id = jobId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collect { workInfo ->
                info = workInfo
                if (workInfo?.state?.isFinished == true) {
                    results = BatchTitleSearchWorker.readResults(context, workInfo.outputData.getString(BatchTitleSearchWorker.KEY_RESULT_FILE))
                }
            }
        }
        val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        val progress = info?.progress
        val completed = progress?.getInt(BatchTitleSearchWorker.KEY_COMPLETED, 0) ?: 0
        val total = progress?.getInt(BatchTitleSearchWorker.KEY_TOTAL, 0) ?: 0
        val paused = progress?.getBoolean(BatchTitleSearchWorker.KEY_PAUSED, false) ?: BatchTitleSearchWorker.isPaused(context)
        val selectedUrls = results.filter { it.id in selected }.map { it.url }.distinct()

        Scaffold(topBar = { scroll ->
            AppBar(title = "Find titles in enabled sources", navigateUp = { navigator?.pop() }, scrollBehavior = scroll)
        }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Each input title shows at most one result across all sources. Exact title matches are preferred; review the selected representative before adding.", style = MaterialTheme.typography.bodyMedium)
                }
                if (running) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text(if (total > 0) "Searching $completed/$total titles…" else "Searching enabled sources…")
                    }
                    if (total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { jobId?.let { BatchTitleSearchWorker.setPaused(context, true) } }) { Text("Pause") }
                        Button(onClick = { BatchTitleSearchWorker.setPaused(context, false) }) { Text("Resume") }
                        TextButton(onClick = { jobId?.let { manager.cancelWorkById(UUID.fromString(it)) } }) { Text("Cancel") }
                    }
                    if (paused) Text("Paused · no new source searches will start", style = MaterialTheme.typography.bodySmall)
                }
                if (!running && results.isNotEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${results.size} candidates found", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (selected.size == results.size) selected.clear() else { selected.clear(); selected.addAll(results.map { it.id }) } }) { Text("Select all") }
                    }
                    Button(
                        enabled = selectedUrls.isNotEmpty(),
                        onClick = {
                            BatchImportJob.startTitleMatches(context.applicationContext, selectedUrls)
                            navigator?.pop()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add selected confirmed titles (${selectedUrls.size})") }
                }
                if (!running && results.isEmpty() && info?.state?.isFinished == true) item {
                    Text("No matching titles were found across the enabled installed sources.", style = MaterialTheme.typography.bodyMedium)
                }
                items(results, key = { it.id }) { candidate ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            Checkbox(checked = candidate.id in selected, onCheckedChange = {
                                if (it) selected.add(candidate.id) else selected.remove(candidate.id)
                            })
                            AsyncImage(
                                model = candidate.thumbnailUrl,
                                contentDescription = candidate.matchedTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(candidate.matchedTitle, style = MaterialTheme.typography.titleSmall)
                                Text("${candidate.sourceName} · from: ${candidate.inputTitle}", style = MaterialTheme.typography.bodySmall)
                                Text(candidate.url, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

data class TitleSearchCandidate(
    val id: String,
    val inputTitle: String,
    val matchedTitle: String,
    val sourceName: String,
    val url: String,
    val thumbnailUrl: String?,
)

internal object TitleSearchCandidateDeduplicator {
    fun collapse(candidates: List<TitleSearchCandidate>): List<TitleSearchCandidate> {
        val parent = mutableMapOf<String, String>()
        fun find(key: String): String {
            val current = parent[key] ?: run {
                parent[key] = key
                return key
            }
            if (current == key) return key
            val root = find(current)
            parent[key] = root
            return root
        }
        fun union(left: String, right: String) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) {
                val root = minOf(leftRoot, rightRoot)
                val child = maxOf(leftRoot, rightRoot)
                parent[child] = root
            }
        }

        val keysByCandidate = candidates.map { candidate ->
            buildList {
                normalize(candidate.inputTitle).takeIf(String::isNotEmpty)?.let { add("title:$it") }
                normalize(candidate.matchedTitle).takeIf(String::isNotEmpty)?.let { add("title:$it") }
                candidate.url.trim().trimEnd('/').takeIf(String::isNotEmpty)?.let { add("url:$it") }
                if (isEmpty()) add("candidate:${candidate.id}")
            }.distinct()
        }
        keysByCandidate.forEach { keys ->
            val first = keys.first()
            find(first)
            keys.drop(1).forEach { union(first, it) }
        }

        val groups = linkedMapOf<String, MutableList<TitleSearchCandidate>>()
        candidates.forEachIndexed { index, candidate ->
            groups.getOrPut(find(keysByCandidate[index].first())) { mutableListOf() } += candidate
        }
        return groups.values.map { group ->
            group.reduce { preferred, candidate ->
                if (isPreferred(candidate, preferred)) candidate else preferred
            }
        }
    }

    private fun isPreferred(candidate: TitleSearchCandidate, current: TitleSearchCandidate): Boolean {
        val candidateIsExact = candidate.matchedTitle.trim().equals(candidate.inputTitle.trim(), ignoreCase = true)
        val currentIsExact = current.matchedTitle.trim().equals(current.inputTitle.trim(), ignoreCase = true)
        if (candidateIsExact != currentIsExact) return candidateIsExact

        val sourceOrder = candidate.sourceName.compareTo(current.sourceName, ignoreCase = true)
        if (sourceOrder != 0) return sourceOrder < 0
        return candidate.url.compareTo(current.url, ignoreCase = true) < 0
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " "), Normalizer.Form.NFKD)
        .lowercase()
        .filter { it.isLetterOrDigit() }
}

class BatchTitleSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
    override suspend fun doWork(): Result {
        val resultDirectory = File(applicationContext.filesDir, "batch-title-search-${id}.result-rows")
        val finalResultFile = File(applicationContext.filesDir, "batch-title-search-${id}.final-results")
        val titles = runCatching {
            inputData.getString(KEY_TITLES_FILE)?.let { File(it).readLines() }
                ?: inputData.getString(KEY_URI)?.let(Uri::parse)?.let { uri ->
                    applicationContext.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines -> lines.toList() }.orEmpty()
                }
                ?: emptyList()
        }.map { lines ->
            lines.map(::cleanTitle).map(String::trim).filter(String::isNotBlank).distinct()
        }.getOrElse { xLogE("Title file read failed", it); return Result.failure() }
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabled = sourcePreferences.disabledSources().get()
        val sources = sourceManager.getVisibleSources()
            .filterIsInstance<HttpSource>()
            .filter { it.lang in enabledLanguages && it.id.toString() !in disabled }
        if (!resultDirectory.exists() && !resultDirectory.mkdirs()) return Result.failure()
        resultDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(".") && it.name.endsWith(".tmp") }
            .forEach { it.delete() }
        fun rowFile(index: Int) = File(resultDirectory, "${index.toString().padStart(8, '0')}.row")
        val completed = AtomicInteger(titles.indices.count { rowFile(it).isFile })
        val semaphore = Semaphore(12)
        setForeground(getForegroundInfo())
        setProgress(workDataOf(KEY_COMPLETED to completed.get(), KEY_TOTAL to titles.size))
        updateNotification(completed.get(), titles.size, "Searching ${sources.size} enabled sources")
        coroutineScope {
            titles.mapIndexedNotNull { index, title ->
                if (rowFile(index).isFile) return@mapIndexedNotNull null
                async(Dispatchers.IO) {
                    awaitNotPaused()
                    val candidates = coroutineScope {
                        sources.map { source -> async { searchSource(source, title, semaphore) } }.awaitAll().flatten()
                    }
                    val outputFile = rowFile(index)
                    val tempFile = File(resultDirectory, ".${outputFile.name}.tmp")
                    val rows = candidates.distinctBy { it.url }.take(8).joinToString("\n") { encode(it) }
                    try {
                        FileOutputStream(tempFile).use { stream ->
                            if (rows.isNotEmpty()) stream.write(rows.toByteArray(Charsets.UTF_8))
                            stream.fd.sync()
                        }
                        if (!tempFile.renameTo(outputFile)) throw IOException("Unable to commit title-search checkpoint ${outputFile.name}")
                    } catch (error: Throwable) {
                        tempFile.delete()
                        throw error
                    }
                    val done = completed.incrementAndGet()
                    setProgress(workDataOf(KEY_COMPLETED to done, KEY_TOTAL to titles.size, KEY_PAUSED to isPaused(applicationContext)))
                    updateNotification(done, titles.size, "Finished $done/${titles.size}: ${title.take(48)}")
                }
            }.awaitAll()
        }
        // Run once after every title task has finished and committed its checkpoint.
        // Only this consolidated file is exposed to the screen and auto-import flow.
        val uniqueTitleCount = writeConsolidatedResults(resultDirectory, finalResultFile)
        updateNotification(titles.size, titles.size, "Search complete · $uniqueTitleCount unique title matches")
        if (inputData.getBoolean(KEY_AUTO_IMPORT, false)) {
            val urls = readResults(applicationContext, finalResultFile.absolutePath).map { it.url }.distinct()
            if (urls.isNotEmpty()) runCatching { BatchImportJob.startTitleMatches(applicationContext, urls) }
        }
        return Result.success(workDataOf(KEY_RESULT_FILE to finalResultFile.absolutePath, KEY_COMPLETED to titles.size, KEY_TOTAL to titles.size))
    }

    private fun writeConsolidatedResults(resultDirectory: File, finalResultFile: File): Int {
        val candidates = resultDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".row") }
            .sortedBy { it.name }
            .flatMap { it.readLines() }
            .mapNotNull(::decode)
            .distinctBy { it.id }
        val uniqueCandidates = TitleSearchCandidateDeduplicator.collapse(candidates)
        val atomicFile = AtomicFile(finalResultFile)
        val output = atomicFile.startWrite()
        try {
            val payload = uniqueCandidates.joinToString("\n") { encode(it) }
            if (payload.isNotEmpty()) output.write(payload.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
        return uniqueCandidates.size
    }
    private suspend fun searchSource(source: Source, title: String, semaphore: Semaphore): List<TitleSearchCandidate> {
        return try {
            awaitNotPaused()
            val page = semaphore.withPermit { withTimeoutOrNull(15_000L) { source.getSearchManga(1, title.sanitize(), source.getFilterList()) } }
                ?: return emptyList()
            page.mangas.map { manga ->
                val local = manga.toDomainManga(source.id)
                TitleSearchCandidate(
                    id = "${title.hashCode()}-${source.id}-${manga.url.hashCode()}",
                    inputTitle = title,
                    matchedTitle = manga.title,
                    sourceName = source.name,
                    url = absoluteUrl(source, manga.url),
                    thumbnailUrl = local.thumbnailUrl?.let { absoluteUrl(source, it) },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
    private suspend fun awaitNotPaused() {
        while (isPaused(applicationContext) && !isStopped) {
            setProgress(workDataOf(KEY_PAUSED to true))
            updateNotification(0, 0, "Paused · waiting to resume")
            delay(750)
        }
        if (!isStopped) setProgress(workDataOf(KEY_PAUSED to false))
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        applicationContext.notificationBuilder(Notifications.CHANNEL_KOMIKKU_IMPORT) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Finding titles in enabled sources")
            setContentText("Searching enabled sources")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
        }.build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
    private fun updateNotification(completed: Int, total: Int, detail: String) {
        val openIntent = Intent(applicationContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java).apply {
            action = ACTION_OPEN_BATCH_TITLE_SEARCH
            putExtra(EXTRA_WORK_ID, id.toString())
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(applicationContext, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(applicationContext).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_KOMIKKU_IMPORT)
                .setSmallIcon(R.drawable.ic_komikku)
                .setContentTitle("Finding titles in enabled sources")
                .setContentText(detail)
                .setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total), false)
                .setOngoing(completed < total)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(false)
                .addAction(R.drawable.ic_close_24dp, "Cancel", applicationContext.workManager.createCancelPendingIntent(id))
                .build(),
        )
    }
    companion object {
        const val KEY_URI = "title_uri"
        const val KEY_TITLES_FILE = "title_file"
        const val KEY_AUTO_IMPORT = "auto_import"
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_PAUSED = "paused"
        const val NOTIFICATION_ID = -1810
        const val ACTION_OPEN_BATCH_TITLE_SEARCH = "com.manus.komikku.OPEN_BATCH_TITLE_SEARCH"
        const val EXTRA_WORK_ID = "batch_title_search_work_id"
        private const val PREFS = "batch_title_search_controls"
        private const val PAUSED = "paused"
        fun isPaused(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSED, false)
        fun setPaused(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSED, value).apply() }
        suspend fun enqueue(context: Context, uri: Uri): UUID = withContext(Dispatchers.IO) {
            val workManager = context.workManager
            val uniqueName = uniqueUriWorkName(uri)
            val activeWork = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }
            }.getOrNull()
            if (activeWork != null) return@withContext activeWork.id

            setPaused(context, false)
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString()))
                .addTag("batch_title_search")
                .build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request).result.get()
            runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueName).get().firstOrNull { !it.state.isFinished }?.id
            }.getOrNull() ?: request.id
        }
        fun enqueueTitles(context: Context, titles: List<String>, autoImport: Boolean = false): UUID {
            setPaused(context, false)
            val id = UUID.randomUUID()
            val file = File(context.filesDir, "batch-title-search-$id.txt")
            file.writeText(titles.joinToString("\n"))
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>()
                .setId(id)
                .setInputData(workDataOf(KEY_TITLES_FILE to file.absolutePath, KEY_AUTO_IMPORT to autoImport))
                .addTag("batch_title_search")
                .build()
            context.workManager.enqueue(request)
            return id
        }
        fun readResults(context: Context, path: String?): List<TitleSearchCandidate> = runCatching {
            val result = path?.let(::File) ?: return@runCatching emptyList()
            val lines = if (result.isDirectory) {
                result.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".row") }
                    .sortedBy { it.name }
                    .flatMap { it.readLines() }
            } else if (result.isFile) {
                result.readLines()
            } else {
                emptyList()
            }
            TitleSearchCandidateDeduplicator.collapse(lines.mapNotNull(::decode).distinctBy { it.id })
        }.getOrDefault(emptyList())
        private fun uniqueUriWorkName(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "batch_title_search_uri_$key"
        }
        private fun encode(value: TitleSearchCandidate): String = listOf(value.id, value.inputTitle, value.matchedTitle, value.sourceName, value.url, value.thumbnailUrl.orEmpty()).joinToString("\t") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        private fun decode(line: String): TitleSearchCandidate? = runCatching { val v = line.split('\t').map { String(Base64.decode(it, Base64.DEFAULT)) }; TitleSearchCandidate(v[0], v[1], v[2], v[3], v[4], v.getOrNull(5)?.ifBlank { null }) }.getOrNull()
        private fun cleanTitle(value: String): String = value.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun absoluteUrl(source: Source, value: String): String {
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
            val baseUrl = (source as? HttpSource)?.baseUrl?.trimEnd('/') ?: return value
            return "$baseUrl/${value.trimStart('/')}"
        }
    }
}
