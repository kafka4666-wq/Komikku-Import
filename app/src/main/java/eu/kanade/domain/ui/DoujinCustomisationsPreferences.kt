package eu.kanade.domain.ui

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preferences for doujin/image-heavy discovery and reading workflows.
 * These keys are local-only and never alter the WebDAV single-file protocol.
 */
class DoujinCustomisationsPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun discoveryEnabled() = preferenceStore.getBoolean("doujin_discovery_enabled", true)
    fun discoveryMode() = preferenceStore.getString("doujin_discovery_mode", "latest")
    fun discoverySources() = preferenceStore.getString("doujin_discovery_sources", "all")
    fun discoveryPageSize() = preferenceStore.getString("doujin_discovery_page_size", "24")
    fun rememberSeen() = preferenceStore.getBoolean("doujin_discovery_remember_seen", true)
    fun discoveryRefresh() = preferenceStore.getBoolean("doujin_discovery_refresh", true)
    fun discoveryLocalFiltering() = preferenceStore.getBoolean("doujin_discovery_local_filtering", true)

    fun advancedTagSearch() = preferenceStore.getBoolean("doujin_advanced_tag_search", true)
    fun includeTags() = preferenceStore.getString("doujin_include_tags", "")
    fun excludeTags() = preferenceStore.getString("doujin_exclude_tags", "")
    fun exactTagMatching() = preferenceStore.getBoolean("doujin_exact_tag_matching", false)
    fun savedTagCombinations() = preferenceStore.getBoolean("doujin_saved_tag_combinations", true)
    fun personalTags() = preferenceStore.getBoolean("doujin_personal_tags", true)
    fun tagWeighting() = preferenceStore.getBoolean("doujin_tag_weighting", true)
    fun tagWeights() = preferenceStore.getString("doujin_tag_weights", "")

    fun creatorPages() = preferenceStore.getBoolean("doujin_creator_pages", true)
    fun creatorSort() = preferenceStore.getString("doujin_creator_sort", "newest")
    fun similaritySearch() = preferenceStore.getBoolean("doujin_similarity_search", true)
    fun similarityLimit() = preferenceStore.getString("doujin_similarity_limit", "40")
    fun duplicateScanner() = preferenceStore.getBoolean("doujin_duplicate_scanner", true)
    fun duplicateScanLimit() = preferenceStore.getString("doujin_duplicate_scan_limit", "200")
    fun sourceAgnosticTitles() = preferenceStore.getBoolean("doujin_source_agnostic_titles", true)

    fun galleryIntegrityScanner() = preferenceStore.getBoolean("doujin_gallery_integrity_scanner", true)
    fun metadataScanner() = preferenceStore.getBoolean("doujin_metadata_scanner", true)
    fun repairOnlySelected() = preferenceStore.getBoolean("doujin_repair_only_selected", true)
    fun preservePersonalMetadata() = preferenceStore.getBoolean("doujin_preserve_personal_metadata", true)

    fun pageStrip() = preferenceStore.getBoolean("doujin_reader_page_strip", true)
    fun pageGrid() = preferenceStore.getBoolean("doujin_reader_page_grid", true)
    fun thumbnailSize() = preferenceStore.getString("doujin_reader_thumbnail_size", "medium")
    fun pageBookmarks() = preferenceStore.getBoolean("doujin_reader_page_bookmarks", true)
    fun bookmarkNotes() = preferenceStore.getBoolean("doujin_reader_bookmark_notes", true)
    fun smartRandom() = preferenceStore.getBoolean("doujin_smart_random", true)
    fun randomMode() = preferenceStore.getString("doujin_random_mode", "unread")
    fun minimumPages() = preferenceStore.getString("doujin_random_min_pages", "0")
    fun maximumPages() = preferenceStore.getString("doujin_random_max_pages", "0")
    fun excludeRead() = preferenceStore.getBoolean("doujin_random_exclude_read", true)
    fun excludeHidden() = preferenceStore.getBoolean("doujin_random_exclude_hidden", true)

    fun masonryLayout() = preferenceStore.getBoolean("doujin_masonry_layout", true)
    fun masonryColumns() = preferenceStore.getString("doujin_masonry_columns", "3")
    fun fuzzySearch() = preferenceStore.getBoolean("doujin_fuzzy_search", true)
    fun metadataSidePanel() = preferenceStore.getBoolean("doujin_metadata_side_panel", true)
    fun readingHeatmap() = preferenceStore.getBoolean("doujin_reading_heatmap", true)
    fun heatmapMetric() = preferenceStore.getString("doujin_heatmap_metric", "pages")
    fun stealthReader() = preferenceStore.getBoolean("doujin_stealth_reader", false)
    fun secureWindow() = preferenceStore.getBoolean("doujin_stealth_secure_window", false)
    fun hideNotifications() = preferenceStore.getBoolean("doujin_stealth_hide_notifications", true)

    fun resetToDefaults() {
        listOf(
            discoveryEnabled() to true,
            rememberSeen() to true,
            discoveryRefresh() to true,
            discoveryLocalFiltering() to true,
            advancedTagSearch() to true,
            exactTagMatching() to false,
            savedTagCombinations() to true,
            personalTags() to true,
            tagWeighting() to true,
            creatorPages() to true,
            similaritySearch() to true,
            duplicateScanner() to true,
            sourceAgnosticTitles() to true,
            galleryIntegrityScanner() to true,
            metadataScanner() to true,
            repairOnlySelected() to true,
            preservePersonalMetadata() to true,
            pageStrip() to true,
            pageGrid() to true,
            pageBookmarks() to true,
            bookmarkNotes() to true,
            smartRandom() to true,
            excludeRead() to true,
            excludeHidden() to true,
            masonryLayout() to true,
            fuzzySearch() to true,
            metadataSidePanel() to true,
            readingHeatmap() to true,
            stealthReader() to false,
            secureWindow() to false,
            hideNotifications() to true,
        ).forEach { (preference, value) -> preference.set(value) }
        listOf(
            discoveryMode() to "latest",
            discoverySources() to "all",
            discoveryPageSize() to "24",
            includeTags() to "",
            excludeTags() to "",
            tagWeights() to "",
            creatorSort() to "newest",
            similarityLimit() to "40",
            duplicateScanLimit() to "200",
            thumbnailSize() to "medium",
            randomMode() to "unread",
            minimumPages() to "0",
            maximumPages() to "0",
            masonryColumns() to "3",
            heatmapMetric() to "pages",
        ).forEach { (preference, value) -> preference.set(value) }
    }
}
