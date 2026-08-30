package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun LazyLibraryMasonry(
    items: List<LibraryItem>,
    showTitle: Boolean,
    showAuthor: Boolean,
    showStatus: Boolean,
    showUnread: Boolean,
    showSourceBadge: Boolean,
    appearance: DoujinCardAppearance,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (tachiyomi.domain.library.model.LibraryManga) -> Unit,
    onLongClick: (tachiyomi.domain.library.model.LibraryManga) -> Unit,
    onClickContinueReading: ((tachiyomi.domain.library.model.LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    val preferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val spacing by preferences.cardSpacing().collectAsState()
    val configuredColumns by preferences.masonryColumns().collectAsState()
    val columnCount = configuredColumns.toIntOrNull()?.coerceIn(2, 6) ?: columns.coerceIn(2, 6)

    LazyVerticalStaggeredGrid(
        columns = if (columns == 0) {
            StaggeredGridCells.Adaptive(128.dp)
        } else {
            StaggeredGridCells.Fixed(columnCount)
        },
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.coerceIn(0, 24).dp),
        verticalItemSpacing = spacing.coerceIn(0, 24).dp,
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                GlobalSearchItem(searchQuery = searchQuery, onClick = onGlobalSearchClicked)
            }
        }
        items(
            items = items,
            key = { it.libraryManga.manga.id },
            contentType = { "library_masonry_item" },
        ) { libraryItem ->
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
                if (libraryItem.libraryManga.totalChapters > 0) {
                    add("Pages: ${libraryItem.libraryManga.totalChapters}")
                    val read = libraryItem.libraryManga.readCount.coerceIn(0, libraryItem.libraryManga.totalChapters)
                    add("Read: $read/${libraryItem.libraryManga.totalChapters}")
                }
            }.joinToString("\n").takeIf { it.isNotBlank() }

            MangaComfortableGridItem(
                coverData = MangaCover(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    ogUrl = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                ),
                title = cardText ?: manga.title,
                onClick = { onClick(libraryItem.libraryManga) },
                onLongClick = { onLongClick(libraryItem.libraryManga) },
                isSelected = manga.id in selection,
                titleMaxLines = if (showTitle) 3 else 1,
                onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                    { onClickContinueReading(libraryItem.libraryManga) }
                } else {
                    null
                },
                coverBadgeStart = {
                    DownloadsBadge(count = libraryItem.downloadCount)
                    if (showUnread) UnreadBadge(count = libraryItem.unreadCount)
                },
                coverBadgeEnd = {
                    LanguageBadge(
                        isLocal = libraryItem.isLocal,
                        sourceLanguage = libraryItem.sourceLanguage,
                        useLangIcon = libraryItem.useLangIcon,
                    )
                    if (showSourceBadge) SourceIconBadge(source = libraryItem.source)
                },
                preserveCoverAspect = true,
                appearance = appearance,
                usePanoramaCover = false,
            )
        }
    }
}
