package eu.kanade.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Manga-only Aurora/Glass primitives. Home and reader surfaces intentionally do not use these. */
object MangaAuroraDesignSystem {
    data class Tokens(
        val radius: Dp = 20.dp,
        val contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        val glassAlpha: Float = 0.72f,
        val borderAlpha: Float = 0.24f,
        val elevation: Dp = 2.dp,
    )

    @Composable
    fun rememberTokens(): Tokens = remember(MaterialTheme.colorScheme) { Tokens() }

    @Composable
    fun GlassSurface(
        modifier: Modifier = Modifier,
        tokens: Tokens = rememberTokens(),
        accent: Color = MaterialTheme.colorScheme.primary,
        enabled: Boolean = true,
        content: @Composable BoxScope.() -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed && enabled) 0.985f else 1f,
            animationSpec = spring(stiffness = 700f),
            label = "mangaAuroraPressScale",
        )
        Box(
            modifier = modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(tokens.radius))
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                colors.surfaceVariant.copy(alpha = tokens.glassAlpha),
                                colors.surface.copy(alpha = tokens.glassAlpha * 0.86f),
                            ),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(tokens.radius.toPx()),
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = tokens.borderAlpha),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(tokens.radius.toPx()),
                    )
                }
                .padding(tokens.contentPadding),
            content = content,
        )
    }

    @Composable
    fun Modifier.mangaAuroraCard(
        radius: Dp = 18.dp,
        accent: Color = Color.Unspecified,
        glassAlpha: Float = 0.72f,
    ): Modifier {
        val primary = MaterialTheme.colorScheme.primary
        return drawBehind {
        val resolvedAccent = if (accent == Color.Unspecified) primary else accent
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        )
        drawRoundRect(
            color = resolvedAccent.copy(alpha = 0.18f * glassAlpha),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        )
        }
    }

    @Composable
    fun Modifier.mangaAuroraState(enabled: Boolean, selected: Boolean): Modifier {
        val colors = MaterialTheme.colorScheme
        return drawBehind {
            if (selected) drawRect(colors.primary.copy(alpha = 0.10f))
            if (!enabled) drawRect(colors.onSurface.copy(alpha = 0.04f))
        }
    }
}
