package exh.ui.nhentaidate

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.BatchImportStatus
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.ui.batchadd.BatchImportJob
import exh.ui.batchadd.BatchImportRequestLimiter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* Notifications are optional; the import has already started. */ }
        fun startImport() {
            // Never gate the actual import on notification permission. On Android 13+
            // a denied/dismissed prompt previously left the button looking idle and
            // prevented the worker from ever being enqueued.
            started = true
            NhentaiDateImportWorker.start(context, startDate, endDate, excludedTags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Scaffold { contentPadding ->
            Column(
                modifier = Modifier.padding(contentPadding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Nhentai Book Import", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Find books with nhentai’s server-side date and tag filters, then add them in the background at a safe four-second interval.",
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
                    OutlinedButton(onClick = { showDatePicker(context, startDate) { startDate = it } }) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Text(" Start date")
                    }
                    OutlinedButton(onClick = { showDatePicker(context, endDate) { endDate = it } }) {
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
                    supportingText = { Text("Comma-separated tags are sent to the server and excluded before queueing") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !started && startDate <= endDate,
                    onClick = { startImport() },
                ) {
                    Text(if (started) "Adding manga…" else "Add books from $startDate to $endDate")
                }
                if (started) {
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
                            enabled = !paused,
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
                            enabled = paused,
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Text(" Resume")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                BatchImportJob.cancel(context)
                                NhentaiDateImportWorker.stop(context)
                                started = false
                                paused = false
                            },
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                            Text(" Cancel")
                        }
                    }
                    Text(
                        if (paused) "Import paused. Resume when you want additions to continue." else "Discovery and adding continue when you leave this screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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

    private fun showDatePicker(context: Context, initial: String, onDateSelected: (String) -> Unit) {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(initial) ?: Calendar.getInstance().time
        val calendar = Calendar.getInstance().apply { time = parsed }
        DatePickerDialog(
            context,
            { _, year, month, day -> onDateSelected(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)) },
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
        val startDate = inputData.getString(KEY_START_DATE) ?: return Result.failure()
        val endDate = inputData.getString(KEY_END_DATE) ?: return Result.failure()
        val excludedTags = inputData.getString(KEY_EXCLUDED_TAGS).orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank).distinct()
        if (startDate > endDate) return Result.failure()

        val queueId = inputData.getString(KEY_QUEUE_ID)
            ?: "legacy-${startDate}-${endDate}-${System.currentTimeMillis()}"
        val queueFile = File(applicationContext.cacheDir, "nhentai-import-$queueId.txt")
        val doneFile = File("${queueFile.absolutePath}.done")
        val startedFile = File("${queueFile.absolutePath}.started")
        queueFile.parentFile?.mkdirs()
        if (!queueFile.exists()) queueFile.createNewFile()
        status.begin(total = queueFile.readCompleteQueueLines().size, events = listOf("Adding manga…"))
        setForegroundSafely()
        // WorkManager can retry discovery after a transient error. Keep the original
        // queue worker alive instead of replacing it and losing already discovered URLs.
        if (startedFile.createNewFile()) {
            BatchImportJob.startFromFile(applicationContext, queueFile)
        }

        return try {
            val found = fetchGalleryUrls(startDate, endDate, excludedTags, queueFile)
            doneFile.writeText("done")
            if (found == 0) {
                status.restore(0, 0, 0, 0, listOf("No nhentai books matched the selected date range."), running = false)
                applicationContext.cancelNotification(Notifications.ID_BATCH_IMPORT_PROGRESS)
            }
            Result.success()
        } catch (error: Throwable) {
            // Do not create .done here. The queue worker must continue draining URLs
            // already discovered while WorkManager retries discovery.
            status.restore(
                queueFile.readCompleteQueueLines().size,
                0,
                0,
                1,
                listOf("[RETRYING] nhentai date discovery — ${error.message.orEmpty()}"),
                running = true,
            )
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_BATCH_IMPORT_PROGRESS,
        buildInitialAddingNotification(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun buildInitialAddingNotification() = applicationContext.notificationBuilder(Notifications.CHANNEL_BATCH_IMPORT_PROGRESS) {
        setSmallIcon(R.drawable.ic_komikku)
        setContentTitle("Adding manga")
        setContentText("Adding recognized books in the background")
        setOngoing(true)
        setOnlyAlertOnce(true)
        setAutoCancel(false)
        addAction(R.drawable.ic_pause_24dp, "Pause", NotificationReceiver.pauseBatchImportPendingBroadcast(applicationContext))
        addAction(R.drawable.ic_play_arrow_24dp, "Resume", NotificationReceiver.resumeBatchImportPendingBroadcast(applicationContext))
        addAction(R.drawable.ic_close_24dp, "Cancel", NotificationReceiver.cancelBatchImportPendingBroadcast(applicationContext))
    }.build()

    private suspend fun fetchGalleryUrls(
        startDate: String,
        endDate: String,
        excludedTags: List<String>,
        queueFile: File,
    ): Int {
        val client = Injekt.get<NetworkHelper>().client
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val start = dateFormat.parse(startDate) ?: return 0
        val end = dateFormat.parse(endDate) ?: return 0
        val now = System.currentTimeMillis()
        if (start.time > now) return 0

        val startAgeDays = ((now - start.time) / DAY_MS).coerceAtLeast(0L)
        val endAgeDays = ((now - end.time) / DAY_MS).coerceAtLeast(0L)
        val upperBound = (startAgeDays + 1L).coerceAtLeast(2L)
        val filters = mutableListOf("uploaded:<${upperBound}d")
        if (end.time < now - (2L * DAY_MS)) {
            filters += "uploaded:>${endAgeDays.coerceAtLeast(2L)}d"
        }
        filters += excludedTags.map { "-tags:$it" }
        val query = filters.joinToString(" ")
        // A WorkManager retry must resume the same queue rather than starting
        // from an empty set and risking loss of already discovered pages.
        val all = LinkedHashSet<String>(queueFile.readCompleteQueueLines())
        var page = 1
        var totalPages = MAX_PAGES
        var transientFailures = 0

        while (page <= totalPages && page <= MAX_PAGES) {
            BatchImportRequestLimiter.await()
            val url = "https://nhentai.net/api/v2/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("sort", "date")
                .addQueryParameter("page", page.toString())
                .build()
            val response = client.newCall(GET(url)).execute()
            if (response.code == 429) {
                response.close()
                delay(60_000L)
                continue
            }
            if (response.code == 404) break
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 408 || code == 425 || code in 500..599) {
                    transientFailures++
                    if (transientFailures <= DISCOVERY_RETRIES) {
                        delay((15_000L * transientFailures).coerceAtMost(60_000L))
                        continue
                    }
                }
                throw IOException("Nhentai search HTTP $code")
            }
            transientFailures = 0
            val json = JSONObject(response.use { it.body.string() })
            val results = json.optJSONArray("result") ?: break
            if (results.length() == 0) break
            totalPages = json.optInt("num_pages", totalPages).coerceAtMost(MAX_PAGES)
            val pageUrls = LinkedHashSet<String>()
            for (index in 0 until results.length()) {
                val id = results.optJSONObject(index)?.optLong("id", 0L) ?: 0L
                if (id > 0L) pageUrls += "https://nhentai.net/g/$id/"
            }
            val newUrls = pageUrls.filter { all.add(it) }
            if (newUrls.isNotEmpty()) queueFile.appendText(newUrls.joinToString("\n") + "\n")
            page++
        }
        return all.size
    }

    /**
     * Discovery appends URLs while the importer drains this file. Ignore an
     * unterminated final line so a partially written URL cannot be skipped.
     */
    private fun File.readCompleteQueueLines(): List<String> = runCatching {
        if (!exists()) return@runCatching emptyList()
        val text = readText()
        val end = text.lastIndexOf('\n')
        if (end < 0) return@runCatching emptyList()
        text.substring(0, end)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY_START_DATE = "start_date"
        private const val KEY_END_DATE = "end_date"
        private const val KEY_EXCLUDED_TAGS = "excluded_tags"
        private const val KEY_QUEUE_ID = "queue_id"
        private const val MAX_PAGES = 400
        private const val DISCOVERY_RETRIES = 5
        private const val DAY_MS = 86_400_000L
        private const val TAG = "nhentai-date-import"

        fun start(context: Context, startDate: String, endDate: String = startDate, excludedTags: String = "") {
            val request = OneTimeWorkRequestBuilder<NhentaiDateImportWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        KEY_START_DATE to startDate,
                        KEY_END_DATE to endDate,
                        KEY_EXCLUDED_TAGS to excludedTags,
                        KEY_QUEUE_ID to System.currentTimeMillis().toString(),
                    ),
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
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
                setContentText("Open More → Nhentai Book Import to add today’s books")
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
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
