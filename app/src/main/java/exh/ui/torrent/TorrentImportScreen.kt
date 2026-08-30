package exh.ui.torrent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.data.TorrentImportStatus
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.torrent.TorrentImportControl
import eu.kanade.tachiyomi.torrent.TorrentStreamManager
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TorrentImportScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val manager = remember { Injekt.get<TorrentStreamManager>() }
        val importStatus = remember { Injekt.get<TorrentImportStatus>() }
        val torrentState by importStatus.state.collectAsState()
        val scope = rememberCoroutineScope()
        var link by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var paused by remember { mutableStateOf(TorrentImportControl.isPaused(context)) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }

        fun requestNotificationsIfNeeded() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        fun importTorrent() {
            if (torrentState.running || link.isBlank()) return
            paused = false
            TorrentImportControl.reset(context)
            importStatus.begin(link.trim().takeLast(80))
            status = "Starting Torrent import…"
            requestNotificationsIfNeeded()
            TorrentImportWorker.start(context, link.trim())
        }

        Scaffold { contentPadding ->
            Column(
                modifier = Modifier.padding(contentPadding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.GetApp, contentDescription = null)
                    Text("Torrent", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "Paste a Sukebei/Nyaa detail page, magnet, or direct .torrent URL. Komikku reads the torrent file list first, adds supported CBZ/ZIP items to the library, and fetches only the selected archive while you read. Filename and embedded metadata are used without inventing external details.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Sukebei/Nyaa, magnet, or .torrent link") },
                    placeholder = { Text("https://sukebei.nyaa.si/view/4051004") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !torrentState.running && link.isNotBlank(),
                    onClick = ::importTorrent,
                ) {
                    Text(if (torrentState.running) "Adding torrent books…" else "Add all books to library")
                }
                if (torrentState.running || torrentState.total > 0) {
                    Text(
                        "${torrentState.completed}/${torrentState.total} processed • ${torrentState.added} added • ${torrentState.failed} failed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    torrentState.currentTitle.takeIf { it.isNotBlank() }?.let {
                        Text("Current: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = torrentState.running && !paused,
                        onClick = { paused = true; TorrentImportControl.pause(context); scope.launch { manager.pauseAll() } },
                    ) {
                        Icon(Icons.Outlined.Pause, contentDescription = null)
                        Text(" Pause")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = torrentState.running && paused,
                        onClick = { paused = false; TorrentImportControl.resume(context); scope.launch { manager.resumeAll() } },
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(" Resume")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = torrentState.running,
                        onClick = {
                            TorrentImportWorker.cancel(context)
                            paused = false
                            importStatus.fail("Torrent import canceled")
                            status = "Torrent import canceled."
                            scope.launch { manager.pauseAll() }
                        },
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                        Text(" Cancel")
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !torrentState.running,
                    onClick = { scope.launch { manager.clearTemporaryCache(); status = "Temporary torrent cache cleared." } },
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Text(" Clear temporary stream cache")
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
                torrentState.message.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Streaming requires network data and a bounded temporary cache. It does not permanently download the complete torrent, but the currently read CBZ/ZIP may be cached temporarily. Metadata that cannot be derived from the torrent filename is not silently fabricated.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
