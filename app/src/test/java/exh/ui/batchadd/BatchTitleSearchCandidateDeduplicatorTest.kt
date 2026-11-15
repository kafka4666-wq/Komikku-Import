package exh.ui.batchadd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

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
    fun `successful retry of the saved match moves the input into added`() {
        val savedMatch = candidate("saved", "Summer Girl", "Summer Girl", "A Source", "https://a.example/saved")
        val original = AutoImportedTitleResult("Summer Girl", savedMatch, false, "The source rejected this title")

        val updated = applyManualTitleImportAttempts(
            rows = listOf(original),
            candidates = listOf(savedMatch),
            attempts = mapOf("https://a.example/saved" to TitleImportAttempt(true, null)),
        ).single()

        assertTrue(updated.added)
        assertEquals("https://a.example/saved", updated.candidate?.url)
        assertEquals(null, updated.detail)
    }

    @Test
    fun `failed retry remains visible as not added`() {
        val savedMatch = candidate("saved", "Summer Girl", "Summer Girl", "A Source", "https://a.example/saved")
        val original = AutoImportedTitleResult("Summer Girl", savedMatch, false, "Previous add failed")

        val updated = applyManualTitleImportAttempts(
            listOf(original),
            listOf(savedMatch),
            mapOf("https://a.example/saved" to TitleImportAttempt(false, "Source rejected add")),
        ).single()

        assertFalse(updated.added)
        assertEquals(savedMatch, updated.candidate)
        assertEquals("Source rejected add", updated.detail)
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

        assertEquals("#batch-title-search-complete:VGl0bGUgQQ==", TitleSearchCheckpoint.marker("Title A"))
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
    fun `source errors are not retried but worker storage recovery remains available`() {
        assertTrue(TitleSearchWorkerRecoveryPolicy.shouldRetryWorker(IOException("temporary storage failure")))
        assertFalse(TitleSearchWorkerRecoveryPolicy.shouldRetryWorker(IllegalStateException("selected source is not installed")))
    }

    @Test
    fun `source checkpoints bind completion to both input title and source`() {
        val marker = TitleSearchSourceCheckpoint.marker("Title A", 41L)

        assertTrue(TitleSearchSourceCheckpoint.isComplete(listOf(marker), "Title A", 41L))
        assertFalse(TitleSearchSourceCheckpoint.isComplete(listOf(marker), "Title B", 41L))
        assertFalse(TitleSearchSourceCheckpoint.isComplete(listOf(marker), "Title A", 42L))
    }

    @Test
    fun `the first source error marker is terminal and never schedules a source retry`() {
        val firstError = TitleSearchSourceCheckpoint.failureMarker("Title A", 41L, 1)

        assertEquals(1, TitleSearchSourceCheckpoint.failureAttempts(listOf(firstError), "Title A", 41L))
        assertEquals(0, TitleSearchSourceCheckpoint.failureAttempts(listOf(firstError), "Title B", 41L))
        assertEquals(0, TitleSearchSourceCheckpoint.failureAttempts(listOf(firstError), "Title A", 42L))
        assertTrue(TitleSearchSourceCheckpoint.isTerminalFailure(1))
        assertTrue(TitleSearchSourceCheckpoint.isTerminalFailure(2))
    }

    @Test
    fun `not added result discloses that exhausted source errors may hide a match`() {
        val result = buildAutomaticTitleResults(
            inputTitles = listOf("Title A"),
            candidates = emptyList(),
            attempts = emptyMap(),
            terminalSourceFailureTitles = setOf("title a"),
        ).single()

        assertFalse(result.added)
        assertTrue(result.detail.orEmpty().contains("were not retried"))
    }

    @Test
    fun `failed title export excludes successful adds and writes one title per line`() {
        val text = failedTitleListText(
            listOf(
                AutoImportedTitleResult("Added title", null, true, null),
                AutoImportedTitleResult("No match", null, false, "Not found"),
                AutoImportedTitleResult("Add failed", candidate("failed", "Add failed", "Add failed", "Source", "https://example.com/fail"), false, "Rejected"),
                AutoImportedTitleResult(" no match ", null, false, "Duplicate input"),
            ),
        )

        assertEquals("No match\nAdd failed\n", text)
    }

    @Test
    fun `failed title export filename is a txt with timestamp and is provider safe`() {
        assertEquals("Komikku_failed_titles_20261115_113800_042.txt", failedTitleExportFileName("20261115_113800_042"))
    }

    @Test
    fun `a match from another source finalizes the title without retrying failed sources`() {
        assertTrue(TitleSearchSourceCheckpoint.shouldFinalizeTitle(hasMatch = true, allSourceChecksSettled = false))
        assertTrue(TitleSearchSourceCheckpoint.shouldFinalizeTitle(hasMatch = false, allSourceChecksSettled = true))
        assertFalse(TitleSearchSourceCheckpoint.shouldFinalizeTitle(hasMatch = false, allSourceChecksSettled = false))
    }

    @Test
    fun `unknown saved speed preset falls back to fast`() {
        assertEquals(TitleSearchSpeedPreset.FAST, TitleSearchSpeedPreset.fromName("UNKNOWN"))
    }

    @Test
    fun `durable session manifest round trips input source settings and automatic import`() {
        val manifest = TitleSearchSessionManifest(
            sessionId = "session-123",
            titleFilePath = "/private/files/titles with spaces.txt",
            sourceIds = setOf(9L, 2L, 41L),
            speedPresetName = "BALANCED",
            autoImport = true,
            uniqueWorkName = "batch_title_search_session_session-123",
        )

        val restored = TitleSearchSessionManifest.parse(manifest.serialize())

        assertEquals(manifest, restored)
    }

    @Test
    fun `durable session manifest preserves the default enabled-source selection`() {
        val manifest = TitleSearchSessionManifest(
            sessionId = "session-default",
            titleFilePath = "/private/titles.txt",
            sourceIds = null,
            speedPresetName = "FAST",
            autoImport = false,
            uniqueWorkName = "batch_title_search_session_session-default",
        )

        assertEquals(manifest, TitleSearchSessionManifest.parse(manifest.serialize()))
    }

    @Test
    fun `durable session manifest round trips output folder permission`() {
        val manifest = TitleSearchSessionManifest(
            sessionId = "session-output",
            titleFilePath = "/private/titles.txt",
            sourceIds = setOf(7L),
            speedPresetName = "FAST",
            autoImport = true,
            uniqueWorkName = "batch_title_search_session_output",
            outputTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads",
        )

        assertEquals(manifest, TitleSearchSessionManifest.parse(manifest.serialize()))
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
