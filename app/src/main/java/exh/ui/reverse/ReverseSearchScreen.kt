package exh.ui.reverse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.workManager
import exh.ui.batchadd.BatchImportJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ReverseSearchScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        var selected by remember { mutableStateOf(loadPersistedSelection(context)) }
        var jobId by remember { mutableStateOf(ReverseSearchWorker.activeWorkId(context)) }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        var reviewRows by remember { mutableStateOf<List<ReviewRow>>(emptyList()) }
        val checked = remember { mutableStateListOf<String>() }
        var notFoundText by remember { mutableStateOf<String?>(null) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                selected = listOf(uri)
            }
        }
        val notFoundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) notFoundText?.let { text -> runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } } }
        }
        LaunchedEffect(Unit) {
            var trackedId = jobId
            while (true) {
                val snapshot = withContext(Dispatchers.IO) {
                    var currentId = trackedId
                    var currentInfo = currentId?.let { runCatching { manager.getWorkInfoById(it).get() }.getOrNull() }
                    if (currentInfo == null || currentInfo.state.isFinished) {
                        val tagged = runCatching { manager.getWorkInfosByTag(ReverseSearchWorker.TAG).get() }
                            .getOrNull()
                            ?.filter { it.state != WorkInfo.State.CANCELLED }
                            .orEmpty()
                        currentInfo = tagged.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                            ?: tagged.maxByOrNull { it.id.toString() }
                        if (currentInfo != null) currentId = currentInfo.id
                    }
                    val journal = currentId?.let { File(context.filesDir, "reverse-search/${it}.results.jsonl") }
                    val path = currentInfo?.outputData?.getString(ReverseSearchWorker.KEY_REVIEW_FILE)
                        ?: currentInfo?.progress?.getString(ReverseSearchWorker.KEY_REVIEW_FILE)
                        ?: journal?.absolutePath
                    Triple(currentId, currentInfo, readReviewRows(path))
                }
                trackedId = snapshot.first
                if (snapshot.first != null && snapshot.first != jobId) {
                    jobId = snapshot.first
                    selected = loadPersistedSelection(context)
                }
                info = snapshot.second
                if (snapshot.third.isNotEmpty()) reviewRows = snapshot.third
                if (snapshot.second?.state == WorkInfo.State.SUCCEEDED) checked.clear()
                delay(1000)
            }
        }
        val progress = info?.progress
        val done = progress?.getInt(ReverseSearchWorker.KEY_COMPLETED, 0) ?: 0
        val total = progress?.getInt(ReverseSearchWorker.KEY_TOTAL, 0) ?: 0
        val found = progress?.getInt(ReverseSearchWorker.KEY_FOUND, 0) ?: 0
        val paused = progress?.getBoolean(ReverseSearchWorker.KEY_PAUSED, false) ?: ReverseSearchWorker.isPaused(context)
        val phase = progress?.getString(ReverseSearchWorker.KEY_PHASE).orEmpty()
        val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        val selectedRows = reviewRows.filter { it.id in checked }
        val directSelected = selectedRows.mapNotNull { it.galleryUrl }.distinct()
        val notFoundRows = reviewRows.filter { it.title.isNullOrBlank() }
        val startSearch: (List<Uri>) -> Unit = { uris ->
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            jobId = ReverseSearchWorker.enqueue(context.applicationContext, uris)
            info = null
            reviewRows = emptyList()
            checked.clear()
        }
        Scaffold(topBar = { TopAppBar(title = { Text("Reverse Search Review") }, navigationIcon = { TextButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Search first, then review every result before importing. Original image is on the left, Yandex title in the middle, and the matched source image on the right.", style = MaterialTheme.typography.bodyMedium)
                    Button(enabled = !running, onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Icon(Icons.Outlined.FolderOpen, null); Text(" Select album folder") }
                    if (selected.isNotEmpty()) Text("Folder restored. Subfolders are included; you can start or resume this search.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = selected.isNotEmpty() && !running, onClick = { startSearch(selected) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Start Reverse Search") }
                }
                if (running || info?.state?.isFinished == true) item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) CircularProgressIndicator()
                        Text(when { running && total > 0 -> "Searching $done/$total images"; running -> "Discovering images…"; info?.state == WorkInfo.State.SUCCEEDED -> "Complete · $found result(s) ready for review"; info?.state == WorkInfo.State.FAILED -> "Reverse search failed"; else -> "Reverse search" }, style = MaterialTheme.typography.titleMedium)
                        if (phase.isNotBlank()) Text(phase, style = MaterialTheme.typography.bodySmall)
                        if (running && total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        if (running) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { ReverseSearchWorker.setPaused(context, true) }) { Text("Pause") }
                                Button(onClick = { ReverseSearchWorker.setPaused(context, false) }) { Text("Resume") }
                                TextButton(onClick = { jobId?.let(manager::cancelWorkById) }) { Text("Cancel") }
                            }
                            if (paused) Text("Paused · no new images will be searched", style = MaterialTheme.typography.bodySmall)
                        }
                    } }
                }
                if (!running && reviewRows.isNotEmpty()) item {
                    Text("${reviewRows.count { it.title != null }} found · ${notFoundRows.size} not found · ${checked.size} selected", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { if (checked.size == reviewRows.size) checked.clear() else { checked.clear(); checked.addAll(reviewRows.map { it.id }) } }) { Text("Select all") }
                        TextButton(enabled = checked.isNotEmpty(), onClick = { val uris = selectedRows.map { Uri.parse(it.originalUri) }; if (uris.isNotEmpty()) startSearch(uris) }) { Text("Research selected") }
                    }
                    Button(enabled = directSelected.isNotEmpty(), onClick = { BatchImportJob.start(context.applicationContext, directSelected); Toast.makeText(context, "Approved galleries sent to library import", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Text("Add approved galleries (${directSelected.size})") }
                    Button(enabled = notFoundRows.isNotEmpty(), onClick = { notFoundText = notFoundRows.joinToString("\n") { it.originalUri }; notFoundLauncher.launch("not found.txt") }, modifier = Modifier.fillMaxWidth()) { Text("Save not found (${notFoundRows.size})") }
                    Text("Select wrong or duplicate rows and use Research selected. Nothing is imported automatically.", style = MaterialTheme.typography.labelSmall)
                }
                items(reviewRows, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = row.id in checked, onCheckedChange = { if (it) checked.add(row.id) else checked.remove(row.id) })
                                AsyncImage(model = Uri.parse(row.originalUri), contentDescription = "Original image", contentScale = ContentScale.Crop, modifier = Modifier.size(82.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(row.title ?: "Not found", style = MaterialTheme.typography.titleSmall); Text(if (row.galleryUrl != null) "Gallery candidate available" else "Title/image match only · research to find enabled-source gallery", style = MaterialTheme.typography.bodySmall) }
                                if (row.matchedImage != null) AsyncImage(model = row.matchedImage, contentDescription = "Yandex matched image", contentScale = ContentScale.Crop, modifier = Modifier.size(82.dp))
                            }
                            if (row.galleryUrl != null) Text(row.galleryUrl, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    private fun readReviewRows(path: String?): List<ReviewRow> = runCatching { path?.let(::File)?.readLines()?.mapNotNull { line -> JSONObject(line).let { ReviewRow(it.optString("originalUri"), it.optString("title").takeIf(String::isNotBlank), it.optString("galleryUrl").takeIf(String::isNotBlank), it.optString("matchedImage").takeIf(String::isNotBlank)) } }.orEmpty() }.getOrDefault(emptyList())

    private fun loadPersistedSelection(context: android.content.Context): List<Uri> = runCatching {
        val id = ReverseSearchWorker.activeWorkId(context) ?: return emptyList()
        File(context.filesDir, "reverse-search/$id.selection").takeIf(File::exists)?.readLines()?.map(Uri::parse).orEmpty()
    }.getOrDefault(emptyList())
}

data class ReviewRow(val originalUri: String, val title: String?, val galleryUrl: String?, val matchedImage: String?) { val id: String get() = "$originalUri|${title.orEmpty()}|${galleryUrl.orEmpty()}" }
