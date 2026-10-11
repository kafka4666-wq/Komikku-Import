package eu.kanade.tachiyomi.torrent

import java.util.Locale

data class TorrentImportValidation(
    val normalizedInput: String?,
    val errorMessage: String?,
) {
    val isValid: Boolean get() = normalizedInput != null && errorMessage == null
}

object TorrentImportInput {
    private val supportedBookExtensions = setOf(".cbz", ".zip", ".cbr", ".pdf")

    fun validate(raw: String): TorrentImportValidation {
        val input = raw.trim()
        if (input.isEmpty()) {
            return TorrentImportValidation(null, "Enter a magnet, .torrent URL, or local .torrent file path.")
        }
        if (input.startsWith("magnet:", ignoreCase = true)) {
            if (!input.contains("xt=urn:btih:", ignoreCase = true)) {
                return TorrentImportValidation(null, "Magnet links must include an xt=urn:btih hash.")
            }
            return TorrentImportValidation(input, null)
        }
        if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
            return TorrentImportValidation(input, null)
        }
        if (input.startsWith("file://", ignoreCase = true)) {
            if (!input.endsWith(".torrent", ignoreCase = true)) {
                return TorrentImportValidation(null, "Local file links must point to a .torrent file.")
            }
            return TorrentImportValidation(input, null)
        }
        if (input.endsWith(".torrent", ignoreCase = true)) {
            return TorrentImportValidation(input, null)
        }
        return TorrentImportValidation(null, "Unsupported input. Use a magnet URI, .torrent URL, or .torrent file path.")
    }

    fun isSupportedBookPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return supportedBookExtensions.any(lower::endsWith)
    }
}
