package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.plus

@Composable
internal fun LazyLibraryGrid(
    modifier: Modifier = Modifier,
    columns: Int,
    contentPadding: PaddingValues,
    content: LazyGridScope.() -> Unit,
) {
    val doujinPreferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val spacing by doujinPreferences.cardSpacing().collectAsState()
    val gridStyle by doujinPreferences.gridStyle().collectAsState()
    val masonryLayout by doujinPreferences.masonryLayout().collectAsState()
    val masonry = masonryLayout || gridStyle == "masonry"
    FastScrollLazyVerticalGrid(
        columns = if (columns == 0) {
            GridCells.Adaptive(if (masonry) 116.dp else if (gridStyle == "compact") 112.dp else 128.dp)
        } else {
            GridCells.Fixed(if (masonry) (columns + 1).coerceAtMost(8) else if (gridStyle == "compact") (columns + 1).coerceAtMost(8) else columns)
        },
        modifier = modifier,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy((if (masonry) spacing - 1 else spacing).coerceIn(0, 24).dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.coerceIn(0, 24).dp),
        content = content,
    )
}

internal fun LazyGridScope.globalSearchItem(
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    if (!searchQuery.isNullOrEmpty()) {
        item(
            span = { GridItemSpan(maxLineSpan) },
            contentType = { "library_global_search_item" },
        ) {
            GlobalSearchItem(
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }
    }
}
