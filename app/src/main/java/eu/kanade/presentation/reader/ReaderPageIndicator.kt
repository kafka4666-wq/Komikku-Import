package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ReaderPageIndicator(
    // SY -->
    currentPage: String,
    // SY <--
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    if (currentPage.isEmpty() || totalPages <= 0) return

    val preferences = remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val counterStyle by preferences.pageCounterStyle().collectAsState()
    val showPageCount by preferences.showPageCount().collectAsState()
    val showReadingProgress by preferences.showReadingProgress().collectAsState()
    val readerUiStyle by preferences.readerUiStyle().collectAsState()
    val pageSpacing by preferences.readerPageSpacing().collectAsState()
    val text = when {
        !showPageCount || counterStyle == "minimal" -> currentPage
        else -> "$currentPage / $totalPages"
    }

    val style = TextStyle(
        // KMK -->
        color = MaterialTheme.colorScheme.primary,
        // KMK <--
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    val strokeStyle = style.copy(
        color = Color(45, 45, 45),
        drawStyle = Stroke(width = 4f),
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(bottom = pageSpacing.coerceIn(0, 40).dp)
            .then(if (counterStyle == "floating" || readerUiStyle == "translucent") Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = if (readerUiStyle == "translucent") 0.48f else 0.72f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp) else Modifier),
    ) {
        if (counterStyle == "progress" && showReadingProgress) {
            LinearProgressIndicator(
                progress = { currentPage.toFloatOrNull()?.div(totalPages)?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(0.6f),
            )
        }
        Text(
            text = text,
            style = strokeStyle,
        )
        Text(
            text = text,
            style = style,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPageIndicator(currentPage = "10", totalPages = 69)
        }
    }
}
