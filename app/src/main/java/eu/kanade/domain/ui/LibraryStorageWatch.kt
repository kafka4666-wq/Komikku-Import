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
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/** Daily, read-only check: notifications are advisory and never delete downloads. */
object LibraryStorageWatch {
    private const val UNIQUE_NAME = "komikku-library-storage-watch"
    private const val CHANNEL_ID = "library_storage_watch"
    private const val NOTIFICATION_ID = 8132

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LibraryStorageWatchWorker>(24, TimeUnit.HOURS)
            .addTag(UNIQUE_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }

    internal fun postThresholdNotification(context: Context, used: Long, threshold: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Library storage watch", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            8132,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Komikku download storage is near your limit")
            .setContentText("${format(used)} used · threshold ${format(threshold)}. Review files before removing anything.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Downloads use ${format(used)}. Your review threshold is ${format(threshold)}. Open Komikku's Library Companion to inspect the space planner. Nothing was deleted."))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f KB".format(bytes / 1024.0)
    }
}

class LibraryStorageWatchWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val threshold = LibraryCompanionStore.storageThresholdBytes(applicationContext)
        if (threshold <= 0L) return@withContext Result.success()
        val root = runCatching { Injekt.get<StorageManager>().getDownloadsDirectory() }.getOrNull()
            ?: return@withContext Result.retry()
        fun size(file: com.hippo.unifile.UniFile): Long = if (file.isDirectory) {
            file.listFiles().orEmpty().sumOf(::size)
        } else {
            file.length().coerceAtLeast(0L)
        }
        val used = runCatching { size(root) }.getOrElse { return@withContext Result.retry() }
        if (used >= threshold) LibraryStorageWatch.postThresholdNotification(applicationContext, used, threshold)
        Result.success()
    }
}
