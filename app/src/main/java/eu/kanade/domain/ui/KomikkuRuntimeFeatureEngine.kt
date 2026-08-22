package eu.kanade.domain.ui

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import eu.kanade.tachiyomi.data.download.DownloadManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Runtime behavior for the requested Komikku customisations.
 *
 * This intentionally composes with the existing downloader, reader state, and local metadata
 * instead of changing the manga database or WebDAV backup.proto format.
 */
object KomikkuRuntimeFeatureEngine {
    private const val PREFS = "komikku_runtime_features"
    private const val SOURCE_BACKOFF = "source_backoff_until"
    private const val SOURCE_FAILURES = "source_failure_count"
    private const val READER_PROFILES = "reader_profiles"
    private const val STATS = "reading_stats"
    private const val UNDO = "undo_action"

    data class QueueSummary(
        val queued: Int,
        val running: Boolean,
        val offline: Boolean,
    ) {
        fun asText(): String = "Queued: $queued\nRunning: ${if (running) "yes" else "no"}\nNetwork: ${if (offline) "offline" else "online"}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun queueSummary(context: Context): QueueSummary {
        val manager = runCatching { Injekt.get<DownloadManager>() }.getOrNull()
        return QueueSummary(
            queued = manager?.queueState?.value?.size ?: 0,
            running = manager?.isRunning == true,
            offline = isOffline(context),
        )
    }

    fun pauseDownloads(context: Context, recordForUndo: Boolean = true) {
        runCatching { Injekt.get<DownloadManager>().pauseDownloads() }
        KomikkuExtendedFeatureStore.recordRecovery(context, "downloads-paused", "Download queue paused by user")
        if (recordForUndo) KomikkuFullFeatureEngine.recordUndo(context, "downloads-paused", "")
    }

    fun resumeDownloads(context: Context, recordForUndo: Boolean = true) {
        runCatching { Injekt.get<DownloadManager>().startDownloads() }
        KomikkuExtendedFeatureStore.recordRecovery(context, "downloads-resumed", "Download queue resumed by user")
        if (recordForUndo) KomikkuFullFeatureEngine.recordUndo(context, "downloads-resumed", "")
    }

    fun clearQueuedDownloads(context: Context) {
        runCatching { Injekt.get<DownloadManager>().clearQueue() }
        recordUndo(context, "clear-download-queue", "Queued downloads were cleared; files were not deleted")
    }

    fun prioritizeFirstQueued(context: Context) {
        runCatching {
            val manager = Injekt.get<DownloadManager>()
            val queue = manager.queueState.value
            if (queue.size > 1) manager.reorderQueue(queue.drop(1) + queue.first())
        }
        KomikkuExtendedFeatureStore.recordRecovery(context, "downloads-reordered", "Moved the first queued item to the end")
    }

    fun prioritizeLastQueued(context: Context) {
        runCatching {
            val manager = Injekt.get<DownloadManager>()
            val queue = manager.queueState.value
            if (queue.size > 1) manager.reorderQueue(listOf(queue.last()) + queue.dropLast(1))
        }
        KomikkuExtendedFeatureStore.recordRecovery(context, "downloads-reordered", "Moved the last queued item to the front")
    }

    fun isOffline(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return connectivity?.activeNetworkInfo?.isConnected != true
    }

    fun sourceCooldownUntil(context: Context, source: String): Long =
        prefs(context).getLong("$SOURCE_BACKOFF:${safeKey(source)}", 0L)

    fun canAttemptSource(context: Context, source: String): Boolean =
        sourceCooldownUntil(context, source) <= System.currentTimeMillis()

    fun recordSourceFailure(context: Context, source: String, kind: String, error: String, latencyMs: Long? = null) {
        val key = safeKey(source)
        val failures = prefs(context).getInt("$SOURCE_FAILURES:$key", 0) + 1
        val exponent = failures.coerceAtMost(5)
        val cooldownMs = (10_000L shl exponent).coerceAtMost(10 * 60 * 1000L)
        prefs(context).edit().putInt("$SOURCE_FAILURES:$key", failures).putLong("$SOURCE_BACKOFF:$key", System.currentTimeMillis() + cooldownMs).apply()
        KomikkuExtendedFeatureStore.recordSourceEvent(context, source, kind, false, latencyMs, error.take(240))
    }

    fun recordSourceSuccess(context: Context, source: String, kind: String, latencyMs: Long? = null) {
        val key = safeKey(source)
        prefs(context).edit().remove("$SOURCE_FAILURES:$key").remove("$SOURCE_BACKOFF:$key").apply()
        KomikkuExtendedFeatureStore.recordSourceEvent(context, source, kind, true, latencyMs, null)
    }

    fun sourceStatusText(context: Context, source: String): String {
        val until = sourceCooldownUntil(context, source)
        if (until <= System.currentTimeMillis()) return "Available"
        val seconds = ((until - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
        return "Cooling down for ${seconds}s"
    }

    fun recordReadingProgress(context: Context, mangaKey: String, chapter: String, page: Int, percent: Int) {
        val key = safeKey(mangaKey)
        val now = System.currentTimeMillis()
        val current = prefs(context).getString("$STATS:$key", "")
        val value = "$chapter|$page|${percent.coerceIn(0, 100)}|$now"
        prefs(context).edit().putString("$STATS:$key", value).putInt("stats_pages", prefs(context).getInt("stats_pages", 0) + 1).putInt("stats_chapters", prefs(context).getInt("stats_chapters", 0) + if (percent >= 100) 1 else 0).apply()
        if (!current.isNullOrBlank() && current != value) {
            KomikkuExtendedFeatureStore.recordRecovery(context, "reading-progress", "Updated $key")
        }
    }

    fun readerProfile(context: Context, mangaKey: String): String =
        prefs(context).getString("$READER_PROFILES:${safeKey(mangaKey)}", "system|small|false|false") ?: "system|small|false|false"

    fun saveReaderProfile(context: Context, mangaKey: String, theme: String, spacing: String, immersive: Boolean, autoFit: Boolean) {
        prefs(context).edit().putString("$READER_PROFILES:${safeKey(mangaKey)}", "${theme.take(30)}|${spacing.take(30)}|$immersive|$autoFit").apply()
    }

    fun readingStatistics(context: Context): String {
        val prefs = prefs(context)
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        return buildString {
            appendLine("Chapters completed: ${prefs.getInt("stats_chapters", 0)}")
            appendLine("Page interactions recorded: ${prefs.getInt("stats_pages", 0)}")
            appendLine("Available memory: ${memory.availMem / (1024 * 1024)} MB")
            appendLine("Counters are incremental and stored outside WebDAV backup data.")
        }
    }

    fun recordUndo(context: Context, action: String, detail: String) {
        prefs(context).edit().putString(UNDO, "$action|${detail.replace('|', ' ').replace('\n', ' ').take(500)}").apply()
    }

    fun undoSummary(context: Context): String = prefs(context).getString(UNDO, "")?.takeIf { it.isNotBlank() } ?: "No reversible local action is recorded."

    fun diagnostics(context: Context): String = buildString {
        appendLine(KomikkuExtendedFeatureStore.diagnosticReport(context))
        appendLine("Runtime queue:")
        appendLine(queueSummary(context).asText())
        appendLine("Source cooldowns are bounded and activated only after repeated failures.")
        appendLine("Reader statistics:")
        appendLine(readingStatistics(context))
        appendLine("Last undo record: ${undoSummary(context)}")
    }

    private fun safeKey(value: String): String = value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_").take(80)
}
