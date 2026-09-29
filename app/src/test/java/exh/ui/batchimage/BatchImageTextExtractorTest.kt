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
    fun `search variants remove a trailing artist and OCR badge and keep title keywords`() {
        val queries = BatchImageTextExtractor.searchQueries("Nuru Never Drain 3 by Navier Haruka 2T")
        assertEquals("Nuru Never Drain 3", queries.first())
        assertTrue(queries.contains("nuru never drain"))
        assertTrue(queries.none { it.contains("Navier") || it.endsWith("2T") })
    }

    @Test
    fun `short title prefix matches an edition suffix but unrelated same-word matches do not`() {
        assertTrue(BatchImageTitleMatcher.score("Female Boss Hints", "Female Boss Hints (Tabal)") >= 92)
        assertTrue(BatchImageTitleMatcher.score("Female Boss Hints", "Female Boss Anime Hints Collection") < 92)
    }

    @Test
    fun `automatic add requires a high score and a clear lead over a competing title`() {
        assertTrue(BatchImageTitleMatcher.isConfidentMatch(100, null))
        assertTrue(BatchImageTitleMatcher.isConfidentMatch(95, null))
        assertTrue(BatchImageTitleMatcher.isConfidentMatch(100, 92))
        assertTrue(!BatchImageTitleMatcher.isConfidentMatch(100, 93))
        assertTrue(!BatchImageTitleMatcher.isConfidentMatch(94, null))
    }

    @Test
    fun `source failure details are bounded and do not expose request URLs`() {
        val note = BatchImageSourceDiagnostics.sourceIssueNote(
            listOf(
                "Slow Source: exceeded 45s",
                "Broken Source: java.net.SocketTimeoutException https://private.example/path?token=secret",
                "Broken Source: java.net.SocketTimeoutException https://private.example/path?token=secret",
                "Extra Source: ${"x".repeat(300)}",
            ),
        )

        assertTrue(note.startsWith(" Sources: Slow Source: exceeded 45s"))
        assertTrue(!note.contains("private.example"))
        assertTrue(!note.contains("secret"))
        assertTrue(note.length <= 200)
    }

    @Test
    fun `source-local cancellation is recorded but worker cancellation still propagates`() {
        assertTrue(!BatchImageSourceDiagnostics.shouldPropagateCancellation(workerStopped = false, coroutineActive = true))
        assertTrue(BatchImageSourceDiagnostics.shouldPropagateCancellation(workerStopped = true, coroutineActive = true))
        assertTrue(BatchImageSourceDiagnostics.shouldPropagateCancellation(workerStopped = false, coroutineActive = false))

        val detail = BatchImageSourceDiagnostics.issueSummary(
            "Example Source",
            "request cancelled https://example.invalid/private?token=secret",
            "CancellationException",
        )
        assertEquals("Example Source: request cancelled [URL]", detail)
        assertEquals("Example Source: CancellationException", BatchImageSourceDiagnostics.issueSummary("Example Source", null, "CancellationException"))
    }

    @Test
    fun `duplicate OCR titles share one search group while retaining both screenshot records`() {
        val first = BatchImageRecord("one", "Female Boss Hints", null, null, null, "content://one", 92)
        val duplicate = BatchImageRecord("two", "Female Boss Hints by Tabal", "Tabal", null, null, "content://two", 87)
        val noTitle = BatchImageRecord("three", null, null, "682640", null, "content://three", 96)

        val groups = BatchImageTitleMatcher.groupRecords(listOf(first, duplicate, noTitle))

        assertEquals(1, groups.size)
        assertEquals("Female Boss Hints", groups.single().title)
        assertEquals(listOf("one", "two"), groups.single().records.map { it.id })
    }

    @Test
    fun `selection plan separates unique title searches codes and other review rows`() {
        val firstTitle = BatchImageRecord("title1", "Female Boss Hints", null, null, null, "content://one", 92)
        val sameTitleDifferentArtist = BatchImageRecord("title2", "Female Boss Hints by Tabal", "Tabal", null, null, "content://two", 87)
        val firstCode = BatchImageRecord("code1", null, null, "682640", null, "content://three", 96)
        val duplicateCode = BatchImageRecord("code2", null, null, "682640", null, "content://four", 92)
        val linkOnly = BatchImageRecord("link", null, null, null, "https://example.org/item", "content://five", 82)

        val plan = BatchImageSelectionPlan.from(listOf(firstTitle, sameTitleDifferentArtist, firstCode, duplicateCode, linkOnly))

        assertEquals(2, plan.titleRows.size)
        assertEquals(1, plan.titleGroups.size)
        assertEquals(2, plan.codeRows.size)
        assertEquals(listOf("https://nhentai.net/g/682640/"), plan.codeUrls)
        assertEquals(listOf("link"), plan.otherRows.map { it.id })
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

    @Test
    fun `social commenter names and feed labels are not extracted as titles`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "Posts you may have missed",
                "Ask about this image",
                "Nguyen Nhh",
                "Veins Akudora",
                "8 months ago 735x820",
                "77 shares",
                "Contact the moderators of this subreddit if you have any questions",
                "I am a bot and this action was performed automatically",
            ),
            "content://example/social-ui-clutter.jpg",
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `reddit body sentence fragments do not become titles but standalone answer does`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "r/doujinshi",
                "u/automoderator 1h",
                "to use these types of reverse image search",
                "Due to our enforced title format you can use reddit search to find it",
                "girl was either one of the Kongo sisters, Nagato sisters, or Yamato,",
                "Immoral Netori Style",
            ),
            "content://example/reddit-answer.jpg",
        )
        assertEquals(listOf("Immoral Netori Style"), results.map { it.title })
    }

    @Test
    fun `two-word commenter name is rejected next to a valid longer post title`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf("X r/doujinshi", "Veins Akudora", "Full sauce: The Perfect Fuck Buddy 3"),
            "content://example/commenter-and-title.jpg",
        )
        assertEquals(listOf("The Perfect Fuck Buddy 3"), results.map { it.title })
    }

    @Test
    fun `facebook feed comments names labels and reactions are not import candidates`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "Facebook",
                "Artist: Age doesn't matter madam",
                "Age doesn't matter madam",
                "Yo boy stood on business!! r/Animemes",
                "That's all for now",
                "Back to Notifications",
                "CrotPedia Project 2dAuthor",
                "77 shares",
                "All comments",
                "今Top fan",
                "Best artist",
                "See translation",
                "Nah gini loh realita modern JP pacaran tinggal",
            ),
            "content://example/facebook-feed.jpg",
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `google lens recommendations and reddit header clutter are filtered but real title remains`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "O Instagram",
                "UP NEXT: The Craft Boy Anime 0:21",
                "18 Cuidado Con Manola (Futa) Mada kimi no",
                "De nandy estamos a nada del viernes #sempai... more",
                "X rldoujinshi",
                "V View 2 replies",
                "See translation",
                "Spend Time with Your AL Girlfriend Now",
            ),
            "content://example/google-lens-results.jpg",
        )
        assertEquals(listOf("Spend Time with Your AL Girlfriend Now"), results.map { it.title })
    }

    @Test
    fun `reddit timestamp and sauce-hero comment are not treated as titles`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "X r/doujinshi",
                "February 19, 2020 -17:10:",
                "u/example 2h",
                "It seems that the sauce hero has not arrived yet",
                "Join the conversation",
            ),
            "content://example/reddit-timestamp-and-comment.jpg",
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `three-word Facebook commenter name is not promoted as a title`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf("Michael Encienzo Omega", "All comments", "77 shares", "Reply"),
            "content://example/facebook-commenter-name.jpg",
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `female boss OCR suffix has a title-only keyword fallback`() {
        val queries = BatchImageTextExtractor.searchQueries("Female Boss Hints Taba")
        assertTrue(queries.contains("female boss hints"))
    }

    @Test
    fun `reddit comment reactions do not outrank a title linked in reply`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "X r/doujinshi",
                "Amazing work, cute couple",
                "u/commenter 3h",
                "Nice! That's all for now",
                "Full sauce: Let Me Stay the Night, Otaku",
            ),
            "content://example/reddit-comment-extras.jpg",
        )
        assertEquals(listOf("Let Me Stay the Night, Otaku"), results.map { it.title })
    }

    @Test
    fun `page metadata and reddit comment snippets do not duplicate a code result`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "X r/doujinshi",
                "Page Number: 136",
                "Additional Links: Pixiv",
                "Search image on Google, Yandex, SauceNao",
                "Since it's not included in the tankōbon volumes, information",
                "Code: 465278",
            ),
            "content://example/reddit-code-extra-comments.jpg",
        )
        assertEquals(1, results.size)
        assertEquals("465278", results.single().code)
        assertNull(results.single().title)
    }

    @Test
    fun `web links are exported in normalized form and social profile URLs are omitted`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("Source: pixiv.net/artworks/12345", "https://reddit.com/r/doujinshi", "nhentai.net/g/465278/"),
            "content://example/top-comment-links.jpg",
        ).single()
        assertEquals(listOf("https://pixiv.net/artworks/12345", "https://nhentai.net/g/465278/"), result.links)
        assertEquals("465278", result.code)
    }

    @Test
    fun `search query variants remove artist suffix and include useful title keywords`() {
        val queries = BatchImageTextExtractor.searchQueries("Gyaru to Mama desu zo Artist: Okumoto Yuutta")
        assertTrue(queries.first().contains("Gyaru to Mama desu zo"))
        assertTrue(queries.none { it.contains("Okumoto", ignoreCase = true) })
        assertTrue(queries.any { it.split(' ').size in 2..4 })
    }

    @Test
    fun `record carries all distinct detected links for export`() {
        val record = BatchImageRecord("id", "A Title", "An Artist", "465278", null, "content://example/image.jpg", 92, listOf("https://pixiv.net/1", "https://site.example/title"))
        assertEquals(listOf("https://pixiv.net/1", "https://site.example/title"), record.links)
    }

    @Test
    fun `trailing by artist is excluded from every generated source query`() {
        val queries = BatchImageTextExtractor.searchQueries("Attack Next Door Girl by Ichinose Land")
        assertTrue(queries.first().contains("Attack Next Door Girl"))
        assertTrue(queries.none { it.contains("Ichinose", ignoreCase = true) || it.contains("Land", ignoreCase = true) })
    }

    @Test
    fun `short title above following artist label is retained with the artist`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("My Title", "Artist: Kasei"),
            "content://example/short-title-artist.jpg",
        ).single()
        assertEquals("My Title", result.title)
        assertEquals("Kasei", result.artist)
    }

    @Test
    fun `facebook and reddit screenshot extras are dropped when a formatted title exists`() {
        val results = BatchImageTextExtractor.extractLines(
            listOf(
                "Diksi baru >>> BPJS",
                "Sederhana tapi penikmat ntr tidak suka",
                "Since it's not included in the tankōbon volumes, information",
                "Title: Gyaru to Mama desu zo",
                "Artist: Okumoto Yuutta",
            ),
            "content://example/facebook-and-reddit-extra.jpg",
        )
        assertEquals(listOf("Gyaru to Mama desu zo"), results.map { it.title })
        assertEquals(listOf("Okumoto Yuutta"), results.map { it.artist })
    }
}
