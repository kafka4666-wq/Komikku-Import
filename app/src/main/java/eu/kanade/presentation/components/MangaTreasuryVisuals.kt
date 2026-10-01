package eu.kanade.presentation.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Manga-facing Treasury visuals. These are deliberately independent of achievements and profile
 * state: every effect is available immediately and can be selected from manga customisation.
 * Home, anime, and novel surfaces are not touched by this adapter.
 */
object MangaTreasuryVisuals {
    const val NONE = "none"
    const val AURORA_PRIME = "aurora_prime"
    const val ONYX_GOLD = "onyx_gold"
    const val SAKURA_NOIR = "sakura_noir"
    const val NEBULA_TIDE = "nebula_tide"
    const val EVENT_HORIZON = "event_horizon_library"
    const val VOID_RED = "void_red"
    const val LATTICE_PROTOCOL = "lattice_protocol"

    const val BLOOD_OF_LILITH = "blood_of_lilith"
    const val WEEPING_VOID = "void_weeping_red"
    const val CORE_MELT = "core_melt"
    const val CRIMSON_GLITCH = "crimson_glitch"
    const val CRIMSON_AVATAR_FRAME = "avatar_frame_glitch_red"
    const val NEON_FRAME = "avatar_frame_neon"
    const val HOLOGRAM_FRAME = "avatar_frame_hologram"
    const val PRISMATIC_FRAME = "avatar_frame_prismatic"
    const val TRINITY_ORBIT_FRAME = "avatar_frame_trinity_orbit"
    const val DEEP_ARCHIVE_FRAME = "avatar_frame_deep_archive"
    const val HYBRID_SCROLL_FRAME = "avatar_frame_hybrid_scroll"
    const val ASCENDANT_FRAME = "avatar_frame_ascendant"
    const val PETAL_STORM = "petal_storm"
    const val NEON_ORBIT = "neon_orbit"
    const val TRINITY_CONSTELLATION = "trinity_constellation"
    const val DEEP_SPACE_ARCHIVE = "deep_space_archive"
    const val SHADOW_REALM = "shadow_realm"
    const val INK_WATER = "ink_water"

    val allEffects: List<String> = listOf(
        NONE, AURORA_PRIME, ONYX_GOLD, SAKURA_NOIR, NEBULA_TIDE, EVENT_HORIZON, VOID_RED,
        LATTICE_PROTOCOL, BLOOD_OF_LILITH, WEEPING_VOID, CORE_MELT, CRIMSON_GLITCH,
        CRIMSON_AVATAR_FRAME, NEON_FRAME, HOLOGRAM_FRAME, PRISMATIC_FRAME, TRINITY_ORBIT_FRAME,
        DEEP_ARCHIVE_FRAME, HYBRID_SCROLL_FRAME, ASCENDANT_FRAME, PETAL_STORM, NEON_ORBIT,
        TRINITY_CONSTELLATION, DEEP_SPACE_ARCHIVE, SHADOW_REALM, INK_WATER,
    )

    /** Tadami's manga-facing special backgrounds; Home/navbar customisations are excluded. */
    val mangaBackgroundEffects: List<String> = listOf(
        PETAL_STORM,
        NEON_ORBIT,
        TRINITY_CONSTELLATION,
        DEEP_SPACE_ARCHIVE,
        SHADOW_REALM,
        EVENT_HORIZON,
        WEEPING_VOID,
        INK_WATER,
    )

    val effectLabels: Map<String, String> = allEffects.associateWith { key ->
        key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun palette(effect: String): Triple<Color, Color, Color> = when (effect) {
        ONYX_GOLD, DEEP_ARCHIVE_FRAME -> Triple(Color(0xFF0D0C10), Color(0xFFFFC857), Color(0xFF8A5A20))
        SAKURA_NOIR, PETAL_STORM -> Triple(Color(0xFF190B1D), Color(0xFFFF7AB8), Color(0xFF7B2C68))
        NEBULA_TIDE, NEON_ORBIT -> Triple(Color(0xFF07132B), Color(0xFF5DE2FF), Color(0xFFB94BFF))
        EVENT_HORIZON, SHADOW_REALM, DEEP_SPACE_ARCHIVE -> Triple(Color(0xFF070811), Color(0xFF9B7CFF), Color(0xFF3C2B70))
        VOID_RED, BLOOD_OF_LILITH, WEEPING_VOID, CRIMSON_GLITCH, CRIMSON_AVATAR_FRAME -> Triple(Color(0xFF120004), Color(0xFFFF304F), Color(0xFF770018))
        LATTICE_PROTOCOL, TRINITY_CONSTELLATION -> Triple(Color(0xFF050A0F), Color(0xFF61F4FF), Color(0xFFFFC857))
        INK_WATER -> Triple(Color(0xFF061A22), Color(0xFF38D6C4), Color(0xFF226D87))
        CORE_MELT -> Triple(Color(0xFF1B0804), Color(0xFFFF8B3D), Color(0xFFE22D59))
        HOLOGRAM_FRAME, PRISMATIC_FRAME, HYBRID_SCROLL_FRAME, ASCENDANT_FRAME -> Triple(Color(0xFF10131F), Color(0xFFD7E7FF), Color(0xFF7D8CFF))
        TRINITY_ORBIT_FRAME -> Triple(Color(0xFF0B1020), Color(0xFFFFC857), Color(0xFF61F4FF))
        else -> Triple(Color(0xFF0A0A12), Color(0xFF9B7CFF), Color(0xFF38D6C4))
    }

    @Composable
    fun Modifier.mangaTreasuryCard(
        effect: String,
        intensity: Int,
        cornerRadius: Dp,
        animated: Boolean,
    ): Modifier {
        val phase by treasuryPhase(animated)
        return drawBehind { drawTreasuryFrame(effect, intensity, cornerRadius.toPx(), phase) }
    }

    @Composable
    fun Modifier.mangaTreasuryTitleSurface(
        effect: String,
        intensity: Int,
        animated: Boolean,
    ): Modifier {
        val phase by treasuryPhase(animated)
        return drawBehind {
            val (base, accent, secondary) = palette(effect)
            val amount = intensity.coerceIn(0, 100) / 100f
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.16f * amount), Color.Transparent),
                ),
            )
            if (effect != NONE) drawTreasuryFrame(effect, intensity, 24.dp.toPx(), phase, accent, secondary)
        }
    }

    @Composable
    private fun treasuryPhase(animated: Boolean): androidx.compose.runtime.State<Float> {
        if (!animated) return androidx.compose.runtime.mutableStateOf(0f)
        val transition = rememberInfiniteTransition(label = "treasuryMotion")
        return transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse),
            label = "treasuryPhase",
        )
    }

    private fun DrawScope.drawTreasuryFrame(
        effect: String,
        intensity: Int,
        radius: Float,
        phase: Float,
        overrideAccent: Color? = null,
        overrideSecondary: Color? = null,
    ) {
        if (effect == NONE) return
        val (base, paletteAccent, paletteSecondary) = palette(effect)
        val accent = overrideAccent ?: paletteAccent
        val secondary = overrideSecondary ?: paletteSecondary
        val amount = intensity.coerceIn(0, 100) / 100f
        val atmosphere = if (phase == 0f) 0f else 1f
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    base.copy(alpha = 0.34f * amount),
                    accent.copy(alpha = 0.11f * amount),
                    secondary.copy(alpha = 0.18f * amount),
                    base.copy(alpha = 0.30f * amount),
                ),
                start = Offset(size.width * (phase * 0.55f + 0.15f), 0f),
                end = Offset(size.width * (phase * 0.55f + 0.85f), size.height),
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        if (atmosphere > 0f) {
            drawCircle(
                color = accent.copy(alpha = 0.12f * amount),
                radius = size.minDimension * 0.52f,
                center = Offset(size.width * (0.5f + phase * 0.42f), size.height * 0.38f),
            )
            drawCircle(
                color = secondary.copy(alpha = 0.08f * amount),
                radius = size.minDimension * 0.34f,
                center = Offset(size.width * (0.45f - phase * 0.28f), size.height * 0.74f),
            )
        }
        if (effect == CRIMSON_GLITCH || effect == CRIMSON_AVATAR_FRAME || effect == LATTICE_PROTOCOL) {
            repeat(6) { index ->
                val y = ((index + 1) / 7f) * size.height
                val drift = phase * (8f + index * 3f)
                drawLine(
                    color = accent.copy(alpha = 0.08f * amount),
                    start = Offset(drift, y),
                    end = Offset(size.width + drift, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(accent.copy(alpha = 0.62f * amount), secondary.copy(alpha = 0.5f * amount), accent.copy(alpha = 0.62f * amount)),
                start = Offset(0f, phase * size.height * 0.35f),
                end = Offset(size.width, size.height),
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = (1.2f + amount * 2.2f).dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        if (effect == CORE_MELT || effect == BLOOD_OF_LILITH || effect == WEEPING_VOID) {
            drawCircle(accent.copy(alpha = 0.08f * amount), radius = size.minDimension * (0.42f + amount * 0.08f), center = center)
        }
        if (effect == CRIMSON_GLITCH || effect == CRIMSON_AVATAR_FRAME) {
            repeat(3) { index ->
                val y = size.height * (0.25f + index * 0.22f)
                drawLine(accent.copy(alpha = 0.18f * amount), Offset(0f, y), Offset(size.width, y + phase * 18f), strokeWidth = 1.dp.toPx())
            }
        }
        if (effect == TRINITY_ORBIT_FRAME || effect == TRINITY_CONSTELLATION) {
            drawCircle(secondary.copy(alpha = 0.35f * amount), radius = size.minDimension * (0.42f + phase * 0.04f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }
        if (effect == NONE) drawRect(base.copy(alpha = 0f))
    }
}

@Composable
fun BoxScope.MangaTreasuryOverlay(
    effect: String,
    intensity: Int,
    animated: Boolean,
) {
    // Kept as a named composable for future screen-level surfaces; card/title modifiers are the
    // low-overhead path used by large lazy libraries.
}
