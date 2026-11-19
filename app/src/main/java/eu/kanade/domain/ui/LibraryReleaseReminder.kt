package eu.kanade.domain.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.KomikkuHomeShortcuts
import eu.kanade.tachiyomi.ui.main.MainActivity
import java.util.concurrent.TimeUnit
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object LibraryReleaseReminder {
    private const val WORK_NAME = "komikku-library-release-reminders"
    private const val CHANNEL_ID = "library_release_reminders"
    private const val SETTING_ENABLED = "release_reminder_enabled"

    fun enabled(context: Context): Boolean = LibraryCompanionStore.setting(context, SETTING_ENABLED) == "true"

    fun setEnabled(context: Context, enabled: Boolean) {
        LibraryCompanionStore.setSetting(context, SETTING_ENABLED, enabled.toString())
        if (enabled) schedule(context) else WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LibraryReleaseReminderWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    internal fun notifyNewReleases(context: Context, titles: List<String>) {
        if (titles.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Series release reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(KomikkuHomeShortcuts.ACTION_COMPANION)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, 8141, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val preview = titles.take(5).joinToString(", ") + if (titles.size > 5) " and ${titles.size - 5} more" else ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New chapters in your tracked series")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText("New chapter updates were found for ${titles.size} series marked Reading or Ongoing: $preview"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(8141, notification) }
    }
}

class LibraryReleaseReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!LibraryReleaseReminder.enabled(applicationContext)) return Result.success()
        return runCatching {
            val rows = Injekt.get<GetLibraryManga>().await()
            val records = LibraryCompanionStore.getRecords(applicationContext)
            val previous = LibraryCompanionStore.releaseReminderSnapshots(applicationContext).toMutableMap()
            val updatedTitles = mutableListOf<String>()
            rows.forEach { row ->
                val id = row.manga.id
                val record = records[id] ?: return@forEach
                val current = row.latestUpload
                val old = previous[id]
                if (LibraryCompanionRules.shouldNotifyNewRelease(record.status, old, current)) updatedTitles += row.manga.title
                previous[id] = current
            }
            LibraryCompanionStore.saveReleaseReminderSnapshots(applicationContext, previous)
            LibraryReleaseReminder.notifyNewReleases(applicationContext, updatedTitles.distinct())
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
