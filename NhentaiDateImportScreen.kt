package exh.ui.nhentaidate

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.BatchImportStatus
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import exh.ui.batchadd.BatchImportJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

class NhentaiDateImportScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        var startDate by remember { mutableStateOf(today()) }
        var endDate by remember { mutableStateOf(today()) }
        var started by remember { mutableStateOf(false) }
        var paused by remember { mutableStateOf(BatchImportJob.isPaused(context)) }
        var excludedTags by remember { mutableStateOf("") }

        Scaffold { contentPadding ->
            Column(
                modifier = Modifier.padding(contentPadding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("nhentai Books Imported", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Import every nhentai(all) book uploaded on one date or across an inclusive date range. Requests run in the background with the same safe pacing as Batch Add.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Start date: $startDate", style = MaterialTheme.typography.titleMedium)
                Text("End date: $endDate", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { val now = today(); startDate = now; endDate = now }) {
                        Icon(Icons.Outlined.Today, contentDescription = null)
                        Text(" Today")
                    }
                    OutlinedButton(onClick = {
                        showDatePicker(context, startDate) { startDate = it }
                    }) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Text(" Start date")
                    }
                    OutlinedButton(onClick = {
                        showDatePicker(context, endDate) { endDate = it }
                    }) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Text(" End date")
                    }
                }
                OutlinedTextField(
                    value = excludedTags,
                    onValueChange = { excludedTags = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Exclude tags") },
                    placeholder = { Text("yaoi, futanari") },
                    supportingText = { Text("Comma-separated tags will not be added") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !started && startDate <= endDate,
                    onClick = {
                        started = true
                        NhentaiDateImportWorker.start(context, startDate, endDate, excludedTags)
                    },
                ) {
                    Text(if (started) "Preparing import…" else "Add books from $startDate to $endDate")
                }
                if (started || paused) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                BatchImportJob.pause(context)
                                paused = true
                            },
                        ) {
                            Icon(Icons.Outlined.Pause, contentDescription = null)
                            Text(" Pause")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                BatchImportJob.resume(context)
                                paused = false
                            },
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Text(" Resume")
                        }
                    }
                }
                if (startDate > endDate) {
                    Text("The end date must be on or after the start date.", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "A reminder is scheduled daily at 9:00 PM local time. It will remind you to open this page and import that day’s books.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    private fun formatDate(year: Int, month: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)

    private fun showDatePicker(context: Context, initial: String, onDateSelected: (String) -> Unit) {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(initial) ?: Calendar.getInstance().time
        val calendar = Calendar.getInstance().apply { time = parsed }
        DatePickerDialog(
            context,
            { _, year, month, day -> onDateSelected(formatDate(year, month, day)) },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }
}

class NhentaiDateImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val status: BatchImportStatus = Injekt.get()
        status.begin(total = 1)
        val startDate = inputData.getString(KEY_START_DATE) ?: return Result.failure()
        val endDate = inputData.getString(KEY_END_DATE) ?: return Result.failure()
        val excludedTags = inputData.getString(KEY_EXCLUDED_TAGS).orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank).distinct()
        if (startDate > endDate) return Result.failure()
        return runCatching {
            val urls = fetchGalleryUrls(startDate, endDate, excludedTags)
            if (urls.isEmpty()) {
                status.restore(0, 0, 0, 0, emptyList(), running = false)
                return Result.success()
            }
            BatchImportJob.start(applicationContext, urls)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private suspend fun fetchGalleryUrls(startDate: String, endDate: String, excludedTags: List<String>): List<String> {
        val client = Injekt.get<NetworkHelper>().client
        val all = LinkedHashSet<String>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val start = dateFormat.parse(startDate) ?: return emptyList()
        val end = dateFormat.parse(endDate) ?: return emptyList()
        val now = System.currentTimeMillis()
        val lowerBoundDays = ((now - start.time) / DAY_MS).coerceAtLeast(0L)
        val tagQuery = excludedTags.joinToString(" ") { "-Tags:$it" }
        var page = 1
        var nextRequestAt = 0L
        suspend fun awaitRequestSlot() {
            val now = System.currentTimeMillis()
            val waitFor = (nextRequestAt - now).coerceAtLeast(0L)
            nextRequestAt = maxOf(now, nextRequestAt) + REQUEST_INTERVAL_MS
            if (waitFor > 0) delay(waitFor)
        }
        while (page <= MAX_PAGES) {
            val query = "Uploaded:>${lowerBoundDays}d $tagQuery".trim()
            val url = "https://nhentai.net/api/v2/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("sort", "date")
                .addQueryParameter("page", page.toString())
                .build()
            awaitRequestSlot()
            val response = client.newCall(GET(url)).execute()
            if (response.code == 404) break
            if (response.code == 429) {
                response.close()
                delay(60_000L)
                continue
            }
            if (!response.isSuccessful) {
                response.close()
                break
            }
            val json = JSONObject(response.use { it.body.string() })
            val results = json.optJSONArray("result") ?: break
            if (results.length() == 0) break
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= 0L) continue
                val details = fetchGalleryDetails(client, id, ::awaitRequestSlot) ?: continue
                val uploaded = details.optLong("upload_date", 0L) * 1000L
                val tags = details.optJSONArray("tags")
                val hasExcludedTag = excludedTags.any { excluded ->
                    (0 until (tags?.length() ?: 0)).any { tagIndex ->
                        tags?.optJSONObject(tagIndex)?.optString("name").equals(excluded, ignoreCase = true)
                    }
                }
                if (!hasExcludedTag && uploaded in start.time..(end.time + DAY_MS - 1L)) {
                    all += "https://nhentai.net/g/$id/"
                }
            }
            val pages = json.optInt("num_pages", page)
            if (page >= pages) break
            page++
            delay(REQUEST_INTERVAL_MS)
        }
        return all.toList()
    }

    private suspend fun fetchGalleryDetails(
        client: okhttp3.OkHttpClient,
        id: Long,
        awaitRequestSlot: suspend () -> Unit,
    ): JSONObject? {
        repeat(3) { attempt ->
            awaitRequestSlot()
            val response = client.newCall(GET("https://nhentai.net/api/v2/galleries/$id".toHttpUrl())).execute()
            when {
                response.code == 404 -> { response.close(); return null }
                response.code == 429 -> {
                    response.close()
                    delay((60_000L shl attempt).coerceAtMost(600_000L))
                }
                response.isSuccessful -> return JSONObject(response.use { it.body.string() })
                else -> response.close()
            }
            delay(REQUEST_INTERVAL_MS)
        }
        return null
    }

    companion object {
        private const val KEY_START_DATE = "start_date"
        private const val KEY_END_DATE = "end_date"
        private const val MAX_PAGES = 400
        private const val DAY_MS = 86_400_000L
        private const val REQUEST_INTERVAL_MS = 4_000L
        private const val KEY_EXCLUDED_TAGS = "excluded_tags"
        private const val TAG = "nhentai-date-import"

        fun start(context: Context, startDate: String, endDate: String = startDate, excludedTags: String = "") {
            val request = OneTimeWorkRequestBuilder<NhentaiDateImportWorker>()
                .setInputData(androidx.work.workDataOf(KEY_START_DATE to startDate, KEY_END_DATE to endDate, KEY_EXCLUDED_TAGS to excludedTags))
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class NhentaiDailyReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        applicationContext.notify(
            Notifications.ID_NHENTAI_DAILY_REMINDER,
            applicationContext.notificationBuilder(Notifications.CHANNEL_NHENTAI_REMINDER) {
                setSmallIcon(R.drawable.ic_komikku)
                setContentTitle("nhentai books of the day")
                setContentText("Open More → nhentai Books Imported to add today’s books")
                setAutoCancel(true)
            }.build(),
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "nhentai-daily-reminder"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val initialDelay = (next.timeInMillis - now.timeInMillis).coerceAtLeast(1L)
            val request = PeriodicWorkRequestBuilder<NhentaiDailyReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

