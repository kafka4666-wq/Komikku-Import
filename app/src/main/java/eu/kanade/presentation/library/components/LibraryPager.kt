package eu.kanade.presentation.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.KomikkuCustomisationPreferences
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.domain.ui.KomikkuFullFeatureEngine
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun LibraryPager(
    state: PagerState,
    contentPadding: PaddingValues,
    hasActiveFilters: Boolean,
    selection: Set<Long>,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    getCategoryForPage: (Int) -> Category,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    onClickManga: (Category, LibraryManga) -> Unit,
    onLongClickManga: (Category, LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val customisationPreferences = remember { Injekt.get<KomikkuCustomisationPreferences>() }
    val doujinPreferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val showSectionSubtitles by doujinPreferences.showSectionSubtitles().collectAsState()
    val showTitleOverlay = remember { customisationPreferences.showTitleOverlay().get() }
    val showAuthorOverlay = remember { customisationPreferences.showAuthorOverlay().get() }
    val showStatusOverlay = remember { customisationPreferences.showStatusOverlay().get() }
    val showUnreadBadges = remember { customisationPreferences.showUnreadBadges().get() }

    HorizontalPager(
        modifier = Modifier.fillMaxSize(),
        state = state,
        verticalAlignment = Alignment.Top,
    ) { page ->
        if (page !in ((state.currentPage - 1)..(state.currentPage + 1))) {
            // To make sure only one offscreen page is being composed
            return@HorizontalPager
        }
        // Categories can be replaced while a source-grouped library is recomputing. Never ask
        // the caller for a stale index during that transient frame.
        if (page < 0) return@HorizontalPager
        val category = runCatching { getCategoryForPage(page) }.getOrNull() ?: return@HorizontalPager
        val items = getItemsForCategory(category)

        if (items.isEmpty()) {
            LibraryPagerEmptyScreen(
                searchQuery = searchQuery,
                hasActiveFilters = hasActiveFilters,
                contentPadding = contentPadding,
                onGlobalSearchClicked = onGlobalSearchClicked,
            )
            return@HorizontalPager
        }

        val configuredDisplayMode by getDisplayMode(page)
        val context = LocalContext.current
        val layoutDisplayMode = when (KomikkuFullFeatureEngine.layout(context)) {
            // MEDIUM_GRID is the default and leaves the existing per-category mode intact.
            KomikkuFullFeatureEngine.Layout.MEDIUM_GRID -> configuredDisplayMode
            KomikkuFullFeatureEngine.Layout.LARGE_GRID -> LibraryDisplayMode.ComfortableGrid
            KomikkuFullFeatureEngine.Layout.SMALL_GRID -> LibraryDisplayMode.CompactGrid
            KomikkuFullFeatureEngine.Layout.COMPACT_GRID -> LibraryDisplayMode.CompactGrid
            KomikkuFullFeatureEngine.Layout.COVER_ONLY -> LibraryDisplayMode.CoverOnlyGrid
            KomikkuFullFeatureEngine.Layout.LIST,
            KomikkuFullFeatureEngine.Layout.DETAILED_LIST -> LibraryDisplayMode.List
        }
        val cardStyle = customisationPreferences.cardStyle().get()
        val displayMode = when (cardStyle) {
            "minimal" -> LibraryDisplayMode.CoverOnlyGrid
            "detailed" -> LibraryDisplayMode.List
            else -> layoutDisplayMode
        }
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val adaptiveLayout = remember(configuration.screenWidthDp) {
            KomikkuFullFeatureEngine.adaptiveLayout(context)
        }
        val columns by if (displayMode != LibraryDisplayMode.List) {
            val configuredColumns by remember(isLandscape) { getColumnsForOrientation(isLandscape) }
            remember(isLandscape, configuredColumns, adaptiveLayout.columns) {
                mutableIntStateOf(maxOf(configuredColumns, adaptiveLayout.columns))
            }
        } else {
            remember { mutableIntStateOf(0) }
        }

        val onClickManga: (LibraryManga) -> Unit = { onClickManga(category, it) }
        val onLongClickManga: (LibraryManga) -> Unit = { onLongClickManga(category, it) }
        val visibleSections = remember(items, context) {
            KomikkuFullFeatureEngine.sections(context)
                .asSequence()
                .filter { it.enabled && it.name.isNotBlank() }
                .filter { rule ->
                    // Bound evaluation to a small sample so custom sections remain cheap for huge libraries.
                    items.take(512).any { item ->
                        val manga = item.libraryManga.manga
                        KomikkuFullFeatureEngine.matchesAdvancedQuery(
                            rule.expression,
                            listOf(manga.title, manga.author, manga.artist, manga.description, manga.genre?.joinToString(" "), manga.url),
                        )
                    }
                }
                .take(8)
                .toList()
        }

        val libraryBody: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxWidth()) {
                visibleSections.forEach { rule ->
                    Text(
                        text = rule.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    )
                    if (showSectionSubtitles) {
                        Text(
                            text = "Curated for your doujin library",
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                when (displayMode) {
                    LibraryDisplayMode.List -> {
                        LibraryList(
                            items = items,
                            contentPadding = contentPadding,
                            selection = selection,
                            onClick = onClickManga,
                            onLongClick = onLongClickManga,
                            onClickContinueReading = onClickContinueReading,
                            searchQuery = searchQuery,
                            onGlobalSearchClicked = onGlobalSearchClicked,
                        )
                    }
                    LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                        LibraryCompactGrid(
                            items = items,
                            showTitle = displayMode is LibraryDisplayMode.CompactGrid && showTitleOverlay,
                            showAuthor = displayMode is LibraryDisplayMode.CompactGrid && showAuthorOverlay,
                            showStatus = displayMode is LibraryDisplayMode.CompactGrid && showStatusOverlay,
                            showUnread = showUnreadBadges,
                            columns = columns,
                            contentPadding = contentPadding,
                            selection = selection,
                            onClick = onClickManga,
                            onLongClick = onLongClickManga,
                            onClickContinueReading = onClickContinueReading,
                            searchQuery = searchQuery,
                            onGlobalSearchClicked = onGlobalSearchClicked,
                        )
                    }
                    LibraryDisplayMode.ComfortableGrid -> {
                        LibraryComfortableGrid(
                            items = items,
                            columns = columns,
                            contentPadding = contentPadding,
                            selection = selection,
                            onClick = onClickManga,
                            onLongClick = onLongClickManga,
                            onClickContinueReading = onClickContinueReading,
                            searchQuery = searchQuery,
                            onGlobalSearchClicked = onGlobalSearchClicked,
                        )
                    }
                    // KMK -->
                    LibraryDisplayMode.ComfortableGridPanorama -> {
                        LibraryComfortableGrid(
                            items = items,
                            columns = columns,
                            contentPadding = contentPadding,
                            selection = selection,
                            onClick = onClickManga,
                            onLongClick = onLongClickManga,
                            onClickContinueReading = onClickContinueReading,
                            searchQuery = searchQuery,
                            onGlobalSearchClicked = onGlobalSearchClicked,
                            usePanoramaCover = true,
                        )
                    }
                    // KMK <--
                }
            }
        }
        if (adaptiveLayout.twoPane) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.width(200.dp).padding(12.dp)) {
                    Text("Library", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(category.name, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Text("${items.size} visible items", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                Column(modifier = Modifier.weight(1f)) { libraryBody() }
            }
        } else {
            libraryBody()
        }
    }
}

@Composable
private fun LibraryPagerEmptyScreen(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding + PaddingValues(8.dp))
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            GlobalSearchItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }

        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.weight(1f),
        )
    }
}
