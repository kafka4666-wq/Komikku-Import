package exh.ui.batchimage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchImageTextExtractorTest {
    @Test
    fun `reddit recommendation title and bracketed artist are prioritized`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("r/doujinshi", "u/example 6h", "NSFW", "Rec: Girlfriend Sex (Benimura karu)", "Join the conversation"),
            "content://example/reddit.jpg",
        )
        assertEquals("Girlfriend Sex", result?.title)
        assertEquals("Benimura karu", result?.artist)
    }

    @Test
    fun `facebook structured Japanese title and artist are extracted`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("CrotPedia Project · 4m · Author", "Judul : Osananajimi no Sukinahito wa", "Chapter : 1", "Artist : Kasei", "Tags/Genre : Osananajimi", "See more"),
            "content://example/facebook.jpg",
        )
        assertEquals("Osananajimi no Sukinahito wa", result?.title)
        assertEquals("Kasei", result?.artist)
    }

    @Test
    fun `explicit code and full nhentai link are normalized`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("r/netorare", "Code: 682781", "https://nhentai.net/g/682781/", "Reply", "Join the conversation"),
            "content://example/reddit-code.jpg",
        )
        assertEquals("682781", result?.code)
        assertEquals("https://nhentai.net/g/682781/", result?.link)
    }

    @Test
    fun `standalone five to seven digit codes are retained`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("r/doujinshi", "NSFW", "682640", "Share", "Comment"),
            "content://example/code.jpg",
        )
        assertEquals("682640", result?.code)
        assertNull(result?.title)
    }

    @Test
    fun `social clutter and conversational comments alone are rejected`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("u/justhereforfun 1d", "This was a good one. The English title kinda spoils the fun.", "Join the conversation", "Upvote", "Share"),
            "content://example/clutter.jpg",
        )
        assertNull(result)
    }

    @Test
    fun `title with scanlation credit keeps title and creator`() {
        val result = BatchImageTextExtractor.extractLines(
            listOf("Female Boss Hints [Tabal] [TOMISCANS]", "Home / Ahegao / Female Boss Hints"),
            "content://example/web.jpg",
        )
        assertEquals("Female Boss Hints", result?.title)
        assertEquals("Tabal", result?.artist)
        assertTrue((result?.confidence ?: 0) > 0)
    }
}
