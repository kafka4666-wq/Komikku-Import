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
        """(?i)(?:https?://|www\.)[^\s<>\"']+|(?<![@\w])(?:[a-z0-9-]+\.)+(?:com|net|org|io|co|jp|me|info|xyz|to|tv)(?:/[^\s<>\"']*)?""",
    )
    private val markdownUrlRegex = Regex("""(?i)\]\((https?://[^)]+)\)""")
    private val leadingNoise = "([{<\"'"
    private val trailingNoise = ".,;:!?)]}>\"'"

    /**
     * Returns distinct, normalized URLs in encounter order.
     * E-Hentai visited tokens are converted to the currently selected E-Hentai
     * or ExHentai gallery host, preserving the existing Batch Add behavior.
     */
    fun parse(text: String, useExhentai: Boolean = false): List<String> {
        if (text.isBlank()) return emptyList()

        val visited = visitedGalleryRegex.findAll(text).mapNotNull { match ->
            val parts = match.value.split('.')
            if (parts.size < 2) return@mapNotNull null
            val host = if (useExhentai) "exhentai.org" else "e-hentai.org"
            "https://$host/g/${parts[0]}/${parts[1].removePrefix(":")}"
        }

        val extracted = buildList {
            addAll(visited)
            markdownUrlRegex.findAll(text).forEach { add(it.groupValues[1]) }
            urlRegex.findAll(text).forEach { add(it.value) }
        }

        return extracted
            .asSequence()
            .map(::normalize)
            .filter(String::isNotBlank)
            .filterNot { it.startsWith("#") }
            .distinctBy(String::lowercase)
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
