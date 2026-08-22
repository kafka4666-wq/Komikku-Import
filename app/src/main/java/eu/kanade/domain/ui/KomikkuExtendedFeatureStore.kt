package eu.kanade.domain.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local operational metadata for the added feature controls. This store is
 * intentionally separate from the manga database and WebDAV backup payload.
 */
object KomikkuExtendedFeatureStore {
    private const val PREFS = "komikku_extended_feature_store"
    private const val SEARCHES = "saved_searches"
    private const val SOURCE_EVENTS = "source_events"
    private const val RECOVERY_EVENTS = "recovery_events"
    private const val MAX_ITEMS = 100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveSearch(context: Context, name: String, query: String) {
        val cleanName = name.trim().replace('|', ' ').replace('\n', ' ')
        val cleanQuery = query.trim().replace('|', ' ').replace('\n', ' ')
        if (cleanName.isBlank() || cleanQuery.isBlank()) return
        val rows = readLines(context, SEARCHES).filterNot { it.substringBefore('|') == cleanName }.toMutableList()
        rows.add(0, "$cleanName|$cleanQuery")
        writeLines(context, SEARCHES, rows)
    }

    fun savedSearches(context: Context): List<Pair<String, String>> = readLines(context, SEARCHES)
        .mapNotNull { row ->
            val name = row.substringBefore('|').trim()
            val query = row.substringAfter('|', "").trim()
            if (name.isBlank() || query.isBlank()) null else name to query
        }

    fun recordSourceEvent(context: Context, source: String, kind: String, success: Boolean, latencyMs: Long? = null, error: String? = null) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safe = listOf(stamp, source, kind, if (success) "OK" else "FAIL", latencyMs?.toString().orEmpty(), error.orEmpty())
            .joinToString("\t") { it.replace('\n', ' ').replace('\t', ' ').take(240) }
        writeLines(context, SOURCE_EVENTS, listOf(safe) + readLines(context, SOURCE_EVENTS))
    }

    fun sourceEvents(context: Context): List<String> = readLines(context, SOURCE_EVENTS)

    fun recordRecovery(context: Context, operation: String, detail: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safe = "$stamp\t${operation.replace('\n', ' ')}\t${detail.replace('\n', ' ')}"
        writeLines(context, RECOVERY_EVENTS, listOf(safe) + readLines(context, RECOVERY_EVENTS))
    }

    fun recoveryEvents(context: Context): List<String> = readLines(context, RECOVERY_EVENTS)

    fun diagnosticReport(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val appInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = appInfo?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) appInfo?.longVersionCode?.toString() else "unknown"
        return buildString {
            appendLine("Komikku privacy-safe diagnostics")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Low-memory device: ${memory.lowMemory}")
            appendLine("Available memory: ${memory.availMem / (1024 * 1024)} MB")
            appendLine("Total memory: ${memory.totalMem / (1024 * 1024)} MB")
            appendLine("Saved searches: ${savedSearches(context).size}")
            appendLine("Source diagnostic events: ${sourceEvents(context).size}")
            appendLine("Recovery events: ${recoveryEvents(context).size}")
            appendLine("Recent source events:")
            sourceEvents(context).take(10).forEach { appendLine(it) }
            appendLine("Recent recovery events:")
            recoveryEvents(context).take(10).forEach { appendLine(it) }
            appendLine("Credentials, cookies, tokens, account data, and private URLs are intentionally excluded.")
        }
    }

    fun duplicateReviewSummary(context: Context): String = buildString {
        appendLine("Duplicate review")
        appendLine("This review is non-destructive. No manga or files are removed automatically.")
        appendLine("Use normalized title, alternative title, author, artist, source metadata, and external IDs in the library’s review workflow.")
        appendLine("Saved searches available for review: ${savedSearches(context).size}")
        appendLine("Manual confirmation is required before merge, link, or removal.")
    }

    fun recoverySummary(context: Context): String = buildString {
        appendLine("Automatic recovery summary")
        if (recoveryEvents(context).isEmpty()) appendLine("No recovery events recorded.")
        recoveryEvents(context).take(12).forEach { appendLine(it) }
    }

    private fun readLines(context: Context, key: String): List<String> = prefs(context).getString(key, "")
        .orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .take(MAX_ITEMS)
        .toList()

    private fun writeLines(context: Context, key: String, rows: List<String>) {
        prefs(context).edit().putString(key, rows.distinct().take(MAX_ITEMS).joinToString("\n")).apply()
    }
}
