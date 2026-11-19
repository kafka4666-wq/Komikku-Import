package eu.kanade.domain.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryCompanionRulesTest {
    private val subject = LibraryCompanionRules.Subject(
        title = "Blue Archive Anthology",
        source = "Example Source",
        tags = setOf("Romance", "Comedy"),
        status = "Reading",
        unreadCount = 7,
        rating = 4,
        downloaded = true,
        addedAt = 1_700_000_000_000L,
        lastUpdatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `smart list terms are combined with AND and groups with OR`() {
        assertTrue(LibraryCompanionRules.matches("unread tag:romance rating>=4", subject))
        assertFalse(LibraryCompanionRules.matches("unread tag:yaoi", subject))
        assertTrue(LibraryCompanionRules.matches("status:Finished | source:Example", subject))
    }

    @Test
    fun `supports excluded tags and title words`() {
        assertTrue(LibraryCompanionRules.matches("-tag:yaoi blue", subject))
        assertFalse(LibraryCompanionRules.matches("-tag:romance", subject))
    }

    @Test
    fun `completed list recognizes personal Finished status`() {
        assertTrue(LibraryCompanionRules.matches("completed", subject.copy(status = "Finished", unreadCount = 2)))
        assertFalse(LibraryCompanionRules.matches("completed", subject.copy(status = "Reading", unreadCount = 2)))
    }

    @Test
    fun `catch-up snooze lasts until unread count drops below its saved value`() {
        val now = 1_700_000_000_000L
        assertTrue(LibraryCompanionRules.isSnoozed(now + 60_000, -1, 2, now))
        assertTrue(LibraryCompanionRules.isSnoozed(now - 1, 2, 2, now))
        assertTrue(LibraryCompanionRules.isSnoozed(now - 1, 2, 3, now))
        assertFalse(LibraryCompanionRules.isSnoozed(now - 1, 2, 1, now))
    }

    @Test
    fun `status snooze expires when the personal status changes`() {
        assertTrue(LibraryCompanionRules.isSnoozed(0L, -1, 3, "On hold", "On hold"))
        assertFalse(LibraryCompanionRules.isSnoozed(0L, -1, 3, "Reading", "On hold"))
    }

    @Test
    fun `time estimates and release predictions reject invalid values`() {
        assertEquals(35, LibraryCompanionRules.estimatedMinutes(300.0, 7))
        assertEquals(0, LibraryCompanionRules.estimatedMinutes(Double.NaN, 3))
        assertEquals(1_700_000_000_000L + 14L * 24 * 60 * 60 * 1000, LibraryCompanionRules.predictedReleaseAt(1_700_000_000_000L, 14))
        assertNull(LibraryCompanionRules.predictedReleaseAt(0L, 14))
    }

    @Test
    fun `recent update filter excludes future timestamps`() {
        val now = 1_700_000_000_000L
        assertTrue(LibraryCompanionRules.matches("updated:recent", subject.copy(lastUpdatedAt = now - 5L * 24 * 60 * 60 * 1000), now))
        assertFalse(LibraryCompanionRules.matches("updated:recent", subject.copy(lastUpdatedAt = now + 1_000), now))
    }

    @Test
    fun `new chapter reminders only notify tracked statuses after a saved baseline`() {
        assertFalse(LibraryCompanionRules.shouldNotifyNewRelease("Reading", null, 200L))
        assertFalse(LibraryCompanionRules.shouldNotifyNewRelease("Finished", 100L, 200L))
        assertFalse(LibraryCompanionRules.shouldNotifyNewRelease("Ongoing", 200L, 200L))
        assertTrue(LibraryCompanionRules.shouldNotifyNewRelease("Ongoing", 100L, 200L))
        assertTrue(LibraryCompanionRules.shouldNotifyNewRelease("Reading", 100L, 200L))
    }

    @Test
    fun `title normalization groups punctuation and case variants`() {
        assertEquals("a great series", LibraryCompanionRules.normalizeTitle("A Great-Series!"))
        assertEquals(LibraryCompanionRules.normalizeTitle("A Great Series"), LibraryCompanionRules.normalizeTitle("a great series"))
    }
}
