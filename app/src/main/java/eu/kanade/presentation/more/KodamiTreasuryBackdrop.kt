package eu.kanade.presentation.more

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.domain.ui.KodamiTreasuryPreferences
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Animated, low-cost Treasury backdrops inspired by Tadami's ambient canvas. */
private const val BACKGROUND_CYCLE_MILLIS = 14_000L
private const val BACKGROUND_FRAME_DELAY_MILLIS = 33L

@Composable
internal fun KodamiTreasuryBackdrop(
    modifier: Modifier = Modifier,
    effectOverride: String? = null,
    animateOverride: Boolean? = null,
) {
    val preferences = remember { KodamiTreasuryPreferences(Injekt.get<PreferenceStore>()) }
    val storedEffect by preferences.backgroundEffect().collectAsState()
    val effect = effectOverride ?: storedEffect
    if (effect == "none") return

    val lifecycleOwner = LocalLifecycleOwner.current
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldAnimate = isStarted && (animateOverride ?: true)
    var elapsedMillis by remember(effect) { mutableStateOf(0L) }
    LaunchedEffect(effect, shouldAnimate) {
        if (!shouldAnimate) {
            elapsedMillis = 0L
            return@LaunchedEffect
        }
        val animationStart = SystemClock.uptimeMillis()
        while (isActive) {
            elapsedMillis = SystemClock.uptimeMillis() - animationStart
            delay(BACKGROUND_FRAME_DELAY_MILLIS)
        }
    }
    val phase = if (shouldAnimate) {
        (elapsedMillis % BACKGROUND_CYCLE_MILLIS).toFloat() / BACKGROUND_CYCLE_MILLIS
    } else 0f

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cycle = phase * (2f * PI.toFloat())
        val violet = Color(0xFF9C7CFF)
        val cyan = Color(0xFF5DE7D8)
        val gold = Color(0xFFFFD36E)
        val pink = Color(0xFFFF8FB1)

        when (effect) {
            "petal_storm" -> {
                drawRect(Brush.verticalGradient(listOf(pink.copy(alpha = 0.09f), Color.Transparent)))
                repeat(30) { i ->
                    val drift = sin(cycle + i * 0.73f)
                    val progress = (i / 30f + phase * 0.72f) % 1f
                    val x = ((i * 83 + 19) % 997) / 997f * w + drift * w * 0.018f
                    val y = progress * h
                    val rotation = (drift * 42f + (i * 29 % 80) - 40f)
                    rotate(rotation, pivot = Offset(x, y)) {
                        drawOval(
                            color = pink.copy(alpha = 0.10f + (i % 3) * 0.025f),
                            topLeft = Offset(x - 8.dp.toPx(), y - 3.5.dp.toPx()),
                            size = Size(16.dp.toPx(), 7.dp.toPx()),
                        )
                    }
                }
            }
            "neon_orbit" -> {
                val center = Offset(w * (0.78f + sin(cycle) * 0.025f), h * (0.3f + cos(cycle) * 0.035f))
                val pulse = 0.82f + 0.18f * (0.5f + 0.5f * sin(cycle))
                drawRect(
                    Brush.radialGradient(
                        listOf(cyan.copy(alpha = 0.13f * pulse), Color.Transparent),
                        center = center,
                        radius = w * 0.8f,
                    ),
                )
                rotate(phase * 360f, pivot = center) {
                    repeat(4) { i ->
                        drawOval(
                            color = cyan.copy(alpha = (0.13f - i * 0.018f) * pulse),
                            topLeft = Offset(w * (0.43f - i * 0.04f), h * (0.17f - i * 0.025f)),
                            size = Size(w * (0.52f + i * 0.08f), h * (0.24f + i * 0.05f)),
                            style = Stroke(width = (1 + i % 2).dp.toPx()),
                        )
                    }
                }
                val orbitAngle = cycle
                drawCircle(
                    color = Color.White.copy(alpha = 0.72f),
                    radius = 3.dp.toPx(),
                    center = Offset(center.x + cos(orbitAngle) * w * 0.29f, center.y + sin(orbitAngle) * h * 0.12f),
                )
            }
            "trinity_constellation" -> {
                val points = listOf(
                    Offset(w * (0.16f + sin(cycle) * 0.008f), h * 0.24f),
                    Offset(w * 0.82f, h * (0.34f + cos(cycle) * 0.01f)),
                    Offset(w * (0.53f + sin(cycle + 1f) * 0.008f), h * 0.78f),
                )
                drawLine(violet.copy(alpha = 0.18f), points[0], points[1], 1.dp.toPx())
                drawLine(cyan.copy(alpha = 0.16f), points[1], points[2], 1.dp.toPx())
                drawLine(gold.copy(alpha = 0.15f), points[2], points[0], 1.dp.toPx())
                points.forEachIndexed { i, point ->
                    val twinkle = 0.62f + 0.38f * (0.5f + 0.5f * sin(cycle + i * 2f))
                    drawCircle(listOf(violet, cyan, gold)[i].copy(alpha = 0.34f * twinkle), 4.dp.toPx(), point)
                }
                repeat(28) { i ->
                    val x = ((i * 191 + 37) % 997) / 997f * w
                    val y = ((i * 79 + 11) % 991) / 991f * h
                    val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(cycle * 1.3f + i * 1.7f))
                    drawCircle(Color.White.copy(alpha = 0.28f * twinkle), (i % 3 + 1).dp.toPx(), Offset(x, y))
                }
            }
            "deep_space_archive" -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF071D2D).copy(alpha = 0.18f), Color.Transparent)))
                repeat(8) { row ->
                    val y = h * (0.18f + row * 0.1f) + sin(cycle + row * 0.45f) * h * 0.006f
                    val scanAlpha = 0.08f + 0.04f * (0.5f + 0.5f * sin(cycle + row))
                    drawLine(cyan.copy(alpha = scanAlpha), Offset(w * 0.06f, y), Offset(w * 0.96f, y), 1.dp.toPx())
                    repeat(9) { col ->
                        val x = w * (0.08f + col * 0.105f)
                        val bookH = h * (0.025f + ((row + col) % 4) * 0.008f)
                        val bob = sin(cycle + row * 0.6f + col * 0.4f) * h * 0.004f
                        drawLine(
                            listOf(cyan, violet, gold)[(row + col) % 3].copy(alpha = 0.11f + 0.06f * (0.5f + 0.5f * sin(cycle + col))),
                            Offset(x, y),
                            Offset(x, y - bookH + bob),
                            2.dp.toPx(),
                        )
                    }
                }
            }
            "shadow_realm" -> {
                val center = Offset(w * (0.52f + sin(cycle) * 0.025f), h * (0.52f + cos(cycle) * 0.02f))
                val pulse = 0.75f + 0.25f * (0.5f + 0.5f * sin(cycle))
                drawRect(
                    Brush.radialGradient(
                        listOf(violet.copy(alpha = 0.14f * pulse), Color(0xFF08030D).copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = w * 0.95f,
                    ),
                )
                rotate(sin(cycle) * 6f, pivot = center) {
                    repeat(5) { i ->
                        val y = h * (0.2f + i * 0.12f) + sin(cycle + i) * h * 0.008f
                        drawOval(
                            Color(0xFF9C63DC).copy(alpha = (0.07f + (i % 2) * 0.018f) * pulse),
                            Offset(w * (0.12f + i * 0.04f), y),
                            Size(w * 0.78f, h * 0.13f),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            "event_horizon_library" -> {
                val center = Offset(w * (0.5f + sin(cycle) * 0.025f), h * (0.4f + cos(cycle) * 0.02f))
                val pulse = 0.78f + 0.22f * (0.5f + 0.5f * sin(cycle))
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF1A4FE0).copy(alpha = 0.16f * pulse), Color(0xFF071127).copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = w * 0.65f,
                    ),
                )
                rotate(phase * 360f, pivot = center) {
                    repeat(7) { i ->
                        drawOval(
                            Color(0xFF5DE7D8).copy(alpha = (0.12f - i * 0.01f) * pulse),
                            Offset(w * (0.25f - i * 0.02f), h * (0.28f - i * 0.025f)),
                            Size(w * (0.5f + i * 0.04f), h * (0.16f + i * 0.025f)),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                val angle = cycle * 1.4f
                drawCircle(cyan.copy(alpha = 0.78f), 3.dp.toPx(), Offset(center.x + cos(angle) * w * 0.28f, center.y + sin(angle) * h * 0.11f))
            }
            "void_weeping_red" -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF33000A).copy(alpha = 0.18f), Color.Transparent)))
                repeat(24) { i ->
                    val x = ((i * 89 + 31) % 991) / 991f * w + sin(cycle + i) * w * 0.006f
                    val base = ((i * 163 + 17) % 997) / 997f
                    val y = ((base + phase * 1.25f) % 1f) * h
                    val length = h * (0.035f + (i % 5) * 0.016f)
                    val alpha = 0.07f + (i % 4) * 0.024f
                    drawLine(Color(0xFFFF1E27).copy(alpha = alpha), Offset(x, y), Offset(x, y + length), (1 + i % 2).dp.toPx())
                    drawCircle(Color(0xFFFF1E27).copy(alpha = alpha + 0.04f), 2.dp.toPx(), Offset(x, y + length))
                }
            }
            "ink_water" -> {
                val center = Offset(w * (0.48f + sin(cycle) * 0.03f), h * (0.42f + cos(cycle) * 0.025f))
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF6C7CE0).copy(alpha = 0.14f), Color.Transparent),
                        center = center,
                        radius = w * 0.72f,
                    ),
                )
                repeat(7) { i ->
                    val sway = sin(cycle + i * 0.55f) * h * 0.035f
                    val path = Path().apply {
                        moveTo(-w * 0.05f, h * (0.26f + i * 0.08f))
                        cubicTo(
                            w * 0.26f,
                            h * (0.05f + i * 0.1f) + sway,
                            w * 0.68f,
                            h * (0.52f - i * 0.04f) - sway,
                            w * 1.05f,
                            h * (0.22f + i * 0.09f),
                        )
                    }
                    val alpha = (0.08f + (i % 3) * 0.025f) * (0.82f + 0.18f * sin(cycle + i).let { (it + 1f) / 2f })
                    drawPath(path, Color(0xFF8998FF).copy(alpha = alpha), style = Stroke(width = (1 + i % 2).dp.toPx()))
                }
            }
        }
    }
}
