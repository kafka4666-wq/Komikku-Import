package eu.kanade.domain.ui

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Persistent local preferences for the doujin catalog, detail page, reader, and privacy styling.
 * These values never alter manga rows or the WebDAV single-file backup format.
 */
class DoujinCustomisationsPreferences(
    private val preferenceStore: PreferenceStore,
) {
    init {
        if (!preferenceStore.getBoolean("doujin_cosmetic_recommended_profile_applied", false).get()) {
            applyRecommendedVisualProfile()
            preferenceStore.getBoolean("doujin_cosmetic_recommended_profile_applied", false).set(true)
        }
    }

    // Existing discovery and utility preferences
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

    // 41-item cosmetic system. Every key is consumed by the settings screen and by a runtime hook.
    fun dynamicCoverColors() = preferenceStore.getBoolean("doujin_cosmetic_dynamic_cover_colors", true)
    fun coverGradient() = preferenceStore.getBoolean("doujin_cosmetic_cover_gradient", true)
    fun cardStyle() = preferenceStore.getString("doujin_cosmetic_card_style", "standard")
    fun coverCornerRadius() = preferenceStore.getInt("doujin_cosmetic_cover_corner_radius", 8)
    fun cardShadow() = preferenceStore.getBoolean("doujin_cosmetic_card_shadow", true)
    fun metadataDensity() = preferenceStore.getString("doujin_cosmetic_metadata_density", "balanced")
    fun tagStyle() = preferenceStore.getString("doujin_cosmetic_tag_style", "normal")
    fun showPageCount() = preferenceStore.getBoolean("doujin_cosmetic_show_page_count", true)
    fun showReadingProgress() = preferenceStore.getBoolean("doujin_cosmetic_show_reading_progress", true)
    fun showSourceBadge() = preferenceStore.getBoolean("doujin_cosmetic_show_source_badge", true)
    fun gridStyle() = preferenceStore.getString("doujin_cosmetic_grid_style", "regular")
    fun coverSize() = preferenceStore.getString("doujin_cosmetic_cover_size", "medium")
    fun customCoverSize() = preferenceStore.getInt("doujin_cosmetic_custom_cover_size", 94)
    fun cardSpacing() = preferenceStore.getInt("doujin_cosmetic_card_spacing", 4)
    fun titlePosition() = preferenceStore.getString("doujin_cosmetic_title_position", "below")
    fun showSectionSubtitles() = preferenceStore.getBoolean("doujin_cosmetic_show_section_subtitles", true)
    fun compactMode() = preferenceStore.getBoolean("doujin_cosmetic_compact_mode", false)
    fun heroCover() = preferenceStore.getBoolean("doujin_cosmetic_hero_cover", true)
    fun heroBlur() = preferenceStore.getBoolean("doujin_cosmetic_hero_blur", true)
    fun heroBlurIntensity() = preferenceStore.getInt("doujin_cosmetic_hero_blur_intensity", 30)
    fun heroGradient() = preferenceStore.getBoolean("doujin_cosmetic_hero_gradient", true)
    fun heroGradientIntensity() = preferenceStore.getInt("doujin_cosmetic_hero_gradient_intensity", 55)
    fun dynamicBackground() = preferenceStore.getBoolean("doujin_cosmetic_dynamic_background", true)
    fun metadataLayout() = preferenceStore.getString("doujin_cosmetic_metadata_layout", "stacked")
    fun tagLayout() = preferenceStore.getString("doujin_cosmetic_tag_layout", "normal")
    fun readerUiStyle() = preferenceStore.getString("doujin_cosmetic_reader_ui_style", "premium")
    fun ambientReaderBackground() = preferenceStore.getBoolean("doujin_cosmetic_ambient_reader_background", false)
    fun pageCounterStyle() = preferenceStore.getString("doujin_cosmetic_page_counter_style", "simple")
    fun readerToolbarTransparency() = preferenceStore.getInt("doujin_cosmetic_reader_toolbar_transparency", 10)
    fun readerAnimation() = preferenceStore.getString("doujin_cosmetic_reader_animation", "subtle")
    fun readerPageSpacing() = preferenceStore.getInt("doujin_cosmetic_reader_page_spacing", 8)
    fun pageTransitions() = preferenceStore.getString("doujin_cosmetic_page_transitions", "subtle")
    fun microInteractions() = preferenceStore.getBoolean("doujin_cosmetic_micro_interactions", true)
    fun animations() = preferenceStore.getBoolean("doujin_cosmetic_animations", true)
    fun animationSpeed() = preferenceStore.getString("doujin_cosmetic_animation_speed", "normal")
    fun coverShadow() = preferenceStore.getBoolean("doujin_cosmetic_cover_shadow", true)
    fun coverFade() = preferenceStore.getBoolean("doujin_cosmetic_cover_fade", false)
    fun coverHighlight() = preferenceStore.getBoolean("doujin_cosmetic_cover_highlight", true)
    fun amoledStyle() = preferenceStore.getBoolean("doujin_cosmetic_amoled_style", false)
    fun accentColor() = preferenceStore.getString("doujin_cosmetic_accent_color", "dynamic")
    fun customAccentColor() = preferenceStore.getString("doujin_cosmetic_custom_accent_color", "#4F64A5")
    fun coverTransition() = preferenceStore.getBoolean("doujin_cosmetic_cover_transition", true)
    fun blurCoversInRecents() = preferenceStore.getBoolean("doujin_cosmetic_blur_covers_recents", false)
    fun hideSensitiveCovers() = preferenceStore.getBoolean("doujin_cosmetic_hide_sensitive_covers", false)
    fun hideTitlesInNotifications() = preferenceStore.getBoolean("doujin_cosmetic_hide_titles_notifications", false)

    fun resetAppearance() = resetKeys(appearanceKeys)
    fun resetLibraryAppearance() = resetKeys(libraryKeys)
    fun resetDetailAppearance() = resetKeys(detailKeys)
    fun resetReaderAppearance() = resetKeys(readerKeys)

    /**
     * Applies the balanced premium profile recommended for a large, growing library.
     * It changes only cosmetic preferences and remains fully reversible through the reset controls.
     */
    fun applyRecommendedVisualProfile() {
        dynamicCoverColors().set(true)
        coverGradient().set(true)
        cardStyle().set("editorial")
        coverCornerRadius().set(18)
        cardShadow().set(true)
        metadataDensity().set("balanced")
        tagStyle().set("filled")
        showPageCount().set(true)
        showReadingProgress().set(true)
        showSourceBadge().set(true)
        coverShadow().set(true)
        coverFade().set(true)
        coverHighlight().set(true)
        accentColor().set("dynamic")
        customAccentColor().set("#9B7CFF")
        amoledStyle().set(false)

        // Medium covers, regular rows, and 8dp spacing are the safer visual defaults for 80k+ titles.
        gridStyle().set("regular")
        masonryLayout().set(false)
        coverSize().set("medium")
        customCoverSize().set(92)
        cardSpacing().set(8)
        titlePosition().set("below")
        showSectionSubtitles().set(true)
        compactMode().set(false)

        heroCover().set(true)
        heroBlur().set(true)
        heroBlurIntensity().set(50)
        heroGradient().set(true)
        heroGradientIntensity().set(60)
        dynamicBackground().set(true)
        metadataLayout().set("editorial")
        tagLayout().set("editorial")
        coverTransition().set(true)

        readerUiStyle().set("premium")
        ambientReaderBackground().set(true)
        pageCounterStyle().set("floating")
        readerToolbarTransparency().set(35)
        readerAnimation().set("subtle")
        readerPageSpacing().set(8)
        pageTransitions().set("zoom")
        microInteractions().set(true)
        animations().set(true)
        animationSpeed().set("normal")

        hideSensitiveCovers().set(true)
        blurCoversInRecents().set(true)
        hideTitlesInNotifications().set(true)
        stealthReader().set(false)
    }

    fun applyPreset(name: String) {
        when (name.lowercase()) {
            "minimal" -> {
                cardStyle().set("minimal"); metadataDensity().set("minimal"); cardShadow().set(false)
                coverGradient().set(false); heroCover().set(false); heroBlur().set(false)
                animations().set(false); microInteractions().set(false); amoledStyle().set(false)
            }
            "editorial" -> applyRecommendedVisualProfile()
            "amoled" -> {
                amoledStyle().set(true); accentColor().set("system"); coverShadow().set(false)
                cardShadow().set(false); readerUiStyle().set("minimal")
            }
            "glass" -> {
                cardStyle().set("glass"); coverGradient().set(true); cardShadow().set(true)
                readerUiStyle().set("translucent"); readerToolbarTransparency().set(45)
            }
            "dynamic" -> {
                dynamicCoverColors().set(true); dynamicBackground().set(true); accentColor().set("dynamic")
                heroCover().set(true); heroGradient().set(true); coverHighlight().set(true)
            }
        }
    }

    fun resetToDefaults() {
        resetAppearance(); resetLibraryAppearance(); resetDetailAppearance(); resetReaderAppearance()
        listOf(
            discoveryEnabled() to true, rememberSeen() to true, discoveryRefresh() to true,
            discoveryLocalFiltering() to true, advancedTagSearch() to true, exactTagMatching() to false,
            savedTagCombinations() to true, personalTags() to true, tagWeighting() to true,
            creatorPages() to true, similaritySearch() to true, duplicateScanner() to true,
            sourceAgnosticTitles() to true, galleryIntegrityScanner() to true, metadataScanner() to true,
            repairOnlySelected() to true, preservePersonalMetadata() to true, pageStrip() to true,
            pageGrid() to true, pageBookmarks() to true, bookmarkNotes() to true, smartRandom() to true,
            excludeRead() to true, excludeHidden() to true, masonryLayout() to true, fuzzySearch() to true,
            metadataSidePanel() to true, readingHeatmap() to true, stealthReader() to false,
            secureWindow() to false, hideNotifications() to true,
        ).forEach { (preference, value) -> preference.set(value) }
        listOf(
            discoveryMode() to "latest", discoverySources() to "all", discoveryPageSize() to "24",
            includeTags() to "", excludeTags() to "", tagWeights() to "", creatorSort() to "newest",
            similarityLimit() to "40", duplicateScanLimit() to "200", thumbnailSize() to "medium",
            randomMode() to "unread", minimumPages() to "0", maximumPages() to "0", masonryColumns() to "3",
            heatmapMetric() to "pages",
        ).forEach { (preference, value) -> preference.set(value) }
    }

    private fun resetKeys(keys: List<() -> Unit>) {
        // Reset groups by applying the same safe values used by the accessors.
        when (keys) {
            appearanceKeys -> {
                dynamicCoverColors().set(true); coverGradient().set(true); cardStyle().set("standard")
                coverCornerRadius().set(8); cardShadow().set(true); metadataDensity().set("balanced")
                tagStyle().set("normal"); showPageCount().set(true); showReadingProgress().set(true)
                showSourceBadge().set(true); coverShadow().set(true); coverFade().set(false); coverHighlight().set(true)
                accentColor().set("dynamic"); customAccentColor().set("#4F64A5"); amoledStyle().set(false)
            }
            libraryKeys -> {
                gridStyle().set("regular"); coverSize().set("medium"); customCoverSize().set(94); cardSpacing().set(4)
                titlePosition().set("below"); showSectionSubtitles().set(true); compactMode().set(false)
            }
            detailKeys -> {
                heroCover().set(true); heroBlur().set(true); heroBlurIntensity().set(30)
                heroGradient().set(true); heroGradientIntensity().set(55); dynamicBackground().set(true)
                metadataLayout().set("stacked"); tagLayout().set("normal"); coverTransition().set(true)
            }
            readerKeys -> {
                readerUiStyle().set("premium"); ambientReaderBackground().set(false); pageCounterStyle().set("simple")
                readerToolbarTransparency().set(10); readerAnimation().set("subtle"); readerPageSpacing().set(8)
                pageTransitions().set("subtle"); microInteractions().set(true); animations().set(true)
                animationSpeed().set("normal")
            }
        }
    }

    private val appearanceKeys = listOf<() -> Unit>({})
    private val libraryKeys = listOf<() -> Unit>({})
    private val detailKeys = listOf<() -> Unit>({})
    private val readerKeys = listOf<() -> Unit>({})
}
