package eu.kanade.domain.ui

import java.util.Locale

/** Pure helpers shared by the Library Companion UI and its regression tests. */
object LibraryCompanionRules {
    data class Subject(
        val title: String,
        val source: String,
        val tags: Set<String>,
        val status: String,
        val unreadCount: Int,
        val rating: Int,
        val downloaded: Boolean,
        val addedAt: Long,
        val lastUpdatedAt: Long,
    )

    /**
     * Small explicit query language. Terms are ANDed; `|` separates OR groups.
     * Supported terms: unread, read, completed, downloaded, tag:x, -tag:x,
     * status:x, source:x, rating>=N, added:month, updated:recent, and title words.
     */
    fun matches(query: String, subject: Subject, now: Long = System.currentTimeMillis()): Boolean {
        if (query.isBlank()) return true
        return query.split('|').any { group ->
            group.trim().split(Regex("\\s+")).filter(String::isNotBlank).all { raw ->
                val term = raw.trim().trim('"', '\'')
                val lower = term.lowercase(Locale.ROOT)
                when {
                    lower == "unread" -> subject.unreadCount > 0
                    lower == "read" -> subject.unreadCount == 0
                    lower == "completed" -> subject.status.equals("Completed", ignoreCase = true) || subject.status.equals("Finished", ignoreCase = true) || subject.unreadCount == 0
                    lower == "downloaded" -> subject.downloaded
                    lower.startsWith("tag:") -> subject.tags.any { it.equals(term.substringAfter(':'), ignoreCase = true) }
                    lower.startsWith("-tag:") -> subject.tags.none { it.equals(term.substringAfter(':'), ignoreCase = true) }
                    lower.startsWith("status:") -> subject.status.equals(term.substringAfter(':'), ignoreCase = true)
                    lower.startsWith("source:") -> subject.source.contains(term.substringAfter(':'), ignoreCase = true)
                    lower.startsWith("rating>=") -> subject.rating >= (term.substringAfter(">=").toIntOrNull() ?: 0).coerceAtLeast(0)
                    lower == "added:month" -> isWithinMonth(subject.addedAt, now)
                    lower == "updated:recent" -> subject.lastUpdatedAt in (now - 30L * DAY_MS)..now
                    lower.startsWith("unread>") -> subject.unreadCount > (term.substringAfter('>').toIntOrNull() ?: 0).coerceAtLeast(0)
                    else -> subject.title.contains(term, ignoreCase = true)
                }
            }
        }
    }

    fun isSnoozed(untilMillis: Long, restoreAtUnreadCount: Int, currentUnreadCount: Int, now: Long = System.currentTimeMillis()): Boolean =
        (untilMillis > now) || (restoreAtUnreadCount > 0 && currentUnreadCount >= restoreAtUnreadCount)

    fun isSnoozed(
        untilMillis: Long,
        restoreAtUnreadCount: Int,
        currentUnreadCount: Int,
        currentStatus: String,
        snoozeUntilStatus: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = isSnoozed(untilMillis, restoreAtUnreadCount, currentUnreadCount, now) ||
        (snoozeUntilStatus.isNotBlank() && currentStatus.equals(snoozeUntilStatus, ignoreCase = true))

    fun estimatedMinutes(averageChapterSeconds: Double, unreadChapters: Int): Int {
        if (!averageChapterSeconds.isFinite() || averageChapterSeconds <= 0.0 || unreadChapters <= 0) return 0
        return (averageChapterSeconds * unreadChapters / 60.0).toInt().coerceAtLeast(1)
    }

    fun predictedReleaseAt(lastUploadAt: Long, intervalDays: Int): Long? {
        if (lastUploadAt <= 0L || intervalDays !in 1..365) return null
        return lastUploadAt + intervalDays * DAY_MS
    }

    fun shouldNotifyNewRelease(status: String, previousUploadAt: Long?, currentUploadAt: Long): Boolean =
        (status.equals("Reading", ignoreCase = true) || status.equals("Ongoing", ignoreCase = true)) &&
            previousUploadAt != null && previousUploadAt > 0L && currentUploadAt > previousUploadAt

    fun normalizeTitle(title: String): String = title.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun isWithinMonth(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (timestamp <= 0L) return false
        val current = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        val value = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
        return current.year == value.year && current.month == value.month
    }

    private const val DAY_MS = 24L * 60L * 60L * 1_000L
}
