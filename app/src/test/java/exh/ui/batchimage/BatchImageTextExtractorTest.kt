package exh.ui.batchimage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchImageTextExtractorTest {
    @Test
    fun `reddit recommendation title and bracketed artist are prioritized`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("r/doujinshi", "u/example 6h", "NSFW", "Rec: Girlfriend Sex (Benimura karu)", "Join the conversation"),
            "content://example/reddit.jpg",
        )
        assertEquals("Girlfriend Sex", result?.title)
        assertEquals("Benimura karu", result?.artist)
    }

    @Test
    fun `facebook structured Japanese title and artist are extracted`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("CrotPedia Project · 4m · Author", "Judul : Osananajimi no Sukinahito wa", "Chapter : 1", "Artist : Kasei", "Tags/Genre : Osananajimi", "See more"),
            "content://example/facebook.jpg",
        )
        assertEquals("Osananajimi no Sukinahito wa", result?.title)
        assertEquals("Kasei", result?.artist)
    }

    @Test
    fun `explicit code and full nhentai link are normalized`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("r/netorare", "Code: 682781", "https://nhentai.net/g/682781/", "Reply", "Join the conversation"),
            "content://example/reddit-code.jpg",
        )
        assertEquals("682781", result?.code)
        assertEquals("https://nhentai.net/g/682781/", result?.link)
    }

    @Test
    fun `standalone five to seven digit codes are retained`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("r/doujinshi", "NSFW", "682640", "Share", "Comment"),
            "content://example/code.jpg",
        )
        assertEquals("682640", result?.code)
        assertNull(result?.title)
    }

    @Test
    fun `social clutter and conversational comments alone are rejected`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("u/justhereforfun 1d", "This was a good one. The English title kinda spoils the fun.", "Join the conversation", "Upvote", "Share"),
            "content://example/clutter.jpg",
        )
        assertNull(result)
    }

    @Test
    fun `OCR prefixed subreddit is skipped in favor of title inside later sauce comment`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf(
                "X r/SauceSharingCommunity",
                "u/automoderator · 1h",
                "Please follow the community rules before posting.",
                "Full sauce: What-If Scenarios with Akari-san",
                "u/someone 3h",
                "That was a great chapter!",
            ),
            "content://example/reddit-comments.jpg",
        )
        assertEquals("What-If Scenarios with Akari-san", result?.title)
    }

    @Test
    fun `short all caps UI text does not beat a Facebook post title`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("CRIT DMG", "The Virgin Tutor and The Wealthy Wife", "Like", "Comment", "Share"),
            "content://example/facebook-title.jpg",
        )
        assertEquals("The Virgin Tutor and The Wealthy Wife", result?.title)
    }

    @Test
    fun `title cue inside a reply is extracted after the subreddit label`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("X r/netorare", "u/example 2h", "The English title is: Match Made in Heaven: The Perfect Fuck Buddy 3"),
            "content://example/reply-title.jpg",
        )
        assertEquals("Match Made in Heaven: The Perfect Fuck Buddy 3", result?.title)
    }

    @Test
    fun `search variants remove incomplete OCR artist and try specific subtitle`() {
        val queries = BatchImageTextExtractor.searchQueries("Match Made in Heaven: The Perfect Fuck Buddy 3 [koujil")
        assertEquals("Match Made in Heaven: The Perfect Fuck Buddy 3", queries.first())
        assertTrue(queries.contains("The Perfect Fuck Buddy 3"))
        assertTrue(queries.none { it.contains("koujil", ignoreCase = true) })
    }

    @Test
    fun `subreddit and short reply fragments alone are not accepted as titles`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("X r/SauceSharingCommunity", "Nice!", "1 upvote", "Search image for Anime, YouTube, Something"),
            "content://example/subreddit-only.jpg",
        )
        assertNull(result)
    }

    @Test
    fun `multiple title lines in comments remain separate review candidates`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "X r/doujinshi",
                "The English title is: The Perfect Fuck Buddy 3",
                "Full sauce: What-If Scenarios with Akari-san",
            ),
            "content://example/two-titles.jpg",
        )
        assertEquals(listOf("The Perfect Fuck Buddy 3", "What-If Scenarios with Akari-san"), results.map { it.title })
        assertEquals(2, results.map { it.id }.distinct().size)
    }

    @Test
    fun `structured title variants deduplicate against matching comment title`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf("Title: Match Made in Heaven: The Perfect Fuck Buddy 3", "Full sauce: Match Made in Heaven: The Perfect Fuck Buddy 3"),
            "content://example/duplicate-title.jpg",
        )
        assertEquals(1, results.size)
        assertEquals("Match Made in Heaven: The Perfect Fuck Buddy 3", results.single().title)
    }

    @Test
    fun `title with scanlation credit keeps title and creator`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("Female Boss Hints [Tabal] [TOMISCANS]", "Home / Ahegao / Female Boss Hints"),
            "content://example/web.jpg",
        )
        assertEquals("Female Boss Hints", result?.title)
        assertEquals("Tabal", result?.artist)
        assertTrue((result?.confidence ?: 0) > 0)
    }

    @Test
    fun `reddit sauce comment hyperlink and trailing author yield clean title`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("Full sauce: [What-if Scenarios with Akari-san](https://hentainexus.com/view/17469) by Rokkaku Yasosuke."),
            "content://example/reddit-sauce-link.jpg",
        )
        assertEquals("What-if Scenarios with Akari-san", result?.title)
        assertEquals("Rokkaku Yasosuke", result?.artist)
    }

    @Test
    fun `facebook Name and By lines keep title and creator instead of creator as a title`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf("Name : My Gyaru Single Mother Ex-Girlfriend", "By : Sukeichi"),
            "content://example/facebook-name-by.jpg",
        )
        assertEquals(1, results.size)
        assertEquals("My Gyaru Single Mother Ex-Girlfriend", results.single().title)
        assertEquals("Sukeichi", results.single().artist)
    }

    @Test
    fun `creator title rows in a Reddit list become separate import candidates`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf("1. Dramus - Monopolize", "2. Terasu MC - Kokujin no Tenkousei NTR ru"),
            "content://example/reddit-list.jpg",
        )
        assertEquals(listOf("Monopolize", "Kokujin no Tenkousei NTR ru"), results.map { it.title })
        assertEquals(listOf("Dramus", "Terasu MC"), results.map { it.artist })
    }

    @Test
    fun `nhentai metadata accepts parenthesized code`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("Title: (K)Night & Day", "nHentai#: (465278)"),
            "content://example/reddit-metadata.jpg",
        )
        assertEquals("465278", result?.code)
    }

    @Test
    fun `reddit title requests and descriptions are not treated as work titles`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("LF Doujinshi: Netorase maybe", "Looking for a ntr doujin similar to Midareuchi", "It consists of three parts and a long story description"),
            "content://example/reddit-request.jpg",
        )
        assertNull(result)
    }

    @Test
    fun `shared folder screenshot title sauce by creator preserves title and author`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("Full sauce: [What-if Scenarios with Akari-san](https://hentainexus.com/view/17469) by Rokkaku Yasosuke."),
            "content://drive/reddit-sauce.jpg",
        )
        assertEquals("What-if Scenarios with Akari-san", result?.title)
        assertEquals("Rokkaku Yasosuke", result?.artist)
    }

    @Test
    fun `shared folder Facebook name title and by creator are not merged`() {
        val result = BatchImageTextExtractor.extractFirstLines(
            listOf("Name : My Gyaru Single Mother Ex-Girlfriend", "By : Sukeichi"),
            "content://drive/facebook-name.jpg",
        )
        assertEquals("My Gyaru Single Mother Ex-Girlfriend", result?.title)
        assertEquals("Sukeichi", result?.artist)
    }

    @Test
    fun `shared folder Reddit source answer listing many titles yields independent results`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "1. Dramus - Monopolize",
                "2. Terasu MC - Kokujin no Tenkousei NTR ru",
                "3. Laliberte - Motherly",
            ),
            "content://drive/reddit-title-list.jpg",
        )
        assertEquals(listOf("Monopolize", "Kokujin no Tenkousei NTR ru", "Motherly"), results.map { it.title })
    }

    @Test
    fun `separate structured artist labels attach to their own title records`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf("Title: First Work", "Artist: Hino Himoto", "Title: Second Work", "Artist: Rokkaku Yasosuke"),
            "content://example/two-title-artist-pairs.jpg",
        )
        assertEquals(listOf("First Work", "Second Work"), results.map { it.title })
        assertEquals(listOf("Hino Himoto", "Rokkaku Yasosuke"), results.map { it.artist })
    }
}
