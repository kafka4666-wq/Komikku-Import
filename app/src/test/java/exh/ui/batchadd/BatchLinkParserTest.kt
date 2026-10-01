package exh.ui.batchadd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchLinkParserTest {
    @Test
    fun extractsMixedSourceLinksFromPastedText() {
        val result = BatchLinkParser.parse(
            """
            # gallery queue
            https://imhentai.xxx/g/12345/,
            [AsmHentai](https://asmhentai.com/gallery/example/)
            www.hentaiera.com/gallery/example
            https://hentaifox.com/read/example/)
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "https://imhentai.xxx/g/12345/",
                "https://asmhentai.com/gallery/example/",
                "https://www.hentaiera.com/gallery/example",
                "https://hentaifox.com/read/example/",
            ),
            result,
        )
    }

    @Test
    fun convertsVisitedTokensAndDeduplicatesLinks() {
        val result = BatchLinkParser.parse(
            "123456.abcdef12: https://e-hentai.org/g/123456/abcdef12/\n123456.abcdef12:",
        )

        assertEquals(
            listOf("https://e-hentai.org/g/123456/abcdef12"),
            result.map { it.trimEnd('/') }.distinct(),
        )
        assertEquals(
            listOf("https://exhentai.org/g/123456/abcdef12"),
            BatchLinkParser.parse("123456.abcdef12:", useExhentai = true),
        )
    }

    @Test
    fun supportsBareDomainsAndDoesNotRequireOneUrlPerLine() {
        val result = BatchLinkParser.parse("HentaiNexus: hentai-nexus.com/g/example; Hitomi: hitomi.la/reader/example")

        assertEquals(
            listOf(
                "https://hentai-nexus.com/g/example",
                "https://hitomi.la/reader/example",
            ),
            result,
        )
    }
}
