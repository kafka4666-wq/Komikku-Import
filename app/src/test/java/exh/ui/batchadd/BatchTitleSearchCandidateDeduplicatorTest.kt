package exh.ui.batchadd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchTitleSearchCandidateDeduplicatorTest {
    @Test
    fun `different input titles are never collapsed together`() {
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

        assertEquals(3, result.size)
        assertEquals(
            setOf("https://a.example/cafe-girl", "https://z.example/cafe-girl", "https://a.example/cafe-girl-2"),
            result.map { it.url }.toSet(),
        )
    }

    @Test
    fun `same url for one input is kept once but different inputs stay separate`() {
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
                id = "dup-url",
                inputTitle = "Cafe Girl English",
                matchedTitle = "Cafe Girl",
                sourceName = "B Source",
                url = "https://a.example/cafe-girl/",
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

        assertEquals(3, result.size)
        assertEquals(
            setOf("https://a.example/cafe-girl", "https://z.example/cafe-girl", "https://a.example/another-book"),
            result.map { it.url.trimEnd('/') }.toSet(),
        )
    }

    @Test
    fun `best per input prefers exact then source order`() {
        val candidates = listOf(
            candidate("z-first", "Summer Girl", "Summer Girl", "Z Source", "https://z.example/summer"),
            candidate("a-second", "Summer Girl", "Summer Girl", "A Source", "https://a.example/summer"),
            candidate("decorated", "Summer Girl", "Summer Girl Official", "0 Source", "https://0.example/summer"),
        )

        val best = TitleSearchCandidateDeduplicator.bestPerInput(candidates)

        assertEquals(1, best.size)
        assertEquals("https://a.example/summer", best.single().url)
    }

    @Test
    fun `best per input chooses the closest weak title`() {
        val candidates = listOf(
            candidate("far", "Summer Girl", "Completely Different", "A Source", "https://a.example/far"),
            candidate("close", "Summer Girl", "Summer Gurl", "Z Source", "https://z.example/close"),
        )

        val best = TitleSearchCandidateDeduplicator.bestPerInput(candidates)

        assertEquals("https://z.example/close", best.single().url)
        assertTrue(!TitleMatchQuality.isStrong(candidates[1]))
    }

    @Test
    fun `automatic selection prefers any strong hit over a closer weak hit`() {
        val weak = candidate("weak", "Summer Girl", "Summer Gurlz", "A Source", "https://a.example/weak")
        val strong = candidate("strong", "Summer Girl", "Summer", "Z Source", "https://z.example/strong")
        assertTrue(!TitleMatchQuality.isStrong(weak))
        assertTrue(TitleMatchQuality.isStrong(strong))

        val selected = TitleSearchCandidateDeduplicator.bestPerInputPreferringStrong(listOf(weak, strong))

        assertEquals(listOf("https://z.example/strong"), selected.map { it.url })
    }

    @Test
    fun `automatic selection falls back to the best weak hit when there are no strong hits`() {
        val candidates = listOf(
            candidate("far", "Summer Girl", "Completely Different", "A Source", "https://a.example/far"),
            candidate("close", "Summer Girl", "Summer Gurlz", "Z Source", "https://z.example/close"),
        )

        val selected = TitleSearchCandidateDeduplicator.bestPerInputPreferringStrong(candidates)

        assertEquals(listOf("https://z.example/close"), selected.map { it.url })
        assertTrue(selected.none(TitleMatchQuality::isStrong))
    }

    @Test
    fun `all unique sources for one input are kept after collapse`() {
        val candidates = listOf(
            candidate("a", "Summer Girl", "Summer Girl", "A Source", "https://a.example/summer"),
            candidate("z", "Summer Girl", "Summer Girl", "Z Source", "https://z.example/summer"),
            candidate("weak", "Summer Girl", "Totally Unrelated Hit", "M Source", "https://m.example/other"),
        )

        val result = TitleSearchCandidateDeduplicator.collapse(candidates)
        assertEquals(3, result.size)
        assertTrue(result.any { TitleMatchQuality.isExact(it) })
        assertTrue(result.any { !TitleMatchQuality.isStrong(it) })
    }

    @Test
    fun `checkpoint marker only completes the matching input title`() {
        val otherTitle = candidate("id", "Title B", "Title B", "Source", "https://example.com/b")

        assertTrue(TitleSearchCheckpoint.isComplete(listOf(TitleSearchCheckpoint.marker("Title A")), "Title A", emptyList()))
        assertTrue(!TitleSearchCheckpoint.isComplete(listOf(TitleSearchCheckpoint.marker("Title A")), "Title B", listOf(otherTitle)))
    }

    @Test
    fun `legacy checkpoint results only complete their own input title`() {
        val otherTitle = candidate("id", "Title B", "Title B", "Source", "https://example.com/b")
        val matchingTitle = candidate("id2", "Title A", "Title A", "Source", "https://example.com/a")

        assertTrue(!TitleSearchCheckpoint.isComplete(listOf("legacy-row"), "Title A", listOf(otherTitle)))
        assertTrue(TitleSearchCheckpoint.isComplete(listOf("legacy-row"), "Title A", listOf(matchingTitle)))
    }

    @Test
    fun `speed presets trade request concurrency for source load`() {
        val fast = TitleSearchSpeedPreset.FAST.concurrencyLimits(throttled = false)
        val balanced = TitleSearchSpeedPreset.BALANCED.concurrencyLimits(throttled = false)
        val gentle = TitleSearchSpeedPreset.GENTLE.concurrencyLimits(throttled = false)

        assertTrue(fast.globalSourceSearches > balanced.globalSourceSearches)
        assertTrue(balanced.globalSourceSearches > gentle.globalSourceSearches)
        assertTrue(fast.perSourceSearches > balanced.perSourceSearches)
        assertTrue(balanced.perSourceSearches >= gentle.perSourceSearches)
        assertTrue(gentle.timeoutMillis > fast.timeoutMillis)
    }

    @Test
    fun `thermal throttling lowers concurrency for every speed preset`() {
        TitleSearchSpeedPreset.values().forEach { preset ->
            val normal = preset.concurrencyLimits(throttled = false)
            val throttled = preset.concurrencyLimits(throttled = true)

            assertTrue(throttled.globalSourceSearches < normal.globalSourceSearches)
            assertTrue(throttled.perSourceSearches <= normal.perSourceSearches)
            assertTrue(throttled.parallelTitles < normal.parallelTitles)
        }
    }

    @Test
    fun `unknown saved speed preset falls back to fast`() {
        assertEquals(TitleSearchSpeedPreset.FAST, TitleSearchSpeedPreset.fromName("UNKNOWN"))
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
