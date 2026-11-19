package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import java.io.File

/**
 * Persists the most recently rendered Library order for the reader's end-of-book handoff.
 * Only IDs are stored; manga metadata and chapter state remain in the app database.
 */
internal object ReaderLibraryOrderStore {
    private const val FILE_NAME = "reader_library_order.txt"
    private const val MAX_MANGA_IDS = 100_000

    data class Target(
        val mangaId: Long,
        val chapterId: Long,
    )

    fun save(context: Context, orderedMangaIds: List<Long>) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val temporary = File(context.filesDir, "$FILE_NAME.tmp")
            val content = orderedMangaIds.asSequence()
                .filter { it > 0L }
                .distinct()
                .take(MAX_MANGA_IDS)
                .joinToString(separator = "\n")
            temporary.writeText(content)
            if (!temporary.renameTo(file)) {
                file.writeText(content)
                temporary.delete()
            }
        }
    }

    fun read(context: Context): List<Long> = runCatching {
        File(context.filesDir, FILE_NAME)
            .takeIf(File::isFile)
            ?.useLines { lines ->
                lines.mapNotNull(String::toLongOrNull)
                    .filter { it > 0L }
                    .distinct()
                    .take(MAX_MANGA_IDS)
                    .toList()
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    /** Returns IDs strictly after [currentMangaId]; the order never wraps back to the start. */
    fun followingIds(orderedMangaIds: List<Long>, currentMangaId: Long): List<Long> {
        val ordered = orderedMangaIds.distinct()
        val currentIndex = ordered.indexOf(currentMangaId)
        if (currentIndex < 0 || currentIndex >= ordered.lastIndex) return emptyList()
        return ordered.subList(currentIndex + 1, ordered.size)
    }
}
