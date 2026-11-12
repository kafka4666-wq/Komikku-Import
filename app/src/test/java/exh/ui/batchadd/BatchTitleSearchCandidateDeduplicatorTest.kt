package exh.ui.batchadd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchTitleSearchCandidateDeduplicatorTest {
    @Test
    fun `one result is kept per normalized input title even when sources name it differently`() {
        val candidates = listOf(
            candidate(
                id = "first-source",
                inputTitle = "Café-Girl! [English]",
                matchedTitle = "Café Girl — Official English Release",
                sourceName = "A Source",
                url = "https://a.example/cafe-girl",
            ),
            candidate(
                id = "exact-source",
                inputTitle = "Cafe Girl",
                matchedTitle = "Cafe Girl",
                sourceName = "Z Source",
                url = "https://z.example/cafe-girl",
            ),
            candidate(
                id = "different-title",
                inputTitle = "Cafe Girl 2",
                matchedTitle = "Cafe Girl 2",
                sourceName = "A Source",
                url = "https://a.example/cafe-girl-2",
            ),
        )

        val result = TitleSearchCandidateDeduplicator.collapse(candidates)

        assertEquals(2, result.size)
        assertEquals(
            listOf("https://z.example/cafe-girl", "https://a.example/cafe-girl-2"),
            result.map { it.url },
        )
    }

    @Test
    fun `same matched title from different input rows is kept once`() {
        val candidates = listOf(
            candidate(
                id = "alias-search",
                inputTitle = "Cafe Girl English",
                matchedTitle = "Cafe Girl",
                sourceName = "A Source",
                url = "https://a.example/cafe-girl",
            ),
            candidate(
                id = "exact-search",
                inputTitle = "Cafe Girl",
                matchedTitle = "Cafe Girl",
                sourceName = "Z Source",
                url = "https://z.example/cafe-girl",
            ),
            candidate(
                id = "other-title",
                inputTitle = "Another Book",
                matchedTitle = "Another Book",
                sourceName = "A Source",
                url = "https://a.example/another-book",
            ),
        )

        val result = TitleSearchCandidateDeduplicator.collapse(candidates)

        assertEquals(2, result.size)
        assertEquals(
            listOf("https://z.example/cafe-girl", "https://a.example/another-book"),
            result.map { it.url },
        )
    }

    @Test
    fun `title match ignores punctuation accents and bracketed language notes`() {
        assertTrue(TitleSearchMatchQuality.isConfidentMatch("Café-Girl [English]", "Cafe Girl"))
    }

    @Test
    fun `a typo in a title is not accepted as a match`() {
        assertFalse(TitleSearchMatchQuality.isConfidentMatch("The Adventures of Summer Girl", "The Adventures of Sumner Girl"))
    }

    @Test
    fun `unrelated and short substring results are not accepted`() {
        assertFalse(TitleSearchMatchQuality.isConfidentMatch("My Favorite Manga", "Completely Different Series"))
        assertFalse(TitleSearchMatchQuality.isConfidentMatch("Hero", "Heroic Fantasy Story"))
    }

    private fun candidate(
        id: String,
        inputTitle: String,
        matchedTitle: String,
        sourceName: String,
        url: String,
    ) = TitleSearchCandidate(
        id = id,
        inputTitle = inputTitle,
        matchedTitle = matchedTitle,
        sourceName = sourceName,
        url = url,
        thumbnailUrl = null,
    )
}
