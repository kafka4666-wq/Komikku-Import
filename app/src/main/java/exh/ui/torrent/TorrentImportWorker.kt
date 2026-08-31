package exh.ui.torrent

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.TorrentImportStatus
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.online.TorrentSource
import eu.kanade.tachiyomi.torrent.TorrentImportControl
import eu.kanade.tachiyomi.torrent.TorrentStreamManager
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class TorrentImportWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val manager: TorrentStreamManager = Injekt.get()
    private val status: TorrentImportStatus = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val chapterRepository: ChapterRepository = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val torrentMangaIds = mutableMapOf<String, Long>()

    override suspend fun doWork(): Result {
        val link = inputData.getString(INPUT_LINK)?.trim().orEmpty()
        if (link.isBlank()) return Result.failure()
        TorrentImportControl.reset(context)
        status.begin(link.takeLast(80))
        setForegroundSafely()
        return try {
            val result = manager.importLink(link)
            status.begin(result.torrentName, result.books.size, "Adding recognized books…")
            postProgress(context)
            result.books.chunked(BATCH_SIZE).forEach { batch ->
                TorrentImportControl.awaitResume(context)
                check(!TorrentImportControl.isCancelled(context)) { CANCELED_MESSAGE }
                addBatch(batch)
                postProgress(context)
            }
            val snapshot = status.state.value
            status.finish("Torrent import complete")
            postComplete(
                context,
                "Torrent import complete",
                "100% • ${snapshot.completed}/${snapshot.total} processed • ${snapshot.added} added • ${snapshot.failed} failed",
            )
            Result.success()
        } catch (error: IllegalStateException) {
            if (error.message == CANCELED_MESSAGE || TorrentImportControl.isCancelled(context)) {
                status.fail("Torrent import canceled")
                postComplete(context, "Torrent import canceled", "The Torrent queue was canceled.")
                Result.success()
            } else {
                status.fail(error.message ?: "Torrent import failed")
                postComplete(context, "Torrent import failed", status.state.value.message)
                Result.failure()
            }
        } catch (cancelled: CancellationException) {
            status.fail("Torrent import canceled")
            postComplete(context, "Torrent import canceled", "The Torrent queue was canceled.")
            throw cancelled
        } catch (error: Throwable) {
            status.fail(error.message ?: "Torrent import failed")
            postComplete(context, "Torrent import failed", status.state.value.message)
            Result.failure()
        }
    }

    private suspend fun addBatch(books: List<TorrentStreamManager.TorrentBook>) {
        val drafts = books.map { book ->
            Manga.create().copy(
                source = TorrentSource.ID,
                url = book.key,
                ogTitle = book.title,
                ogArtist = book.artist,
                ogAuthor = book.artist,
                ogThumbnailUrl = TorrentSource.coverUrl(book.key),
                ogDescription = "Torrent-backed online stream • ${book.size / (1024 * 1024)} MiB",
                ogGenre = listOf("Torrent"),
                favorite = true,
            )
        }
        val imported = runCatching {
            networkToLocalManga(drafts, updateInfo = false)
        }.getOrElse {
            drafts.map { networkToLocalManga(it, updateInfo = false) }
        }
        val dateAdded = System.currentTimeMillis()
        updateManga.awaitAll(imported.map { manga ->
            MangaUpdate(id = manga.id, favorite = true, dateAdded = dateAdded)
        })
        val chapters = imported.map { manga ->
            Chapter.create().copy(
                mangaId = manga.id,
                url = manga.url,
                name = "Stream",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateUpload = dateAdded,
            )
        }
        runCatching {
            chapterRepository.addAll(chapters)
        }.onFailure {
            // Repeated imports may already contain a Stream row. Fall back to an idempotent check
            // only when the single transaction rejects the batch.
            chapters.forEach { chapter ->
                if (chapterRepository.getChapterByUrlAndMangaId(chapter.url, chapter.mangaId) == null) {
                    runCatching { chapterRepository.addAll(listOf(chapter)) }
                }
            }
        }
        imported.zip(books).forEach { (manga, book) ->
            torrentMangaIds[book.key] = manga.id
            manager.registerLibraryManga(book.key, manga.id)
            status.record(manga.ogTitle.ifBlank { book.title }, true)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_TORRENT_PROGRESS,
        buildProgressNotification(context),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    companion object {
        private const val TAG = "torrent-import"
        private const val INPUT_LINK = "torrent_link"
        // The older working APK registered torrent books one at a time. Keeping one book per
        // transaction avoids opening multiple torrent-backed streams and makes each library item
        // independently readable even when the torrent contains many archives.
        private const val BATCH_SIZE = 1
        private const val CANCELED_MESSAGE = "Torrent import canceled."

        fun start(context: Context, link: String) {
            val request = OneTimeWorkRequestBuilder<TorrentImportWorker>()
                .setInputData(workDataOf(INPUT_LINK to link))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            TorrentImportControl.cancel(context)
            context.workManager.cancelUniqueWork(TAG)
        }

        fun buildProgressNotification(context: Context) = context.notificationBuilder(Notifications.CHANNEL_TORRENT_PROGRESS) {
            val state = Injekt.get<TorrentImportStatus>().state.value
            val total = state.total.coerceAtLeast(1)
            val completed = state.completed.coerceIn(0, total)
            val percent = if (state.total == 0) 0 else completed * 100 / state.total
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Adding torrent books")
            setContentText("$percent% • $completed/${state.total} processed • ${state.added} added • ${state.failed} failed")
            setSubText(state.torrentName.takeIf { it.isNotBlank() } ?: "Torrent")
            setProgress(total, completed, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setAutoCancel(false)
            addAction(R.drawable.ic_pause_24dp, "Pause", NotificationReceiver.pauseTorrentImportPendingBroadcast(context))
            addAction(R.drawable.ic_play_arrow_24dp, "Resume", NotificationReceiver.resumeTorrentImportPendingBroadcast(context))
            addAction(R.drawable.ic_close_24dp, "Cancel", NotificationReceiver.cancelTorrentImportPendingBroadcast(context))
        }.build()

        fun postProgress(context: Context) {
            context.notify(Notifications.ID_TORRENT_PROGRESS, buildProgressNotification(context))
        }

        fun postComplete(context: Context, title: String, text: String) {
            context.cancelNotification(Notifications.ID_TORRENT_PROGRESS)
            context.notify(
                Notifications.ID_TORRENT_COMPLETE,
                context.notificationBuilder(Notifications.CHANNEL_TORRENT_COMPLETE) {
                    setSmallIcon(R.drawable.ic_komikku)
                    setContentTitle(title)
                    setContentText(text)
                    setAutoCancel(true)
                }.build(),
            )
        }
    }
}
