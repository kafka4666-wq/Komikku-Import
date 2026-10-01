package eu.kanade.domain.ui

/**
 * Single source of truth for the manga-only port audit. This is intentionally data-only so it can
 * be used by diagnostics/tests without coupling the visual layer to navigation or persistence.
 */
object MangaPortCoverage {
    enum class Decision { ADAPT, KEEP_KOMIKKU, EXCLUDE }
    data class Area(val id: String, val title: String, val decision: Decision, val implementation: String)

    val areas: List<Area> = listOf(
        Area("details.hero", "Manga hero/title and cover", Decision.ADAPT, "MangaInfoHeader + hero/blur/gradient preferences"),
        Area("details.metadata", "Metadata, status, genres, description, Markdown, show-more", Decision.KEEP_KOMIKKU, "MangaInfoHeader and MarkdownRender"),
        Area("details.actions", "Favorite, tracking, notes, edit info, share, copy, cover, download", Decision.KEEP_KOMIKKU, "MangaScreen, MangaInfoButtons, MangaDialogs, MangaBottomActionMenu"),
        Area("details.chapters", "Chapter rows, progress, unread/download indicators and actions", Decision.KEEP_KOMIKKU, "MangaChapterListItem and ChapterSettingsDialog"),
        Area("details.related", "Recommendations, related manga and source information", Decision.KEEP_KOMIKKU, "RelatedMangasRow and browse recommendation flows"),
        Area("library.cards", "Cards, spacing, shape, overlays, badges and selection states", Decision.ADAPT, "CommonMangaItem + MangaAuroraDesignSystem + Treasury visuals"),
        Area("library.organization", "Categories, tabs, filters, sorting, grouping and bulk actions", Decision.KEEP_KOMIKKU, "LibraryPager, LibraryToolbar, LibrarySettingsDialog and screen models"),
        Area("library.performance", "Lazy rendering, fast scroll, masonry and large-library safeguards", Decision.KEEP_KOMIKKU, "LazyLibraryGrid, LazyLibraryMasonry and FastScroll components"),
        Area("browse.sources", "Source list/cards, grouping, pins, filters and extension behavior", Decision.KEEP_KOMIKKU, "BrowseSourceScreen and source components"),
        Area("browse.search", "Search bar, result cards, tabs, loading/empty/error states", Decision.KEEP_KOMIKKU, "GlobalSearchScreen and browse components"),
        Area("settings.sheets", "Manga settings, filters, dialogs, notes, tracking and import controls", Decision.KEEP_KOMIKKU, "Existing Komikku settings/dialog stack"),
        Area("visual.aurora", "Reusable Aurora/Glass surfaces, borders, spacing, press and disabled states", Decision.ADAPT, "MangaAuroraDesignSystem"),
        Area("visual.treasury", "All applicable Treasury themes, auras, backgrounds and frames", Decision.ADAPT, "MangaTreasuryVisuals; all effects are immediately available"),
        Area("reader.baseline", "Reader engine, page rendering, gestures, zoom, caching and settings", Decision.KEEP_KOMIKKU, "Existing Komikku reader"),
        Area("reader.additions", "Only useful missing reader features", Decision.KEEP_KOMIKKU, "No visual reader transplant; preserve Komikku baseline"),
        Area("home", "Komikku Home layout, navigation, cards and effects", Decision.EXCLUDE, "Explicitly excluded by scope"),
        Area("anime-novel", "Anime, player, light-novel and novel-reader features", Decision.EXCLUDE, "Explicitly excluded by scope"),
    )

    val allTreasuryEffectsAvailable: Boolean = true
    val homePorted: Boolean = false
    val readerReplaced: Boolean = false
}
