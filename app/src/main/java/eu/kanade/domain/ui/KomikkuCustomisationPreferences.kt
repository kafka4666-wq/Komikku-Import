package eu.kanade.domain.ui

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Optional Komikku feature preferences. These are local UI/operational controls;
 * they intentionally do not alter the manga database or WebDAV backup format.
 */
class KomikkuCustomisationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun dashboardEnabled() = preferenceStore.getBoolean("kmk_custom_dashboard_enabled", false)
    fun dashboardCardOrder() = preferenceStore.getString("kmk_custom_dashboard_card_order", "library,downloads,imports,sync,recent")
    fun dashboardVisibleItems() = preferenceStore.getString("kmk_custom_dashboard_visible_items", "6")
    fun dashboardSectionSize() = preferenceStore.getString("kmk_custom_dashboard_section_size", "medium")
    fun continueBrowsing() = preferenceStore.getBoolean("kmk_custom_continue_browsing", true)
    fun toolbarCollapsed() = preferenceStore.getBoolean("kmk_custom_toolbar_collapsed", false)
    fun toolbarCounter() = preferenceStore.getBoolean("kmk_custom_toolbar_show_counter", true)
    fun gridDensity() = preferenceStore.getString("kmk_custom_grid_density", "comfortable")
    fun gridColumnsOverride() = preferenceStore.getString("kmk_custom_grid_columns_override", "auto")
    fun libraryLayout() = preferenceStore.getString("kmk_custom_library_layout", "medium_grid")
    fun cardStyle() = preferenceStore.getString("kmk_custom_card_style", "normal")
    fun coverRadius() = preferenceStore.getString("kmk_custom_cover_radius", "medium")
    fun coverAspect() = preferenceStore.getString("kmk_custom_cover_aspect", "original")
    fun showTitleOverlay() = preferenceStore.getBoolean("kmk_custom_show_title_overlay", true)
    fun showAuthorOverlay() = preferenceStore.getBoolean("kmk_custom_show_author_overlay", false)
    fun showStatusOverlay() = preferenceStore.getBoolean("kmk_custom_show_status_overlay", false)
    fun showProgressOverlay() = preferenceStore.getBoolean("kmk_custom_show_progress_overlay", true)
    fun showDownloadOverlay() = preferenceStore.getBoolean("kmk_custom_show_download_overlay", true)
    fun showFavoriteOverlay() = preferenceStore.getBoolean("kmk_custom_show_favorite_overlay", true)
    fun showUpdateOverlay() = preferenceStore.getBoolean("kmk_custom_show_update_overlay", true)
    fun showSourceBadges() = preferenceStore.getBoolean("kmk_custom_show_source_badges", true)
    fun showUnreadBadges() = preferenceStore.getBoolean("kmk_custom_show_unread_badges", true)
    fun showImportBadges() = preferenceStore.getBoolean("kmk_custom_show_import_badges", true)
    fun fastLibraryNavigation() = preferenceStore.getBoolean("kmk_custom_fast_library_navigation", true)
    fun boundedStats() = preferenceStore.getBoolean("kmk_custom_use_bounded_stats", true)
    fun smartCollections() = preferenceStore.getBoolean("kmk_custom_smart_collections", true)
    fun compactSettings() = preferenceStore.getBoolean("kmk_custom_compact_settings", false)
    fun savedSearches() = preferenceStore.getBoolean("kmk_custom_saved_searches", true)
    fun advancedFilters() = preferenceStore.getBoolean("kmk_custom_advanced_filters", true)
    fun customSections() = preferenceStore.getBoolean("kmk_custom_sections", true)
    fun duplicateScanner() = preferenceStore.getBoolean("kmk_custom_duplicate_scanner", true)
    fun bulkUndo() = preferenceStore.getBoolean("kmk_custom_bulk_undo", true)

    fun performanceMode() = preferenceStore.getString("kmk_custom_performance_mode", "balanced")
    fun preloadPolicy() = preferenceStore.getString("kmk_custom_preload_policy", "balanced")
    fun animationMode() = preferenceStore.getString("kmk_custom_animation_mode", "full")
    fun lowMemoryImageMode() = preferenceStore.getBoolean("kmk_custom_low_memory_images", false)
    fun backgroundUpdates() = preferenceStore.getBoolean("kmk_custom_background_updates", true)
    fun automaticSourceChecking() = preferenceStore.getBoolean("kmk_custom_auto_source_checking", false)
    fun imageQuality() = preferenceStore.getString("kmk_custom_image_quality", "balanced")
    fun cacheBehavior() = preferenceStore.getString("kmk_custom_cache_behavior", "bounded")
    fun libraryPreloadWindow() = preferenceStore.getString("kmk_custom_library_preload_window", "nearby")

    fun importManager() = preferenceStore.getBoolean("kmk_custom_import_manager", true)
    fun retryFailedOnly() = preferenceStore.getBoolean("kmk_custom_retry_failed_only", true)
    fun resumeCheckpoints() = preferenceStore.getBoolean("kmk_custom_resume_checkpoints", true)
    fun importPreview() = preferenceStore.getBoolean("kmk_custom_import_preview", true)
    fun importTimeline() = preferenceStore.getBoolean("kmk_custom_import_timeline", true)
    fun importQueuePreview() = preferenceStore.getBoolean("kmk_custom_import_queue_preview", true)
    fun importErrorCategories() = preferenceStore.getBoolean("kmk_custom_import_error_categories", true)
    fun importScheduleEnabled() = preferenceStore.getBoolean("kmk_custom_import_schedule_enabled", false)
    fun importScheduleTime() = preferenceStore.getString("kmk_custom_import_schedule_time", "21:00")
    fun importNotificationDetails() = preferenceStore.getBoolean("kmk_custom_import_notification_details", true)
    fun importSyncAfter() = preferenceStore.getBoolean("kmk_custom_import_sync_after", false)
    fun importSkipSync() = preferenceStore.getBoolean("kmk_custom_import_skip_sync", true)

    fun syncStatusPanel() = preferenceStore.getBoolean("kmk_custom_sync_status_panel", true)
    fun syncDryRun() = preferenceStore.getBoolean("kmk_custom_sync_dry_run", false)
    fun syncAfterImport() = preferenceStore.getBoolean("kmk_custom_sync_after_import", false)
    fun storageDashboard() = preferenceStore.getBoolean("kmk_custom_storage_dashboard", true)
    fun offlineStatus() = preferenceStore.getBoolean("kmk_custom_offline_status", true)
    fun preserveLocalMetadata() = preferenceStore.getBoolean("kmk_custom_preserve_local_metadata", true)
    fun diagnostics() = preferenceStore.getBoolean("kmk_custom_diagnostics", true)
    fun automaticRecovery() = preferenceStore.getBoolean("kmk_custom_automatic_recovery", true)

    fun readerTheme() = preferenceStore.getString("kmk_custom_reader_theme", "system")
    fun readerGestures() = preferenceStore.getBoolean("kmk_custom_reader_gestures", true)
    fun readerChapterStrip() = preferenceStore.getBoolean("kmk_custom_reader_chapter_strip", true)
    fun readerPageSpacing() = preferenceStore.getString("kmk_custom_reader_page_spacing", "small")
    fun readerPageShadows() = preferenceStore.getBoolean("kmk_custom_reader_page_shadows", true)
    fun readerCornerRadius() = preferenceStore.getString("kmk_custom_reader_corner_radius", "none")
    fun readerImmersive() = preferenceStore.getBoolean("kmk_custom_reader_immersive", false)
    fun readerToolbarPosition() = preferenceStore.getString("kmk_custom_reader_toolbar_position", "bottom")
    fun readerVolumeNavigation() = preferenceStore.getBoolean("kmk_custom_reader_volume_navigation", false)
    fun readerBrightnessGestures() = preferenceStore.getBoolean("kmk_custom_reader_brightness_gestures", false)
    fun readerDoubleTapZoom() = preferenceStore.getBoolean("kmk_custom_reader_double_tap_zoom", true)
    fun readerAutoFit() = preferenceStore.getBoolean("kmk_custom_reader_auto_fit", true)
    fun readerPerTitleProfiles() = preferenceStore.getBoolean("kmk_custom_reader_per_title_profiles", true)
    fun readingProgressPrompt() = preferenceStore.getBoolean("kmk_custom_reading_progress_prompt", true)
    fun readingHistory() = preferenceStore.getBoolean("kmk_custom_reading_history", true)
    fun readingStatistics() = preferenceStore.getBoolean("kmk_custom_reading_statistics", true)

    fun downloadGrouping() = preferenceStore.getString("kmk_custom_download_grouping", "manga")
    fun downloadPerItemActions() = preferenceStore.getBoolean("kmk_custom_download_per_item_actions", true)
    fun downloadPriority() = preferenceStore.getString("kmk_custom_download_priority", "normal")
    fun downloadWifiOnly() = preferenceStore.getBoolean("kmk_custom_download_wifi_only", false)
    fun downloadChargingOnly() = preferenceStore.getBoolean("kmk_custom_download_charging_only", false)
    fun downloadMobileData() = preferenceStore.getBoolean("kmk_custom_download_mobile_data", true)
    fun downloadScreenOff() = preferenceStore.getBoolean("kmk_custom_download_screen_off", true)
    fun downloadIntegrity() = preferenceStore.getBoolean("kmk_custom_download_integrity", true)
    fun notificationChannels() = preferenceStore.getBoolean("kmk_custom_notification_channels", true)
    fun notifyDownloads() = preferenceStore.getBoolean("kmk_custom_notify_downloads", true)
    fun notifyDownloadFailures() = preferenceStore.getBoolean("kmk_custom_notify_download_failures", true)
    fun notifyUpdates() = preferenceStore.getBoolean("kmk_custom_notify_updates", false)
    fun notifySync() = preferenceStore.getBoolean("kmk_custom_notify_sync", true)
    fun notifyDigestOnly() = preferenceStore.getBoolean("kmk_custom_notify_digest_only", true)

    fun themeAccent() = preferenceStore.getString("kmk_custom_theme_accent", "default")
    fun themeMode() = preferenceStore.getString("kmk_custom_theme_mode", "system")
    fun dynamicColor() = preferenceStore.getBoolean("kmk_custom_dynamic_color", true)
    fun independentSurfaces() = preferenceStore.getBoolean("kmk_custom_independent_surfaces", false)
    fun amoledTheme() = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)
    fun highContrast() = preferenceStore.getBoolean("kmk_custom_high_contrast", false)
    fun reducedMotion() = preferenceStore.getBoolean("kmk_custom_reduced_motion", false)
    fun largeText() = preferenceStore.getBoolean("kmk_custom_large_text", false)
    fun haptics() = preferenceStore.getBoolean("kmk_custom_haptics", true)
    fun gestureCustomization() = preferenceStore.getBoolean("kmk_custom_gesture_customization", true)
    fun contextActions() = preferenceStore.getBoolean("kmk_custom_context_actions", true)
    fun showBottomLabels() = preferenceStore.getBoolean("pref_show_bottom_bar_labels", true)

    fun sourceDiagnostics() = preferenceStore.getBoolean("kmk_custom_source_diagnostics", true)
    fun sourceFailureBackoff() = preferenceStore.getBoolean("kmk_custom_source_failure_backoff", true)
    fun sourceMigrationReview() = preferenceStore.getBoolean("kmk_custom_source_migration_review", true)
    fun extensionCompatibility() = preferenceStore.getBoolean("kmk_custom_extension_compatibility", true)
    fun globalSearch() = preferenceStore.getBoolean("kmk_custom_global_search", true)
    fun commandPalette() = preferenceStore.getBoolean("kmk_custom_command_palette", true)
    fun keyboardShortcuts() = preferenceStore.getBoolean("kmk_custom_keyboard_shortcuts", true)
    fun adaptiveLayout() = preferenceStore.getBoolean("kmk_custom_adaptive_layout", true)
    fun tabletTwoPane() = preferenceStore.getBoolean("kmk_custom_tablet_two_pane", true)
    fun featureFlags() = preferenceStore.getBoolean("kmk_custom_feature_flags", true)
    fun stressSafeMode() = preferenceStore.getBoolean("kmk_custom_stress_safe_mode", false)

    fun resetToDefaults() {
        listOf(
            dashboardEnabled() to false,
            continueBrowsing() to true,
            toolbarCollapsed() to false,
            toolbarCounter() to true,
            showTitleOverlay() to true,
            showAuthorOverlay() to false,
            showStatusOverlay() to false,
            showProgressOverlay() to true,
            showDownloadOverlay() to true,
            showFavoriteOverlay() to true,
            showUpdateOverlay() to true,
            showSourceBadges() to true,
            showUnreadBadges() to true,
            showImportBadges() to true,
            fastLibraryNavigation() to true,
            boundedStats() to true,
            smartCollections() to true,
            compactSettings() to false,
            savedSearches() to true,
            advancedFilters() to true,
            customSections() to true,
            duplicateScanner() to true,
            bulkUndo() to true,
            lowMemoryImageMode() to false,
            backgroundUpdates() to true,
            automaticSourceChecking() to false,
            importManager() to true,
            retryFailedOnly() to true,
            resumeCheckpoints() to true,
            importPreview() to true,
            importTimeline() to true,
            importQueuePreview() to true,
            importErrorCategories() to true,
            importScheduleEnabled() to false,
            importNotificationDetails() to true,
            importSyncAfter() to false,
            importSkipSync() to true,
            syncStatusPanel() to true,
            syncDryRun() to false,
            syncAfterImport() to false,
            storageDashboard() to true,
            offlineStatus() to true,
            preserveLocalMetadata() to true,
            diagnostics() to true,
            automaticRecovery() to true,
            readerGestures() to true,
            readerChapterStrip() to true,
            readerPageShadows() to true,
            readerImmersive() to false,
            readerVolumeNavigation() to false,
            readerBrightnessGestures() to false,
            readerDoubleTapZoom() to true,
            readerAutoFit() to true,
            readerPerTitleProfiles() to true,
            readingProgressPrompt() to true,
            readingHistory() to true,
            readingStatistics() to true,
            downloadPerItemActions() to true,
            downloadWifiOnly() to false,
            downloadChargingOnly() to false,
            downloadMobileData() to true,
            downloadScreenOff() to true,
            downloadIntegrity() to true,
            notificationChannels() to true,
            notifyDownloads() to true,
            notifyDownloadFailures() to true,
            notifyUpdates() to false,
            notifySync() to true,
            notifyDigestOnly() to true,
            dynamicColor() to true,
            independentSurfaces() to false,
            amoledTheme() to false,
            highContrast() to false,
            reducedMotion() to false,
            largeText() to false,
            haptics() to true,
            gestureCustomization() to true,
            contextActions() to true,
            showBottomLabels() to true,
            sourceDiagnostics() to true,
            sourceFailureBackoff() to true,
            sourceMigrationReview() to true,
            extensionCompatibility() to true,
            globalSearch() to true,
            commandPalette() to true,
            keyboardShortcuts() to true,
            adaptiveLayout() to true,
            tabletTwoPane() to true,
            featureFlags() to true,
            stressSafeMode() to false,
        ).forEach { (preference, value) -> preference.set(value) }
        listOf(
            dashboardCardOrder() to "library,downloads,imports,sync,recent",
            dashboardVisibleItems() to "6",
            dashboardSectionSize() to "medium",
            gridDensity() to "comfortable",
            gridColumnsOverride() to "auto",
            libraryLayout() to "medium_grid",
            cardStyle() to "normal",
            coverRadius() to "medium",
            coverAspect() to "original",
            performanceMode() to "balanced",
            preloadPolicy() to "balanced",
            animationMode() to "full",
            imageQuality() to "balanced",
            cacheBehavior() to "bounded",
            libraryPreloadWindow() to "nearby",
            importScheduleTime() to "21:00",
            readerTheme() to "system",
            readerPageSpacing() to "small",
            readerCornerRadius() to "none",
            readerToolbarPosition() to "bottom",
            downloadGrouping() to "manga",
            downloadPriority() to "normal",
            themeAccent() to "default",
            themeMode() to "system",
        ).forEach { (preference, value) -> preference.set(value) }
    }
}
