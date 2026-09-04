package eu.kanade.tachiyomi.torrent

import java.net.URI
import java.util.Locale

object TorrentImportInput {
    private val magnetHashRegex = Regex("(?i)(^|[?&])xt=urn:btih:[a-z0-9]{32,40}([&]|$)")

    fun validationError(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return "Enter a magnet URI or direct .torrent URL."

        if (input.startsWith("magnet:", true)) {
            return if (magnetHashRegex.containsMatchIn(input.substringAfter("magnet:", ""))) {
                null
            } else {
                "Invalid magnet URI. Include an xt=urn:btih info hash."
            }
        }

        val uri = runCatching { URI(input) }.getOrNull()
            ?: return "Invalid link. Use a magnet URI or an https://...torrent URL."
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") {
            return "Unsupported link scheme. Use magnet:, https://, or http://."
        }
        val path = uri.path.orEmpty()
        val isTorrentFile = path.endsWith(".torrent", ignoreCase = true)
        val isNyaaLikeDetail = path.contains("/view/", ignoreCase = true)
        if (!isTorrentFile && !isNyaaLikeDetail) {
            return "Link must be a direct .torrent URL or a supported detail page."
        }
        return null
    }

    fun encodeSelectedKeys(keys: Collection<String>): String =
        keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(separator = "\n")

    fun decodeSelectedKeys(raw: String?): Set<String> =
        raw.orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
}
