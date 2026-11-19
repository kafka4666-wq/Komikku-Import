package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderContinuationPrefetchPolicyTest {
    @Test
    fun `warms only in the last three pages of the final chapter`() {
        assertFalse(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(7, 10, isLastChapter = true))
        assertTrue(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(8, 10, isLastChapter = true))
        assertTrue(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(10, 10, isLastChapter = true))
    }

    @Test
    fun `warms a single page final chapter immediately`() {
        assertTrue(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(1, 1, isLastChapter = true))
    }

    @Test
    fun `does not warm non-final chapters or invalid page positions`() {
        assertFalse(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(9, 10, isLastChapter = false))
        assertFalse(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(0, 10, isLastChapter = true))
        assertFalse(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(11, 10, isLastChapter = true))
        assertFalse(ReaderContinuationPrefetchPolicy.shouldWarmNextBook(1, 0, isLastChapter = true))
    }
}
