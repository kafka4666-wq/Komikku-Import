package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.KodamiTreasuryPreferences
import eu.kanade.domain.ui.KomikkuCustomisationPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.Preference as PreferenceData
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTreasuryScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val treasury = remember { KodamiTreasuryPreferences(Injekt.get<PreferenceStore>()) }
        val customisation = remember { KomikkuCustomisationPreferences(Injekt.get<PreferenceStore>()) }

        val selectedTheme by uiPreferences.appTheme().collectAsState()
        val themeName = selectedTheme.titleRes?.let { stringResource(it) } ?: selectedTheme.name
        val amoled by uiPreferences.themeDarkAmoled().collectAsState()
        val selectedEffect by treasury.backgroundEffect().collectAsState()
        val profileTitle by treasury.profileTitle().collectAsState()
        val nicknameEffect by treasury.nicknameEffect().collectAsState()
        val avatarFrame by treasury.avatarFrame().collectAsState()
        val homeBadge by treasury.homeBadge().collectAsState()

        PreferenceScaffold(
            titleRes = KMR.strings.pref_category_treasury,
            onBackPressed = { navigator?.pop() },
            itemsProvider = {
                listOf(
                    Preference.PreferenceGroup(
                        title = "Treasury vault",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.CustomPreference(title = "All cosmetics available") {
                                KodamiTreasuryVaultHeader(
                                    themeName = themeName,
                                    selectedEffect = selectedEffect,
                                    accent = MaterialTheme.colorScheme.primary,
                                )
                            },
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Treasury themes",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.CustomPreference(title = "Exclusive colorways") {
                                KodamiTreasuryThemeSelector(
                                    selectedTheme = selectedTheme,
                                    amoled = amoled,
                                    onThemeSelected = { theme ->
                                        uiPreferences.appTheme().set(theme)
                                        (context as? Activity)?.let { ActivityCompat.recreate(it) }
                                    },
                                )
                            },
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Background effects",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.CustomPreference(title = "Select an animated backdrop") {
                                KodamiTreasuryBackgroundSelector(
                                    selectedEffect = selectedEffect,
                                    onEffectSelected = { treasury.backgroundEffect().set(it) },
                                )
                            },
                            Preference.PreferenceItem.InfoPreference(
                                "The active effect animates behind Library and More. Motion pauses in the background, with system animations disabled, or while Battery Saver is active.",
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Profile options",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.CustomPreference(title = "Titles, effects, frames, and badges") {
                                KodamiTreasuryProfileSelector(
                                    selectedTitle = profileTitle,
                                    selectedNameEffect = nicknameEffect,
                                    selectedFrame = avatarFrame,
                                    selectedBadge = homeBadge,
                                    onTitleSelected = { treasury.profileTitle().set(it) },
                                    onNameEffectSelected = { treasury.nicknameEffect().set(it) },
                                    onFrameSelected = { treasury.avatarFrame().set(it) },
                                    onBadgeSelected = { treasury.homeBadge().set(it) },
                                )
                            },
                            Preference.PreferenceItem.CustomPreference(title = "Profile picture") {
                                ProfilePhotoPreference(treasury)
                            },
                            Preference.PreferenceItem.EditTextPreference(
                                preference = treasury.profileName(),
                                title = "Profile name",
                                subtitle = "%s",
                            ),
                            Preference.PreferenceItem.EditTextPreference(
                                preference = treasury.profileTagline(),
                                title = "Profile status line",
                                subtitle = "%s",
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Daily offline cache",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.CustomPreference(title = "Cache 30 random titles") {
                                DailyOfflineCachePreference(treasury)
                            },
                            Preference.PreferenceItem.InfoPreference(
                                "Chooses up to 30 random library titles and queues every available chapter. Existing downloads are kept. The daily cache expires at local midnight; a large cache can use substantial storage and mobile data.",
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Manga and reading options",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.TextPreference(
                                title = "Manga customization",
                                subtitle = "Library cards, manga details, discovery, filters, and manga-specific tools",
                                onClick = { navigator?.push(SettingsDoujinCustomisationsScreen) },
                            ),
                            Preference.PreferenceItem.TextPreference(
                                title = "Komikku customization",
                                subtitle = "Library layout, manga cards, reader profiles, and app-wide options",
                                onClick = { navigator?.push(SettingsKomikkuCustomisationScreen) },
                            ),
                            Preference.PreferenceItem.TextPreference(
                                title = "Reader settings",
                                subtitle = "Page layout, reading direction, scaling, gestures, and other reader options",
                                onClick = { navigator?.push(SettingsReaderScreen) },
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Performance",
                        preferenceItems = persistentListOf(
                            listPreference(
                                preference = customisation.performanceMode(),
                                title = "Performance mode",
                                values = linkedMapOf(
                                    "performance" to "Fast and smooth",
                                    "balanced" to "Balanced",
                                    "battery" to "Battery saver",
                                    "custom" to "Custom",
                                ),
                            ),
                            Preference.PreferenceItem.InfoPreference(
                                "Fast and smooth reduces costly cover color sampling, shadows, gradients, and cover-based theme animation without deleting your saved appearance choices.",
                            ),
                        ),
                    ),
                )
            },
        )
    }

    @Composable
    private fun <T> listPreference(
        preference: PreferenceData<T>,
        title: String,
        values: Map<T, String>,
    ) = Preference.PreferenceItem.ListPreference(
        preference = preference,
        entries = kotlinx.collections.immutable.persistentMapOf(*values.toList().toTypedArray()),
        title = title,
        subtitle = "%s",
    )
}
