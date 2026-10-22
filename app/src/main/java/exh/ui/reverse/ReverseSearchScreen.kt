package exh.ui.reverse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import exh.ui.batchadd.BatchTitleSearchScreen
import exh.ui.batchadd.BatchTitleSearchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ReverseSearchScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        val powerManager = remember { context.getSystemService(PowerManager::class.java) }
        var batteryOptimizationExempt by remember { mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) }
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
            batteryOptimizationExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
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
                        ?: currentId?.let { File(context.filesDir, "reverse-search/${it}.review.jsonl") }?.takeIf(File::exists)?.absolutePath
                        ?: journal?.absolutePath
                    Triple(currentId, currentInfo, readReviewRows(path))
                }
                trackedId = snapshot.first
                if (snapshot.first != null && snapshot.first != jobId) {
                    jobId = snapshot.first
                    selected = loadPersistedSelection(context)
                    checked.clear()
                    reviewRows = snapshot.third
                } else if (snapshot.third.isNotEmpty() || snapshot.second?.state == WorkInfo.State.SUCCEEDED) {
                    reviewRows = snapshot.third
                }
                info = snapshot.second
                if (snapshot.second?.state == WorkInfo.State.SUCCEEDED) checked.removeAll { id -> reviewRows.none { it.id == id } }
                delay(1000)
            }
        }

        val progress = info?.progress
        val completed = info?.state == WorkInfo.State.SUCCEEDED
        val done = progress?.getInt(ReverseSearchWorker.KEY_COMPLETED, 0)
            ?: info?.outputData?.getInt(ReverseSearchWorker.KEY_COMPLETED, 0)
            ?: 0
        val total = maxOf(
            progress?.getInt(ReverseSearchWorker.KEY_TOTAL, 0) ?: 0,
            info?.outputData?.getInt(ReverseSearchWorker.KEY_TOTAL, 0) ?: 0,
            reviewRows.size,
        )
        val visibleFound = reviewRows.count { it.title != null }
        val found = if (completed && reviewRows.isNotEmpty()) visibleFound else maxOf(
            progress?.getInt(ReverseSearchWorker.KEY_FOUND, 0) ?: 0,
            info?.outputData?.getInt(ReverseSearchWorker.KEY_FOUND, 0) ?: 0,
        )
        val paused = progress?.getBoolean(ReverseSearchWorker.KEY_PAUSED, false) ?: ReverseSearchWorker.isPaused(context)
        val phase = progress?.getString(ReverseSearchWorker.KEY_PHASE).orEmpty()
        val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        val reviewReady = completed && reviewRows.isNotEmpty()
        val selectedRows = reviewRows.filter { it.id in checked }
        val actionableRows = selectedRows.filter { it.galleryUrl != null || it.title != null }
        val directSelected = actionableRows.mapNotNull { it.galleryUrl }.distinct()
        val titleOnlyRows = actionableRows.filter { it.galleryUrl == null }
        val titleOnlySelected = titleOnlyRows.mapNotNull { it.title }.distinct()
        val notFoundRows = reviewRows.filter { it.title == null }
        val allRowsSelected = reviewRows.isNotEmpty() && reviewRows.all { it.id in checked }
        val addSelectionKey = selectedRows.map { it.id }.sorted().joinToString("\u0000")
        var addSubmitted by remember(jobId, addSelectionKey) { mutableStateOf(false) }
        val startSearch: (List<Uri>) -> Unit = { uris ->
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            jobId = ReverseSearchWorker.enqueue(context.applicationContext, uris)
            info = null
            reviewRows = emptyList()
            checked.clear()
        }
        val addSelected = {
            if (!addSubmitted && actionableRows.isNotEmpty()) {
                addSubmitted = true
                if (directSelected.isNotEmpty()) BatchImportJob.start(context.applicationContext, directSelected)
                if (titleOnlySelected.isNotEmpty()) {
                    val titleJob = BatchTitleSearchWorker.enqueueTitles(context.applicationContext, titleOnlySelected, autoImport = false)
                    navigator?.push(BatchTitleSearchScreen("about:blank", titleJob.toString()))
                }
                Toast.makeText(
                    context,
                    buildString {
                        if (directSelected.isNotEmpty()) append("Queued ${directSelected.size} verified gallery link(s).")
                        if (titleOnlySelected.isNotEmpty()) {
                            if (isNotEmpty()) append(' ')
                            append("Finding source galleries for ${titleOnlySelected.size} selected title(s) to review.")
                        }
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Reverse Search Review") },
                    navigationIcon = {
                        TextButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!reviewReady) item {
                    Text(
                        "Search first, then review each result before importing. Verified gallery links can be added directly; title-only matches can be checked against enabled sources.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!batteryOptimizationExempt && !running) {
                        Text(
                            "For long background searches, allow unrestricted battery usage for Komikku so Android or Xiaomi battery saver does not pause the worker.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                            }.onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            }
                        }) { Text("Allow unrestricted background work") }
                    }
                    Button(
                        enabled = !running,
                        onClick = { picker.launch(null) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Text(if (selected.isEmpty()) " Select album folder" else " Change album folder")
                    }
                    if (selected.isNotEmpty()) Text("Folder restored. Subfolders are included.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = selected.isNotEmpty() && !running,
                        onClick = { startSearch(selected) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text(if (running) "Searching…" else "Start Reverse Search") }
                } else item {
                    Text("Complete · $visibleFound result(s) ready for review", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picker.launch(null) }) { Text("Choose another folder") }
                        TextButton(onClick = { if (selected.isNotEmpty()) startSearch(selected) else picker.launch(null) }) {
                            Text("Search again")
                        }
                    }
                }

                if (running) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(
                                when {
                                    total > 0 -> "Searching $done/$total images · $found match(es)"
                                    else -> "Discovering images…"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (phase.isNotBlank()) Text(phase, style = MaterialTheme.typography.bodySmall)
                            if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { ReverseSearchWorker.setPaused(context, true) }) { Text("Pause") }
                                Button(onClick = { ReverseSearchWorker.setPaused(context, false) }) { Text("Resume") }
                                TextButton(onClick = { jobId?.let(manager::cancelWorkById) }) { Text("Cancel") }
                            }
                            if (paused) Text("Paused · no new images will be searched", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (info?.state == WorkInfo.State.FAILED) item {
                    Text("Reverse search failed. You can retry it using the selected folder.", style = MaterialTheme.typography.bodyMedium)
                }

                if (!running && reviewRows.isNotEmpty()) item {
                    Text(
                        "$visibleFound found · ${notFoundRows.size} not found · ${selectedRows.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (allRowsSelected) checked.clear() else {
                                checked.clear()
                                checked.addAll(reviewRows.map { it.id })
                            }
                        }) { Text(if (allRowsSelected) "Deselect all" else "Select all") }
                        TextButton(
                            enabled = selectedRows.isNotEmpty() && !running,
                            onClick = {
                                val uris = selectedRows.mapNotNull { it.originalUri.takeIf(String::isNotBlank) }.map(Uri::parse)
                                if (uris.isNotEmpty()) startSearch(uris)
                            },
                        ) { Text("Research selected") }
                    }
                    Button(
                        enabled = actionableRows.isNotEmpty() && !running && !addSubmitted,
                        onClick = addSelected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                addSubmitted -> "Added / review queued"
                                directSelected.isNotEmpty() && titleOnlySelected.isNotEmpty() -> "Add ${directSelected.size} · find ${titleOnlySelected.size} more"
                                directSelected.isNotEmpty() -> "Add approved galleries (${directSelected.size})"
                                titleOnlySelected.isNotEmpty() -> "Find galleries for selected titles (${titleOnlySelected.size})"
                                else -> "Select matches to add"
                            },
                        )
                    }
                    Button(
                        enabled = notFoundRows.isNotEmpty(),
                        onClick = {
                            notFoundText = notFoundRows.joinToString("\n") { it.originalUri }
                            notFoundLauncher.launch("not found.txt")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save not found (${notFoundRows.size})") }
                    Text(
                        "Only exact verified gallery matches are added directly. Title-only matches open a source-review step; nothing ambiguous is imported automatically.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                items(reviewRows, key = { it.id }) { row ->
                    val filename = displayFilename(row.originalUri)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(
                                    checked = row.id in checked,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) {
                                            if (row.id !in checked) checked.add(row.id)
                                        } else checked.remove(row.id)
                                    },
                                )
                                AsyncImage(
                                    model = Uri.parse(row.originalUri),
                                    contentDescription = "Original image: $filename",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(82.dp),
                                )
                                Column(
                                    Modifier.weight(1f).padding(start = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(row.title ?: filename, style = MaterialTheme.typography.titleSmall)
                                    if (row.title != null) Text(filename, style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        when {
                                            row.galleryUrl != null -> "Verified gallery · ready to add"
                                            row.title != null -> "Title match · find a gallery in enabled sources"
                                            else -> "No match found"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (row.matchedImage != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = row.matchedImage,
                                        contentDescription = "Verified matching source image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(72.dp),
                                    )
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text("Matched source image", style = MaterialTheme.typography.labelMedium)
                                        row.galleryUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readReviewRows(path: String?): List<ReviewRow> = runCatching {
        path?.let(::File)?.takeIf(File::exists)?.readLines()?.mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val originalUri = json.optNullableString("originalUri") ?: return@runCatching null
                ReviewRow(
                    originalUri = originalUri,
                    title = json.optNullableString("title"),
                    galleryUrl = json.optNullableString("galleryUrl"),
                    matchedImage = json.optNullableString("matchedImage"),
                )
            }.getOrNull()
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun JSONObject.optNullableString(key: String): String? = optString(key)
        .trim()
        .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun displayFilename(value: String): String = runCatching {
        Uri.parse(value).lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: "Image"
    }.getOrDefault("Image")

    private fun loadPersistedSelection(context: android.content.Context): List<Uri> = runCatching {
        val id = ReverseSearchWorker.activeWorkId(context) ?: return emptyList()
        File(context.filesDir, "reverse-search/$id.selection")
            .takeIf(File::exists)
            ?.readLines()
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank)?.let(Uri::parse) }
            .orEmpty()
    }.getOrDefault(emptyList())
}

data class ReviewRow(
    val originalUri: String,
    val title: String?,
    val galleryUrl: String?,
    val matchedImage: String?,
) {
    val id: String get() = "$originalUri|${title.orEmpty()}|${galleryUrl.orEmpty()}"
}
