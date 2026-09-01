package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.torrent.TorrentStreamManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * A private in-app source for torrent-backed books. It is not exposed as an installed extension;
 * it only becomes populated after TorrentImportScreen has registered torrent metadata.
 */
class TorrentSource(
    private val manager: TorrentStreamManager,
) : HttpSource() {
    override val name: String = "Torrent"
    override val lang: String = "other"
    override val supportsLatest: Boolean = false
    override val baseUrl: String = "https://torrent.invalid"
    override val id: Long = ID

    override suspend fun getPopularManga(page: Int): MangasPage =
        safeBooksPage(page) { manager.allBooks() }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        safeBooksPage(page) { manager.allBooks().asReversed() }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        safeBooksPage(page) { manager.allBooks().filter { it.title.contains(query, ignoreCase = true) } }

    private inline fun safeBooksPage(
        page: Int,
        books: () -> List<TorrentStreamManager.TorrentBook>,
    ): MangasPage = runCatching { booksPage(books(), page) }
        .getOrElse { MangasPage(emptyList(), false) }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updated = manager.findBook(manga.url)?.toSManga() ?: manga
        val updatedChapters = if (fetchChapters && chapters.isEmpty()) {
            listOf(SChapter.create().apply {
                url = updated.url
                name = "Stream"
                chapter_number = 1f
            })
        } else {
            chapters
        }
        return SMangaUpdate(updated, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val bookKey = chapter.url.substringBefore("#")
        return manager.pageNames(bookKey).mapIndexed { index, entry ->
            val encoded = URLEncoder.encode(entry, StandardCharsets.UTF_8.name())
            Page(index, url = "$bookKey#$encoded", imageUrl = "$baseUrl/page/$bookKey/$encoded")
        }
    }

    override suspend fun getImage(page: Page): Response {
        val raw = page.url.substringBefore("#")
        val encoded = page.url.substringAfter('#', "")
        val entry = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        val request = Request.Builder().url("https://torrent.invalid/page").build()
        return try {
            val (bytes, contentType) = manager.readPage(raw, entry)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody(contentType.toMediaType()))
                .build()
        } catch (error: Throwable) {
            // If streaming fails (no peers, malformed archive, timeouts), return a clear HTTP error
            // response so the image pipeline and caller can handle it gracefully.
            val body = "Torrent streaming error: ${error.message ?: "unknown"}"
                .toResponseBody("text/plain".toMediaType())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(502)
                .message("Bad Gateway")
                .body(body)
                .build()
        }
    }

    private fun booksPage(books: List<TorrentStreamManager.TorrentBook>, page: Int): MangasPage {
        val pageSize = 40
        val start = (page - 1).coerceAtLeast(0) * pageSize
        val slice = books.drop(start).take(pageSize)
        return MangasPage(slice.map { it.toSManga() }, start + pageSize < books.size)
    }

    private fun TorrentStreamManager.TorrentBook.toSManga(): SManga {
        val book = this
        return SManga.create().apply {
            url = book.key
            title = book.title
            thumbnail_url = coverUrl(book.key)
            artist = book.artist
            author = book.artist
            description = "Torrent-backed stream • ${book.size / (1024 * 1024)} MiB"
            genre = "Torrent"
        }
    }

    companion object {
        // Kept in the positive non-extension range and stable across updates.
        const val ID: Long = 0x6B6F6D696B6B7554L
        const val COVER_PREFIX = "https://torrent.invalid/cover/"

        fun coverUrl(bookKey: String): String = COVER_PREFIX + bookKey
    }
}
