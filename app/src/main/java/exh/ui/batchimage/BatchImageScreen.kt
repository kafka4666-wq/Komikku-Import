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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import eu.kanade.tachiyomi.data.BatchImportStatus
import exh.ui.batchadd.BatchImportJob
import kotlinx.coroutines.flow.collectLatest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStreamWriter
import java.util.UUID

class BatchImageScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        val codeImportStatus = remember { Injekt.get<BatchImportStatus>() }
        val codeImportSnapshot by codeImportStatus.state.collectAsState()
        var codeImportWorkId by remember { mutableStateOf(BatchImportJob.savedJobId(context)) }
        var codeImportWorkInfo by remember { mutableStateOf<WorkInfo?>(null) }
        var codeImportStartPending by remember { mutableStateOf(false) }
        var pendingSelections by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
        var jobIdText by remember { mutableStateOf(BatchImageWorker.savedJobId(context)) }
        var workInfo by remember { mutableStateOf<WorkInfo?>(null) }
        var records by remember { mutableStateOf<List<BatchImageRecord>>(emptyList()) }
        var previewRecord by remember { mutableStateOf<BatchImageRecord?>(null) }
        var editingRecord by remember { mutableStateOf<BatchImageRecord?>(null) }
        var editedTitle by remember { mutableStateOf("") }
        var pendingLinkExport by remember { mutableStateOf("") }
        var pendingTitleExport by remember { mutableStateOf("") }
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

        val titleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { it.write(pendingTitleExport) }
                    } ?: error("Could not open selected file")
                    Toast.makeText(context, "Saved recognized titles", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(context, "Could not save titles: ${it.message}", Toast.LENGTH_LONG).show() }
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

        LaunchedEffect(Unit) {
            // Cancel work queued by an older app version; recognized titles are now
            // exported to text, while selected nhentai codes remain importable.
            manager.cancelAllWorkByTag(BatchImageSearchWorker.TAG)
        }

        LaunchedEffect(codeImportWorkId) {
            codeImportWorkInfo = null
            val id = codeImportWorkId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collectLatest { codeImportWorkInfo = it }
        }

        LaunchedEffect(codeImportWorkInfo?.state, codeImportSnapshot.running) {
            if (codeImportWorkInfo?.state != null || codeImportSnapshot.running) codeImportStartPending = false
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
        val selectionPlan = BatchImageSelectionPlan.from(selectedRecords)
        val titleRecords = selectionPlan.titleRows
        val explicitCodes = selectionPlan.codeRows
        val selectedCodeUrls = selectionPlan.codeUrls
        val isCodeImportRunning = codeImportWorkInfo?.state == WorkInfo.State.RUNNING ||
            codeImportWorkInfo?.state == WorkInfo.State.ENQUEUED || codeImportWorkInfo?.state == WorkInfo.State.BLOCKED || codeImportSnapshot.running || codeImportStartPending
        val canImportCodes = selectedCodeUrls.isNotEmpty() && !isCodeImportRunning
        val detectedLinks = records.flatMap { it.links }.distinct()
        val recognizedTitles = BatchImageTitleExport.titles(records)
        val codeImportOutput = codeImportWorkInfo?.outputData
        val codeImportProgress = codeImportWorkInfo?.progress
        val codeCompleted = codeImportProgress?.getInt(BatchImportJob.KEY_COMPLETED, codeImportOutput?.getInt(BatchImportJob.KEY_COMPLETED, codeImportSnapshot.completed) ?: codeImportSnapshot.completed)
            ?: codeImportOutput?.getInt(BatchImportJob.KEY_COMPLETED, codeImportSnapshot.completed) ?: codeImportSnapshot.completed
        val codeTotal = codeImportProgress?.getInt(BatchImportJob.KEY_TOTAL, codeImportOutput?.getInt(BatchImportJob.KEY_TOTAL, codeImportSnapshot.total) ?: codeImportSnapshot.total)
            ?: codeImportOutput?.getInt(BatchImportJob.KEY_TOTAL, codeImportSnapshot.total) ?: codeImportSnapshot.total
        val codeAdded = codeImportProgress?.getInt(BatchImportJob.KEY_ADDED, codeImportOutput?.getInt(BatchImportJob.KEY_ADDED, codeImportSnapshot.added) ?: codeImportSnapshot.added)
            ?: codeImportOutput?.getInt(BatchImportJob.KEY_ADDED, codeImportSnapshot.added) ?: codeImportSnapshot.added
        val codeFailed = codeImportProgress?.getInt(BatchImportJob.KEY_FAILED, codeImportOutput?.getInt(BatchImportJob.KEY_FAILED, codeImportSnapshot.failed) ?: codeImportSnapshot.failed)
            ?: codeImportOutput?.getInt(BatchImportJob.KEY_FAILED, codeImportSnapshot.failed) ?: codeImportSnapshot.failed

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
                        "Scan screenshots on-device with ML Kit (English and Japanese), review the extracted results, save recognized titles and links as .txt files, or import selected nhentai codes.",
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
                            Button(enabled = recognizedTitles.isNotEmpty(), onClick = {
                                pendingTitleExport = BatchImageTitleExport.fileContents(records)
                                titleFilePicker.launch("Batch_Image_Titles.txt")
                            }, modifier = Modifier.weight(1f)) { Text("Save titles .txt (${recognizedTitles.size})") }
                        }
                    }
                    item {
                        Button(
                            enabled = canImportCodes,
                            onClick = {
                                codeImportStartPending = true
                                codeImportWorkId = BatchImportJob.start(context.applicationContext, selectedCodeUrls).toString()
                                codeImportWorkInfo = null
                                Toast.makeText(context, "Started ${selectedCodeUrls.size} direct nhentai code import(s)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) { Text("Import selected nhentai codes (${selectedCodeUrls.size})") }
                    }
                    item {
                        Text(
                            "Selected: ${selectedRecords.size} review rows · ${titleRecords.size} title rows (all ${recognizedTitles.size} recognized titles are saved to the .txt file) · ${explicitCodes.size} nhentai code rows (${selectedCodeUrls.size} unique direct imports) · ${selectionPlan.otherRows.size} other rows stay in review. The links .txt action exports all ${detectedLinks.size} links in this scan.",
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (codeTotal > 0) {
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        if (isCodeImportRunning) {
                                            "Direct code imports $codeCompleted/$codeTotal processed · $codeAdded added · $codeFailed failed"
                                        } else {
                                            "Direct code import results · $codeCompleted/$codeTotal processed · $codeAdded added · $codeFailed failed"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (isCodeImportRunning) LinearProgressIndicator(progress = { codeCompleted.toFloat() / codeTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                                    if (codeImportSnapshot.currentUrl.isNotBlank()) Text(codeImportSnapshot.currentUrl, style = MaterialTheme.typography.bodySmall)
                                    codeImportSnapshot.events.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (isCodeImportRunning) TextButton(onClick = { BatchImportJob.cancel(context.applicationContext) }) { Text("Cancel direct code imports") }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "Recognized titles are saved to the .txt file you choose; they are not searched or imported. Selected nhentai codes can still be imported directly, and detected web links can be saved separately.",
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
                            onPreview = { previewRecord = record },
                            onEdit = { editingRecord = record; editedTitle = record.title.orEmpty() },
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
        editingRecord?.let { record ->
            AlertDialog(
                onDismissRequest = { editingRecord = null },
                title = { Text("Edit title for .txt export") },
                text = {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        label = { Text("Title to include in Batch_Image_Titles.txt") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val corrected = editedTitle.trim()
                        records = records.map { if (it.id == record.id) it.copy(title = corrected.takeIf(String::isNotBlank)) else it }
                        BatchImageWorker.saveRecords(BatchImageWorker.recordsFile(context), records)
                        editingRecord = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editingRecord = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun BatchImageReviewCard(
    record: BatchImageRecord,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
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
                    Text("Candidate score: ${record.confidence}/100", style = MaterialTheme.typography.labelSmall)
                }
            }
            AsyncImage(
                model = Uri.parse(record.imageUri),
                contentDescription = "Source screenshot",
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!record.title.isNullOrBlank()) TextButton(onClick = onEdit) { Text("Edit title") }
                TextButton(onClick = onPreview) { Text("Preview") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
