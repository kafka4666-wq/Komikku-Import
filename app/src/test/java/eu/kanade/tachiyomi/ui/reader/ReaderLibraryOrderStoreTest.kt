package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderLibraryOrderStoreTest {
    @Test
    fun `returns only titles after the current visible library item`() {
        assertEquals(
            listOf(40L, 50L),
            ReaderLibraryOrderStore.followingIds(listOf(10L, 20L, 30L, 40L, 50L), 30L),
        )
    }

    @Test
    fun `deduplicates the displayed order before selecting successors`() {
        assertEquals(
            listOf(20L, 30L),
            ReaderLibraryOrderStore.followingIds(listOf(10L, 10L, 20L, 20L, 30L), 10L),
        )
    }

    @Test
    fun `does not wrap at the end of the library`() {
        assertEquals(
            emptyList<Long>(),
            ReaderLibraryOrderStore.followingIds(listOf(10L, 20L, 30L), 30L),
        )
    }

    @Test
    fun `returns no successors when current title is not in the saved visible order`() {
        assertEquals(
            emptyList<Long>(),
            ReaderLibraryOrderStore.followingIds(listOf(10L, 20L, 30L), 99L),
        )
    }
}
