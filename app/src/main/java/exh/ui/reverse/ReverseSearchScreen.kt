package exh.ui.reverse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.workManager
import cafe.adriel.voyager.navigator.LocalNavigator
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

class ReverseSearchScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val manager = remember { context.applicationContext.workManager }
        var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var jobId by remember { mutableStateOf<UUID?>(null) }
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* The worker starts regardless; permission controls shade visibility. */ }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                selected = listOf(uri)
            }
        }
        LaunchedEffect(jobId) {
            val id = jobId ?: return@LaunchedEffect
            manager.getWorkInfoByIdFlow(id).collectLatest { info = it }
        }
        val progress = info?.progress
        val done = progress?.getInt(ReverseSearchWorker.KEY_COMPLETED, 0) ?: 0
        val total = progress?.getInt(ReverseSearchWorker.KEY_TOTAL, 0) ?: 0
        val found = progress?.getInt(ReverseSearchWorker.KEY_FOUND, 0) ?: 0
        val phase = progress?.getString(ReverseSearchWorker.KEY_PHASE).orEmpty()
        val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        Scaffold(topBar = { TopAppBar(title = { Text("Reverse Search") }, navigationIcon = { TextButton(onClick = { navigator?.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Select an album/folder. Each image is searched in parallel with Yandex Images. A gallery is added only when Yandex returns the image source and the returned image passes strict visual verification.", style = MaterialTheme.typography.bodyMedium)
                Button(enabled = !running, onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FolderOpen, null); Text(" Select album folder") }
                if (selected.isNotEmpty()) Text("Folder selected. Subfolders are included.", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = selected.isNotEmpty() && !running,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        jobId = ReverseSearchWorker.enqueue(context.applicationContext, selected)
                        info = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start Reverse Search") }
                if (running || info?.state?.isFinished == true) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) CircularProgressIndicator()
                        Text(when { running && total > 0 -> "Searching $done/$total images"; running -> "Discovering images…"; info?.state == WorkInfo.State.SUCCEEDED -> "Complete · $found verified Yandex match(es) queued for library import"; info?.state == WorkInfo.State.FAILED -> "Reverse search failed"; else -> "Reverse search" }, style = MaterialTheme.typography.titleMedium)
                        if (phase.isNotBlank()) Text(phase, style = MaterialTheme.typography.bodySmall)
                        if (running && total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        if (running) TextButton(onClick = { jobId?.let(manager::cancelWorkById) }) { Text("Cancel") }
                    } }
                }
                Text("Images are uploaded to Yandex Images. The importer requires a canonical nH/eH gallery URL, a non-empty Yandex title, and a strict pixel/perceptual match against Yandex's returned source image. Unverified or visually similar results are skipped.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
