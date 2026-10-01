package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

enum class AppTheme(
    val titleRes: StringResource?,
    val isHidden: Boolean = false,
) {
    DEFAULT(MR.strings.label_default),
    MONET(MR.strings.theme_monet),

    // Kuukiyomi themes
    CUSTOM(KMR.strings.theme_custom),

    // Aniyomi themes
    COTTONCANDY(KMR.strings.theme_cottoncandy),
    MOCHA(KMR.strings.theme_mocha),

    CATPPUCCIN(MR.strings.theme_catppuccin),
    GREEN_APPLE(MR.strings.theme_greenapple),
    LAVENDER(MR.strings.theme_lavender),
    MIDNIGHT_DUSK(MR.strings.theme_midnightdusk),
    NORD(MR.strings.theme_nord),
    STRAWBERRY_DAIQUIRI(MR.strings.theme_strawberrydaiquiri),
    TAKO(MR.strings.theme_tako),
    TEALTURQUOISE(MR.strings.theme_tealturquoise),
    TIDAL_WAVE(MR.strings.theme_tidalwave),
    YINYANG(MR.strings.theme_yinyang),
    YOTSUBA(MR.strings.theme_yotsuba),
    MONOCHROME(MR.strings.theme_monochrome),

    // Aniyomi themes
    CLOUDFLARE(KMR.strings.theme_cloudflare),
    DOOM(KMR.strings.theme_doom),
    MATRIX(KMR.strings.theme_matrix),
    SAPPHIRE(KMR.strings.theme_sapphire),

    // Tadami Treasury themes — selectable directly in Kodami.
    ONYX_GOLD(KMR.strings.theme_onyx_gold, isHidden = true),
    SAKURA_NOIR(KMR.strings.theme_sakura_noir, isHidden = true),
    NEBULA_TIDE(KMR.strings.theme_nebula_tide, isHidden = true),
    EVENT_HORIZON(KMR.strings.theme_event_horizon, isHidden = true),
    VOID_RED(KMR.strings.theme_void_red, isHidden = true),
    AURORA_PRIME(KMR.strings.theme_aurora_prime, isHidden = true),
    LATTICE_PROTOCOL(KMR.strings.theme_lattice_protocol, isHidden = true),

    // Deprecated
    DARK_BLUE(null),
    HOT_PINK(null),
    BLUE(null),

    // SY -->
    PURE_RED(null),
    // SY <--
}
