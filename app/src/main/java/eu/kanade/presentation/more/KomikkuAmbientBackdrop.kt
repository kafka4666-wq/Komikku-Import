package eu.kanade.presentation.more

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class KomikkuAmbientEffect(val title: String) {
    INK_AURORA("Ink Aurora"),
    PAPER_BLOSSOM("Paper Blossom"),
    LANTERN_ORBITS("Lantern Orbits"),
    NEON_RAIN("Neon Rain"),
    TIDAL_RIBBONS("Tidal Ribbons"),
    CONSTELLATION_TRAILS("Constellation Trails"),
}

private data class AmbientSeed(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val phase: Float,
)

private val ambientSeeds = List(36) { index ->
    AmbientSeed(
        x = ((index * 37 + 13) % 101) / 101f,
        y = ((index * 61 + 29) % 103) / 103f,
        radius = 0.7f + (index % 5) * 0.28f,
        speed = 0.24f + (index % 6) * 0.085f,
        phase = ((index * 17 + 7) % 97) / 97f,
    )
}

/** One selected, low-cost ambient artwork layer; the six styles are drawn entirely in Compose. */
@Composable
internal fun KomikkuAmbientBackdrop(effect: KomikkuAmbientEffect, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val motionEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }

    if (!motionEnabled) {
        AmbientCanvas(effect, slowPhase = 0.37f, fastPhase = 0.68f, modifier = modifier)
        return
    }

    val transition = rememberInfiniteTransition(label = "komikku-library-ambience")
    val slowPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-slow-drift",
    )
    val fastPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 19_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-secondary-drift",
    )
    AmbientCanvas(effect, slowPhase, fastPhase, modifier)
}

@Composable
private fun AmbientCanvas(effect: KomikkuAmbientEffect, slowPhase: Float, fastPhase: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primary, colors.secondary, colors.tertiary, colors.primaryContainer, colors.secondaryContainer)
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val turn = 2f * PI.toFloat()
        val center = Offset(width * 0.5f, height * 0.48f)

        when (effect) {
            KomikkuAmbientEffect.INK_AURORA -> {
                repeat(4) { index ->
                    val p = slowPhase + index * 0.21f
                    val glowCenter = Offset(
                        width * (0.2f + index * 0.2f + sin(p * turn) * 0.08f),
                        height * (0.28f + index * 0.12f + cos(p * turn) * 0.07f),
                    )
                    drawGlow(glowCenter, palette[index % palette.size], maxOf(width, height) * 0.42f, 0.14f)
                }
                repeat(5) { index ->
                    val baseY = height * (0.25f + index * 0.13f)
                    val wave = height * (0.035f + index * 0.004f)
                    val p = (slowPhase + index * 0.14f) * turn
                    val path = Path().apply {
                        moveTo(-width * 0.08f, baseY)
                        cubicTo(width * 0.22f, baseY + sin(p) * wave, width * 0.33f, baseY - cos(p) * wave, width * 0.56f, baseY)
                        cubicTo(width * 0.78f, baseY + sin(p + 1f) * wave, width * 0.9f, baseY - cos(p + 1f) * wave, width * 1.08f, baseY)
                    }
                    drawPath(path, palette[index % palette.size].copy(alpha = 0.095f), style = Stroke(width = (1.2f + index * 0.35f).dp.toPx(), cap = StrokeCap.Round))
                }
                drawMotes(slowPhase, fastPhase, palette, alpha = 0.13f)
            }

            KomikkuAmbientEffect.PAPER_BLOSSOM -> {
                drawGlow(Offset(width * 0.18f, height * 0.27f), palette[0], maxOf(width, height) * 0.36f, 0.10f)
                drawGlow(Offset(width * 0.82f, height * 0.74f), palette[2], maxOf(width, height) * 0.33f, 0.09f)
                drawMotes(slowPhase, fastPhase, palette, alpha = 0.32f, paperPetals = true)
                repeat(8) { index ->
                    val x = width * (((index * 29 + 11) % 97) / 97f)
                    val progress = (ambientSeeds[index].y + slowPhase * ambientSeeds[index].speed * 0.65f) % 1f
                    val y = height * progress
                    val drift = sin((fastPhase + index * 0.19f) * turn) * width * 0.035f
                    drawLine(palette[index % palette.size].copy(alpha = 0.11f), Offset(x + drift - 8.dp.toPx(), y - 13.dp.toPx()), Offset(x + drift + 8.dp.toPx(), y + 13.dp.toPx()), strokeWidth = 0.8.dp.toPx())
                }
            }

            KomikkuAmbientEffect.LANTERN_ORBITS -> {
                val orbitCenter = Offset(width * (0.5f + sin(slowPhase * turn) * 0.035f), height * 0.48f)
                repeat(4) { index ->
                    val rx = width * (0.15f + index * 0.055f)
                    val ry = height * (0.09f + index * 0.04f)
                    val tint = palette[(index + 1) % palette.size]
                    drawOval(
                        color = tint.copy(alpha = 0.10f),
                        topLeft = Offset(orbitCenter.x - rx, orbitCenter.y - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(width = (0.8f + index % 2 * 0.4f).dp.toPx()),
                    )
                    val angle = slowPhase * turn * (0.7f + index * 0.09f) + index * 1.62f
                    val lantern = Offset(orbitCenter.x + cos(angle) * rx, orbitCenter.y + sin(angle) * ry)
                    val tail = Offset(orbitCenter.x + cos(angle - 0.18f) * rx, orbitCenter.y + sin(angle - 0.18f) * ry)
                    drawLine(tint.copy(alpha = 0.22f), tail, lantern, strokeWidth = 2.dp.toPx())
                    drawGlow(lantern, tint, 34.dp.toPx(), 0.21f)
                    drawCircle(tint.copy(alpha = 0.65f), 6.dp.toPx(), lantern, style = Stroke(width = 1.2.dp.toPx()))
                    drawCircle(tint.copy(alpha = 0.44f), 2.3.dp.toPx(), lantern)
                }
                drawGlow(center, palette[0], maxOf(width, height) * 0.25f, 0.07f)
            }

            KomikkuAmbientEffect.NEON_RAIN -> {
                ambientSeeds.forEachIndexed { index, seed ->
                    val x = width * seed.x + sin((fastPhase + seed.phase) * turn) * 10.dp.toPx()
                    val y = height * ((seed.y + slowPhase * seed.speed + 1f) % 1f)
                    val length = height * (0.035f + seed.radius * 0.018f)
                    val tint = palette[index % palette.size]
                    val alpha = if (index % 5 == 0) 0.24f else 0.105f
                    drawLine(tint.copy(alpha = alpha), Offset(x, y), Offset(x - 0.022f * width, y + length), strokeWidth = if (index % 5 == 0) 1.5.dp.toPx() else 0.75.dp.toPx())
                    if (index % 5 == 0) drawGlow(Offset(x, y), tint, 20.dp.toPx(), 0.15f)
                }
                drawGlow(Offset(width * 0.78f, height * (0.32f + 0.1f * sin(slowPhase * turn))), palette[1], maxOf(width, height) * 0.25f, 0.065f)
            }

            KomikkuAmbientEffect.TIDAL_RIBBONS -> {
                repeat(6) { index ->
                    val baseY = height * (0.2f + index * 0.115f)
                    val amplitude = height * (0.045f + index * 0.008f)
                    val p = (slowPhase * turn * (0.62f + index * 0.035f)) + index * 0.8f
                    val path = Path().apply {
                        moveTo(-width * 0.12f, baseY)
                        cubicTo(width * 0.12f, baseY + sin(p) * amplitude, width * 0.22f, baseY - cos(p * 0.8f) * amplitude, width * 0.48f, baseY + sin(p + 1.2f) * amplitude)
                        cubicTo(width * 0.7f, baseY + cos(p + 0.4f) * amplitude, width * 0.82f, baseY - sin(p) * amplitude, width * 1.12f, baseY + cos(p + 1.6f) * amplitude)
                    }
                    drawPath(path, palette[index % palette.size].copy(alpha = 0.12f), style = Stroke(width = (1.5f + (index % 3) * 0.7f).dp.toPx(), cap = StrokeCap.Round))
                }
                drawGlow(Offset(width * (0.34f + 0.12f * sin(fastPhase * turn)), height * 0.5f), palette[2], maxOf(width, height) * 0.32f, 0.08f)
                drawMotes(slowPhase, fastPhase, palette, alpha = 0.09f)
            }

            KomikkuAmbientEffect.CONSTELLATION_TRAILS -> {
                val stars = ambientSeeds.take(24).mapIndexed { index, seed ->
                    val driftX = sin((fastPhase + seed.phase) * turn) * width * 0.018f
                    val driftY = cos((slowPhase * seed.speed + seed.phase) * turn) * height * 0.018f
                    Offset(width * seed.x + driftX, height * seed.y + driftY)
                }
                stars.forEachIndexed { index, star ->
                    val next = stars[(index + 1) % stars.size]
                    val cross = stars[(index + 7) % stars.size]
                    val tint = palette[index % palette.size]
                    drawLine(tint.copy(alpha = 0.075f), star, next, strokeWidth = 0.75.dp.toPx())
                    if (index % 2 == 0) drawLine(tint.copy(alpha = 0.055f), star, cross, strokeWidth = 0.6.dp.toPx())
                    val twinkle = 0.55f + 0.45f * sin((fastPhase + index * 0.117f) * turn)
                    drawGlow(star, tint, (10f + twinkle * 10f).dp.toPx(), 0.09f + twinkle * 0.08f)
                    drawCircle(tint.copy(alpha = 0.26f + twinkle * 0.4f), (0.8f + twinkle * 1.15f).dp.toPx(), star)
                    if (index % 6 == 0) {
                        drawLine(tint.copy(alpha = 0.28f * twinkle), Offset(star.x - 4.dp.toPx(), star.y), Offset(star.x + 4.dp.toPx(), star.y), strokeWidth = 0.7.dp.toPx())
                        drawLine(tint.copy(alpha = 0.28f * twinkle), Offset(star.x, star.y - 4.dp.toPx()), Offset(star.x, star.y + 4.dp.toPx()), strokeWidth = 0.7.dp.toPx())
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawGlow(center: Offset, color: Color, radius: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.drawMotes(
    slowPhase: Float,
    fastPhase: Float,
    palette: List<Color>,
    alpha: Float,
    paperPetals: Boolean = false,
) {
    val turn = 2f * PI.toFloat()
    val canvasWidth = size.width
    val canvasHeight = size.height
    ambientSeeds.forEachIndexed { index, seed ->
        val x = canvasWidth * ((seed.x + sin((fastPhase + seed.phase) * turn) * 0.026f + 1f) % 1f)
        val y = canvasHeight * ((seed.y + slowPhase * seed.speed + 1f) % 1f)
        val tint = palette[index % palette.size]
        val shimmer = 0.55f + 0.45f * sin((fastPhase + seed.phase) * turn)
        if (paperPetals) {
            val radius = (2.1f + seed.radius * 1.4f).dp.toPx()
            val petal = Path().apply {
                moveTo(x, y - radius)
                cubicTo(x + radius * 1.1f, y - radius * 0.55f, x + radius * 1.25f, y + radius * 0.2f, x, y + radius)
                cubicTo(x - radius * 1.25f, y + radius * 0.2f, x - radius * 1.1f, y - radius * 0.55f, x, y - radius)
                close()
            }
            drawPath(petal, tint.copy(alpha = alpha * (0.28f + shimmer * 0.3f)))
            drawLine(tint.copy(alpha = alpha * 0.6f), Offset(x, y - radius * 0.55f), Offset(x, y + radius * 0.65f), strokeWidth = 0.6.dp.toPx())
        } else {
            val radius = (0.65f + seed.radius * 0.55f).dp.toPx()
            drawCircle(tint.copy(alpha = alpha * (0.35f + shimmer * 0.5f)), radius, Offset(x, y))
            if (index % 7 == 0) drawGlow(Offset(x, y), tint, 12.dp.toPx(), alpha * 0.26f)
        }
    }
}
