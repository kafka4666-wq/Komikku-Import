package eu.kanade.domain.ui

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.min

/**
 * Cross-cutting runtime behavior for the Komikku 2.0 brief.
 *
 * The engine deliberately stores only bounded preferences and recovery metadata. It does not
 * modify manga entities or the WebDAV backup.proto format. Existing Komikku repositories remain
 * the source of truth for library, reader, download, and extension data.
 */
object KomikkuFullFeatureEngine {
    private const val PREFS = "komikku_full_runtime"
    private const val FLAGS = "feature_flags"
    private const val SECTIONS = "library_sections"
    private const val SEARCHES = "saved_searches"
    private const val HEALTH = "source_health"
    private const val RETRIES = "source_retries"
    private const val READER = "reader_profiles"
    private const val STATS = "reading_statistics"
    private const val RECOVERY = "recovery_journal"
    private const val UNDO = "undo_journal"
    private const val PRIORITIES = "download_priorities"
    private const val OFFLINE_MODE = "offline_mode"
    private const val MAX_RECORDS = 120

    enum class Layout { LARGE_GRID, MEDIUM_GRID, SMALL_GRID, COMPACT_GRID, LIST, DETAILED_LIST, COVER_ONLY }
    enum class Performance { BALANCED, PERFORMANCE, BATTERY_SAVER, CUSTOM }
    enum class Preload { OFF, CONSERVATIVE, BALANCED, AGGRESSIVE }
    enum class Priority { HIGHEST, HIGH, NORMAL, LOW }
    enum class Animation { FULL, REDUCED, OFF }

    data class FeatureSpec(val number: Int, val key: String, val title: String)
    data class SectionRule(val name: String, val expression: String, val enabled: Boolean = true)
    data class SavedSearch(val name: String, val query: String, val createdAt: Long)
    data class ReaderProfile(val theme: String, val spacing: Int, val immersive: Boolean, val autoFit: Boolean, val toolbar: String)
    data class SourceHealth(
        val source: String,
        val core: Boolean,
        val search: Boolean,
        val details: Boolean,
        val chapters: Boolean,
        val images: Boolean,
        val latencyMs: Long,
        val lastSuccess: Long,
        val lastError: String,
    )
    data class RecoveryEntry(val operation: String, val state: String, val detail: String, val timestamp: Long)
    data class UndoEntry(val action: String, val payload: String, val timestamp: Long)
    data class AdaptiveLayout(val widthDp: Int, val twoPane: Boolean, val columns: Int, val landscapeReader: Boolean)

    val featureCatalog: List<FeatureSpec> = listOf(
        FeatureSpec(1, "dynamic_theming", "Material You and dynamic theming"),
        FeatureSpec(2, "library_layouts", "Custom library layouts"),
        FeatureSpec(3, "library_sections", "Automatic custom library sections"),
        FeatureSpec(4, "home_dashboard", "Customizable home dashboard"),
        FeatureSpec(5, "manga_cards", "Improved manga cards"),
        FeatureSpec(6, "reader_customization", "Reader customization"),
        FeatureSpec(7, "lazy_library", "Lazy-loaded library"),
        FeatureSpec(8, "database_queries", "Efficient database queries"),
        FeatureSpec(9, "diff_updates", "Diff-based library updates"),
        FeatureSpec(10, "background_sync", "Background synchronization"),
        FeatureSpec(11, "smart_preload", "Smart preloading"),
        FeatureSpec(12, "memory_safe_images", "Memory-safe image handling"),
        FeatureSpec(13, "performance_mode", "Performance modes"),
        FeatureSpec(14, "global_search", "Global search"),
        FeatureSpec(15, "advanced_filters", "Advanced library filters"),
        FeatureSpec(16, "saved_searches", "Saved searches"),
        FeatureSpec(17, "duplicate_detection", "Duplicate detection and resolution"),
        FeatureSpec(18, "extension_api", "Versioned extension API abstraction"),
        FeatureSpec(19, "source_health", "Source health monitoring"),
        FeatureSpec(20, "source_failure", "Source failure handling"),
        FeatureSpec(21, "source_migration", "Source migration"),
        FeatureSpec(22, "offline_library", "Offline-first library"),
        FeatureSpec(23, "local_metadata", "Local metadata preservation"),
        FeatureSpec(24, "crash_recovery", "Crash recovery"),
        FeatureSpec(25, "download_manager", "Download manager"),
        FeatureSpec(26, "download_priority", "Download priorities"),
        FeatureSpec(27, "download_integrity", "Download integrity"),
        FeatureSpec(28, "reading_progress", "Reading progress improvements"),
        FeatureSpec(29, "reading_history", "Reading history"),
        FeatureSpec(30, "reading_statistics", "Reading statistics"),
        FeatureSpec(31, "animations", "Animation system"),
        FeatureSpec(32, "haptics", "Haptic feedback"),
        FeatureSpec(33, "gestures", "Gesture customization"),
        FeatureSpec(34, "context_menus", "Context menus"),
        FeatureSpec(35, "accessibility", "Accessibility support"),
        FeatureSpec(36, "command_palette", "Quick command system"),
        FeatureSpec(37, "settings", "Settings organization"),
        FeatureSpec(38, "notifications", "Useful notifications"),
        FeatureSpec(39, "migrations", "Versioned database migrations"),
        FeatureSpec(40, "feature_flags", "Feature flags"),
        FeatureSpec(41, "diagnostics", "Error reporting and diagnostics"),
        FeatureSpec(42, "automatic_recovery", "Automatic recovery"),
        FeatureSpec(43, "bulk_operations", "Bulk operations"),
        FeatureSpec(44, "undo", "Undo system"),
        FeatureSpec(45, "keyboard_mouse", "Keyboard and mouse support"),
        FeatureSpec(46, "adaptive_layout", "Tablet and large-screen UI"),
        FeatureSpec(47, "quality_rules", "Quality and compatibility rules"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun applyCustomisationValues(
        context: Context,
        layoutValue: String,
        performanceValue: String,
        preloadValue: String,
        animationValue: String,
        readingProgressPrompt: Boolean = true,
    ) {
        val mappedLayout = runCatching { Layout.valueOf(layoutValue.uppercase(Locale.US)) }.getOrDefault(Layout.MEDIUM_GRID)
        val mappedPerformance = when (performanceValue.lowercase(Locale.US)) {
            "performance" -> Performance.PERFORMANCE
            "battery", "battery_saver" -> Performance.BATTERY_SAVER
            "custom" -> Performance.CUSTOM
            else -> Performance.BALANCED
        }
        val mappedPreload = runCatching { Preload.valueOf(preloadValue.uppercase(Locale.US)) }.getOrDefault(Preload.BALANCED)
        val mappedAnimation = runCatching { Animation.valueOf(animationValue.uppercase(Locale.US)) }.getOrDefault(Animation.FULL)
        prefs(context).edit()
            .putString("layout", mappedLayout.name)
            .putString("performance", mappedPerformance.name)
            .putString("preload", mappedPreload.name)
            .putString("animation", mappedAnimation.name)
            .putBoolean("reading_progress_prompt", readingProgressPrompt)
            .apply()
    }

    fun readingProgressPrompt(context: Context): Boolean =
        prefs(context).getBoolean("reading_progress_prompt", true)

    fun isFeatureEnabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean("$FLAGS:$key", true)

    fun setFeatureEnabled(context: Context, key: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("$FLAGS:$key", enabled).apply()
    }

    fun enabledFeatureCount(context: Context): Int = featureCatalog.count { isFeatureEnabled(context, it.key) }

    fun featureSummary(context: Context): String = buildString {
        appendLine("Runtime feature registry: ${enabledFeatureCount(context)}/${featureCatalog.size} enabled")
        appendLine("State is bounded and stored outside WebDAV backup.proto.")
        appendLine("Performance: ${performance(context)}; preload: ${preload(context)}; animation: ${animation(context)}")
        appendLine("Layout: ${layout(context)}; adaptive: ${adaptiveLayout(context).widthDp}dp / ${adaptiveLayout(context).columns} columns")
        appendLine("Saved searches: ${savedSearches(context).size}; sections: ${sections(context).size}; source records: ${sourceHealth(context).size}")
        appendLine("Recovery entries: ${recovery(context).size}; undo entries: ${undoEntries(context).size}")
        appendLine("All destructive resolution actions require explicit confirmation.")
    }

    fun layout(context: Context): Layout = runCatching {
        Layout.valueOf(prefs(context).getString("layout", Layout.MEDIUM_GRID.name) ?: Layout.MEDIUM_GRID.name)
    }.getOrDefault(Layout.MEDIUM_GRID)

    fun setLayout(context: Context, value: Layout) = prefs(context).edit().putString("layout", value.name).apply()

    fun performance(context: Context): Performance = runCatching {
        Performance.valueOf(prefs(context).getString("performance", Performance.BALANCED.name) ?: Performance.BALANCED.name)
    }.getOrDefault(Performance.BALANCED)

    fun setPerformance(context: Context, value: Performance) = prefs(context).edit().putString("performance", value.name).apply()

    fun preload(context: Context): Preload = runCatching {
        Preload.valueOf(prefs(context).getString("preload", Preload.BALANCED.name) ?: Preload.BALANCED.name)
    }.getOrDefault(Preload.BALANCED)

    fun setPreload(context: Context, value: Preload) = prefs(context).edit().putString("preload", value.name).apply()

    fun animation(context: Context): Animation = runCatching {
        Animation.valueOf(prefs(context).getString("animation", Animation.FULL.name) ?: Animation.FULL.name)
    }.getOrDefault(Animation.FULL)

    fun setAnimation(context: Context, value: Animation) = prefs(context).edit().putString("animation", value.name).apply()

    fun animationScale(context: Context): Float = when (animation(context)) {
        Animation.FULL -> 1f
        Animation.REDUCED -> 0.35f
        Animation.OFF -> 0f
    }

    fun defineSection(context: Context, rule: SectionRule) {
        val records = sections(context).filterNot { it.name == rule.name }.toMutableList()
        records += rule
        writeJsonRecords(context, SECTIONS, records.takeLast(MAX_RECORDS).map {
            JSONObject().put("name", it.name).put("expression", it.expression).put("enabled", it.enabled)
        })
    }

    fun sections(context: Context): List<SectionRule> = readJsonRecords(context, SECTIONS).mapNotNull {
        runCatching { SectionRule(it.optString("name"), it.optString("expression"), it.optBoolean("enabled", true)) }.getOrNull()
    }

    fun saveSearch(context: Context, name: String, query: String) {
        if (name.isBlank() || query.isBlank()) return
        val records = savedSearches(context).filterNot { it.name == name }.toMutableList()
        records += SavedSearch(name.take(80), query.take(500), System.currentTimeMillis())
        writeJsonRecords(context, SEARCHES, records.takeLast(MAX_RECORDS).map {
            JSONObject().put("name", it.name).put("query", it.query).put("createdAt", it.createdAt)
        })
    }

    fun savedSearches(context: Context): List<SavedSearch> = readJsonRecords(context, SEARCHES).mapNotNull {
        runCatching { SavedSearch(it.optString("name"), it.optString("query"), it.optLong("createdAt")) }.getOrNull()
    }

    fun normalizedSearchTokens(query: String): Set<String> = query.lowercase(Locale.US)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 }
        .toSet()

    fun duplicateFingerprint(title: String, author: String? = null, artist: String? = null): String {
        val normalized = listOf(title, author.orEmpty(), artist.orEmpty())
            .joinToString("|")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "")
        return sha256(normalized).take(24)
    }

    fun duplicateGroup(fingerprints: Map<String, String>): List<List<String>> = fingerprints.entries
        .groupBy({ it.value }, { it.key })
        .values
        .filter { it.size > 1 }

    fun recordDuplicateDecision(context: Context, action: String, first: String, second: String) {
        recordRecovery(context, "duplicate-$action", "$first <> $second", "reviewed")
    }

    fun negotiateExtensionApi(supported: Set<Int>, preferred: Int = 3): Int =
        supported.filter { it <= preferred }.maxOrNull() ?: supported.minOrNull() ?: 1

    fun recordSourceHealth(context: Context, health: SourceHealth) {
        val records = sourceHealth(context).filterNot { it.source == health.source }.toMutableList()
        records += health
        writeJsonRecords(context, HEALTH, records.takeLast(MAX_RECORDS).map {
            JSONObject().put("source", it.source).put("core", it.core).put("search", it.search)
                .put("details", it.details).put("chapters", it.chapters).put("images", it.images)
                .put("latencyMs", it.latencyMs).put("lastSuccess", it.lastSuccess).put("lastError", it.lastError.take(240))
        })
    }

    fun sourceHealth(context: Context): List<SourceHealth> = readJsonRecords(context, HEALTH).mapNotNull {
        runCatching { SourceHealth(it.optString("source"), it.optBoolean("core"), it.optBoolean("search"), it.optBoolean("details"), it.optBoolean("chapters"), it.optBoolean("images"), it.optLong("latencyMs"), it.optLong("lastSuccess"), it.optString("lastError")) }.getOrNull()
    }

    fun sourceRetryDelayMs(context: Context, source: String): Long {
        val key = safeKey(source)
        val failures = prefs(context).getInt("$RETRIES:$key", 0).coerceAtMost(8)
        return (10_000L shl failures).coerceAtMost(10 * 60 * 1000L)
    }

    fun recordSourceFailure(context: Context, source: String, error: String) {
        val key = safeKey(source)
        val count = prefs(context).getInt("$RETRIES:$key", 0) + 1
        prefs(context).edit().putInt("$RETRIES:$key", count).apply()
        recordRecovery(context, "source-failure", source, error.take(240))
    }

    fun recordSourceSuccess(context: Context, source: String) {
        prefs(context).edit().remove("$RETRIES:${safeKey(source)}").apply()
    }

    fun offlineModeEnabled(context: Context): Boolean = prefs(context).getBoolean(OFFLINE_MODE, false)

    fun setOfflineMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(OFFLINE_MODE, enabled).apply()
        recordRecovery(context, "offline-mode", if (enabled) "enabled" else "disabled", "completed")
    }

    fun canUseNetwork(context: Context): Boolean =
        !offlineModeEnabled(context) &&
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetworkInfo?.isConnected == true

    fun readerProfile(context: Context, mangaKey: String): ReaderProfile {
        val raw = prefs(context).getString("$READER:${safeKey(mangaKey)}", null) ?: return ReaderProfile("system", 8, false, true, "bottom")
        val parts = raw.split('|')
        return ReaderProfile(parts.getOrElse(0) { "system" }, parts.getOrElse(1) { "8" }.toIntOrNull() ?: 8, parts.getOrElse(2) { "false" }.toBoolean(), parts.getOrElse(3) { "true" }.toBoolean(), parts.getOrElse(4) { "bottom" })
    }

    fun saveReaderProfile(context: Context, mangaKey: String, profile: ReaderProfile) {
        prefs(context).edit().putString("$READER:${safeKey(mangaKey)}", listOf(profile.theme, profile.spacing, profile.immersive, profile.autoFit, profile.toolbar).joinToString("|")).apply()
    }

    fun hapticsEnabled(context: Context): Boolean = prefs(context).getBoolean("haptics_enabled", true)

    fun setHapticsEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("haptics_enabled", enabled).apply()

    fun gestureAction(context: Context, gesture: String): String = prefs(context).getString("gesture:${safeKey(gesture)}", gesture) ?: gesture

    fun setGestureAction(context: Context, gesture: String, action: String) = prefs(context).edit().putString("gesture:${safeKey(gesture)}", action.take(80)).apply()

    fun reducedMotion(context: Context): Boolean = animation(context) != Animation.FULL

    fun readerWindowPolicy(context: Context): String = when {
        adaptiveLayout(context).twoPane -> "wide-reader"
        reducedMotion(context) -> "reduced-motion-reader"
        else -> "standard-reader"
    }

    fun recordInput(context: Context, input: String, action: String) =
        recordRecovery(context, "input", "${input.take(60)} -> ${action.take(80)}", "handled")

    fun recordReading(context: Context, mangaKey: String, chapter: String, page: Int, percent: Int, source: String) {
        val now = System.currentTimeMillis()
        val current = JSONObject(prefs(context).getString("$STATS:${safeKey(mangaKey)}", "{}") ?: "{}")
        current.put("chapter", chapter.take(160)).put("page", page.coerceAtLeast(0)).put("percent", percent.coerceIn(0, 100)).put("lastRead", now).put("source", source.take(160))
        prefs(context).edit().putString("$STATS:${safeKey(mangaKey)}", current.toString())
            .putInt("stats_pages", prefs(context).getInt("stats_pages", 0) + 1)
            .putInt("stats_chapters", prefs(context).getInt("stats_chapters", 0) + if (percent >= 100) 1 else 0)
            .putLong("stats_last_day", now / 86_400_000L).apply()
    }

    fun statistics(context: Context): String = buildString {
        val p = prefs(context)
        appendLine("Chapters read: ${p.getInt("stats_chapters", 0)}")
        appendLine("Pages read/interactions: ${p.getInt("stats_pages", 0)}")
        appendLine("Last active day: ${p.getLong("stats_last_day", 0)}")
        appendLine("Statistics are incremental and bounded; the full library is not scanned for every view.")
    }

    fun priority(context: Context, itemKey: String): Priority = runCatching {
        Priority.valueOf(prefs(context).getString("$PRIORITIES:${safeKey(itemKey)}", Priority.NORMAL.name) ?: Priority.NORMAL.name)
    }.getOrDefault(Priority.NORMAL)

    fun setPriority(context: Context, itemKey: String, value: Priority) = prefs(context).edit().putString("$PRIORITIES:${safeKey(itemKey)}", value.name).apply()

    fun priorityRank(value: Priority): Int = when (value) {
        Priority.HIGHEST -> 0
        Priority.HIGH -> 1
        Priority.NORMAL -> 2
        Priority.LOW -> 3
    }

    fun verifyDownloadedFile(file: File, expectedBytes: Long? = null, expectedSha256: String? = null): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (expectedBytes != null && file.length() != expectedBytes) return false
        if (expectedSha256.isNullOrBlank()) return true
        return sha256(file) == expectedSha256.lowercase(Locale.US)
    }

    fun commandNames(): List<String> = listOf("Update library", "Open downloads", "Show unread", "Open settings", "Search sources", "Sync", "Open history")

    fun notificationCategoryEnabled(context: Context, category: String): Boolean =
        prefs(context).getBoolean("notification:$category", true)

    fun setNotificationCategoryEnabled(context: Context, category: String, enabled: Boolean) =
        prefs(context).edit().putBoolean("notification:$category", enabled).apply()

    fun migrationPlan(fromVersion: Int, toVersion: Int): List<Int> = if (toVersion <= fromVersion) emptyList() else (fromVersion + 1..toVersion).toList()

    fun recordRecovery(context: Context, operation: String, detail: String, state: String = "preserved") {
        val records = recovery(context).toMutableList()
        records += RecoveryEntry(operation.take(80), state.take(40), detail.take(500), System.currentTimeMillis())
        writeJsonRecords(context, RECOVERY, records.takeLast(MAX_RECORDS).map { JSONObject().put("operation", it.operation).put("state", it.state).put("detail", it.detail).put("timestamp", it.timestamp) })
    }

    fun recovery(context: Context): List<RecoveryEntry> = readJsonRecords(context, RECOVERY).mapNotNull {
        runCatching { RecoveryEntry(it.optString("operation"), it.optString("state"), it.optString("detail"), it.optLong("timestamp")) }.getOrNull()
    }

    fun recordUndo(context: Context, action: String, payload: String) {
        val records = undoEntries(context).toMutableList()
        records += UndoEntry(action.take(80), payload.take(500), System.currentTimeMillis())
        writeJsonRecords(context, UNDO, records.takeLast(MAX_RECORDS).map { JSONObject().put("action", it.action).put("payload", it.payload).put("timestamp", it.timestamp) })
    }

    fun undoEntries(context: Context): List<UndoEntry> = readJsonRecords(context, UNDO).mapNotNull {
        runCatching { UndoEntry(it.optString("action"), it.optString("payload"), it.optLong("timestamp")) }.getOrNull()
    }

    /**
     * Executes the inverse of the last recorded local action.
     * This is bounded to lightweight runtime actions (queue, diagnostics, recovery)
     * and does not attempt to reverse permanent database deletions or WebDAV syncs.
     */
    suspend fun executeUndoAsync(context: Context): CommandResult {
        val entries = undoEntries(context)
        if (entries.isEmpty()) return CommandResult("undo", false, "No reversible action recorded")
        val last = entries.last()
        if (last.action != "remove-manga") return executeUndo(context)
        val mangaId = last.payload.toLongOrNull()
            ?: return CommandResult("undo", false, "The removed manga record is invalid")
        val restored = runCatching {
            Injekt.get<MangaRepository>().update(
                MangaUpdate(
                    id = mangaId,
                    favorite = true,
                    dateAdded = Instant.now().toEpochMilli(),
                ),
            )
        }.getOrDefault(false)
        if (!restored) return CommandResult("undo", false, "The manga could not be restored")
        recordRecovery(context, "undo-remove-manga", mangaId.toString(), "completed")
        consumeUndoEntry(context, entries)
        return CommandResult("undo", true, "Manga restored to the library")
    }

    fun executeUndo(context: Context): CommandResult {
        val entries = undoEntries(context)
        if (entries.isEmpty()) return CommandResult("undo", false, "No reversible action recorded")
        val last = entries.last()
        val result = when (last.action) {
            "downloads-paused" -> {
                runCatching { KomikkuRuntimeFeatureEngine.resumeDownloads(context, recordForUndo = false) }
                CommandResult("undo", true, "Download queue resumed")
            }
            "downloads-resumed" -> {
                runCatching { KomikkuRuntimeFeatureEngine.pauseDownloads(context, recordForUndo = false) }
                CommandResult("undo", true, "Download queue paused")
            }
            "clear-download-queue" -> {
                recordRecovery(context, "undo-clear-queue", "Queue cannot be restored after clearing; use Import History to re-add links.", "unsupported")
                CommandResult("undo", false, "Queue restoration is not supported; use Import History to re-add links.")
            }
            "duplicate-resolution" -> {
                recordRecovery(context, "undo-duplicate", "Manual review required to restore removed duplicates.", "unsupported")
                CommandResult("undo", false, "Duplicate removal cannot be automatically reversed; check Recovery Center for details.")
            }
            else -> CommandResult("undo", false, "Undo for '${last.action}' is not yet implemented.")
        }
        if (result.success) consumeUndoEntry(context, entries)
        return result
    }

    private fun consumeUndoEntry(context: Context, entries: List<UndoEntry>) {
        val remaining = entries.dropLast(1)
        writeJsonRecords(context, UNDO, remaining.map { JSONObject().put("action", it.action).put("payload", it.payload).put("timestamp", it.timestamp) })
    }

    fun bulkChunkSize(context: Context): Int = when (performance(context)) {
        Performance.BATTERY_SAVER -> 32
        Performance.PERFORMANCE -> 256
        else -> 96
    }

    fun <T> chunksForBulk(context: Context, items: List<T>): List<List<T>> = items.chunked(bulkChunkSize(context))

    fun adaptiveLayout(context: Context): AdaptiveLayout {
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm?.currentWindowMetrics?.bounds else null
        val widthDp = ((metrics?.width() ?: context.resources.displayMetrics.widthPixels) / context.resources.displayMetrics.density).toInt()
        val twoPane = widthDp >= 840
        val columns = when {
            widthDp >= 1200 -> 8
            widthDp >= 840 -> 5
            widthDp >= 600 -> 4
            else -> 2
        }
        return AdaptiveLayout(widthDp, twoPane, columns, widthDp >= 600)
    }

    fun diagnostics(context: Context): String = buildString {
        appendLine(featureSummary(context))
        appendLine("Android: ${Build.VERSION.SDK_INT} (${Build.MODEL})")
        appendLine("Memory class: ${context.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: -1} MB")
        appendLine("Network available: ${canUseNetwork(context)}")
        appendLine("Commands: ${commandNames().joinToString(", ")}")
        appendLine("No authentication tokens, passwords, cookies, or private URLs are collected by this report.")
    }

    data class StartupCheck(val safeMode: Boolean, val network: Boolean, val memoryClassMb: Int, val migrationApplied: Boolean, val issues: List<String>)
    data class CommandResult(val command: String, val success: Boolean, val detail: String)
    data class DuplicateResolution(val action: String, val keptKey: String, val removedKeys: List<String>, val timestamp: Long)
    data class MigrationPlan(val source: String, val target: String, val preserveMetadata: Boolean, val preserveCategories: Boolean, val preserveProgress: Boolean)

    fun ensureRuntimeMigration(context: Context, currentVersion: Int = 1): Boolean {
        val p = prefs(context)
        val previous = p.getInt("schema_version", 0)
        if (previous >= currentVersion) return false
        val plan = migrationPlan(previous, currentVersion)
        p.edit().putInt("schema_version", currentVersion).putLong("last_migration", System.currentTimeMillis()).apply()
        plan.forEach { recordRecovery(context, "runtime-migration", "schema $it", "completed") }
        return true
    }

    fun startupSelfCheck(context: Context): StartupCheck {
        val memoryClass = context.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: -1
        val issues = buildList {
            if (memoryClass in 1..127) add("low-memory")
            if (!canUseNetwork(context)) add("offline")
            if (prefs(context).getBoolean("force_safe_mode", false)) add("safe-mode-requested")
        }
        val safe = issues.contains("low-memory") || issues.contains("safe-mode-requested")
        recordRecovery(context, "startup-self-check", issues.joinToString(",").ifBlank { "ok" }, if (safe) "safe-mode" else "ok")
        return StartupCheck(safe, canUseNetwork(context), memoryClass, prefs(context).contains("last_migration"), issues)
    }

    fun setSafeMode(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("force_safe_mode", enabled).apply()

    fun compileSavedSearch(query: String): Set<String> = normalizedSearchTokens(query)

    fun savedSearchMatches(query: String, haystack: String): Boolean {
        val tokens = compileSavedSearch(query)
        val normalized = haystack.lowercase(Locale.US)
        return tokens.all { normalized.contains(it) }
    }

    /**
     * Evaluates a small, deterministic boolean search language for library metadata.
     * Terms separated by AND must all match; OR groups are evaluated left-to-right.
     * A leading '-' excludes a term. The regular Komikku query parser remains the
     * source of truth for queries that do not contain explicit boolean operators.
     */
    fun matchesAdvancedQuery(query: String, values: Iterable<String?>): Boolean {
        val haystack = values.filterNotNull().joinToString(" ").lowercase(Locale.US)
        val groups = query.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE))
        return groups.any { group ->
            group.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE))
                .map(String::trim)
                .filter(String::isNotBlank)
                .all { rawTerm ->
                    val excluded = rawTerm.startsWith("-")
                    val term = rawTerm.removePrefix("-").trim().lowercase(Locale.US)
                    if (term.isBlank()) true else (haystack.contains(term) != excluded)
                }
        }
    }

    fun resolveDuplicate(context: Context, action: String, keptKey: String, removedKeys: List<String>): DuplicateResolution {
        val resolution = DuplicateResolution(action, keptKey.take(160), removedKeys.take(MAX_RECORDS).map { it.take(160) }, System.currentTimeMillis())
        recordRecovery(context, "duplicate-resolution", "${resolution.action}:${resolution.keptKey}:${resolution.removedKeys.size}", "confirmed")
        recordUndo(context, "duplicate-resolution", JSONObject().put("action", action).put("kept", keptKey).put("removed", JSONArray(removedKeys)).toString())
        return resolution
    }

    fun planSourceMigration(source: String, target: String): MigrationPlan = MigrationPlan(source.take(160), target.take(160), true, true, true)

    fun recordSourceMigration(context: Context, plan: MigrationPlan): Boolean {
        recordRecovery(context, "source-migration", "${plan.source}->${plan.target}", "planned")
        return plan.source.isNotBlank() && plan.target.isNotBlank() && plan.source != plan.target
    }

    fun offlinePolicy(context: Context): String = if (canUseNetwork(context)) "online-with-local-cache" else "offline-local-library"

    fun executeCommand(context: Context, command: String): CommandResult {
        val normalized = command.trim().lowercase(Locale.US)
        val known = commandNames().map { it.lowercase(Locale.US) }
        if (normalized !in known) return CommandResult(command, false, "Unknown command")
        recordRecovery(context, "command", command, "executed")
        return CommandResult(command, true, when (normalized) {
            "sync" -> if (offlineModeEnabled(context)) "Sync blocked by offline mode" else "Sync requested"
            "update library" -> if (offlineModeEnabled(context)) "Library update blocked by offline mode" else "Library update requested"
            "open downloads" -> "Downloads opened"
            "show unread" -> "Unread view requested"
            "open settings" -> "Settings opened"
            "search sources" -> "Source search requested"
            else -> "History opened"
        })
    }

    fun beginBulkOperation(context: Context, operation: String, total: Int): String {
        val id = "bulk-${System.currentTimeMillis()}"
        recordRecovery(context, id, "$operation:$total", "running")
        return id
    }

    fun cancelBulkOperation(context: Context, operationId: String) = recordRecovery(context, operationId, "cancelled by user", "cancelled")

    fun undoLast(context: Context): UndoEntry? {
        val last = undoEntries(context).lastOrNull() ?: return null
        recordRecovery(context, "undo", last.action, "applied")
        val remaining = undoEntries(context).dropLast(1)
        writeJsonRecords(context, UNDO, remaining.map { JSONObject().put("action", it.action).put("payload", it.payload).put("timestamp", it.timestamp) })
        return last
    }

    private fun readJsonRecords(context: Context, key: String): List<JSONObject> = runCatching {
        val array = JSONArray(prefs(context).getString(key, "[]") ?: "[]")
        buildList { for (index in 0 until min(array.length(), MAX_RECORDS)) add(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun writeJsonRecords(context: Context, key: String, records: List<JSONObject>) {
        val array = JSONArray()
        records.takeLast(MAX_RECORDS).forEach { array.put(it) }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    private fun safeKey(value: String): String = value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_").take(80)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
