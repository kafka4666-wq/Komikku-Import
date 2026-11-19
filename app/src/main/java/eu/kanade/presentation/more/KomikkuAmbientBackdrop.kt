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

/** Six original ambient illustrations, intentionally distinct from the app's existing themes. */
internal enum class KomikkuAmbientEffect(val title: String) {
    CLOCKWORK_ORRERY("Clockwork Orrery"),
    FACET_SHIFT("Facet Shift"),
    ORIGAMI_FOLD("Origami Fold"),
    COMIC_PANEL_PARALLAX("Comic Panel Parallax"),
    HALFTONE_PULSE("Halftone Pulse"),
    CIRCUIT_LOOM("Circuit Loom"),
}

private data class AmbientSeed(
    val x: Float,
    val y: Float,
    val scale: Float,
    val phase: Float,
)

private val ambientSeeds = List(30) { index ->
    AmbientSeed(
        x = ((index * 37 + 13) % 101) / 101f,
        y = ((index * 61 + 29) % 103) / 103f,
        scale = 0.68f + (index % 5) * 0.16f,
        phase = ((index * 17 + 7) % 97) / 97f,
    )
}

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
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer, scheme.secondaryContainer)
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val turn = 2f * PI.toFloat()

        when (effect) {
            KomikkuAmbientEffect.CLOCKWORK_ORRERY -> {
                val center = Offset(width * 0.5f, height * 0.47f)
                drawGear(center, width * 0.145f, teeth = 14, rotation = slowPhase * turn, color = palette[0], alpha = 0.22f)
                drawGear(Offset(center.x + width * 0.205f, center.y + height * 0.115f), width * 0.092f, teeth = 11, rotation = -slowPhase * turn * 1.25f, color = palette[1], alpha = 0.2f)
                drawGear(Offset(center.x - width * 0.19f, center.y + height * 0.13f), width * 0.083f, teeth = 10, rotation = slowPhase * turn * 1.45f, color = palette[2], alpha = 0.19f)
                drawGear(Offset(width * 0.17f, height * 0.19f), width * 0.053f, teeth = 9, rotation = -fastPhase * turn, color = palette[3], alpha = 0.15f)
                repeat(18) { index ->
                    val angle = turn * index / 18f + fastPhase * turn * 0.18f
                    val start = Offset(center.x + cos(angle) * width * 0.31f, center.y + sin(angle) * width * 0.31f)
                    val end = Offset(center.x + cos(angle) * width * 0.34f, center.y + sin(angle) * width * 0.34f)
                    drawLine(palette[index % palette.size].copy(alpha = 0.11f), start, end, strokeWidth = 1.dp.toPx())
                }
            }

            KomikkuAmbientEffect.FACET_SHIFT -> {
                ambientSeeds.take(13).forEachIndexed { index, seed ->
                    val travel = (slowPhase * (0.24f + (index % 4) * 0.055f) + seed.phase + 1f) % 1f
                    val center = Offset(
                        width * ((seed.x + sin((fastPhase + seed.phase) * turn) * 0.035f + 1f) % 1f),
                        height * ((seed.y + travel * 0.28f + 1f) % 1f),
                    )
                    val radius = (16f + seed.scale * 38f).dp.toPx()
                    drawFacetShard(center, radius, fastPhase * turn * (0.32f + index % 3 * 0.12f) + seed.phase * turn, palette[index % palette.size], alpha = 0.16f)
                }
                drawFacetShard(Offset(width * 0.78f, height * 0.34f), width * 0.18f, -slowPhase * turn * 0.22f, palette[1], alpha = 0.08f)
            }

            KomikkuAmbientEffect.ORIGAMI_FOLD -> {
                ambientSeeds.take(12).forEachIndexed { index, seed ->
                    val progress = (seed.y + slowPhase * (0.2f + seed.scale * 0.12f) + 1f) % 1f
                    val x = width * ((seed.x + sin((fastPhase + seed.phase) * turn) * 0.05f + 1f) % 1f)
                    val y = height * progress
                    val paperWidth = (14f + seed.scale * 22f).dp.toPx()
                    val paperHeight = paperWidth * (1.25f + (index % 3) * 0.14f)
                    drawOrigamiKite(
                        center = Offset(x, y),
                        width = paperWidth,
                        height = paperHeight,
                        rotation = sin((slowPhase + seed.phase) * turn) * 0.45f + fastPhase * 0.42f,
                        color = palette[index % palette.size],
                        alpha = 0.19f,
                    )
                }
                drawOrigamiKite(Offset(width * 0.72f, height * 0.29f), width * 0.105f, height * 0.17f, slowPhase * 0.32f, palette[2], 0.085f)
            }

            KomikkuAmbientEffect.COMIC_PANEL_PARALLAX -> {
                repeat(7) { index ->
                    val panelHeight = height * (0.075f + (index % 3) * 0.018f)
                    val panelWidth = width * (0.34f + (index % 4) * 0.12f)
                    val phaseOffset = index * 0.173f
                    val wrap = height + panelHeight * 2f
                    val top = ((index * height * 0.155f + slowPhase * height * (0.36f + index % 2 * 0.16f) + panelHeight) % wrap) - panelHeight
                    val direction = if (index % 2 == 0) 1f else -1f
                    val left = (width - panelWidth) * (0.5f + direction * sin((fastPhase + phaseOffset) * turn) * 0.32f)
                    drawComicPanel(left, top, panelWidth, panelHeight, palette[index % palette.size], fastPhase + phaseOffset)
                }
            }

            KomikkuAmbientEffect.HALFTONE_PULSE -> {
                val spacing = 28.dp.toPx()
                val columns = (width / spacing).toInt().coerceIn(8, 26)
                val rows = (height / spacing).toInt().coerceIn(12, 38)
                val stepX = width / columns
                val stepY = height / rows
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val wave = 0.5f + 0.5f * sin((column * 0.41f + row * 0.18f - slowPhase * 2.1f + fastPhase * 0.7f) * turn)
                        val radius = (0.55f + wave * 1.45f).dp.toPx()
                        val tint = palette[(column / 4 + row / 7) % palette.size]
                        val point = Offset((column + 0.5f) * stepX, (row + 0.5f) * stepY)
                        drawCircle(tint.copy(alpha = 0.07f + wave * 0.12f), radius, point)
                    }
                }
                repeat(5) { index ->
                    val x = width * ((index / 5f + slowPhase * 0.22f) % 1f)
                    drawLine(palette[index % palette.size].copy(alpha = 0.035f), Offset(x, 0f), Offset(x - width * 0.22f, height), strokeWidth = 1.dp.toPx())
                }
            }

            KomikkuAmbientEffect.CIRCUIT_LOOM -> {
                repeat(8) { track ->
                    val laneY = height * (0.11f + track * 0.112f)
                    val amplitude = height * (0.022f + (track % 3) * 0.008f)
                    val xs = listOf(-0.04f, 0.13f, 0.13f, 0.31f, 0.31f, 0.52f, 0.52f, 0.73f, 0.73f, 1.04f)
                    val ys = listOf(0f, 0f, 1f, 1f, -1f, -1f, 1f, 1f, 0f, 0f)
                    val route = xs.indices.map { index -> Offset(width * xs[index], laneY + amplitude * ys[index]) }
                    val path = Path().apply {
                        moveTo(route.first().x, route.first().y)
                        route.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    val tint = palette[track % palette.size]
                    drawPath(path, tint.copy(alpha = 0.12f), style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Square))
                    listOf(2, 4, 6, 8).forEach { corner ->
                        val point = route[corner]
                        drawRect(tint.copy(alpha = 0.17f), topLeft = Offset(point.x - 2.2.dp.toPx(), point.y - 2.2.dp.toPx()), size = androidx.compose.ui.geometry.Size(4.4.dp.toPx(), 4.4.dp.toPx()))
                    }
                    val progress = (slowPhase * (0.55f + track * 0.045f) + fastPhase * 0.2f + track * 0.13f) % 1f
                    val scaled = progress * (route.size - 1)
                    val segment = scaled.toInt().coerceIn(0, route.lastIndex - 1)
                    val fraction = scaled - segment
                    val a = route[segment]
                    val b = route[segment + 1]
                    val pulse = Offset(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
                    drawCircle(tint.copy(alpha = 0.62f), 3.dp.toPx(), pulse)
                    drawCircle(tint.copy(alpha = 0.16f), 8.dp.toPx(), pulse, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
    }
}

private fun DrawScope.drawGear(center: Offset, radius: Float, teeth: Int, rotation: Float, color: Color, alpha: Float) {
    val points = teeth * 4
    val turn = 2f * PI.toFloat()
    val rootRadius = radius * 0.79f
    val path = Path()
    repeat(points) { index ->
        val toothRadius = if (index % 4 == 1 || index % 4 == 2) radius else rootRadius
        val angle = rotation + turn * index / points
        val point = Offset(center.x + cos(angle) * toothRadius, center.y + sin(angle) * toothRadius)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color.copy(alpha = alpha * 0.22f))
    drawPath(path, color.copy(alpha = alpha), style = Stroke(width = 1.2.dp.toPx()))
    drawCircle(color.copy(alpha = alpha * 0.72f), radius * 0.34f, center, style = Stroke(width = 1.1.dp.toPx()))
    drawCircle(color.copy(alpha = alpha * 0.56f), radius * 0.12f, center)
    repeat(6) { spoke ->
        val angle = rotation + turn * spoke / 6f
        drawLine(
            color.copy(alpha = alpha * 0.42f),
            Offset(center.x + cos(angle) * radius * 0.35f, center.y + sin(angle) * radius * 0.35f),
            Offset(center.x + cos(angle) * radius * 0.72f, center.y + sin(angle) * radius * 0.72f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.drawFacetShard(center: Offset, radius: Float, rotation: Float, color: Color, alpha: Float) {
    val turn = 2f * PI.toFloat()
    val vertices = List(6) { index ->
        val angle = rotation + turn * index / 6f
        val localRadius = radius * if (index % 2 == 0) 1f else 0.64f
        Offset(center.x + cos(angle) * localRadius, center.y + sin(angle) * localRadius)
    }
    val shape = Path().apply {
        moveTo(vertices.first().x, vertices.first().y)
        vertices.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(shape, color.copy(alpha = alpha * 0.23f))
    drawPath(shape, color.copy(alpha = alpha * 0.9f), style = Stroke(width = 1.dp.toPx()))
    listOf(0, 2, 4).forEachIndexed { index, vertex ->
        val edge = vertices[vertex]
        drawLine(color.copy(alpha = alpha * (0.25f + index * 0.08f)), center, edge, strokeWidth = 0.85.dp.toPx())
    }
    val glint = vertices[1]
    drawLine(color.copy(alpha = alpha * 0.65f), glint, Offset(glint.x + 5.dp.toPx(), glint.y - 8.dp.toPx()), strokeWidth = 1.1.dp.toPx())
}

private fun DrawScope.drawOrigamiKite(center: Offset, width: Float, height: Float, rotation: Float, color: Color, alpha: Float) {
    fun rotated(point: Offset): Offset = Offset(
        point.x * cos(rotation) - point.y * sin(rotation) + center.x,
        point.x * sin(rotation) + point.y * cos(rotation) + center.y,
    )
    val top = rotated(Offset(0f, -height * 0.55f))
    val right = rotated(Offset(width * 0.44f, -height * 0.05f))
    val bottom = rotated(Offset(0f, height * 0.55f))
    val left = rotated(Offset(-width * 0.44f, -height * 0.05f))
    val middle = rotated(Offset(0f, height * 0.12f))
    val silhouette = Path().apply {
        moveTo(top.x, top.y)
        lineTo(right.x, right.y)
        lineTo(bottom.x, bottom.y)
        lineTo(left.x, left.y)
        close()
    }
    drawPath(silhouette, color.copy(alpha = alpha * 0.21f))
    drawPath(silhouette, color.copy(alpha = alpha * 0.78f), style = Stroke(width = 0.9.dp.toPx()))
    fun face(a: Offset, b: Offset, c: Offset, shade: Float) {
        val triangle = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        }
        drawPath(triangle, color.copy(alpha = alpha * shade))
    }
    face(top, right, middle, 0.35f)
    face(right, bottom, middle, 0.18f)
    face(bottom, left, middle, 0.32f)
    face(left, top, middle, 0.13f)
    drawLine(color.copy(alpha = alpha * 0.86f), top, middle, strokeWidth = 0.75.dp.toPx())
    drawLine(color.copy(alpha = alpha * 0.72f), left, right, strokeWidth = 0.7.dp.toPx())
}

private fun DrawScope.drawComicPanel(left: Float, top: Float, width: Float, height: Float, color: Color, phase: Float) {
    val cut = minOf(10.dp.toPx(), width * 0.12f, height * 0.24f)
    val panel = Path().apply {
        moveTo(left, top)
        lineTo(left + width - cut, top)
        lineTo(left + width, top + cut)
        lineTo(left + width, top + height)
        lineTo(left + cut * 0.6f, top + height)
        lineTo(left, top + height - cut * 0.6f)
        close()
    }
    drawPath(panel, color.copy(alpha = 0.035f))
    drawPath(panel, color.copy(alpha = 0.17f), style = Stroke(width = 1.05.dp.toPx()))
    val splitX = left + width * (0.28f + 0.38f * ((phase + 1f) % 1f))
    drawLine(color.copy(alpha = 0.10f), Offset(splitX, top + 4.dp.toPx()), Offset(splitX - 5.dp.toPx(), top + height - 4.dp.toPx()), strokeWidth = 0.75.dp.toPx())
    repeat(3) { index ->
        val lineStart = left + width * (0.08f + index * 0.055f)
        val lineY = top + height * (0.24f + index * 0.2f)
        val length = width * (0.12f + index * 0.025f)
        drawLine(color.copy(alpha = 0.065f), Offset(lineStart, lineY), Offset(lineStart + length, lineY - 2.dp.toPx()), strokeWidth = 0.7.dp.toPx())
    }
}
