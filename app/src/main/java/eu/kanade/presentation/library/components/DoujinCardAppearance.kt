package eu.kanade.presentation.library.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.domain.ui.KomikkuCustomisationPreferences
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Immutable
data class DoujinCardAppearance(
    val cornerRadius: Int,
    val cardStyle: String,
    val dynamicCoverColors: Boolean,
    val coverFade: Boolean,
    val coverGradient: Boolean,
    val cardShadow: Boolean,
    val coverShadow: Boolean,
    val coverHighlight: Boolean,
    val compactMode: Boolean,
    val titlePosition: String,
    val coverSize: String,
    val customCoverSize: Int,
    val metadataDensity: String,
)

@Composable
internal fun rememberDoujinCardAppearance(): DoujinCardAppearance {
    val preferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val appPreferences = remember { KomikkuCustomisationPreferences(Injekt.get<PreferenceStore>()) }
    val performanceMode by appPreferences.performanceMode().collectAsState()
    val cornerRadius by preferences.coverCornerRadius().collectAsState()
    val cardStyle by preferences.cardStyle().collectAsState()
    val dynamicCoverColors by preferences.dynamicCoverColors().collectAsState()
    val coverFade by preferences.coverFade().collectAsState()
    val coverGradient by preferences.coverGradient().collectAsState()
    val cardShadow by preferences.cardShadow().collectAsState()
    val coverShadow by preferences.coverShadow().collectAsState()
    val coverHighlight by preferences.coverHighlight().collectAsState()
    val compactMode by preferences.compactMode().collectAsState()
    val titlePosition by preferences.titlePosition().collectAsState()
    val coverSize by preferences.coverSize().collectAsState()
    val customCoverSize by preferences.customCoverSize().collectAsState()
    val metadataDensity by preferences.metadataDensity().collectAsState()
    val reducedEffects = performanceMode == "performance" || performanceMode == "battery"
    return remember(
        performanceMode,
        cornerRadius,
        cardStyle,
        dynamicCoverColors,
        coverFade,
        coverGradient,
        cardShadow,
        coverShadow,
        coverHighlight,
        compactMode,
        titlePosition,
        coverSize,
        customCoverSize,
        metadataDensity,
    ) {
        DoujinCardAppearance(
            cornerRadius = cornerRadius,
            cardStyle = cardStyle,
            dynamicCoverColors = dynamicCoverColors && !reducedEffects,
            coverFade = coverFade && !reducedEffects,
            coverGradient = coverGradient && !reducedEffects,
            cardShadow = cardShadow && !reducedEffects,
            coverShadow = coverShadow && !reducedEffects,
            coverHighlight = coverHighlight && !reducedEffects,
            compactMode = compactMode,
            titlePosition = titlePosition,
            coverSize = coverSize,
            customCoverSize = customCoverSize,
            metadataDensity = metadataDensity,
        )
    }
}
