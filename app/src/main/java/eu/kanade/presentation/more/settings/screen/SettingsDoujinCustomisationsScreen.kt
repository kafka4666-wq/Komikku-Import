package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
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
        val query = remember { mutableStateOf("") }
        val allPreferences = buildPreferences(preferences)
        PreferenceScaffold(
            titleRes = KMR.strings.pref_category_doujin_customisations,
            onBackPressed = { navigator?.pop() },
            itemsProvider = {
                listOf(
                    customizationFeatureSearchPreference(query.value) { query.value = it },
                ) + filterCustomizationPreferences(allPreferences, query.value)
            },
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
                info("All scanners and ranking operations remain paged or backgrounded for large libraries. No option here changes the WebDAV single-file protocol."),
                Preference.PreferenceItem.TextPreference(
                    title = "Reset all Doujin Customisations",
                    subtitle = "Restore every cosmetic and utility preference without touching library entries, progress, downloads, imports, or WebDAV data.",
                    onClick = preferences::resetToDefaults,
                ),
            ),
        ),
    ) + cosmeticPreferences(preferences)

    @Composable
    private fun cosmeticPreferences(preferences: DoujinCustomisationsPreferences): List<Preference> {
        val previewStyle by preferences.cardStyle().collectAsState()
        val previewRadius by preferences.coverCornerRadius().collectAsState()
        return listOf(
            Preference.PreferenceGroup(
                title = "Appearance and cards",
                preferenceItems = persistentListOf(
                    info("These controls are persistent and are consumed by the shared doujin/library card and detail renderers. Defaults restore the normal Komikku presentation."),
                    switch(preferences.dynamicCoverColors(), "Dynamic Cover Colors", "Use a restrained cover-derived accent where the detail/card theme supports it."),
                    switch(preferences.coverGradient(), "Cover Gradient", "Show the subtle cover-to-surface gradient on cover cards."),
                    list(preferences.cardStyle(), "Card Style", mapOf("minimal" to "Minimal", "standard" to "Standard", "detailed" to "Detailed", "editorial" to "Editorial", "glass" to "Glass")),
                    slider(preferences.coverCornerRadius(), "Cover Corner Radius", "0–32dp", 0..32),
                    switch(preferences.cardShadow(), "Card Shadow", "Use restrained elevation on cards; disabling it returns flat surfaces."),
                    list(preferences.metadataDensity(), "Metadata Density", mapOf("minimal" to "Minimal", "balanced" to "Balanced", "detailed" to "Detailed")),
                    list(preferences.tagStyle(), "Tag Style", mapOf("simple" to "Simple", "normal" to "Normal", "compact" to "Compact", "editorial" to "Editorial", "filled" to "Filled")),
                    switch(preferences.showPageCount(), "Show Page Count", "Display page totals where metadata provides them."),
                    switch(preferences.showReadingProgress(), "Show Reading Progress", "Display reading progress where the card has progress data."),
                    switch(preferences.showSourceBadge(), "Show Source Badge", "Display the source indicator on cards that expose source metadata."),
                    switch(preferences.coverShadow(), "Cover Shadow", "Apply a subtle image shadow where supported."),
                    switch(preferences.coverFade(), "Cover Fade", "Fade cover edges into the card surface instead of using a hard edge."),
                    switch(preferences.coverHighlight(), "Cover Highlight", "Add a restrained highlight to loaded covers."),
                    list(preferences.accentColor(), "Custom Accent Color", mapOf("dynamic" to "Dynamic", "system" to "System", "blue" to "Blue", "purple" to "Purple", "green" to "Green", "red" to "Red", "custom" to "Custom")),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.customAccentColor(),
                        title = "Custom accent hex",
                        subtitle = "#RRGGBB",
                        onValueChanged = { value ->
                            if (Regex("^#[0-9A-Fa-f]{6}$").matches(value.trim())) {
                                preferences.accentColor().set("custom")
                                true
                            } else false
                        },
                    ),
                    switch(preferences.amoledStyle(), "Doujin AMOLED Style", "Use layered near-black surfaces rather than flattening every surface to black."),
                    Preference.PreferenceItem.CustomPreference(
                        title = "Live card preview",
                        content = {
                            Column(
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = androidx.compose.ui.Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .background(
                                            if (previewStyle == "glass") MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(previewRadius.dp),
                                        )
                                        .padding(12.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Preview doujin", style = MaterialTheme.typography.titleMedium)
                                        Text(previewStyle.replaceFirstChar { it.uppercase() } + " • ${previewRadius}dp radius", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Library layout",
                preferenceItems = persistentListOf(
                    list(preferences.gridStyle(), "Grid Style", mapOf("regular" to "Regular Grid", "masonry" to "Masonry", "compact" to "Compact")),
                    switch(preferences.masonryLayout(), "Masonry Layout", "Use the existing lazy masonry discovery layout while preserving virtualized loading."),
                    list(preferences.coverSize(), "Cover Size", mapOf("small" to "Small", "medium" to "Medium", "large" to "Large", "custom" to "Custom")),
                    slider(preferences.customCoverSize(), "Custom Cover Size", "70–100% of the available card width; used when Cover Size is Custom", 70..100),
                    slider(preferences.cardSpacing(), "Card Spacing", "0–24dp", 0..24),
                    list(preferences.titlePosition(), "Title Position", mapOf("below" to "Below cover", "overlay" to "Overlay", "hidden" to "Hidden")),
                    switch(preferences.showSectionSubtitles(), "Show Section Subtitles", "Show or remove descriptive subtitles under library sections."),
                    switch(preferences.compactMode(), "Compact Mode", "Reduce card padding and secondary metadata for dense browsing."),
                    Preference.PreferenceItem.TextPreference("Reset Library Appearance", "Restore library layout, size, spacing, and title defaults.", onClick = preferences::resetLibraryAppearance),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Detail page",
                preferenceItems = persistentListOf(
                    switch(preferences.heroCover(), "Hero Cover", "Use the large editorial cover header; disabling it returns to the compact detail header."),
                    switch(preferences.heroBlur(), "Hero Blur", "Show a low-opacity blurred cover behind the hero area."),
                    slider(preferences.heroBlurIntensity(), "Hero Blur Intensity", "0–100%", 0..100),
                    switch(preferences.heroGradient(), "Hero Gradient", "Blend the hero cover into the page surface."),
                    slider(preferences.heroGradientIntensity(), "Hero Gradient Intensity", "0–100%", 0..100),
                    switch(preferences.dynamicBackground(), "Dynamic Background", "Tint the detail surface from the cover when dynamic cover colors are enabled."),
                    list(preferences.metadataLayout(), "Metadata Layout", mapOf("inline" to "Inline", "stacked" to "Stacked", "editorial" to "Editorial")),
                    list(preferences.tagLayout(), "Tag Layout", mapOf("simple" to "Simple", "normal" to "Normal", "compact" to "Compact", "editorial" to "Editorial")),
                    switch(preferences.coverTransition(), "Cover-based Detail Transition", "Use the stable cover-aware transition where supported; disabling it uses standard navigation."),
                    Preference.PreferenceItem.TextPreference("Reset Detail Page Appearance", "Restore hero, background, metadata, and transition defaults.", onClick = preferences::resetDetailAppearance),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Reader, motion, and privacy",
                preferenceItems = persistentListOf(
                    list(preferences.readerUiStyle(), "Reader UI Style", mapOf("premium" to "Premium", "minimal" to "Minimal", "translucent" to "Translucent")),
                    switch(preferences.ambientReaderBackground(), "Ambient Reader Background", "Use a restrained darkened/blurred page backdrop where the reader layout supports it."),
                    list(preferences.pageCounterStyle(), "Page Counter Style", mapOf("simple" to "47 / 184", "minimal" to "47", "progress" to "47 / 184 + progress", "floating" to "Floating")),
                    slider(preferences.readerToolbarTransparency(), "Reader Toolbar Transparency", "0–100%", 0..100),
                    list(preferences.readerAnimation(), "Reader Animation", mapOf("instant" to "Instant", "subtle" to "Subtle", "smooth" to "Smooth")),
                    slider(preferences.readerPageSpacing(), "Reader Page Spacing", "0–40dp", 0..40),
                    list(preferences.pageTransitions(), "Page Transitions", mapOf("instant" to "Instant", "slide" to "Slide", "fade" to "Fade", "zoom" to "Subtle Zoom")),
                    switch(preferences.microInteractions(), "Micro-interactions", "Enable subtle actions such as favorite/download feedback."),
                    switch(preferences.animations(), "Animations", "Master switch for nonessential cosmetic animations."),
                    list(preferences.animationSpeed(), "Animation Speed", mapOf("slow" to "Slow", "normal" to "Normal", "fast" to "Fast")),
                    switch(preferences.hideSensitiveCovers(), "Hide Sensitive Covers in Recents", "Use a privacy-safe recent-task preview where Android permits it."),
                    switch(preferences.blurCoversInRecents(), "Blur Covers in Recents", "Blur sensitive cover content in the recent-apps preview where supported."),
                    switch(preferences.hideTitlesInNotifications(), "Hide Titles in Notifications", "Use generic notification titles for sensitive reading/import notices."),
                    switch(preferences.stealthReader(), "Stealth Reader", "Minimize reader chrome and combine the existing secure-window behavior."),
                    Preference.PreferenceItem.TextPreference("Reset Reader Appearance", "Restore reader, motion, and privacy appearance defaults.", onClick = preferences::resetReaderAppearance),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Preset themes",
                preferenceItems = persistentListOf(
                    list(preferences.cardStyle(), "Current card preset", mapOf("minimal" to "Minimal", "standard" to "Standard", "detailed" to "Detailed", "editorial" to "Editorial", "glass" to "Glass"), onValueChanged = { true }),
                    Preference.PreferenceItem.TextPreference("Apply Minimal preset", "Clean, restrained styling with animation and shadows reduced.", onClick = { preferences.applyPreset("minimal") }),
                    Preference.PreferenceItem.TextPreference("Apply Editorial preset", "Apply the recommended balanced premium profile for a large library.", onClick = preferences::applyRecommendedVisualProfile),
                    Preference.PreferenceItem.TextPreference("Apply AMOLED preset", "Layered dark surfaces and restrained contrast.", onClick = { preferences.applyPreset("amoled") }),
                    Preference.PreferenceItem.TextPreference("Apply Glass preset", "Translucent surfaces with restrained transparency.", onClick = { preferences.applyPreset("glass") }),
                    Preference.PreferenceItem.TextPreference("Apply Dynamic preset", "Cover-derived accents and dynamic detail surfaces.", onClick = { preferences.applyPreset("dynamic") }),
                    Preference.PreferenceItem.TextPreference("Reset Appearance", "Reset appearance only; utility and library data remain unchanged.", onClick = preferences::resetAppearance),
                ),
            ),
        )
    }

    @Composable
    private fun slider(
        preference: tachiyomi.core.common.preference.Preference<Int>,
        title: String,
        subtitle: String,
        range: IntRange,
    ) = Preference.PreferenceItem.SliderPreference(
        value = preference.get().coerceIn(range),
        title = title,
        subtitle = subtitle,
        valueString = "${preference.get()}dp",
        valueRange = range,
        onValueChanged = { preference.set(it) },
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
        onValueChanged: suspend (String) -> Boolean = { true },
    ) = Preference.PreferenceItem.ListPreference(
        preference = preference,
        entries = persistentMapOf(*values.toList().toTypedArray()),
        title = title,
        subtitle = "%s",
        onValueChanged = onValueChanged,
    )
}
