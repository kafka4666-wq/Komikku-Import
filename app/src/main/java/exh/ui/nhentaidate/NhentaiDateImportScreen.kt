package exh.ui.nhentaidate

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.BuildConfig
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
import java.util.TimeZone
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
        var dailyImportEnabled by remember { mutableStateOf(NhentaiDailyImportSchedule.isEnabled(context)) }
        var dailyImportTime by remember { mutableStateOf(NhentaiDailyImportSchedule.time(context)) }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = dailyImportEnabled,
                        onCheckedChange = {
                            dailyImportEnabled = it
                            NhentaiDailyImportSchedule.setEnabled(context, it)
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatically import every day", style = MaterialTheme.typography.titleMedium)
                        Text("Runs at ${dailyImportTime} IST and adds that day’s books.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = {
                            val (hour, minute) = NhentaiDailyImportSchedule.parseTime(dailyImportTime)
                            TimePickerDialog(context, { _, selectedHour, selectedMinute ->
                                dailyImportTime = NhentaiDailyImportSchedule.formatTime(selectedHour, selectedMinute)
                                NhentaiDailyImportSchedule.setTime(context, dailyImportTime)
                            }, hour, minute, true).show()
                        },
                    ) { Text(dailyImportTime) }
                }
                Text(
                    "The schedule uses Asia/Kolkata time. Exact alarms can run during Doze; for the most reliable overnight import, set Komikku to Unrestricted battery use in Android settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setPackage(context.packageName))
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                ) { Text("Open battery and alarm settings") }
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
        setContentText("0% • 0/0 processed • 0 added • 0 failed")
        // Discovery appends URLs while BatchImportJob drains them. That worker replaces
        // this placeholder with the live total and determinate progress on each update.
        setProgress(1, 0, false)
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
        if (start.time > now || end.before(start)) return 0

        val query = NhentaiDateQuery.build(startDate, endDate, now, excludedTags)
        val endExclusive = end.time + 86_400_000L
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
            if (response.code == 404) {
                response.close()
                // A 404 before the advertised final page is a transient server/CDN
                // response, not the end of the search. Treating it as completion
                // silently truncated large imports (for example at ~1,113 items).
                if (page > totalPages) break
                throw IOException("Nhentai search page $page returned HTTP 404")
            }
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
                val result = results.optJSONObject(index) ?: continue
                val id = result.optLong("id", 0L)
                val uploadMillis = result.optLong("upload_date", 0L) * 1_000L
                // nhentai's search endpoint currently omits upload_date from result
                // objects. The relative uploaded query is the server-side filter in
                // that case; only apply the precise local boundary when the timestamp
                // is actually present. Treating the missing value as a date caused the
                // queue to remain empty and the UI to finish at 0/0.
                val matchesRange = uploadMillis <= 0L || uploadMillis in start.time until endExclusive
                if (id > 0L && matchesRange) {
                    pageUrls += "https://nhentai.net/g/$id/"
                }
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

object NhentaiDailyImportSchedule {
    private const val PREFS = "nhentai_daily_import"
    private const val ENABLED = "enabled"
    private const val TIME = "time"
    private const val DEFAULT_TIME = "00:00"
    private const val TAG = "nhentai-daily-import"
    private const val ALARM_REQUEST_CODE = 9040
    const val ACTION_DAILY_IMPORT_ALARM = "${BuildConfig.APPLICATION_ID}.ACTION_NHENTAI_DAILY_IMPORT"
    private val IST = TimeZone.getTimeZone("Asia/Kolkata")

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun time(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TIME, DEFAULT_TIME) ?: DEFAULT_TIME

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun setTime(context: Context, value: String) {
        val (hour, minute) = parseTime(value)
        val normalized = formatTime(hour, minute)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TIME, normalized).apply()
        if (isEnabled(context)) schedule(context)
    }

    fun parseTime(value: String): Pair<Int, Int> {
        val parts = value.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour to minute
    }

    fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.US, hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val now = Calendar.getInstance(IST)
        val (hour, minute) = parseTime(time(context))
        val next = Calendar.getInstance(IST).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val triggerAt = next.timeInMillis
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val alarmIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(ACTION_DAILY_IMPORT_ALARM).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exactScheduled = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                false
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent)
                true
            }
        }.getOrDefault(false)
        if (exactScheduled) {
            context.workManager.cancelUniqueWork(TAG)
        } else {
            // Devices that deny exact alarms still get a persisted fallback.
            val request = OneTimeWorkRequestBuilder<NhentaiDailyReminderWorker>()
                .setInitialDelay((triggerAt - System.currentTimeMillis()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val alarmIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(ACTION_DAILY_IMPORT_ALARM).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(alarmIntent)
        context.workManager.cancelUniqueWork(TAG)
    }

    fun onAlarm(context: Context) {
        if (!isEnabled(context)) return
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ist }
            .format(Calendar.getInstance(ist).time)
        NhentaiDateImportWorker.start(context, today, today)
        schedule(context)
    }
}

class NhentaiDailyReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        NhentaiDailyImportSchedule.onAlarm(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) = NhentaiDailyImportSchedule.schedule(context)
    }
}
