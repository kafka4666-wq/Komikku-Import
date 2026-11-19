package eu.kanade.domain.ui

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, app-local personal Library data for the Library Companion tools.
 * This database is deliberately separate from source metadata and WebDAV backups.
 */
object LibraryCompanionStore {
    private const val DB_NAME = "komikku_library_companion.db"
    private const val DB_VERSION = 2
    private const val TABLE_MANGA = "personal_manga"
    private const val TABLE_QUEUE = "reading_queue"
    private const val TABLE_GOALS = "reading_goals"
    private const val TABLE_VIEWS = "saved_views"
    private const val TABLE_CREATORS = "followed_creators"
    private const val TABLE_COLORS = "tag_colors"
    private const val TABLE_IMPORT_PROFILES = "import_profiles"
    private const val TABLE_BACKUP_PROFILES = "backup_profiles"
    private const val TABLE_ACTIVITY = "activity_log"
    private const val TABLE_UNDO = "undo_log"
    private const val TABLE_SETTINGS = "tool_settings"

    data class PersonalRecord(
        val mangaId: Long,
        val note: String = "",
        val rating: Int = 0,
        val tags: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
        val status: String = "Want to read",
        val warning: String = "",
        val snoozedUntil: Long = 0L,
        val snoozeAtUnreadCount: Int = -1,
        val snoozeUntilStatus: String = "",
        val favoriteRank: Int = 0,
        val bundleName: String = "",
        val readingMode: Int = -1,
        val expectedReleaseDays: Int = 0,
        val inboxReviewed: Boolean = false,
        val lastSkippedAt: Long = 0L,
    )

    data class QueueEntry(
        val mangaId: Long,
        val position: Int,
        val unreadLimit: Int = 5,
        val priority: Int = 0,
        val addedAt: Long = System.currentTimeMillis(),
        val currentChapterId: Long? = null,
    )

    data class Goal(val month: String, val chapterTarget: Int, val bookTarget: Int)
    data class SavedView(val name: String, val query: String, val updatedAt: Long)
    data class ImportProfile(val name: String, val sourceIds: List<Long>, val matchThreshold: Int, val strongOnly: Boolean)
    data class BackupProfile(
        val name: String,
        val includeNotes: Boolean,
        val includeTags: Boolean,
        val includeRatings: Boolean,
        val includeStatuses: Boolean,
        val includeQueues: Boolean,
        val includeGoals: Boolean,
        val includeProgress: Boolean = true,
        val includeCategories: Boolean = false,
        val includeSettings: Boolean = false,
    )
    data class ActivityEntry(val id: Long, val mangaId: Long?, val action: String, val details: String, val timestamp: Long)
    data class UndoResult(val mangaId: Long, val previous: PersonalRecord?)

    private class Helper(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE_MANGA (
                    manga_id INTEGER PRIMARY KEY,
                    note TEXT NOT NULL DEFAULT '',
                    rating INTEGER NOT NULL DEFAULT 0,
                    tags TEXT NOT NULL DEFAULT '[]',
                    labels TEXT NOT NULL DEFAULT '[]',
                    status TEXT NOT NULL DEFAULT 'Want to read',
                    warning TEXT NOT NULL DEFAULT '',
                    snoozed_until INTEGER NOT NULL DEFAULT 0,
                    snooze_unread_count INTEGER NOT NULL DEFAULT -1,
                    snooze_until_status TEXT NOT NULL DEFAULT '',
                    favorite_rank INTEGER NOT NULL DEFAULT 0,
                    bundle_name TEXT NOT NULL DEFAULT '',
                    reading_mode INTEGER NOT NULL DEFAULT -1,
                    expected_release_days INTEGER NOT NULL DEFAULT 0,
                    inbox_reviewed INTEGER NOT NULL DEFAULT 0,
                    last_skipped_at INTEGER NOT NULL DEFAULT 0
                )""".trimIndent(),
            )
            db.execSQL("CREATE TABLE $TABLE_QUEUE (manga_id INTEGER PRIMARY KEY, position INTEGER NOT NULL, unread_limit INTEGER NOT NULL DEFAULT 5, priority INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL, current_chapter_id INTEGER)")
            db.execSQL("CREATE TABLE $TABLE_GOALS (month TEXT PRIMARY KEY, chapter_target INTEGER NOT NULL DEFAULT 0, book_target INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE $TABLE_VIEWS (name TEXT PRIMARY KEY, query TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_CREATORS (name TEXT PRIMARY KEY COLLATE NOCASE, added_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_COLORS (tag TEXT PRIMARY KEY COLLATE NOCASE, color TEXT NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_IMPORT_PROFILES (name TEXT PRIMARY KEY, source_ids TEXT NOT NULL, match_threshold INTEGER NOT NULL, strong_only INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_BACKUP_PROFILES (name TEXT PRIMARY KEY, include_notes INTEGER NOT NULL, include_tags INTEGER NOT NULL, include_ratings INTEGER NOT NULL, include_statuses INTEGER NOT NULL, include_queues INTEGER NOT NULL, include_goals INTEGER NOT NULL, include_progress INTEGER NOT NULL, include_categories INTEGER NOT NULL, include_settings INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_ACTIVITY (id INTEGER PRIMARY KEY AUTOINCREMENT, manga_id INTEGER, action TEXT NOT NULL, details TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_UNDO (id INTEGER PRIMARY KEY AUTOINCREMENT, manga_id INTEGER NOT NULL, previous_json TEXT, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_SETTINGS (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE INDEX activity_timestamp_idx ON $TABLE_ACTIVITY(timestamp DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE_MANGA ADD COLUMN snooze_until_status TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    private inline fun <T> withDatabase(context: Context, write: Boolean = false, block: (SQLiteDatabase) -> T): T {
        Helper(context).use { helper ->
            val db = if (write) helper.writableDatabase else helper.readableDatabase
            return block(db)
        }
    }

    fun getRecord(context: Context, mangaId: Long): PersonalRecord = withDatabase(context) { db ->
        db.query(TABLE_MANGA, null, "manga_id = ?", arrayOf(mangaId.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPersonalRecord() else PersonalRecord(mangaId)
        }
    }

    fun getRecords(context: Context): Map<Long, PersonalRecord> = withDatabase(context) { db ->
        buildMap {
            db.query(TABLE_MANGA, null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = cursor.toPersonalRecord()
                    put(record.mangaId, record)
                }
            }
        }
    }

    /** Saves personal fields and records a reversible snapshot of the previous values. */
    fun saveRecord(context: Context, record: PersonalRecord, action: String = "Personal details updated") {
        withDatabase(context, write = true) { db ->
            db.beginTransaction()
            try {
                val previous = getRecord(db, record.mangaId)
                db.insert(TABLE_UNDO, null, ContentValues().apply {
                    put("manga_id", record.mangaId)
                    put("previous_json", if (previous == null) null else recordToJson(previous).toString())
                    put("timestamp", System.currentTimeMillis())
                })
                saveRecord(db, record)
                appendActivity(db, record.mangaId, action, "Personal fields saved")
                db.execSQL("DELETE FROM $TABLE_UNDO WHERE id NOT IN (SELECT id FROM $TABLE_UNDO ORDER BY id DESC LIMIT 100)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun markInboxReviewed(context: Context, mangaId: Long) {
        val old = getRecord(context, mangaId)
        saveRecord(context, old.copy(inboxReviewed = true), "Inbox item reviewed")
    }

    fun queue(context: Context): List<QueueEntry> = withDatabase(context) { db ->
        db.query(TABLE_QUEUE, null, null, null, null, null, "priority DESC, position ASC, added_at ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toQueueEntry()) }
        }
    }

    fun addToQueue(context: Context, mangaId: Long, unreadLimit: Int = 5, priority: Int = 0, currentChapterId: Long? = null) {
        withDatabase(context, write = true) { db ->
            val existing = queue(db).firstOrNull { it.mangaId == mangaId }
            val pos = existing?.position ?: queue(db).size
            db.insertWithOnConflict(TABLE_QUEUE, null, ContentValues().apply {
                put("manga_id", mangaId)
                put("position", pos)
                put("unread_limit", unreadLimit.coerceIn(1, 100))
                put("priority", priority.coerceIn(0, 3))
                put("added_at", existing?.addedAt ?: System.currentTimeMillis())
                if (currentChapterId == null) putNull("current_chapter_id") else put("current_chapter_id", currentChapterId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            normalizeQueue(db)
            appendActivity(db, mangaId, "Added to reading queue", "limit=$unreadLimit; priority=$priority")
        }
    }

    fun removeFromQueue(context: Context, mangaId: Long) = withDatabase(context, write = true) { db ->
        db.delete(TABLE_QUEUE, "manga_id = ?", arrayOf(mangaId.toString()))
        normalizeQueue(db)
        appendActivity(db, mangaId, "Removed from reading queue", "")
    }

    fun moveQueueItem(context: Context, mangaId: Long, offset: Int) {
        withDatabase(context, write = true) { db ->
            val items = queue(db).toMutableList()
            val index = items.indexOfFirst { it.mangaId == mangaId }
            val target = (index + offset).coerceIn(0, items.lastIndex)
            if (index >= 0 && target in items.indices && index != target) {
                val item = items.removeAt(index)
                items.add(target, item)
                saveQueue(db, items)
                appendActivity(db, mangaId, "Reading queue reordered", "position=$target")
            }
        }
    }

    fun applyQueuePriority(context: Context, mangaId: Long, priority: Int) {
        withDatabase(context, write = true) { db ->
            val entry = queue(db).firstOrNull { it.mangaId == mangaId } ?: return@withDatabase
            db.update(TABLE_QUEUE, ContentValues().apply { put("priority", priority.coerceIn(0, 3)) }, "manga_id = ?", arrayOf(mangaId.toString()))
            appendActivity(db, mangaId, "Reading queue priority changed", "${entry.priority}->$priority")
        }
    }

    fun shuffleQueue(context: Context, random: kotlin.random.Random = kotlin.random.Random.Default) = withDatabase(context, write = true) { db ->
        val shuffled = queue(db)
            .groupBy { it.priority }
            .toSortedMap(compareByDescending<Int> { it })
            .values
            .flatMap { it.shuffled(random) }
        saveQueue(db, shuffled)
        appendActivity(db, null, "Reading queue shuffled", "Kept priority levels; randomized titles within each level")
    }

    fun goal(context: Context, month: String): Goal = withDatabase(context) { db ->
        db.query(TABLE_GOALS, null, "month = ?", arrayOf(month), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) Goal(month, cursor.getInt(cursor.getColumnIndexOrThrow("chapter_target")), cursor.getInt(cursor.getColumnIndexOrThrow("book_target")))
            else Goal(month, 0, 0)
        }
    }

    fun saveGoal(context: Context, goal: Goal) = withDatabase(context, write = true) { db ->
        db.insertWithOnConflict(TABLE_GOALS, null, ContentValues().apply {
            put("month", goal.month)
            put("chapter_target", goal.chapterTarget.coerceIn(0, 100_000))
            put("book_target", goal.bookTarget.coerceIn(0, 10_000))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        appendActivity(db, null, "Reading goal updated", "${goal.month}: ${goal.chapterTarget} chapters, ${goal.bookTarget} books")
    }

    fun goals(context: Context): List<Goal> = withDatabase(context) { db ->
        db.query(TABLE_GOALS, null, null, null, null, null, "month DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(Goal(cursor.getString(cursor.getColumnIndexOrThrow("month")), cursor.getInt(cursor.getColumnIndexOrThrow("chapter_target")), cursor.getInt(cursor.getColumnIndexOrThrow("book_target")))) }
        }
    }

    fun saveView(context: Context, name: String, query: String) {
        val cleanName = name.trim().take(80)
        val cleanQuery = query.trim().take(500)
        if (cleanName.isBlank() || cleanQuery.isBlank()) return
        withDatabase(context, write = true) { db ->
            db.insertWithOnConflict(TABLE_VIEWS, null, ContentValues().apply {
                put("name", cleanName)
                put("query", cleanQuery)
                put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun views(context: Context): List<SavedView> = withDatabase(context) { db ->
        db.query(TABLE_VIEWS, null, null, null, null, null, "updated_at DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(SavedView(cursor.getString(cursor.getColumnIndexOrThrow("name")), cursor.getString(cursor.getColumnIndexOrThrow("query")), cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))) }
        }
    }

    fun deleteView(context: Context, name: String) = withDatabase(context, write = true) { db -> db.delete(TABLE_VIEWS, "name = ?", arrayOf(name)) }

    fun followCreator(context: Context, name: String, followed: Boolean) = withDatabase(context, write = true) { db ->
        val cleanName = name.trim().take(160)
        if (cleanName.isBlank()) return@withDatabase
        if (followed) db.insertWithOnConflict(TABLE_CREATORS, null, ContentValues().apply { put("name", cleanName); put("added_at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        else db.delete(TABLE_CREATORS, "name = ? COLLATE NOCASE", arrayOf(cleanName))
    }

    fun followedCreators(context: Context): List<String> = withDatabase(context) { db ->
        db.query(TABLE_CREATORS, arrayOf("name"), null, null, null, null, "name COLLATE NOCASE ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    fun setTagColor(context: Context, tag: String, color: String) = withDatabase(context, write = true) { db ->
        val cleanTag = tag.trim().take(100)
        val cleanColor = color.trim().take(24)
        if (cleanTag.isBlank() || cleanColor.isBlank()) return@withDatabase
        db.insertWithOnConflict(TABLE_COLORS, null, ContentValues().apply { put("tag", cleanTag); put("color", cleanColor) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun tagColors(context: Context): Map<String, String> = withDatabase(context) { db ->
        buildMap {
            db.query(TABLE_COLORS, null, null, null, null, null, "tag COLLATE NOCASE ASC").use { cursor ->
                while (cursor.moveToNext()) put(cursor.getString(cursor.getColumnIndexOrThrow("tag")), cursor.getString(cursor.getColumnIndexOrThrow("color")))
            }
        }
    }

    fun saveImportProfile(context: Context, profile: ImportProfile) = withDatabase(context, write = true) { db ->
        val name = profile.name.trim().take(80)
        if (name.isBlank()) return@withDatabase
        db.insertWithOnConflict(TABLE_IMPORT_PROFILES, null, ContentValues().apply {
            put("name", name)
            put("source_ids", encodeLongs(profile.sourceIds))
            put("match_threshold", profile.matchThreshold.coerceIn(0, 100))
            put("strong_only", if (profile.strongOnly) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun importProfiles(context: Context): List<ImportProfile> = withDatabase(context) { db ->
        db.query(TABLE_IMPORT_PROFILES, null, null, null, null, null, "name COLLATE NOCASE ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(ImportProfile(cursor.getString(cursor.getColumnIndexOrThrow("name")), decodeLongs(cursor.getString(cursor.getColumnIndexOrThrow("source_ids"))), cursor.getInt(cursor.getColumnIndexOrThrow("match_threshold")), cursor.getInt(cursor.getColumnIndexOrThrow("strong_only")) != 0)) }
        }
    }

    fun setActiveImportProfile(context: Context, name: String?) = withDatabase(context, write = true) { db ->
        if (name.isNullOrBlank()) {
            db.delete(TABLE_SETTINGS, "key = ?", arrayOf("active_import_profile"))
        } else {
            db.insertWithOnConflict(TABLE_SETTINGS, null, ContentValues().apply {
                put("key", "active_import_profile")
                put("value", name.trim().take(80))
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun activeImportProfile(context: Context): ImportProfile? {
        val selectedName = withDatabase(context) { db ->
            db.query(TABLE_SETTINGS, arrayOf("value"), "key = ?", arrayOf("active_import_profile"), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } ?: return null
        return importProfiles(context).firstOrNull { it.name == selectedName }
    }

    fun recordLibrarySnapshot(
        context: Context,
        mangaIds: Set<Long>,
        categoryIdsByManga: Map<Long, Set<Long>> = emptyMap(),
    ) = withDatabase(context, write = true) { db ->
        val key = "library_snapshot_ids"
        val previous = db.query(TABLE_SETTINGS, arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) decodeLongs(cursor.getString(0)).toSet() else null
        }
        val previousCategories = db.query(TABLE_SETTINGS, arrayOf("value"), "key = ?", arrayOf("library_snapshot_categories"), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else runCatching {
                val json = JSONObject(cursor.getString(0))
                buildMap<Long, Set<Long>> {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val mangaId = keys.next().toLongOrNull() ?: continue
                        val categories = json.optJSONArray(mangaId.toString()) ?: continue
                        put(mangaId, buildSet {
                            for (index in 0 until categories.length()) {
                                categories.optLong(index).takeIf { it >= 0L }?.let(::add)
                            }
                        })
                    }
                }
            }.getOrNull()
        }
        if (previous != null) {
            (mangaIds - previous).take(500).forEach { appendActivity(db, it, "Added to library", "Observed in favorite-library snapshot") }
            (previous - mangaIds).take(500).forEach { appendActivity(db, it, "Removed from library", "No longer present in favorite-library snapshot") }
            if (previousCategories != null) {
                (mangaIds intersect previous).forEach { mangaId ->
                    val before = previousCategories[mangaId].orEmpty()
                    val after = categoryIdsByManga[mangaId].orEmpty()
                    if (before != after) appendActivity(db, mangaId, "Category assignment changed", "Category IDs: ${before.sorted()} → ${after.sorted()}")
                }
            }
        }
        db.insertWithOnConflict(TABLE_SETTINGS, null, ContentValues().apply {
            put("key", key)
            put("value", encodeLongs(mangaIds.toList()))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val categoryJson = JSONObject()
        categoryIdsByManga.toSortedMap().forEach { (mangaId, categories) -> categoryJson.put(mangaId.toString(), JSONArray(categories.sorted())) }
        db.insertWithOnConflict(TABLE_SETTINGS, null, ContentValues().apply {
            put("key", "library_snapshot_categories")
            put("value", categoryJson.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveBackupProfile(context: Context, profile: BackupProfile) = withDatabase(context, write = true) { db ->
        val name = profile.name.trim().take(80)
        if (name.isBlank()) return@withDatabase
        db.insertWithOnConflict(TABLE_BACKUP_PROFILES, null, ContentValues().apply {
            put("name", name)
            put("include_notes", profile.includeNotes.asInt())
            put("include_tags", profile.includeTags.asInt())
            put("include_ratings", profile.includeRatings.asInt())
            put("include_statuses", profile.includeStatuses.asInt())
            put("include_queues", profile.includeQueues.asInt())
            put("include_goals", profile.includeGoals.asInt())
            put("include_progress", profile.includeProgress.asInt())
            put("include_categories", profile.includeCategories.asInt())
            put("include_settings", profile.includeSettings.asInt())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun backupProfiles(context: Context): List<BackupProfile> = withDatabase(context) { db ->
        db.query(TABLE_BACKUP_PROFILES, null, null, null, null, null, "name COLLATE NOCASE ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(BackupProfile(cursor.getString(cursor.getColumnIndexOrThrow("name")), cursor.getInt(cursor.getColumnIndexOrThrow("include_notes")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_tags")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_ratings")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_statuses")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_queues")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_goals")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_progress")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_categories")) != 0, cursor.getInt(cursor.getColumnIndexOrThrow("include_settings")) != 0)) }
        }
    }

    fun setStorageThresholdBytes(context: Context, bytes: Long) = withDatabase(context, write = true) { db ->
        db.insertWithOnConflict(TABLE_SETTINGS, null, ContentValues().apply { put("key", "storage_threshold_bytes"); put("value", bytes.coerceAtLeast(0L).toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun storageThresholdBytes(context: Context): Long = withDatabase(context) { db ->
        db.query(TABLE_SETTINGS, arrayOf("value"), "key = ?", arrayOf("storage_threshold_bytes"), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
        }
    }

    fun setting(context: Context, key: String, default: String = ""): String = withDatabase(context) { db ->
        db.query(TABLE_SETTINGS, arrayOf("value"), "key = ?", arrayOf(key.take(100)), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else default
        }
    }

    fun setSetting(context: Context, key: String, value: String) = withDatabase(context, write = true) { db ->
        val cleanKey = key.trim().take(100)
        if (cleanKey.isBlank()) return@withDatabase
        db.insertWithOnConflict(TABLE_SETTINGS, null, ContentValues().apply {
            put("key", cleanKey)
            put("value", value.take(1_000_000))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun dismissedCleanupKeys(context: Context): Set<String> = runCatching {
        val json = JSONArray(setting(context, "cleanup_dismissed_keys", "[]"))
        buildSet {
            for (index in 0 until json.length()) json.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }.getOrDefault(emptySet())

    fun dismissCleanupFinding(context: Context, key: String) {
        val cleanKey = key.trim().take(200)
        if (cleanKey.isBlank()) return
        val keys = (dismissedCleanupKeys(context) + cleanKey).sorted().take(5_000)
        setSetting(context, "cleanup_dismissed_keys", JSONArray(keys).toString())
    }

    fun clearCleanupDismissals(context: Context) = setSetting(context, "cleanup_dismissed_keys", "[]")

    fun releaseReminderSnapshots(context: Context): Map<Long, Long> {
        val json = runCatching { JSONObject(setting(context, "release_reminder_snapshots", "{}")) }.getOrNull() ?: return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val mangaId = key.toLongOrNull() ?: continue
                val timestamp = json.optLong(key, 0L)
                put(mangaId, timestamp)
            }
        }
    }

    fun saveReleaseReminderSnapshots(context: Context, snapshots: Map<Long, Long>) {
        val json = JSONObject()
        snapshots.entries.sortedBy { it.key }.take(20_000).forEach { (mangaId, timestamp) -> json.put(mangaId.toString(), timestamp) }
        setSetting(context, "release_reminder_snapshots", json.toString())
    }

    fun recordActivity(context: Context, mangaId: Long?, action: String, details: String) = withDatabase(context, write = true) { db ->
        appendActivity(db, mangaId, action, details)
    }

    fun activity(context: Context, limit: Int = 100): List<ActivityEntry> = withDatabase(context) { db ->
        db.query(TABLE_ACTIVITY, null, null, null, null, null, "timestamp DESC", limit.coerceIn(1, 500).toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toActivityEntry()) }
        }
    }

    fun undoLastPersonalEdit(context: Context): UndoResult? = withDatabase(context, write = true) { db ->
        db.beginTransaction()
        try {
            val last = db.query(TABLE_UNDO, null, null, null, null, null, "id DESC", "1").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow("id")) to cursor.getLong(cursor.getColumnIndexOrThrow("manga_id")) to cursor.getString(cursor.getColumnIndexOrThrow("previous_json")) else null
            } ?: return@withDatabase null
            val undoId = last.first.first
            val mangaId = last.first.second
            val previousJson = last.second
            val previous = previousJson?.let { runCatching { jsonToRecord(JSONObject(it)) }.getOrNull() }
            if (previous == null) db.delete(TABLE_MANGA, "manga_id = ?", arrayOf(mangaId.toString())) else saveRecord(db, previous)
            db.delete(TABLE_UNDO, "id = ?", arrayOf(undoId.toString()))
            appendActivity(db, mangaId, "Undo personal edit", "Restored previous personal fields")
            db.setTransactionSuccessful()
            UndoResult(mangaId, previous)
        } finally {
            db.endTransaction()
        }
    }

    fun exportJson(context: Context, profile: BackupProfile, librarySnapshot: JSONArray = JSONArray()): String = withDatabase(context) { db ->
        val root = JSONObject().put("format", "komikku-library-companion").put("version", 1).put("exportedAt", System.currentTimeMillis()).put("profile", profile.name)
        if (profile.includeNotes || profile.includeTags || profile.includeRatings || profile.includeStatuses) {
            val rows = JSONArray()
            db.query(TABLE_MANGA, null, null, null, null, null, "manga_id ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    val record = cursor.toPersonalRecord()
                    val row = JSONObject().put("mangaId", record.mangaId)
                    if (profile.includeNotes) row.put("note", record.note).put("warning", record.warning)
                    if (profile.includeTags) row.put("tags", JSONArray(record.tags)).put("labels", JSONArray(record.labels))
                    if (profile.includeRatings) row.put("rating", record.rating).put("favoriteRank", record.favoriteRank)
                    if (profile.includeStatuses) row.put("status", record.status).put("snoozedUntil", record.snoozedUntil).put("snoozeAtUnreadCount", record.snoozeAtUnreadCount).put("snoozeUntilStatus", record.snoozeUntilStatus).put("bundleName", record.bundleName).put("expectedReleaseDays", record.expectedReleaseDays)
                    rows.put(row)
                }
            }
            root.put("personalBooks", rows)
        }
        if (profile.includeQueues) {
            val rows = JSONArray()
            queue(db).forEach { entry ->
                rows.put(JSONObject().put("mangaId", entry.mangaId).put("position", entry.position).put("unreadLimit", entry.unreadLimit).put("priority", entry.priority).put("addedAt", entry.addedAt).put("currentChapterId", entry.currentChapterId ?: JSONObject.NULL))
            }
            root.put("readingQueue", rows)
        }
        if (profile.includeGoals) {
            val rows = JSONArray()
            db.query(TABLE_GOALS, null, null, null, null, null, "month ASC").use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().put("month", cursor.getString(cursor.getColumnIndexOrThrow("month"))).put("chapters", cursor.getInt(cursor.getColumnIndexOrThrow("chapter_target"))).put("books", cursor.getInt(cursor.getColumnIndexOrThrow("book_target"))))
            }
            root.put("readingGoals", rows)
        }
        if (profile.includeProgress || profile.includeCategories) {
            root.put("libraryState", JSONArray().also { rows ->
                for (index in 0 until librarySnapshot.length()) {
                    val source = librarySnapshot.optJSONObject(index) ?: continue
                    val row = JSONObject().put("mangaId", source.optLong("mangaId"))
                    if (profile.includeProgress) row.put("readCount", source.optLong("readCount")).put("totalChapters", source.optLong("totalChapters"))
                    if (profile.includeCategories) row.put("categoryIds", source.optJSONArray("categoryIds") ?: JSONArray())
                    rows.put(row)
                }
            })
        }
        if (profile.includeSettings) {
            val settings = JSONObject()
            db.query(TABLE_SETTINGS, null, null, null, null, null, "key ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(cursor.getColumnIndexOrThrow("key"))
                    if (key !in setOf("library_snapshot_ids", "library_snapshot_categories", "cleanup_dismissed_keys")) settings.put(key, cursor.getString(cursor.getColumnIndexOrThrow("value")))
                }
            }
            root.put("toolSettings", settings)
        }
        root.toString(2)
    }

    private fun getRecord(db: SQLiteDatabase, mangaId: Long): PersonalRecord? = db.query(TABLE_MANGA, null, "manga_id = ?", arrayOf(mangaId.toString()), null, null, null).use { cursor -> if (cursor.moveToFirst()) cursor.toPersonalRecord() else null }

    private fun saveRecord(db: SQLiteDatabase, record: PersonalRecord) {
        db.insertWithOnConflict(TABLE_MANGA, null, ContentValues().apply {
            put("manga_id", record.mangaId)
            put("note", record.note.take(8_000))
            put("rating", record.rating.coerceIn(0, 5))
            put("tags", encodeStrings(record.tags))
            put("labels", encodeStrings(record.labels))
            put("status", record.status.take(40))
            put("warning", record.warning.take(2_000))
            put("snoozed_until", record.snoozedUntil.coerceAtLeast(0L))
            put("snooze_unread_count", record.snoozeAtUnreadCount.coerceAtLeast(-1))
            put("snooze_until_status", record.snoozeUntilStatus.take(40))
            put("favorite_rank", record.favoriteRank.coerceIn(0, 100))
            put("bundle_name", record.bundleName.take(120))
            put("reading_mode", record.readingMode.coerceIn(-1, 10))
            put("expected_release_days", record.expectedReleaseDays.coerceIn(0, 365))
            put("inbox_reviewed", record.inboxReviewed.asInt())
            put("last_skipped_at", record.lastSkippedAt.coerceAtLeast(0L))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun queue(db: SQLiteDatabase): List<QueueEntry> = db.query(TABLE_QUEUE, null, null, null, null, null, "priority DESC, position ASC, added_at ASC").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toQueueEntry()) } }

    private fun saveQueue(db: SQLiteDatabase, entries: List<QueueEntry>) {
        db.beginTransaction()
        try {
            db.delete(TABLE_QUEUE, null, null)
            entries.forEachIndexed { index, entry -> db.insert(TABLE_QUEUE, null, ContentValues().apply {
                put("manga_id", entry.mangaId)
                put("position", index)
                put("unread_limit", entry.unreadLimit.coerceIn(1, 100))
                put("priority", entry.priority.coerceIn(0, 3))
                put("added_at", entry.addedAt)
                if (entry.currentChapterId == null) putNull("current_chapter_id") else put("current_chapter_id", entry.currentChapterId)
            }) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun normalizeQueue(db: SQLiteDatabase) = saveQueue(db, queue(db))

    private fun appendActivity(db: SQLiteDatabase, mangaId: Long?, action: String, details: String) {
        db.insert(TABLE_ACTIVITY, null, ContentValues().apply {
            if (mangaId == null) putNull("manga_id") else put("manga_id", mangaId)
            put("action", action.take(100))
            put("details", details.take(500))
            put("timestamp", System.currentTimeMillis())
        })
        db.execSQL("DELETE FROM $TABLE_ACTIVITY WHERE id NOT IN (SELECT id FROM $TABLE_ACTIVITY ORDER BY id DESC LIMIT 500)")
    }

    private fun Cursor.toPersonalRecord() = PersonalRecord(
        mangaId = getLong(getColumnIndexOrThrow("manga_id")),
        note = getString(getColumnIndexOrThrow("note")).orEmpty(),
        rating = getInt(getColumnIndexOrThrow("rating")),
        tags = decodeStrings(getString(getColumnIndexOrThrow("tags"))),
        labels = decodeStrings(getString(getColumnIndexOrThrow("labels"))),
        status = getString(getColumnIndexOrThrow("status")).orEmpty(),
        warning = getString(getColumnIndexOrThrow("warning")).orEmpty(),
        snoozedUntil = getLong(getColumnIndexOrThrow("snoozed_until")),
        snoozeAtUnreadCount = getInt(getColumnIndexOrThrow("snooze_unread_count")),
        snoozeUntilStatus = getString(getColumnIndexOrThrow("snooze_until_status")).orEmpty(),
        favoriteRank = getInt(getColumnIndexOrThrow("favorite_rank")),
        bundleName = getString(getColumnIndexOrThrow("bundle_name")).orEmpty(),
        readingMode = getInt(getColumnIndexOrThrow("reading_mode")),
        expectedReleaseDays = getInt(getColumnIndexOrThrow("expected_release_days")),
        inboxReviewed = getInt(getColumnIndexOrThrow("inbox_reviewed")) != 0,
        lastSkippedAt = getLong(getColumnIndexOrThrow("last_skipped_at")),
    )

    private fun Cursor.toQueueEntry() = QueueEntry(
        mangaId = getLong(getColumnIndexOrThrow("manga_id")),
        position = getInt(getColumnIndexOrThrow("position")),
        unreadLimit = getInt(getColumnIndexOrThrow("unread_limit")),
        priority = getInt(getColumnIndexOrThrow("priority")),
        addedAt = getLong(getColumnIndexOrThrow("added_at")),
        currentChapterId = if (isNull(getColumnIndexOrThrow("current_chapter_id"))) null else getLong(getColumnIndexOrThrow("current_chapter_id")),
    )

    private fun Cursor.toActivityEntry() = ActivityEntry(
        id = getLong(getColumnIndexOrThrow("id")),
        mangaId = if (isNull(getColumnIndexOrThrow("manga_id"))) null else getLong(getColumnIndexOrThrow("manga_id")),
        action = getString(getColumnIndexOrThrow("action")),
        details = getString(getColumnIndexOrThrow("details")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )

    private fun recordToJson(record: PersonalRecord) = JSONObject()
        .put("mangaId", record.mangaId).put("note", record.note).put("rating", record.rating)
        .put("tags", JSONArray(record.tags)).put("labels", JSONArray(record.labels)).put("status", record.status)
        .put("warning", record.warning).put("snoozedUntil", record.snoozedUntil).put("snoozeAtUnreadCount", record.snoozeAtUnreadCount).put("snoozeUntilStatus", record.snoozeUntilStatus)
        .put("favoriteRank", record.favoriteRank).put("bundleName", record.bundleName).put("readingMode", record.readingMode)
        .put("expectedReleaseDays", record.expectedReleaseDays).put("inboxReviewed", record.inboxReviewed).put("lastSkippedAt", record.lastSkippedAt)

    private fun jsonToRecord(json: JSONObject) = PersonalRecord(
        mangaId = json.getLong("mangaId"), note = json.optString("note"), rating = json.optInt("rating"),
        tags = json.optJSONArray("tags").toStringList(), labels = json.optJSONArray("labels").toStringList(),
        status = json.optString("status", "Want to read"), warning = json.optString("warning"),
        snoozedUntil = json.optLong("snoozedUntil"), snoozeAtUnreadCount = json.optInt("snoozeAtUnreadCount", -1), snoozeUntilStatus = json.optString("snoozeUntilStatus"),
        favoriteRank = json.optInt("favoriteRank"), bundleName = json.optString("bundleName"),
        readingMode = json.optInt("readingMode", -1), expectedReleaseDays = json.optInt("expectedReleaseDays"),
        inboxReviewed = json.optBoolean("inboxReviewed"), lastSkippedAt = json.optLong("lastSkippedAt"),
    )

    private fun encodeStrings(values: List<String>) = JSONArray(values.map { it.trim().take(160) }.filter(String::isNotBlank).distinct().take(100)).toString()
    private fun decodeStrings(raw: String?): List<String> = runCatching { JSONArray(raw ?: "[]").toStringList() }.getOrDefault(emptyList())
    private fun JSONArray?.toStringList(): List<String> = this?.let { array -> buildList { for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add) } }.orEmpty().distinct().take(100)
    private fun encodeLongs(values: List<Long>) = JSONArray(values.distinct().take(100).map(Long::toString)).toString()
    private fun decodeLongs(raw: String?): List<Long> = runCatching { JSONArray(raw ?: "[]").toStringList().mapNotNull(String::toLongOrNull) }.getOrDefault(emptyList())
    private fun Boolean.asInt() = if (this) 1 else 0
}
