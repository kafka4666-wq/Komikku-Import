package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Dedicated, top-level settings for doujin discovery and image-heavy reading. */
object SettingsDoujinCustomisationsScreen : SearchableSettings {
    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = KMR.strings.pref_category_doujin_customisations

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
        return buildPreferences(preferences)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val preferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
        PreferenceScaffold(
            titleRes = KMR.strings.pref_category_doujin_customisations,
            onBackPressed = { navigator?.pop() },
            itemsProvider = { buildPreferences(preferences) },
        )
    }

    @Composable
    private fun buildPreferences(preferences: DoujinCustomisationsPreferences): List<Preference> = listOf(
        Preference.PreferenceGroup(
            title = "Discovery feed",
            preferenceItems = persistentListOf(
                info("Optional lazy discovery for image-heavy sources. Results are paged, deduplicated, and filtered without claiming remote filtering when filtering is local."),
                switch(preferences.discoveryEnabled(), "Infinite discovery feed", "Enable the dedicated lazy Discovery workflow."),
                list(preferences.discoveryMode(), "Default discovery mode", mapOf("latest" to "Latest", "popular" to "Popular", "updated" to "Recently updated", "random" to "Random", "rated" to "Highest rated", "added" to "Recently added", "recommended" to "Recommended")),
                list(preferences.discoverySources(), "Discovery sources", mapOf("all" to "All enabled sources", "nhentai" to "Nhentai", "favorites" to "Favorite sources")),
                list(preferences.discoveryPageSize(), "Discovery page size", mapOf("12" to "12", "24" to "24", "48" to "48")),
                switch(preferences.rememberSeen(), "Remember seen results", "Avoid repeatedly showing titles already seen in the feed."),
                switch(preferences.discoveryRefresh(), "Allow feed refresh", "Show a refresh action without losing the saved scroll position."),
                switch(preferences.discoveryLocalFiltering(), "Local discovery filtering", "Apply unsupported tag/source filters locally after retrieval."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Tags, creators, and similarity",
            preferenceItems = persistentListOf(
                switch(preferences.advancedTagSearch(), "Advanced tag combination search", "Support include, exclude, required, optional, exact, source-specific, and personal tags."),
                Preference.PreferenceItem.EditTextPreference(preference = preferences.includeTags(), title = "Include tags", subtitle = "Comma-separated tags; all required tags use AND semantics"),
                Preference.PreferenceItem.EditTextPreference(preference = preferences.excludeTags(), title = "Exclude tags", subtitle = "Comma-separated tags excluded with NOT semantics"),
                switch(preferences.exactTagMatching(), "Exact tag matching", "Prefer exact tag boundaries where the source supports them."),
                switch(preferences.savedTagCombinations(), "Saved tag combinations", "Save reusable include/exclude tag searches."),
                switch(preferences.creatorPages(), "Artist, circle, and group pages", "Open dedicated creator pages with sorting and library state filters."),
                list(preferences.creatorSort(), "Creator page sorting", mapOf("newest" to "Newest", "oldest" to "Oldest", "popular" to "Popular", "pages" to "Page count", "alphabetical" to "Alphabetical")),
                switch(preferences.similaritySearch(), "Local similarity search", "Rank library titles using local metadata without requiring network access."),
                list(preferences.similarityLimit(), "Similarity results", mapOf("20" to "20", "40" to "40", "80" to "80")),
                switch(preferences.fuzzySearch(), "Fuzzy metadata search", "Augment exact search with spelling, spacing, punctuation, alternate-title, and partial matches."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Library tools and organization",
            preferenceItems = persistentListOf(
                switch(preferences.duplicateScanner(), "Duplicate Scanner", "Find possible duplicates using normalized titles and available metadata; never delete automatically."),
                list(preferences.duplicateScanLimit(), "Duplicate scan result cap", mapOf("100" to "100 groups", "200" to "200 groups", "500" to "500 groups")),
                switch(preferences.galleryIntegrityScanner(), "Gallery Integrity Scanner", "Detect missing, corrupt, zero-byte, duplicate, unordered, or incomplete local pages."),
                switch(preferences.metadataScanner(), "Metadata Scanner and Repair", "Find incomplete metadata and repair selected or all entries using available source data."),
                switch(preferences.repairOnlySelected(), "Repair selected by default", "Require explicit selection before metadata repair; no automatic replacement of user data."),
                switch(preferences.preservePersonalMetadata(), "Preserve personal metadata", "Never overwrite personal tags, notes, progress, favorites, or categories during repair/merge."),
                switch(preferences.sourceAgnosticTitles(), "Source-agnostic titles", "Represent high-confidence copies as one logical work while retaining source mappings and preferred-source controls."),
                switch(preferences.personalTags(), "Personal tags", "Create, rename, assign, remove, search, filter, and bulk-assign offline user tags separately from source metadata."),
                switch(preferences.tagWeighting(), "Tag weighting", "Use local positive/negative tag preferences for optional discovery and ranking."),
                Preference.PreferenceItem.EditTextPreference(preference = preferences.tagWeights(), title = "Tag preferences", subtitle = "Optional comma-separated tag weights, for example: best art=5,reference=2"),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Reader tools",
            preferenceItems = persistentListOf(
                switch(preferences.pageStrip(), "Page strip", "Show nearby lazy-loaded page thumbnails for rapid navigation."),
                switch(preferences.pageGrid(), "Page grid", "Open a lazy thumbnail overview and jump directly to a page."),
                list(preferences.thumbnailSize(), "Reader thumbnail size", mapOf("small" to "Small", "medium" to "Medium", "large" to "Large")),
                switch(preferences.pageBookmarks(), "Page bookmarks", "Bookmark a page by title/gallery/page and open it later."),
                switch(preferences.bookmarkNotes(), "Bookmark notes", "Allow an optional note on each page bookmark."),
                switch(preferences.stealthReader(), "Stealth Reader", "Minimize sensitive reader UI and clearly indicate when privacy mode is active."),
                switch(preferences.secureWindow(), "Secure reader window", "Prevent screenshots and recent-app previews where Android supports it."),
                switch(preferences.hideNotifications(), "Hide sensitive notifications", "Suppress sensitive reader details in notification previews during stealth sessions."),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Random discovery and layout",
            preferenceItems = persistentListOf(
                switch(preferences.smartRandom(), "Smart Random", "Choose random titles from library, unread, favorites, source, category, artist, or tag filters."),
                list(preferences.randomMode(), "Random source set", mapOf("library" to "Entire library", "unread" to "Unread", "unread_favorite" to "Unread + favorites", "source" to "Selected source", "category" to "Selected category", "artist" to "Selected artist", "tags" to "Selected tags")),
                Preference.PreferenceItem.EditTextPreference(preference = preferences.minimumPages(), title = "Minimum pages", subtitle = "0 means no minimum"),
                Preference.PreferenceItem.EditTextPreference(preference = preferences.maximumPages(), title = "Maximum pages", subtitle = "0 means no maximum"),
                switch(preferences.excludeRead(), "Exclude read titles", "Do not select titles already read when the selected mode supports it."),
                switch(preferences.excludeHidden(), "Exclude hidden titles", "Do not select hidden titles."),
                switch(preferences.masonryLayout(), "Masonry gallery layout", "Preserve cover aspect ratios with lazy, virtualized columns."),
                list(preferences.masonryColumns(), "Masonry columns", mapOf("2" to "2", "3" to "3", "4" to "4", "5" to "5")),
            ),
        ),
        Preference.PreferenceGroup(
            title = "Activity and safety",
            preferenceItems = persistentListOf(
                switch(preferences.metadataSidePanel(), "Metadata side panel", "Inspect and filter by artist, group, tags, language, pages, source, date, and reading state without leaving browsing."),
                switch(preferences.readingHeatmap(), "Reading activity heatmap", "Show local day/week/month/year activity with streaks and incremental counters."),
                list(preferences.heatmapMetric(), "Heatmap metric", mapOf("titles" to "Titles opened", "chapters" to "Galleries completed", "pages" to "Pages read", "sessions" to "Reading sessions")),
                info("All scanners and ranking operations must remain paged or backgrounded for large libraries. No option here changes the WebDAV single-file protocol."),
                Preference.PreferenceItem.TextPreference(
                    title = "Reset Doujin Customisations settings",
                    subtitle = "Restore these optional controls without touching library entries, progress, downloads, imports, or WebDAV data.",
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
