package exh.ui.batchimage

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.work.WorkInfo
import eu.kanade.tachiyomi.util.system.workManager
import coil3.compose.AsyncImage
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import exh.ui.batchadd.BatchImportJob
import kotlinx.coroutines.flow.collectLatest
import java.io.OutputStreamWriter
import java.util.UUID

class BatchImageScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        var pendingSelections by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
        var jobIdText by remember { mutableStateOf(BatchImageWorker.savedJobId(context)) }
        var sourceSearchId by remember { mutableStateOf(BatchImageSearchWorker.savedJobId(context)) }
        var workInfo by remember { mutableStateOf<WorkInfo?>(null) }
        var sourceSearchInfo by remember { mutableStateOf<WorkInfo?>(null) }
        var records by remember { mutableStateOf<List<BatchImageRecord>>(emptyList()) }
        var previewRecord by remember { mutableStateOf<BatchImageRecord?>(null) }
        var pendingLinkExport by remember { mutableStateOf("") }
        val selectedIds = remember { mutableStateListOf<String>() }

        val linkFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { it.write(pendingLinkExport) }
                    } ?: error("Could not open selected file")
                    Toast.makeText(context, "Saved screenshot links", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(context, "Could not save links: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }

        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val persisted = uris.mapNotNull { uri ->
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    uri
                }
                pendingSelections = (pendingSelections + persisted.map { "image" to it }).distinctBy { it.second }
            }
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                pendingSelections = (pendingSelections + listOf("tree" to uri)).distinctBy { it.second }
            }
        }

        LaunchedEffect(jobIdText) {
            workInfo = null
            val id = jobIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collectLatest { info ->
                workInfo = info
                if (info?.state?.isFinished == true) {
                    records = BatchImageWorker.readRecords(BatchImageWorker.recordsFile(context))
                    selectedIds.clear()
                }
            }
        }

        LaunchedEffect(sourceSearchId) {
            sourceSearchInfo = null
            val id = sourceSearchId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collectLatest { sourceSearchInfo = it }
        }

        fun startScan() {
            if (pendingSelections.isEmpty()) {
                Toast.makeText(context, "Choose a folder or images first", Toast.LENGTH_SHORT).show()
                return
            }
            val id = BatchImageWorker.enqueue(context.applicationContext, pendingSelections)
            jobIdText = id.toString()
            workInfo = null
            records = emptyList()
            selectedIds.clear()
        }

        val isRunning = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED || workInfo?.state == WorkInfo.State.BLOCKED
        val progress = workInfo?.progress
        val completed = progress?.getInt(BatchImageWorker.KEY_COMPLETED, 0) ?: 0
        val total = progress?.getInt(BatchImageWorker.KEY_TOTAL, 0) ?: 0
        val discovered = progress?.getInt(BatchImageWorker.KEY_DISCOVERED, 0) ?: 0
        val phase = progress?.getString(BatchImageWorker.KEY_PHASE).orEmpty()
        val selectedRecords = records.filter { it.id in selectedIds }
        val titleRecords = selectedRecords.filter { !it.title.isNullOrBlank() }
        val explicitCodes = selectedRecords.filter { !it.code.isNullOrBlank() && it.link.isNullOrBlank() }
        val isSourceSearchRunning = sourceSearchInfo?.state == WorkInfo.State.RUNNING ||
            sourceSearchInfo?.state == WorkInfo.State.ENQUEUED || sourceSearchInfo?.state == WorkInfo.State.BLOCKED
        val detectedLinks = records.flatMap { it.links }.distinct()
        val selectedQuery = titleRecords.firstOrNull()?.title?.let { BatchImageTextExtractor.searchQueries(it).firstOrNull() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Batch Image") },
                    navigationIcon = {
                        TextButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Scan screenshots on-device with ML Kit (English and Japanese), review the extracted title, artist, code, or link, then search or import only what you select.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !isRunning, onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                            Text(" Folder")
                        }
                        Button(enabled = !isRunning, onClick = { imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                            Text(" Images")
                        }
                    }
                }
                if (pendingSelections.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${pendingSelections.size} selection${if (pendingSelections.size == 1) "" else "s"} ready", style = MaterialTheme.typography.titleSmall)
                                Text("Folders are scanned recursively. You can add more folders or individual images.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !isRunning, onClick = ::startScan) { Text("Start scanning") }
                                    TextButton(enabled = !isRunning, onClick = { pendingSelections = emptyList() }) { Text("Clear") }
                                }
                            }
                        }
                    }
                }
                if (jobIdText != null) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (isRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            when {
                                                isRunning && total > 0 -> "Processing $completed/$total…"
                                                isRunning && discovered > 0 -> "Finding images… $discovered found"
                                                isRunning -> "Finding images…"
                                                workInfo?.state == WorkInfo.State.SUCCEEDED -> "Scan complete · ${records.size} review result${if (records.size == 1) "" else "s"}"
                                                workInfo?.state == WorkInfo.State.CANCELLED -> "Scan cancelled · ${records.size} partial result${if (records.size == 1) "" else "s"}"
                                                workInfo?.state == WorkInfo.State.FAILED -> "Scan failed · ${records.size} partial result${if (records.size == 1) "" else "s"}"
                                                else -> "Batch Image scan"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        if (isRunning && phase.isNotBlank()) Text(phase, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (isRunning) TextButton(onClick = { manager.cancelWorkById(UUID.fromString(jobIdText)) }) { Text("Cancel") }
                                }
                                if (isRunning && total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                                else if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (records.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Review ${records.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (selectedIds.size == records.size) selectedIds.clear() else {
                                    selectedIds.clear(); selectedIds.addAll(records.map { it.id })
                                }
                            }) { Icon(Icons.Outlined.Checklist, contentDescription = null); Text(" Select all") }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = detectedLinks.isNotEmpty(), onClick = {
                                pendingLinkExport = detectedLinks.joinToString("\n", postfix = "\n")
                                linkFilePicker.launch("Batch_Image_Links.txt")
                            }, modifier = Modifier.weight(1f)) { Text("Save links .txt (${detectedLinks.size})") }
                            Button(enabled = selectedQuery != null || explicitCodes.isNotEmpty(), onClick = {
                                if (explicitCodes.isNotEmpty()) {
                                    val codeUrls = explicitCodes.mapNotNull { record -> record.code?.let { "https://nhentai.net/g/$it/" } }.distinct()
                                    if (codeUrls.isNotEmpty()) BatchImportJob.start(context.applicationContext, codeUrls)
                                }
                                if (titleRecords.isNotEmpty()) {
                                    sourceSearchId = BatchImageSearchWorker.enqueue(context.applicationContext, titleRecords).toString()
                                    sourceSearchInfo = null
                                }
                                Toast.makeText(context, "Started search/import for ${titleRecords.size} title(s) and ${explicitCodes.size} code(s)", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                                Text("Search + import")
                            }
                        }
                    }
                    if (sourceSearchId != null) {
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val searchProgress = sourceSearchInfo?.progress
                                    val done = searchProgress?.getInt(BatchImageSearchWorker.KEY_COMPLETED, 0)
                                        ?: sourceSearchInfo?.outputData?.getInt(BatchImageSearchWorker.KEY_COMPLETED, 0) ?: 0
                                    val totalSearch = searchProgress?.getInt(BatchImageSearchWorker.KEY_TOTAL, 0)
                                        ?: sourceSearchInfo?.outputData?.getInt(BatchImageSearchWorker.KEY_TOTAL, 0) ?: titleRecords.size
                                    val added = sourceSearchInfo?.outputData?.getInt(BatchImageSearchWorker.KEY_ADDED, 0)
                                        ?: searchProgress?.getInt(BatchImageSearchWorker.KEY_ADDED, 0) ?: 0
                                    val present = sourceSearchInfo?.outputData?.getInt(BatchImageSearchWorker.KEY_ALREADY_PRESENT, 0)
                                        ?: searchProgress?.getInt(BatchImageSearchWorker.KEY_ALREADY_PRESENT, 0) ?: 0
                                    val unmatched = sourceSearchInfo?.outputData?.getInt(BatchImageSearchWorker.KEY_UNMATCHED, 0)
                                        ?: searchProgress?.getInt(BatchImageSearchWorker.KEY_UNMATCHED, 0) ?: 0
                                    val phase = searchProgress?.getString(BatchImageSearchWorker.KEY_PHASE).orEmpty()
                                    Text(
                                        when {
                                            isSourceSearchRunning -> "Searching installed sources $done/$totalSearch…"
                                            sourceSearchInfo?.state == WorkInfo.State.SUCCEEDED -> "Source search complete · $added added · $present already in library · $unmatched need review"
                                            sourceSearchInfo?.state == WorkInfo.State.CANCELLED -> "Source search cancelled · $added added so far"
                                            sourceSearchInfo?.state == WorkInfo.State.FAILED -> "Source search failed · $added added so far"
                                            else -> "Installed-source search queued"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (isSourceSearchRunning && phase.isNotBlank()) Text(phase, style = MaterialTheme.typography.bodySmall)
                                    if (isSourceSearchRunning && totalSearch > 0) {
                                        LinearProgressIndicator(progress = { done.toFloat() / totalSearch.toFloat() }, modifier = Modifier.fillMaxWidth())
                                        TextButton(onClick = { manager.cancelWorkById(UUID.fromString(sourceSearchId)) }) { Text("Cancel source search") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "Search uses title keywords without artist names. Selected nhentai codes are imported through Batch Add; detected web links are saved only to the .txt file you choose.",
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                    items(records, key = { it.id }) { record ->
                        BatchImageReviewCard(
                            record = record,
                            selected = record.id in selectedIds,
                            onToggle = {
                                if (record.id in selectedIds) selectedIds.remove(record.id) else selectedIds.add(record.id)
                            },
                            onSearch = {
                                val query = record.title?.let { BatchImageTextExtractor.searchQueries(it).firstOrNull() }.orEmpty()
                                if (query.isNotBlank()) navigator?.push(GlobalSearchScreen(query))
                            },
                            onPreview = { previewRecord = record },
                            onRemove = {
                                records = records.filterNot { it.id == record.id }
                                selectedIds.remove(record.id)
                                BatchImageWorker.saveRecords(BatchImageWorker.recordsFile(context), records)
                            },
                        )
                    }
                } else if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                    item {
                        Text("No title, artist, nhentai code, or link was recognized. Try another screenshot or folder.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item { Text("Folders and images stay on this device during OCR. Results are kept locally for review.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall) }
            }
        }
        previewRecord?.let { record ->
            AlertDialog(
                onDismissRequest = { previewRecord = null },
                confirmButton = { TextButton(onClick = { previewRecord = null }) { Text("Close") } },
                title = { Text(record.title ?: record.code?.let { "nhentai #$it" } ?: "Screenshot preview") },
                text = { AsyncImage(model = Uri.parse(record.imageUri), contentDescription = "Full screenshot preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(480.dp)) },
            )
        }
    }
}

@Composable
private fun BatchImageReviewCard(
    record: BatchImageRecord,
    selected: Boolean,
    onToggle: () -> Unit,
    onSearch: () -> Unit,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(record.title ?: record.code?.let { "nhentai #$it" } ?: "Recognized link", style = MaterialTheme.typography.titleSmall)
                    record.artist?.let { Text("Artist: $it", style = MaterialTheme.typography.bodySmall) }
                    record.code?.let { Text("Code: $it", style = MaterialTheme.typography.bodySmall) }
                    record.link?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text("OCR confidence: ${record.confidence}%", style = MaterialTheme.typography.labelSmall)
                }
                if (!record.title.isNullOrBlank()) TextButton(onClick = onSearch) { Text("Search") }
            }
            AsyncImage(
                model = Uri.parse(record.imageUri),
                contentDescription = "Source screenshot",
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onPreview) { Text("Preview") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
