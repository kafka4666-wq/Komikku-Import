package exh.ui.batchadd

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
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
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import android.util.Base64

class BatchTitleSearchScreen(private val inputUri: Uri) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        var jobId by remember { mutableStateOf<String?>(null) }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        var results by remember { mutableStateOf<List<TitleSearchCandidate>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }

        LaunchedEffect(inputUri) {
            runCatching { context.contentResolver.takePersistableUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val id = BatchTitleSearchWorker.enqueue(context.applicationContext, inputUri)
            jobId = id.toString()
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
                    Text("Titles are cleaned by removing parenthesized and bracketed notes before searching. Review the source and title, then select only the correct matches.", style = MaterialTheme.typography.bodyMedium)
                }
                if (running) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text(if (total > 0) "Searching $completed/$total titles…" else "Searching enabled sources…")
                    }
                    if (total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { jobId?.let { manager.cancelWorkById(UUID.fromString(it)) } }) { Text("Cancel") }
                }
                if (!running && results.isNotEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${results.size} candidates found", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (selected.size == results.size) selected.clear() else { selected.clear(); selected.addAll(results.map { it.id }) } }) { Text("Select all") }
                    }
                    Button(
                        enabled = selectedUrls.isNotEmpty(),
                        onClick = {
                            BatchImportJob.start(context.applicationContext, selectedUrls)
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

data class TitleSearchCandidate(val id: String, val inputTitle: String, val matchedTitle: String, val sourceName: String, val url: String)

class BatchTitleSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val resultFile = File(applicationContext.filesDir, "batch-title-search-${id}.results")
        val titles = runCatching {
            applicationContext.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                lines.map(::cleanTitle).map(String::trim).filter(String::isNotBlank).distinct().toList()
            }.orEmpty()
        }.getOrElse { xLogE("Title file read failed", it); return Result.failure() }
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabled = sourcePreferences.disabledSources().get()
        val sources = sourceManager.getVisibleSources().filter { it.lang in enabledLanguages && it.id.toString() !in disabled }
        resultFile.writeText("")
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(12)
        val writeLock = Any()
        setForeground(getForegroundInfo())
        setProgress(workDataOf(KEY_COMPLETED to 0, KEY_TOTAL to titles.size))
        updateNotification(0, titles.size, "Searching ${sources.size} enabled sources")
        coroutineScope {
            titles.map { title -> async(Dispatchers.IO) {
                val candidates = coroutineScope {
                    sources.map { source -> async { searchSource(source, title, semaphore) } }.awaitAll().flatten()
                }
                synchronized(writeLock) {
                    candidates.distinctBy { it.url }.take(8).forEach { candidate -> resultFile.appendText(encode(candidate) + "\n") }
                }
                val done = completed.incrementAndGet()
                setProgress(workDataOf(KEY_COMPLETED to done, KEY_TOTAL to titles.size))
                updateNotification(done, titles.size, "Finished $done/${titles.size}: ${title.take(48)}")
            } }.awaitAll()
        }
        updateNotification(titles.size, titles.size, "Search complete · review matches in the app")
        return Result.success(workDataOf(KEY_RESULT_FILE to resultFile.absolutePath, KEY_COMPLETED to titles.size, KEY_TOTAL to titles.size))
    }
    private suspend fun searchSource(source: Source, title: String, semaphore: Semaphore): List<TitleSearchCandidate> {
        return try {
            val page = semaphore.withPermit { withTimeoutOrNull(15_000L) { source.getSearchManga(1, title.sanitize(), source.getFilterList()) } }
                ?: return emptyList()
            page.mangas.map { manga -> TitleSearchCandidate("${title.hashCode()}-${source.id}-${manga.url.hashCode()}", title, manga.title, source.name, manga.url) }
        } catch (_: Exception) {
            emptyList()
        }
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
                .addAction(R.drawable.ic_close_24dp, "Cancel", applicationContext.workManager.createCancelPendingIntent(id))
                .build(),
        )
    }
    companion object {
        const val KEY_URI = "title_uri"
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val NOTIFICATION_ID = -1810
        fun enqueue(context: Context, uri: Uri): UUID {
            val request = OneTimeWorkRequestBuilder<BatchTitleSearchWorker>().setInputData(workDataOf(KEY_URI to uri.toString())).addTag("batch_title_search").build()
            context.workManager.enqueue(request)
            return request.id
        }
        fun readResults(context: Context, path: String?): List<TitleSearchCandidate> = runCatching { path?.let(::File)?.readLines()?.mapNotNull(::decode).orEmpty() }.getOrDefault(emptyList())
        private fun encode(value: TitleSearchCandidate): String = listOf(value.id, value.inputTitle, value.matchedTitle, value.sourceName, value.url).joinToString("\t") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        private fun decode(line: String): TitleSearchCandidate? = runCatching { val v = line.split('\t').map { String(Base64.decode(it, Base64.DEFAULT)) }; TitleSearchCandidate(v[0], v[1], v[2], v[3], v[4]) }.getOrNull()
        private fun cleanTitle(value: String): String = value.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
