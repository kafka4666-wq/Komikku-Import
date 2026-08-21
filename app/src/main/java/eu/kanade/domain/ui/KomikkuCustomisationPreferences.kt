package eu.kanade.domain.ui

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preferences for the optional Komikku Customisation layer.
 *
 * Defaults intentionally preserve the existing Komikku behavior. Features that
 * could change background work or visual density are opt-in and can be wired
 * incrementally without changing the database or WebDAV backup format.
 */
class KomikkuCustomisationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun dashboardEnabled() = preferenceStore.getBoolean("kmk_custom_dashboard_enabled", false)
    fun dashboardCardOrder() = preferenceStore.getString(
        "kmk_custom_dashboard_card_order",
        "library,downloads,imports,sync,recent",
    )
    fun continueBrowsing() = preferenceStore.getBoolean("kmk_custom_continue_browsing", true)
    fun toolbarCollapsed() = preferenceStore.getBoolean("kmk_custom_toolbar_collapsed", false)
    fun toolbarCounter() = preferenceStore.getBoolean("kmk_custom_toolbar_show_counter", true)
    fun gridDensity() = preferenceStore.getString("kmk_custom_grid_density", "comfortable")
    fun gridColumnsOverride() = preferenceStore.getString("kmk_custom_grid_columns_override", "auto")
    fun coverRadius() = preferenceStore.getString("kmk_custom_cover_radius", "medium")
    fun showSourceBadges() = preferenceStore.getBoolean("kmk_custom_show_source_badges", true)
    fun showUnreadBadges() = preferenceStore.getBoolean("kmk_custom_show_unread_badges", true)
    fun showImportBadges() = preferenceStore.getBoolean("kmk_custom_show_import_badges", true)
    fun fastLibraryNavigation() = preferenceStore.getBoolean("kmk_custom_fast_library_navigation", true)
    fun boundedStats() = preferenceStore.getBoolean("kmk_custom_use_bounded_stats", true)
    fun smartCollections() = preferenceStore.getBoolean("kmk_custom_smart_collections", true)
    fun compactSettings() = preferenceStore.getBoolean("kmk_custom_compact_settings", false)

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

    fun readerTheme() = preferenceStore.getString("kmk_custom_reader_theme", "system")
    fun readerGestures() = preferenceStore.getBoolean("kmk_custom_reader_gestures", true)
    fun readerChapterStrip() = preferenceStore.getBoolean("kmk_custom_reader_chapter_strip", true)
    fun downloadGrouping() = preferenceStore.getString("kmk_custom_download_grouping", "manga")
    fun downloadPerItemActions() = preferenceStore.getBoolean("kmk_custom_download_per_item_actions", true)
    fun notificationChannels() = preferenceStore.getBoolean("kmk_custom_notification_channels", true)

    fun themeAccent() = preferenceStore.getString("kmk_custom_theme_accent", "default")
    fun amoledTheme() = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)
    fun highContrast() = preferenceStore.getBoolean("kmk_custom_high_contrast", false)
    fun reducedMotion() = preferenceStore.getBoolean("kmk_custom_reduced_motion", false)
    fun largeText() = preferenceStore.getBoolean("kmk_custom_large_text", false)
    fun showBottomLabels() = preferenceStore.getBoolean("pref_show_bottom_bar_labels", true)

    fun resetToDefaults() {
        dashboardEnabled().set(false)
        dashboardCardOrder().set("library,downloads,imports,sync,recent")
        continueBrowsing().set(true)
        toolbarCollapsed().set(false)
        toolbarCounter().set(true)
        gridDensity().set("comfortable")
        gridColumnsOverride().set("auto")
        coverRadius().set("medium")
        showSourceBadges().set(true)
        showUnreadBadges().set(true)
        showImportBadges().set(true)
        fastLibraryNavigation().set(true)
        boundedStats().set(true)
        smartCollections().set(true)
        compactSettings().set(false)
        importManager().set(true)
        retryFailedOnly().set(true)
        resumeCheckpoints().set(true)
        importPreview().set(true)
        importTimeline().set(true)
        importQueuePreview().set(true)
        importErrorCategories().set(true)
        importScheduleEnabled().set(false)
        importScheduleTime().set("21:00")
        importNotificationDetails().set(true)
        importSyncAfter().set(false)
        importSkipSync().set(true)
        syncStatusPanel().set(true)
        syncDryRun().set(false)
        syncAfterImport().set(false)
        storageDashboard().set(true)
        readerTheme().set("system")
        readerGestures().set(true)
        readerChapterStrip().set(true)
        downloadGrouping().set("manga")
        downloadPerItemActions().set(true)
        notificationChannels().set(true)
        themeAccent().set("default")
        amoledTheme().set(false)
        highContrast().set(false)
        reducedMotion().set(false)
        largeText().set(false)
        showBottomLabels().set(true)
    }
}
