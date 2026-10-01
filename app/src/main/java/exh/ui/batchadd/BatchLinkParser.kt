package exh.ui.batchadd

/**
 * Extracts gallery URLs from Batch Add text and files.
 *
 * Source-specific resolution remains the responsibility of GalleryAdder and the
 * installed UrlImportableSource extensions. Keeping extraction source-agnostic
 * means new source extensions work without another app release.
 */
object BatchLinkParser {
    private val visitedGalleryRegex = Regex("""[0-9]*?\.[a-z0-9]*?:""", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex(
        """(?i)(?:https?://|www\.)[^\s<>\"']+|(?<![@\w])(?:[a-z0-9-]+\.)+[a-z]{2,63}(?:/[^\s<>\"']*)?""",
    )
    private val markdownUrlRegex = Regex("""(?i)\]\((https?://[^)]+)\)""")
    private val leadingNoise = "([{<\"'"
    private val trailingNoise = ".,;:!?)]}>\"'"

    private data class Candidate(val position: Int, val value: String)

    /**
     * Returns distinct, normalized URLs in encounter order.
     * E-Hentai visited tokens are converted to the currently selected E-Hentai
     * or ExHentai gallery host, preserving the existing Batch Add behavior.
     */
    fun parse(text: String, useExhentai: Boolean = false): List<String> {
        if (text.isBlank()) return emptyList()
        val host = if (useExhentai) "exhentai.org" else "e-hentai.org"

        val candidates = buildList {
            visitedGalleryRegex.findAll(text).forEach { match ->
                val parts = match.value.split('.')
                if (parts.size >= 2) {
                    add(
                        Candidate(
                            match.range.first,
                            "https://$host/g/${parts[0]}/${parts[1].removePrefix(":")}",
                        ),
                    )
                }
            }
            markdownUrlRegex.findAll(text).forEach { match ->
                add(Candidate(match.range.first, match.groupValues[1]))
            }
            urlRegex.findAll(text).forEach { match ->
                add(Candidate(match.range.first, match.value))
            }
        }

        return candidates
            .sortedBy { it.position }
            .asSequence()
            .map { normalize(it.value) }
            .filter(String::isNotBlank)
            .filterNot { it.startsWith("#") }
            .distinctBy { it.lowercase().trimEnd('/') }
            .toList()
    }

    private fun normalize(value: String): String {
        val cleaned = value.trim().trimStart(*leadingNoise.toCharArray()).trimEnd(*trailingNoise.toCharArray())
        return when {
            cleaned.startsWith("www.", ignoreCase = true) -> "https://$cleaned"
            cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            else -> "https://$cleaned"
        }
    }
}
