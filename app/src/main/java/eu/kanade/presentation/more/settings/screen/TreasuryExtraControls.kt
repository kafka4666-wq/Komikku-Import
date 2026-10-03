package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.kanade.domain.ui.KodamiTreasuryPreferences
import eu.kanade.presentation.more.KodamiProfileAvatar
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
    val protectedIdsText by preferences.dailyCacheProtectedMangaIds().collectAsState()
    val cacheStatus by preferences.dailyCacheStatus().collectAsState()
    val scope = rememberCoroutineScope()
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var cachedTitles by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                enabled = !hasCacheToday,
                onClick = { showConfirmation = true },
            ) {
                Text(if (hasCacheToday) "Cached" else "Cache now")
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

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Cache 30 random titles?") },
            text = {
                Text(
                    "This queues all available chapters for up to 30 random titles in your library. It can use substantial storage and mobile data. Existing downloads are kept. Chapters added by this cache are removed at local midnight.",
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
