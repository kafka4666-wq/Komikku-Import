package eu.kanade.domain.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.view.Window
import android.view.WindowManager
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Compact, local-first runtime layer for Doujin Customisations.
 *
 * Every expensive operation accepts a bounded input or produces a bounded result. User-owned
 * tags, bookmarks, seen state, source preferences, repair requests, and heatmap counters are
 * stored separately from source metadata and never modify library rows automatically.
 */
object DoujinFeatureEngine {
    private const val STORE = "doujin_runtime_v2"
    private const val SEEN_KEY = "discovery_seen"
    private const val PERSONAL_TAG_NAMES_KEY = "personal_tag_names"
    private const val PERSONAL_TAG_KEY = "personal_tags"
    private const val BOOKMARK_KEY = "page_bookmarks"
    private const val HEATMAP_KEY = "reading_heatmap"
    private const val SAVED_COMBINATION_KEY = "saved_tag_combinations"
    private const val PREFERRED_SOURCE_KEY = "preferred_sources"
    private const val REPAIR_QUEUE_KEY = "metadata_repair_queue"
    private const val IGNORED_ISSUES_KEY = "ignored_gallery_issues"
    private const val MAX_STORED_IDS = 20_000
    private const val MAX_STORED_TAGS = 100
    private const val MAX_BOOKMARKS = 2_000
    private const val MAX_SAVED_COMBINATIONS = 50

    data class DuplicateCandidate(
        val manga: Manga,
        val confidence: Int,
        val signals: List<String>,
    )

    data class MetadataIssue(
        val manga: Manga,
        val missing: List<String>,
    )

    data class GalleryIssue(
        val path: String,
        val kind: String,
        val detail: String,
    )

    data class PageBookmark(
        val mangaId: Long,
        val chapterId: Long,
        val page: Int,
        val timestamp: Long,
        val note: String = "",
    )

    data class SavedTagCombination(
        val name: String,
        val include: String,
        val exclude: String,
        val optional: String = "",
        val source: String = "all",
    )

    data class HeatmapStats(
        val byDay: Map<String, Int>,
        val total: Int,
        val activeDays: Int,
        val currentStreak: Int,
        val longestStreak: Int,
    )

    data class SourceWorkGroup(
        val title: String,
        val entries: List<Manga>,
        val preferredId: Long?,
    )

    fun normalize(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun titleKey(manga: Manga): String = normalize(manga.title)

    fun creatorKey(manga: Manga): String = normalize(manga.artist ?: manga.author)
        .ifBlank { normalize(manga.author ?: manga.artist) }
        .ifBlank { "unknown creator" }

    fun creatorName(manga: Manga): String = manga.artist?.takeIf { it.isNotBlank() }
        ?: manga.author?.takeIf { it.isNotBlank() }
        ?: "Unknown creator"

    fun tags(manga: Manga): Set<String> = manga.genre.orEmpty()
        .map(::normalize)
        .filter(String::isNotBlank)
        .toSet()

    fun parseTags(value: String): Set<String> = value.split(',', '\n', ';', '+')
        .asSequence()
        .map(::normalize)
        .filter(String::isNotBlank)
        .take(64)
        .toSet()

    fun parseTagWeights(value: String): Map<String, Float> = value.split(',', '\n', ';')
        .asSequence()
        .mapNotNull { row ->
            val parts = row.split('=', ':', limit = 2)
            val key = parts.firstOrNull()?.let(::normalize).orEmpty()
            val weight = parts.getOrNull(1)?.trim()?.toFloatOrNull()
            if (key.isNotBlank() && weight != null) key to weight.coerceIn(-10f, 10f) else null
        }
        .take(100)
        .toMap()

    fun tagExpressionMatches(
        manga: Manga,
        include: String,
        exclude: String,
        exact: Boolean,
        optional: String = "",
        personal: Set<String> = emptySet(),
    ): Boolean {
        val available = tags(manga) + personal.map(::normalize)
        val required = parseTags(include)
        val blocked = parseTags(exclude)
        val optionalTags = parseTags(optional)
        fun matches(wanted: String): Boolean = if (exact) wanted in available else available.any {
            it == wanted || it.contains(wanted) || wanted.contains(it)
        }
        val includesMatch = required.all(::matches)
        val excludesMatch = blocked.none(::matches)
        val optionalMatch = optionalTags.isEmpty() || optionalTags.any(::matches)
        return includesMatch && excludesMatch && optionalMatch
    }

    fun fuzzyScore(query: String, manga: Manga): Float {
        val needle = normalize(query)
        if (needle.isBlank()) return 1f
        val fields = listOf(manga.title, manga.author, manga.artist, manga.ogTitle, manga.ogAuthor, manga.ogArtist)
            .map(::normalize)
            .filter(String::isNotBlank)
        if (fields.any { it == needle }) return 1f
        if (fields.any { it.contains(needle) }) return 0.93f
        val queryTokens = needle.split(' ').filter(String::isNotBlank)
        val tokenHit = queryTokens.count { token -> fields.any { it.contains(token) } }
            .toFloat() / queryTokens.size.coerceAtLeast(1)
        val typo = fields.maxOfOrNull { field ->
            val distance = levenshtein(needle, field.take(160))
            1f - min(1f, distance.toFloat() / max(needle.length, field.length).coerceAtLeast(1))
        } ?: 0f
        return max(tokenHit * 0.82f, typo * 0.78f).coerceIn(0f, 1f)
    }

    fun similarityScore(left: Manga, right: Manga, tagWeights: Map<String, Float> = emptyMap()): Float {
        val title = fuzzyScore(left.title, right)
        val creator = when {
            creatorKey(left) == creatorKey(right) && creatorKey(left) != "unknown creator" -> 1f
            !left.author.isNullOrBlank() && normalize(left.author) == normalize(right.author) -> 0.9f
            !left.artist.isNullOrBlank() && normalize(left.artist) == normalize(right.artist) -> 0.9f
            else -> 0f
        }
        val overlap = tags(left).intersect(tags(right))
        val weightedOverlap = if (overlap.isEmpty()) 0f else {
            val numerator = overlap.sumOf { (tagWeights[it] ?: 1f).toDouble() }.toFloat()
            val denominator = tags(left).union(tags(right)).sumOf { (tagWeights[it] ?: 1f).toDouble() }
                .toFloat().coerceAtLeast(0.01f)
            (numerator / denominator).coerceIn(0f, 1f)
        }
        val languageOrSource = if (left.source == right.source) 0.08f else 0f
        return (title * 0.43f + creator * 0.30f + weightedOverlap * 0.19f + languageOrSource).coerceIn(0f, 1f)
    }

    fun discoveryScore(manga: Manga, query: String, requiredTags: Set<String>, weights: Map<String, Float>): Float {
        val search = fuzzyScore(query, manga)
        val tagScore = requiredTags.count { wanted -> tags(manga).any { it == wanted || it.contains(wanted) } }
            .toFloat() / requiredTags.size.coerceAtLeast(1)
        val preference = tags(manga).sumOf { (weights[it] ?: 0f).toDouble() }.toFloat()
        return (search * 0.70f + tagScore * 0.20f + (preference / 10f).coerceIn(-0.1f, 0.1f)).coerceIn(0f, 1f)
    }

    fun duplicateCandidate(left: Manga, right: Manga, tagWeights: Map<String, Float> = emptyMap()): DuplicateCandidate {
        val signals = buildList {
            if (left.url.isNotBlank() && left.source == right.source && left.url == right.url) add("same source ID/link")
            if (titleKey(left).isNotBlank() && titleKey(left) == titleKey(right)) add("same normalized title")
            if (!left.ogTitle.isBlank() && normalize(left.ogTitle) == normalize(right.ogTitle)) add("same alternate title")
            if (!left.author.isNullOrBlank() && normalize(left.author) == normalize(right.author)) add("same author")
            if (!left.artist.isNullOrBlank() && normalize(left.artist) == normalize(right.artist)) add("same artist/circle")
            if (tags(left).intersect(tags(right)).isNotEmpty()) add("overlapping tags")
            if (left.source == right.source) add("same source")
        }
        val titleExact = titleKey(left).isNotBlank() && titleKey(left) == titleKey(right)
        val score = if (left.source == right.source && left.url == right.url && left.url.isNotBlank()) 100
        else ((similarityScore(left, right, tagWeights) * 100f) + if (titleExact) 12f else 0f).toInt().coerceIn(0, 99)
        return DuplicateCandidate(right, score, signals.ifEmpty { listOf("metadata similarity") })
    }

    fun metadataIssues(manga: Manga): MetadataIssue {
        val missing = buildList {
            if (manga.title.isBlank()) add("title")
            if (manga.thumbnailUrl.isNullOrBlank()) add("cover")
            if (manga.description.isNullOrBlank()) add("description")
            if (manga.author.isNullOrBlank() && manga.artist.isNullOrBlank()) add("artist/circle")
            if (manga.genre.isNullOrEmpty()) add("tags")
            if (manga.source <= 0L) add("source mapping")
        }
        return MetadataIssue(manga, missing)
    }

    suspend fun favoriteWindow(repository: MangaRepository, limit: Int, offset: Int): List<Manga> =
        repository.getFavoriteMangaPage(limit.coerceIn(1, 200).toLong(), offset.coerceAtLeast(0).toLong())

    fun selectRandom(candidates: List<Manga>, query: String = "", requiredTags: Set<String> = emptySet(), weights: Map<String, Float> = emptyMap()): Manga? {
        val filtered = candidates.asSequence()
            .filter { requiredTags.isEmpty() || requiredTags.all { tag -> tags(it).any { value -> value == tag || value.contains(tag) } } }
            .sortedByDescending { discoveryScore(it, query, requiredTags, weights) }
            .take(128)
            .toList()
        return filtered.randomOrNull(Random.Default)
    }

    fun sourceWorkGroups(mangas: List<Manga>, context: Context): List<SourceWorkGroup> = mangas.asSequence()
        .filter { titleKey(it).isNotBlank() }
        .groupBy(::titleKey)
        .values
        .filter { it.size > 1 }
        .map { entries ->
            val preferred = preferredSource(context, entries.first().id)
            SourceWorkGroup(entries.maxByOrNull { it.title.length }?.title.orEmpty(), entries, preferred)
        }
        .sortedBy { normalize(it.title) }
        .take(200)
        .toList()

    fun personalTagNames(context: Context): List<String> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(PERSONAL_TAG_NAMES_KEY, "Favorite,Read Again,Peak,Reference,Best Art")
        .orEmpty().split('|', ',').map(::normalize).filter(String::isNotBlank).distinct().take(MAX_STORED_TAGS)

    fun createPersonalTag(context: Context, name: String): Boolean {
        val clean = normalize(name)
        if (clean.isBlank() || personalTagNames(context).contains(clean)) return false
        val names = personalTagNames(context) + clean
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(PERSONAL_TAG_NAMES_KEY, names.takeLast(MAX_STORED_TAGS).joinToString("|"))
            .apply()
        return true
    }

    fun renamePersonalTag(context: Context, oldName: String, newName: String): Boolean {
        val old = normalize(oldName); val new = normalize(newName)
        if (old.isBlank() || new.isBlank() || old == new || personalTagNames(context).contains(new)) return false
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val names = personalTagNames(context).map { if (it == old) new else it }
        val assignments = allPersonalAssignments(context).mapValues { (_, tags) -> tags.map { if (it == old) new else it }.toSet() }
        prefs.edit().putString(PERSONAL_TAG_NAMES_KEY, names.joinToString("|"))
            .putString(PERSONAL_TAG_KEY, encodeAssignments(assignments)).apply()
        return true
    }

    fun deletePersonalTag(context: Context, name: String) {
        val clean = normalize(name)
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val names = personalTagNames(context).filterNot { it == clean }
        val assignments = allPersonalAssignments(context).mapValues { (_, tags) -> tags - clean }
        prefs.edit().putString(PERSONAL_TAG_NAMES_KEY, names.joinToString("|"))
            .putString(PERSONAL_TAG_KEY, encodeAssignments(assignments)).apply()
    }

    fun personalTags(context: Context, mangaId: Long): Set<String> = allPersonalAssignments(context)[mangaId].orEmpty()

    fun setPersonalTags(context: Context, mangaId: Long, tags: Set<String>) {
        val assignments = allPersonalAssignments(context).toMutableMap()
        assignments[mangaId] = tags.map(::normalize).filter(String::isNotBlank).toSet().take(MAX_STORED_TAGS).toSet()
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(PERSONAL_TAG_KEY, encodeAssignments(assignments.filterValues { it.isNotEmpty() }))
            .apply()
    }

    fun togglePersonalTag(context: Context, mangaId: Long, tag: String): Boolean {
        val current = personalTags(context, mangaId).toMutableSet()
        val clean = normalize(tag)
        val added = current.add(clean)
        setPersonalTags(context, mangaId, current)
        return added
    }

    fun bookmarks(context: Context, mangaId: Long? = null, query: String = ""): List<PageBookmark> =
        allBookmarks(context).asSequence()
            .filter { mangaId == null || it.mangaId == mangaId }
            .filter { query.isBlank() || it.note.contains(query, true) || it.page.toString() == query }
            .take(MAX_BOOKMARKS).toList()

    fun allBookmarks(context: Context): List<PageBookmark> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(BOOKMARK_KEY, "").orEmpty().split('|').asSequence().mapNotNull(::decodeBookmark)
        .sortedByDescending { it.timestamp }.take(MAX_BOOKMARKS).toList()

    fun toggleBookmark(context: Context, bookmark: PageBookmark): Boolean {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val current = allBookmarks(context).toMutableList()
        val removed = current.removeAll { it.mangaId == bookmark.mangaId && it.chapterId == bookmark.chapterId && it.page == bookmark.page }
        if (!removed) current += bookmark.copy(timestamp = System.currentTimeMillis())
        prefs.edit().putString(BOOKMARK_KEY, current.take(MAX_BOOKMARKS).joinToString("|", transform = ::encodeBookmark)).apply()
        return !removed
    }

    fun updateBookmarkNote(context: Context, bookmark: PageBookmark, note: String) {
        val updated = allBookmarks(context).map {
            if (it.mangaId == bookmark.mangaId && it.chapterId == bookmark.chapterId && it.page == bookmark.page) it.copy(note = note) else it
        }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(BOOKMARK_KEY, updated.joinToString("|", transform = ::encodeBookmark)).apply()
    }

    fun deleteBookmark(context: Context, bookmark: PageBookmark) {
        val updated = allBookmarks(context).filterNot { it.mangaId == bookmark.mangaId && it.chapterId == bookmark.chapterId && it.page == bookmark.page }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(BOOKMARK_KEY, updated.joinToString("|", transform = ::encodeBookmark)).apply()
    }

    fun saveTagCombination(context: Context, combination: SavedTagCombination) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val updated = savedTagCombinations(context).filterNot { it.name == combination.name } + combination
        prefs.edit().putString(SAVED_COMBINATION_KEY, updated.takeLast(MAX_SAVED_COMBINATIONS).joinToString("|", transform = ::encodeCombination)).apply()
    }

    fun savedTagCombinations(context: Context): List<SavedTagCombination> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(SAVED_COMBINATION_KEY, "").orEmpty().split('|').asSequence().mapNotNull(::decodeCombination).take(MAX_SAVED_COMBINATIONS).toList()

    fun deleteTagCombination(context: Context, name: String) {
        val updated = savedTagCombinations(context).filterNot { it.name == name }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(SAVED_COMBINATION_KEY, updated.joinToString("|", transform = ::encodeCombination)).apply()
    }

    fun preferredSource(context: Context, logicalId: Long): Long? = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(PREFERRED_SOURCE_KEY, "").orEmpty().split('|').asSequence().mapNotNull { row ->
            val parts = row.split('=', limit = 2); if (parts.firstOrNull()?.toLongOrNull() == logicalId) parts.getOrNull(1)?.toLongOrNull() else null
        }.firstOrNull()

    fun setPreferredSource(context: Context, logicalId: Long, sourceId: Long) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val rows = prefs.getString(PREFERRED_SOURCE_KEY, "").orEmpty().split('|').filter { it.isNotBlank() && !it.startsWith("$logicalId=") }
        prefs.edit().putString(PREFERRED_SOURCE_KEY, (rows + "$logicalId=$sourceId").takeLast(500).joinToString("|")).apply()
    }

    fun requestMetadataRepair(context: Context, mangaIds: Collection<Long>) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val existing = prefs.getString(REPAIR_QUEUE_KEY, "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
        prefs.edit().putString(REPAIR_QUEUE_KEY, (existing + mangaIds).distinct().takeLast(2_000).joinToString(",")).apply()
    }

    fun metadataRepairQueue(context: Context): Set<Long> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(REPAIR_QUEUE_KEY, "").orEmpty().split(',').mapNotNull(String::toLongOrNull).toSet()

    fun clearMetadataRepairQueue(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(REPAIR_QUEUE_KEY).apply()
    }

    fun periodActivity(context: Context, period: String): Int {
        val today = LocalDate.now()
        val start = when (period) {
            "week" -> today.minusDays(6)
            "month" -> today.minusDays(29)
            "year" -> today.minusDays(364)
            else -> today
        }
        return heatmap(context).asSequence().mapNotNull { (day, count) ->
            runCatching { LocalDate.parse(day) to count }.getOrNull()
        }.filter { (day, _) -> day in start..today }.sumOf { it.second }
    }

    fun scanLocalGallery(context: Context, maxFiles: Int = 500): List<GalleryIssue> {
        val roots = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        val issues = mutableListOf<GalleryIssue>()
        val seenNames = mutableSetOf<String>()
        val seenHashes = mutableMapOf<String, String>()
        val pageNumbersByFolder = mutableMapOf<String, MutableSet<Int>>()
        var inspected = 0
        roots.asSequence().flatMap { root -> root.walkTopDown().asSequence() }
            .filter { it.isFile }.take(maxFiles.coerceIn(1, 2_000)).forEach { file ->
                inspected++
                val extension = file.extension.lowercase(Locale.ROOT)
                val supported = extension in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
                when {
                    file.length() == 0L -> issues += GalleryIssue(file.path, "zero-byte", "File is empty")
                    !file.canRead() -> issues += GalleryIssue(file.path, "unreadable", "File cannot be read")
                    extension in setOf("part", "tmp", "download", "crdownload") -> issues += GalleryIssue(file.path, "incomplete-download", "Temporary download file remains")
                    !supported -> issues += GalleryIssue(file.path, "unsupported", "Unsupported image extension")
                    else -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) issues += GalleryIssue(file.path, "corrupt", "Image dimensions could not be decoded")
                        val hash = file.quickHash()
                        val previous = seenHashes.putIfAbsent(hash, file.path)
                        if (previous != null && previous != file.path) issues += GalleryIssue(file.path, "duplicate-page-content", "Same bounded file hash as $previous")
                        val number = Regex("(?:^|[^0-9])(\\d{1,5})(?:[^0-9]|$)").find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (number != null) pageNumbersByFolder.getOrPut(file.parent.orEmpty()) { mutableSetOf() }.add(number)
                    }
                }
                val name = file.name.lowercase(Locale.ROOT)
                if (!seenNames.add(name)) issues += GalleryIssue(file.path, "duplicate-page-name", "Duplicate page filename")
            }
        pageNumbersByFolder.forEach { (folder, numbers) ->
            if (numbers.size >= 3) {
                val expected = (numbers.minOrNull() ?: 0)..(numbers.maxOrNull() ?: 0)
                expected.filterNot(numbers::contains).take(20).forEach { missing ->
                    issues += GalleryIssue(folder, "missing-page", "Numeric page $missing is missing from this gallery")
                }
            }
        }
        if (inspected == 0) issues += GalleryIssue("", "empty-library", "No local gallery files found in app storage")
        return issues.filterNot { isIssueIgnored(context, it) }.take(500)
    }

    private fun File.quickHash(): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            var remaining = 256 * 1024
            while (remaining > 0) {
                val read = stream.read(buffer, 0, min(buffer.size, remaining))
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }.getOrElse { "unreadable:${path}:${length()}" }

    fun ignoreGalleryIssue(context: Context, issue: GalleryIssue) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val key = "${issue.kind}:${issue.path}"
        val rows = prefs.getString(IGNORED_ISSUES_KEY, "").orEmpty().split('|').filter(String::isNotBlank)
        prefs.edit().putString(IGNORED_ISSUES_KEY, (rows + key).distinct().takeLast(2_000).joinToString("|")).apply()
    }

    fun markReading(context: Context, day: String = LocalDate.now().toString(), pages: Int = 1) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val rows = heatmap(context).toMutableMap()
        rows[day] = (rows[day] ?: 0) + pages.coerceAtLeast(1)
        prefs.edit().putString(HEATMAP_KEY, rows.entries.sortedBy { it.key }.takeLast(370).joinToString("|") { "${it.key}=${it.value}" }).apply()
    }

    fun heatmap(context: Context): Map<String, Int> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(HEATMAP_KEY, "").orEmpty().split('|').asSequence().filter(String::isNotBlank).mapNotNull { row ->
            val parts = row.split('=', limit = 2); val day = parts.firstOrNull().orEmpty(); val count = parts.getOrNull(1)?.toIntOrNull()
            if (day.isNotBlank() && count != null) day to count else null
        }.toMap()

    fun heatmapStats(context: Context): HeatmapStats {
        val byDay = heatmap(context).filterKeys { runCatching { LocalDate.parse(it) }.isSuccess }
        val dates = byDay.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
        var longest = 0; var run = 0; var previous: LocalDate? = null
        dates.sorted().forEach { date ->
            run = if (previous?.plusDays(1) == date) run + 1 else 1
            longest = max(longest, run); previous = date
        }
        var current = 0; var cursor = LocalDate.now()
        while (cursor in dates) { current++; cursor = cursor.minusDays(1) }
        return HeatmapStats(byDay, byDay.values.sum(), dates.size, current, longest)
    }

    fun rememberSeen(context: Context, ids: Collection<Long>) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val existing = prefs.getString(SEEN_KEY, "").orEmpty().split(',').filter(String::isNotBlank)
        val merged = (existing + ids.map(Long::toString)).distinct().takeLast(MAX_STORED_IDS)
        prefs.edit().putString(SEEN_KEY, merged.joinToString(",")).apply()
    }

    fun seenIds(context: Context): Set<Long> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(SEEN_KEY, "").orEmpty().split(',').mapNotNull(String::toLongOrNull).toSet()

    fun applyStealthWindow(window: Window, enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun allPersonalAssignments(context: Context): Map<Long, Set<String>> = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(PERSONAL_TAG_KEY, "").orEmpty().split('|').asSequence().mapNotNull { row ->
            val parts = row.split('=', limit = 2); val id = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            id to parts.getOrNull(1).orEmpty().split(',').map(::normalize).filter(String::isNotBlank).toSet()
        }.toMap()

    private fun encodeAssignments(assignments: Map<Long, Set<String>>): String = assignments.entries.joinToString("|") { (id, tags) -> "$id=${tags.joinToString(",")}" }
    private fun encodeBookmark(bookmark: PageBookmark): String = listOf(bookmark.mangaId, bookmark.chapterId, bookmark.page, bookmark.timestamp, bookmark.note.replace('|', ' ')).joinToString(",")
    private fun decodeBookmark(value: String): PageBookmark? {
        val parts = value.split(',', limit = 5); if (parts.size < 4) return null
        return PageBookmark(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2].toIntOrNull() ?: return null, parts[3].toLongOrNull() ?: 0L, parts.getOrNull(4).orEmpty())
    }
    private fun encodeCombination(value: SavedTagCombination): String = listOf(value.name, value.include, value.exclude, value.optional, value.source).joinToString("~") { it.replace('~', ' ') }
    private fun decodeCombination(value: String): SavedTagCombination? {
        val parts = value.split('~', limit = 5); if (parts.size < 3) return null
        return SavedTagCombination(parts[0], parts[1], parts[2], parts.getOrNull(3).orEmpty(), parts.getOrNull(4) ?: "all")
    }
    private fun isIssueIgnored(context: Context, issue: GalleryIssue): Boolean = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(IGNORED_ISSUES_KEY, "").orEmpty().split('|').contains("${issue.kind}:${issue.path}")

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1); current[0] = i + 1
            for (j in right.indices) current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1), previous[j] + if (left[i] == right[j]) 0 else 1)
            previous = current
        }
        return previous[right.length]
    }
}
