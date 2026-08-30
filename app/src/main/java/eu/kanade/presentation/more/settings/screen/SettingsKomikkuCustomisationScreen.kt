package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.KomikkuCustomisationPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsKomikkuCustomisationScreen : SearchableSettings {
    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = KMR.strings.pref_category_komikku_customisation

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences = remember { KomikkuCustomisationPreferences(Injekt.get<PreferenceStore>()) }
        return buildPreferences(preferences)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val preferences = remember { KomikkuCustomisationPreferences(Injekt.get<PreferenceStore>()) }
        val query = remember { mutableStateOf("") }
        val allPreferences = buildPreferences(preferences)
        PreferenceScaffold(
            titleRes = KMR.strings.pref_category_komikku_customisation,
            onBackPressed = { navigator?.pop() },
            itemsProvider = {
                listOf(
                    customizationFeatureSearchPreference(query.value) { query.value = it },
                ) + filterCustomizationPreferences(allPreferences, query.value)
            },
        )
    }

    @Composable
    private fun buildPreferences(preferences: KomikkuCustomisationPreferences): List<Preference> = listOf(
        Preference.PreferenceGroup(
            title = "Appearance and themes",
            preferenceItems = persistentListOf(
                info("Optional controls for Material You, themes, surfaces, and accessibility. Existing Appearance settings remain available."),
                list(preferences.themeMode(), "Theme", mapOf("system" to "Follow system", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED", "custom" to "Custom")),
                switch(preferences.dynamicColor(), "Dynamic color", "Use Android dynamic colors when supported."),
                list(preferences.themeAccent(), "Custom accent color", mapOf("default" to "Default", "blue" to "Blue", "purple" to "Purple", "green" to "Green", "orange" to "Orange")),
                switch(preferences.independentSurfaces(), "Independent surfaces", "Keep background and surface colors separately configurable where supported."),
                switch(preferences.amoledTheme(), "AMOLED black surfaces", "Use pure black surfaces for dark reading and library views."),
                switch(preferences.highContrast(), "High contrast", "Increase contrast for text, controls, and status badges."),
                switch(preferences.largeText(), "Large text mode", "Respect larger text choices in the added management surfaces."),
                switch(preferences.showBottomLabels(), "Bottom navigation labels", "Show or hide labels beneath main navigation icons."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Library layout and cards",
            preferenceItems = persistentListOf(
                info("Library choices are preference-driven and remain compatible with lazy, virtualized library rendering."),
                list(preferences.libraryLayout(), "Library layout", mapOf("large_grid" to "Large grid", "medium_grid" to "Medium grid", "small_grid" to "Small grid", "compact_grid" to "Compact grid", "list" to "List", "detailed_list" to "Detailed list", "cover_only" to "Cover-only")),
                list(preferences.gridColumnsOverride(), "Number of columns", mapOf("auto" to "Automatic", "2" to "2 columns", "3" to "3 columns", "4" to "4 columns", "5" to "5 columns", "6" to "6 columns")),
                list(preferences.gridDensity(), "Card spacing", mapOf("compact" to "Compact", "comfortable" to "Comfortable", "spacious" to "Spacious")),
                list(preferences.cardStyle(), "Card style", mapOf("minimal" to "Minimal", "normal" to "Normal", "detailed" to "Detailed")),
                list(preferences.coverAspect(), "Cover aspect ratio", mapOf("original" to "Original", "portrait" to "Portrait", "square" to "Square")),
                list(preferences.coverRadius(), "Corner radius", mapOf("none" to "Square", "small" to "Small", "medium" to "Medium", "large" to "Large")),
                switch(preferences.showTitleOverlay(), "Show title", "Display titles on library cards."),
                switch(preferences.showAuthorOverlay(), "Show author", "Display author and artist information where available."),
                switch(preferences.showStatusOverlay(), "Show status", "Display completion and series status."),
                switch(preferences.showUnreadBadges(), "Show unread count", "Display unread chapter count as a compact overlay."),
                switch(preferences.showProgressOverlay(), "Show reading progress", "Display current reading progress."),
                switch(preferences.showDownloadOverlay(), "Show download status", "Display downloaded and queued indicators."),
                switch(preferences.showFavoriteOverlay(), "Show favorite indicator", "Display favorite state."),
                switch(preferences.showUpdateOverlay(), "Show update indicator", "Display recently updated state."),
                switch(preferences.showSourceBadges(), "Show source badges", "Display source identity."),
                switch(preferences.showImportBadges(), "Show import status", "Display imported, skipped, or failed status when available."),
                switch(preferences.toolbarCollapsed(), "Collapsible toolbar", "Keep library actions compact until expanded."),
                switch(preferences.toolbarCounter(), "Filtered item counter", "Show a bounded count for the current filter."),
                switch(preferences.customSections(), "Custom library sections", "Enable live sections such as Unread, Favorites, Recently Added, and Recently Updated."),
                switch(preferences.savedSearches(), "Saved searches", "Keep reusable filter combinations as dynamic views."),
                switch(preferences.advancedFilters(), "Advanced filters", "Enable combined title, author, tag, source, category, read, download, favorite, and date filters."),
                switch(preferences.smartCollections(), "Smart collections", "Enable reusable library views without assuming a fixed library size."),
                switch(preferences.duplicateScanner(), "Duplicate scanner", "Allow non-destructive duplicate review based on normalized metadata."),
                switch(preferences.bulkUndo(), "Bulk-action undo", "Keep reversible bulk changes recoverable where technically possible."),
                switch(preferences.continueBrowsing(), "Continue browsing row", "Show a bounded recent-item row instead of loading the entire library."),
                switch(preferences.dashboardEnabled(), "Optional home dashboard", "Show or hide the customizable dashboard."),
                list(preferences.dashboardCardOrder(), "Dashboard section order", mapOf("library,downloads,imports,sync,recent" to "Library → Downloads → Imports → Sync → Recent", "imports,library,downloads,sync,recent" to "Imports → Library → Downloads → Sync → Recent", "library,recent,imports,downloads,sync" to "Library → Recent → Imports → Downloads → Sync")),
                list(preferences.dashboardSectionSize(), "Dashboard section size", mapOf("small" to "Small", "medium" to "Medium", "large" to "Large")),
                list(preferences.dashboardVisibleItems(), "Dashboard visible items", mapOf("4" to "4", "6" to "6", "8" to "8", "12" to "12")),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Performance and memory",
            preferenceItems = persistentListOf(
                list(preferences.performanceMode(), "Performance mode", mapOf("balanced" to "Balanced", "performance" to "Performance", "battery" to "Battery Saver", "custom" to "Custom")),
                list(preferences.preloadPolicy(), "Preloading", mapOf("off" to "Off", "conservative" to "Conservative", "balanced" to "Balanced", "aggressive" to "Aggressive")),
                list(preferences.libraryPreloadWindow(), "Nearby cover window", mapOf("none" to "Visible only", "nearby" to "Nearby", "wide" to "Nearby plus")),
                list(preferences.animationMode(), "Animations", mapOf("full" to "Full", "reduced" to "Reduced", "off" to "Off")),
                switch(preferences.backgroundUpdates(), "Background updates", "Allow normal update work while keeping long operations off the UI thread."),
                switch(preferences.automaticSourceChecking(), "Automatic source checking", "Enable only if you accept extra background network work."),
                list(preferences.imageQuality(), "Image quality", mapOf("data_saver" to "Data saver", "balanced" to "Balanced", "quality" to "Quality")),
                list(preferences.cacheBehavior(), "Cache behavior", mapOf("minimal" to "Minimal", "bounded" to "Bounded", "large" to "Large")),
                switch(preferences.lowMemoryImageMode(), "Low-memory image mode", "Reduce concurrent decoding and preloading on memory-constrained devices."),
                switch(preferences.fastLibraryNavigation(), "Fast library navigation", "Prefer bounded indexes and lazy list work for large libraries."),
                switch(preferences.boundedStats(), "Bounded statistics", "Avoid retaining the full library for counters and dashboard summaries."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Import manager",
            preferenceItems = persistentListOf(
                switch(preferences.importManager(), "Import Manager", "Organize active, paused, completed, canceled, and failed imports."),
                switch(preferences.retryFailedOnly(), "Retry failed only", "Retry checkpointed failures without reprocessing successful links."),
                switch(preferences.resumeCheckpoints(), "Resume checkpoints", "Resume interrupted imports from bounded checkpoint files."),
                switch(preferences.importPreview(), "Import preview", "Preview recognized, duplicate, excluded, and likely failed links."),
                switch(preferences.importTimeline(), "Import timeline", "Show discovery, filtering, queueing, retry, and completion stages."),
                switch(preferences.importQueuePreview(), "Queue preview", "Show a small next-items window rather than rendering a huge queue."),
                switch(preferences.importErrorCategories(), "Error categories", "Separate rate limits, unavailable pages, timeouts, duplicates, and parsing failures."),
                switch(preferences.importScheduleEnabled(), "Scheduled imports", "Enable an opt-in daily or one-time import schedule."),
                Preference.PreferenceItem.EditTextPreference(preference = preferences.importScheduleTime(), title = "Scheduled import time", subtitle = "24-hour time such as 21:00"),
                switch(preferences.importNotificationDetails(), "Detailed import notifications", "Show added, skipped, failed, remaining, retry, and percentage information."),
                switch(preferences.importSyncAfter(), "Sync after import", "Optionally enqueue sync after a successful import."),
                switch(preferences.importSkipSync(), "Avoid sync during import", "Prevent heavy sync from competing with long-running imports."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Reader and reading history",
            preferenceItems = persistentListOf(
                list(preferences.readerTheme(), "Reader background", mapOf("system" to "System", "light" to "White", "dark" to "Gray", "amoled" to "AMOLED", "sepia" to "Sepia")),
                list(preferences.readerPageSpacing(), "Page spacing", mapOf("none" to "None", "small" to "Small", "medium" to "Medium", "large" to "Large")),
                switch(preferences.readerPageShadows(), "Page shadows", "Show page shadows where supported."),
                list(preferences.readerCornerRadius(), "Page corner radius", mapOf("none" to "None", "small" to "Small", "medium" to "Medium")),
                switch(preferences.readerImmersive(), "Immersive/fullscreen reader", "Hide system chrome while reading."),
                list(preferences.readerToolbarPosition(), "Reader toolbar position", mapOf("top" to "Top", "bottom" to "Bottom", "auto" to "Automatic")),
                switch(preferences.readerGestures(), "Reader gestures", "Enable configurable page, brightness, and zoom gestures."),
                switch(preferences.readerVolumeNavigation(), "Volume-button navigation", "Use volume keys to move between pages."),
                switch(preferences.readerBrightnessGestures(), "Brightness gestures", "Adjust brightness with reader gestures."),
                switch(preferences.readerDoubleTapZoom(), "Double-tap zoom", "Enable double-tap zoom behavior."),
                switch(preferences.readerAutoFit(), "Automatic page fitting", "Fit pages to the available reader area."),
                switch(preferences.readerPerTitleProfiles(), "Per-title reader profiles", "Remember reader preferences per manga where supported."),
                switch(preferences.readerChapterStrip(), "Chapter strip", "Show a compact chapter-switching strip."),
                switch(preferences.readingProgressPrompt(), "Continue-reading prompt", "Ask before resuming at the stored page."),
                switch(preferences.readingHistory(), "Reading history", "Keep recent manga, chapters, timestamps, and progress visible."),
                switch(preferences.readingStatistics(), "Reading statistics", "Use incremental counters for chapters, pages, streaks, and activity."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Downloads and notifications",
            preferenceItems = persistentListOf(
                list(preferences.downloadGrouping(), "Download grouping", mapOf("manga" to "By manga", "source" to "By source", "status" to "By status")),
                list(preferences.downloadPriority(), "Default download priority", mapOf("highest" to "Highest", "high" to "High", "normal" to "Normal", "low" to "Low")),
                switch(preferences.downloadPerItemActions(), "Per-item queue actions", "Allow pause, retry, move, reorder, and remove actions."),
                switch(preferences.downloadWifiOnly(), "Wi-Fi only", "Restrict downloads to Wi-Fi when enabled."),
                switch(preferences.downloadChargingOnly(), "Charging only", "Restrict downloads to charging periods when enabled."),
                switch(preferences.downloadMobileData(), "Allow mobile data", "Permit downloads over mobile data."),
                switch(preferences.downloadScreenOff(), "Download with screen off", "Continue eligible downloads while the screen is off."),
                switch(preferences.downloadIntegrity(), "Download integrity checks", "Do not silently mark incomplete or corrupted files as complete."),
                switch(preferences.notificationChannels(), "Separate notification channels", "Keep imports, sync, downloads, and reminders independently controllable."),
                switch(preferences.notifyDownloads(), "Download completion notifications", "Notify when downloads finish."),
                switch(preferences.notifyDownloadFailures(), "Download failure notifications", "Notify about failed downloads without chapter-level spam."),
                switch(preferences.notifyUpdates(), "Manga update notifications", "Notify about available manga updates."),
                switch(preferences.notifySync(), "Sync notifications", "Notify about sync completion and failures."),
                switch(preferences.notifyDigestOnly(), "Digest notifications", "Group repetitive events into a digest."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Sources, offline safety, and recovery",
            preferenceItems = persistentListOf(
                switch(preferences.sourceDiagnostics(), "Source diagnostics", "Manually inspect source availability, search, details, chapters, images, latency, and recent errors."),
                switch(preferences.sourceFailureBackoff(), "Source backoff and cooldown", "Limit repeated failures with retry limits and temporary cooldowns."),
                switch(preferences.sourceMigrationReview(), "Source migration review", "Offer manual replacement suggestions without automatic deletion or migration."),
                switch(preferences.extensionCompatibility(), "Extension compatibility", "Keep source compatibility controls available for future extension API versions."),
                switch(preferences.offlineStatus(), "Offline-state reporting", "Keep local library workflows usable and explain network-only failures."),
                switch(preferences.preserveLocalMetadata(), "Preserve local metadata", "Keep title, tags, cover, progress, source, and chapter metadata locally available."),
                switch(preferences.syncStatusPanel(), "Sync status panel", "Show last sync, next attempt, connection state, and bounded size information."),
                switch(preferences.syncDryRun(), "Sync dry run", "Preview changes without modifying the single-file WebDAV backup."),
                switch(preferences.syncAfterImport(), "Sync after import", "Queue sync after an import worker finishes."),
                switch(preferences.storageDashboard(), "Storage dashboard", "Review database, covers, downloads, cache, and checkpoint usage."),
                switch(preferences.automaticRecovery(), "Automatic recovery", "Preserve state and expose a safe retry or restore action after failures."),
                switch(preferences.diagnostics(), "Privacy-safe diagnostics", "Show app, Android, memory, database, extension, source, and recent-error details without credentials."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Accessibility, input, and large screens",
            preferenceItems = persistentListOf(
                switch(preferences.reducedMotion(), "Reduced motion", "Minimize nonessential animations."),
                switch(preferences.haptics(), "Optional haptic feedback", "Respect system haptic settings for toggles and important actions."),
                switch(preferences.gestureCustomization(), "Gesture customization", "Enable controls for page swipes, long press, double tap, edge navigation, and pinch zoom."),
                switch(preferences.contextActions(), "Manga context actions", "Expose read, continue, mark, download, update, favorite, category, share, source, edit, and remove actions."),
                switch(preferences.keyboardShortcuts(), "Keyboard and mouse support", "Enable keyboard focus, Enter/Escape, arrow navigation, and hover-friendly behavior where supported."),
                switch(preferences.adaptiveLayout(), "Adaptive layouts", "Use responsive spacing and typography on larger screens."),
                switch(preferences.tabletTwoPane(), "Tablet two-pane layout", "Enable navigation rail and two-pane behavior where the host screen supports it."),
                switch(preferences.featureFlags(), "Feature flags", "Keep major added systems independently switchable for safer rollout."),
                switch(preferences.stressSafeMode(), "Low-memory safe mode", "Prefer conservative rendering and background work after memory pressure."),
                Preference.PreferenceItem.TextPreference(
                    title = "Reset Komikku Customisation settings",
                    subtitle = "Restore only these optional settings; library, imports, downloads, reading progress, and WebDAV data are untouched.",
                    onClick = preferences::resetToDefaults,
                ),
            ),
        ),
    )

    @Composable
    private fun info(text: String) = Preference.PreferenceItem.InfoPreference(text)

    @Composable
    private fun switch(
        preference: tachiyomi.core.common.preference.Preference<Boolean>,
        title: String,
        subtitle: String,
    ) = Preference.PreferenceItem.SwitchPreference(preference = preference, title = title, subtitle = subtitle)

    @Composable
    private fun list(
        preference: tachiyomi.core.common.preference.Preference<String>,
        title: String,
        values: Map<String, String>,
    ) = Preference.PreferenceItem.ListPreference(
        preference = preference,
        entries = persistentMapOf(*values.toList().toTypedArray()),
        title = title,
        subtitle = "%s",
    )
}
