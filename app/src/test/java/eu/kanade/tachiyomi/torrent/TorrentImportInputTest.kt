package eu.kanade.tachiyomi.torrent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TorrentImportInputTest {
    @Test
    fun `accepts valid magnet link`() {
        val error = TorrentImportInput.validationError("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=test")
        assertNull(error)
    }

    @Test
    fun `rejects magnet without info hash`() {
        val error = TorrentImportInput.validationError("magnet:?dn=test")
        assertEquals("Invalid magnet URI. Include an xt=urn:btih info hash.", error)
    }

    @Test
    fun `accepts direct torrent url`() {
        val error = TorrentImportInput.validationError("https://example.org/files/book.torrent")
        assertNull(error)
    }

    @Test
    fun `rejects non torrent url`() {
        val error = TorrentImportInput.validationError("https://example.org/files/book.zip")
        assertEquals("Link must be a direct .torrent URL or a supported detail page.", error)
    }

    @Test
    fun `selected key codec is stable`() {
        val encoded = TorrentImportInput.encodeSelectedKeys(listOf("a/1", "b/2", "a/1", " "))
        val decoded = TorrentImportInput.decodeSelectedKeys(encoded)
        assertEquals(setOf("a/1", "b/2"), decoded)
    }
}
