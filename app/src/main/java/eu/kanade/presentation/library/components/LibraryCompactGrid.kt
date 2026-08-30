package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun LibraryCompactGrid(
    items: List<LibraryItem>,
    showTitle: Boolean,
    showAuthor: Boolean = false,
    showStatus: Boolean = false,
    showUnread: Boolean = true,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    val preferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val cardAppearance = rememberDoujinCardAppearance()
    val showPageCount by preferences.showPageCount().collectAsState()
    val showReadingProgress by preferences.showReadingProgress().collectAsState()
    val showSourceBadge by preferences.showSourceBadge().collectAsState()
    val masonryLayout by preferences.masonryLayout().collectAsState()
    val gridStyle by preferences.gridStyle().collectAsState()
    val useMasonry = masonryLayout || gridStyle == "masonry"

    if (useMasonry) {
        LazyLibraryMasonry(
            items = items,
            showTitle = showTitle,
            showAuthor = showAuthor,
            showStatus = showStatus,
            showUnread = showUnread,
            showSourceBadge = showSourceBadge,
            appearance = cardAppearance,
            columns = columns,
            contentPadding = contentPadding,
            selection = selection,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = onClickContinueReading,
            searchQuery = searchQuery,
            onGlobalSearchClicked = onGlobalSearchClicked,
        )
    } else {
        LazyLibraryGrid(
            modifier = Modifier.fillMaxSize(),
            columns = columns,
            contentPadding = contentPadding,
        ) {
            globalSearchItem(searchQuery, onGlobalSearchClicked)
            items(
                items = items,
                key = { it.libraryManga.manga.id },
                contentType = { "library_compact_grid_item" },
            ) { libraryItem ->
                LibraryCompactGridCard(
                    libraryItem = libraryItem,
                    showTitle = showTitle,
                    showAuthor = showAuthor,
                    showStatus = showStatus,
                    showUnread = showUnread,
                    showPageCount = showPageCount,
                    showReadingProgress = showReadingProgress,
                    showSourceBadge = showSourceBadge,
                    appearance = cardAppearance,
                    selection = selection,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClickContinueReading = onClickContinueReading,
                )
            }
        }
    }
}

@Composable
private fun LibraryCompactGridCard(
    libraryItem: LibraryItem,
    showTitle: Boolean,
    showAuthor: Boolean,
    showStatus: Boolean,
    showUnread: Boolean,
    showPageCount: Boolean,
    showReadingProgress: Boolean,
    showSourceBadge: Boolean,
    appearance: DoujinCardAppearance,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
) {
    val manga = libraryItem.libraryManga.manga
    val author = manga.author?.takeIf { it.isNotBlank() }
        ?: manga.artist?.takeIf { it.isNotBlank() }
    val status = when (manga.status) {
        SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
        SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
        SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
        SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
        SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
        SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
        else -> stringResource(MR.strings.unknown)
    }
    val cardText = buildList {
        if (showTitle) add(manga.title)
        if (showAuthor && author != null) add("Author: $author")
        if (showStatus) add("Status: $status")
        if (showPageCount && libraryItem.libraryManga.totalChapters > 0) {
            add("Pages: ${libraryItem.libraryManga.totalChapters}")
        }
        if (showReadingProgress && libraryItem.libraryManga.totalChapters > 0) {
            val read = libraryItem.libraryManga.readCount.coerceIn(0, libraryItem.libraryManga.totalChapters)
            add("Read: $read/${libraryItem.libraryManga.totalChapters}")
        }
    }.joinToString("\n").takeIf { it.isNotBlank() }

    MangaCompactGridItem(
        isSelected = manga.id in selection,
        title = cardText,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            ogUrl = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverBadgeStart = {
            DownloadsBadge(count = libraryItem.downloadCount)
            if (showUnread) UnreadBadge(count = libraryItem.unreadCount)
        },
        appearance = appearance,
        coverBadgeEnd = {
            LanguageBadge(
                isLocal = libraryItem.isLocal,
                sourceLanguage = libraryItem.sourceLanguage,
                useLangIcon = libraryItem.useLangIcon,
            )
            if (showSourceBadge) SourceIconBadge(source = libraryItem.source)
        },
        onLongClick = { onLongClick(libraryItem.libraryManga) },
        onClick = { onClick(libraryItem.libraryManga) },
        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
            { onClickContinueReading(libraryItem.libraryManga) }
        } else {
            null
        },
    )
}
