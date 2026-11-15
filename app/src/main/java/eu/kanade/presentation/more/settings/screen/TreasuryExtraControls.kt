package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.kanade.domain.ui.KodamiTreasuryPreferences
import eu.kanade.presentation.more.KodamiProfileAvatar
import eu.kanade.tachiyomi.data.download.dailycache.DailyCacheHistoryEntry
import eu.kanade.tachiyomi.data.download.dailycache.DailyOfflineCacheJob
import eu.kanade.tachiyomi.data.download.dailycache.DailyOfflineCacheManager
import java.time.LocalDate
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun ProfilePhotoPreference(preferences: KodamiTreasuryPreferences) {
    val context = LocalContext.current
    val name by preferences.profileName().collectAsState()
    val frame by preferences.avatarFrame().collectAsState()
    val photoUri by preferences.profilePhotoUri().collectAsState()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            preferences.profilePhotoUri().set(uri.toString())
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KodamiProfileAvatar(name = name, photoUri = photoUri, frame = frame, size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Choose a photo from this device", fontSize = 14.sp)
            Text("The app keeps a read-only photo URI", fontSize = 12.sp)
        }
        Button(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        ) {
            Text("Choose")
        }
        if (photoUri.isNotBlank()) {
            OutlinedButton(onClick = { preferences.profilePhotoUri().set("") }) {
                Text("Remove")
            }
        }
    }
}

@Composable
internal fun DailyOfflineCachePreference(preferences: KodamiTreasuryPreferences) {
    val context = LocalContext.current
    val cacheDate by preferences.dailyCacheDate().collectAsState()
    val cacheIds by preferences.dailyCacheMangaIds().collectAsState()
    val historyRevision by preferences.dailyCacheHistoryRevision().collectAsState()
    val protectedIdsText by preferences.dailyCacheProtectedMangaIds().collectAsState()
    val cacheStatus by preferences.dailyCacheStatus().collectAsState()
    val scope = rememberCoroutineScope()
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var cachedTitles by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
    var history by remember { mutableStateOf(emptyList<DailyCacheHistoryEntry>()) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val cachedCount = remember(cacheIds) {
        cacheIds.split(',').mapNotNull(String::toLongOrNull).distinct().size
    }
    val hasCacheToday = cacheDate == LocalDate.now().toString() && cachedCount > 0
    val protectedIds = remember(protectedIdsText) { protectedIdsText.split(',').mapNotNull(String::toLongOrNull).toSet() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { DailyOfflineCacheManager(context).expireIfNeeded() }
    }
    LaunchedEffect(cacheIds) {
        val ids = cacheIds.split(',').mapNotNull(String::toLongOrNull).distinct()
        cachedTitles = withContext(Dispatchers.IO) {
            val getManga: GetManga = Injekt.get()
            ids.map { id -> id to (getManga.await(id)?.title ?: "Manga $id") }
        }
    }
    LaunchedEffect(historyRevision, cacheDate, cacheIds) {
        history = withContext(Dispatchers.IO) { DailyOfflineCacheManager(context).getHistory() }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Today") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("History") })
        }
        if (selectedTab == 1) {
            if (history.isEmpty()) {
                Text("No cache history yet.", fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                val historyByDate = remember(history) {
                    history.groupBy { it.date }.toList().sortedByDescending { it.first }
                }
                Text("Cached titles by date", fontSize = 13.sp)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    items(historyByDate, key = { it.first }) { (date, entries) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                formatCacheHistoryDate(date),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                            entries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AsyncImage(
                                        model = entry.thumbnailUrl.takeIf(String::isNotBlank),
                                        contentDescription = entry.title,
                                        modifier = Modifier.size(width = 52.dp, height = 72.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Cached on ${formatCacheHistoryDate(date)}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (hasCacheToday) "Today's cache: $cachedCount titles" else "Cache 30 random titles",
                        fontSize = 14.sp,
                    )
                    Text(
                        if (hasCacheToday) "Expires at local midnight" else "Queues every available chapter for offline reading",
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { showConfirmation = true },
                ) {
                    Text(if (hasCacheToday) "Cache 30 more" else "Cache now")
                }
            }
            val visibleStatus = cacheStatus.ifBlank { status }
            if (visibleStatus.isNotBlank()) Text(visibleStatus, fontSize = 12.sp)
            if (cachedCount > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { DailyOfflineCacheManager(context).expireIfNeeded() }
                            status = "Expired unmarked cache chapters cleaned up; protected titles were kept."
                        }
                    },
                ) { Text("Clean expired cache now") }
            }
            if (cachedTitles.isNotEmpty()) {
                Text("Keep cached overnight", fontSize = 14.sp)
                Text("Choose titles that should survive the midnight cleanup without adding them to your library.", fontSize = 12.sp)
                cachedTitles.forEach { (id, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(
                            onClick = {
                                val next = if (id in protectedIds) protectedIds - id else protectedIds + id
                                preferences.dailyCacheProtectedMangaIds().set(next.joinToString(","))
                            },
                        ) { Text(if (id in protectedIds) "Keep on" else "Keep") }
                    }
                }
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(if (hasCacheToday) "Cache up to 30 more titles?" else "Cache up to 30 random titles?") },
            text = {
                Text(
                    "This queues all available chapters for up to 30 library titles not already included in this cache period. You can run it again for another batch. It can use substantial storage and mobile data. Existing downloads are kept. Chapters added by these cache runs are removed at local midnight unless you choose Keep.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        status = if (DailyOfflineCacheJob.enqueue(context)) {
                            "Cache job started. Track chapter downloads in the download queue."
                        } else {
                            "A cache job is already running."
                        }
                    },
                ) { Text("Start cache") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatCacheHistoryDate(value: String): String = runCatching {
    java.time.LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))
}.getOrDefault(value)
