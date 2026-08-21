package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.KomikkuCustomisationPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.i18n.kmk.KMR

object SettingsKomikkuCustomisationScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { KomikkuCustomisationPreferences(Injekt.get<PreferenceStore>()) }
        PreferenceScaffold(
            titleRes = KMR.strings.pref_category_komikku_customisation,
            onBackPressed = navigator::pop,
            itemsProvider = { getPreferences(preferences) },
        )
    }

    @Composable
    private fun getPreferences(preferences: KomikkuCustomisationPreferences): List<Preference> {
        return listOf(
            Preference.PreferenceGroup(
                title = "Komikku Customisation",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.InfoPreference(
                        "These controls are optional. Defaults preserve existing behavior, use bounded state, and do not change the WebDAV backup format.",
                    ),
                    switch(preferences.dashboardEnabled(), "Custom dashboard", "Show a customizable overview for library, downloads, imports, sync, and recent activity."),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.dashboardCardOrder(),
                        entries = persistentMapOf(
                            "library,downloads,imports,sync,recent" to "Library → Downloads → Imports → Sync → Recent",
                            "imports,library,downloads,sync,recent" to "Imports → Library → Downloads → Sync → Recent",
                            "library,recent,imports,downloads,sync" to "Library → Recent → Imports → Downloads → Sync",
                        ),
                        title = "Dashboard card order",
                        subtitle = "%s",
                    ),
                    switch(preferences.continueBrowsing(), "Continue browsing row", "Show a small recent-item window instead of loading the full library."),
                    switch(preferences.toolbarCollapsed(), "Collapsible library toolbar", "Keep search, filter, sort, layout, and selection actions compact until expanded."),
                    switch(preferences.toolbarCounter(), "Show filtered item counter", "Display a database-backed count for the current filter without materializing all Manga objects."),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.gridDensity(),
                        entries = persistentMapOf("compact" to "Compact", "comfortable" to "Comfortable", "spacious" to "Spacious"),
                        title = "Library grid density",
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.gridColumnsOverride(),
                        entries = persistentMapOf("auto" to "Automatic", "2" to "2 columns", "3" to "3 columns", "4" to "4 columns", "5" to "5 columns"),
                        title = "Library grid columns",
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.coverRadius(),
                        entries = persistentMapOf("none" to "Square", "small" to "Small radius", "medium" to "Medium radius", "large" to "Large radius"),
                        title = "Cover corner radius",
                    ),
                    switch(preferences.showSourceBadges(), "Source badges", "Show the source identity on library cards."),
                    switch(preferences.showUnreadBadges(), "Unread badges", "Show unread counts as compact card badges."),
                    switch(preferences.showImportBadges(), "Import status badges", "Show recently imported, skipped, or failed status when available."),
                    switch(preferences.fastLibraryNavigation(), "Fast library navigation", "Use bounded indexes and lazy lists for large, continuously growing libraries."),
                    switch(preferences.boundedStats(), "Bounded statistics", "Calculate dashboard statistics incrementally instead of retaining the complete library in memory."),
                    switch(preferences.smartCollections(), "Smart collections", "Enable reusable views such as Unread, Imported recently, Failed downloads, and No cover."),
                    switch(preferences.compactSettings(), "Compact settings mode", "Reduce vertical spacing in settings screens."),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Import Manager",
                preferenceItems = persistentListOf(
                    switch(preferences.importManager(), "Import Manager screen", "Organize active, paused, completed, canceled, and failed jobs."),
                    switch(preferences.retryFailedOnly(), "Retry failed only", "Retry checkpointed failures without reprocessing successful links."),
                    switch(preferences.resumeCheckpoints(), "Resume from checkpoints", "Resume interrupted imports from bounded text checkpoints."),
                    switch(preferences.importPreview(), "Preview import changes", "Show estimated additions, duplicates, excluded tags, and date range before starting."),
                    switch(preferences.importTimeline(), "Import activity timeline", "Show discovery, filtering, queueing, retrying, and completion stages."),
                    switch(preferences.importQueuePreview(), "Import queue preview", "Show only a small next-items window to avoid large queue rendering costs."),
                    switch(preferences.importErrorCategories(), "Categorize import errors", "Separate rate limits, missing pages, timeouts, duplicates, and parsing failures."),
                    switch(preferences.importScheduleEnabled(), "Scheduled imports", "Enable an opt-in daily or one-time import schedule."),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.importScheduleTime(),
                        title = "Scheduled import time",
                        subtitle = "24-hour time, for example 21:00",
                    ),
                    switch(preferences.importNotificationDetails(), "Detailed import notifications", "Show added, skipped, failed, remaining, and retry information."),
                    switch(preferences.importSyncAfter(), "Sync after import", "Optionally enqueue sync after a successful import."),
                    switch(preferences.importSkipSync(), "Avoid sync during import", "Prevent heavy sync from competing with a long-running import."),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Sync and storage",
                preferenceItems = persistentListOf(
                    switch(preferences.syncStatusPanel(), "Sync status panel", "Show last successful sync, next attempt, connection state, and bounded size information."),
                    switch(preferences.syncDryRun(), "Sync dry run", "Preview sync changes without changing the single-file WebDAV backup."),
                    switch(preferences.syncAfterImport(), "Sync after import", "Queue sync only after the import worker finishes."),
                    switch(preferences.storageDashboard(), "Storage dashboard", "Show database, covers, downloads, and removable-cache usage."),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Reader, downloads, and notifications",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.readerTheme(),
                        entries = persistentMapOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED"),
                        title = "Reader theme",
                    ),
                    switch(preferences.readerGestures(), "Reader gestures", "Enable configurable brightness and page-navigation gestures."),
                    switch(preferences.readerChapterStrip(), "Reader chapter strip", "Show a compact chapter-switching strip."),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.downloadGrouping(),
                        entries = persistentMapOf("manga" to "Group by manga", "source" to "Group by source", "status" to "Group by status"),
                        title = "Download grouping",
                    ),
                    switch(preferences.downloadPerItemActions(), "Per-item download actions", "Allow pause, retry, move-to-top, and remove actions per item."),
                    switch(preferences.notificationChannels(), "Separate notification channels", "Keep imports, sync, downloads, and reminders independently controllable."),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Appearance and accessibility",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.themeAccent(),
                        entries = persistentMapOf("default" to "Default", "blue" to "Blue", "purple" to "Purple", "green" to "Green", "orange" to "Orange"),
                        title = "Accent color",
                    ),
                    switch(preferences.amoledTheme(), "AMOLED black surfaces", "Use pure black surfaces where supported."),
                    switch(preferences.highContrast(), "High contrast", "Increase contrast for text, controls, and status badges."),
                    switch(preferences.reducedMotion(), "Reduced motion", "Minimize nonessential animations."),
                    switch(preferences.largeText(), "Large text mode", "Use larger text in customization surfaces and management views."),
                    switch(preferences.showBottomLabels(), "Bottom navigation labels", "Show or hide labels beneath the main navigation icons."),
                    Preference.PreferenceItem.TextPreference(
                        title = "Reset Komikku Customisation settings",
                        subtitle = "Restore only these optional settings; library, imports, downloads, and WebDAV data are untouched.",
                        onClick = preferences::resetToDefaults,
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun switch(
        preference: tachiyomi.core.common.preference.Preference<Boolean>,
        title: String,
        subtitle: String,
    ): Preference.PreferenceItem.SwitchPreference = Preference.PreferenceItem.SwitchPreference(
        preference = preference,
        title = title,
        subtitle = subtitle,
    )
}
