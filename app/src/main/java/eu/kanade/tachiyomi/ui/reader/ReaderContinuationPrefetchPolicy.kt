package eu.kanade.tachiyomi.ui.reader

/** Timing policy for warming the next visible Library title without competing with normal reading. */
internal object ReaderContinuationPrefetchPolicy {
    private const val REMAINING_PAGES_TO_WARM = 2

    /** Page numbers are 1-based, matching [eu.kanade.tachiyomi.ui.reader.model.ReaderPage.number]. */
    fun shouldWarmNextBook(pageNumber: Int, pageCount: Int, isLastChapter: Boolean): Boolean {
        if (!isLastChapter || pageCount <= 0 || pageNumber !in 1..pageCount) return false
        val firstWarmPage = (pageCount - REMAINING_PAGES_TO_WARM).coerceAtLeast(1)
        return pageNumber >= firstWarmPage
    }
}
