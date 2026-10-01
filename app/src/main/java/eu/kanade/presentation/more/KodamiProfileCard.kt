package eu.kanade.presentation.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.KodamiTreasuryPreferences
import eu.kanade.presentation.more.settings.screen.SettingsTreasuryScreen
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun KodamiProfileCard(modifier: Modifier = Modifier) {
    val preferences = remember { KodamiTreasuryPreferences(Injekt.get<PreferenceStore>()) }
    val navigator = LocalNavigator.current
    val name by preferences.profileName().collectAsState()
    val tagline by preferences.profileTagline().collectAsState()
    val title by preferences.profileTitle().collectAsState()
    val effect by preferences.nicknameEffect().collectAsState()
    val frame by preferences.avatarFrame().collectAsState()
    val photoUri by preferences.profilePhotoUri().collectAsState()
    val badge by preferences.homeBadge().collectAsState()
    val nameColor = when (effect) {
        "glitch_rune_red" -> Color(0xFFFF4259)
        "aurora_crown", "trinity_prism" -> Color(0xFF9C7CFF)
        "glitch_rune", "cipher" -> Color(0xFF5DE7D8)
        "shadow_crown" -> MaterialTheme.colorScheme.tertiary
        "rank_sigils" -> Color(0xFFFFD36E)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        onClick = { navigator?.push(SettingsTreasuryScreen) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KodamiProfileAvatar(
                name = name,
                photoUri = photoUri,
                frame = frame,
                size = 58.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (tagline.isNotBlank()) {
                    Text(
                        text = tagline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = name.ifBlank { "Komikku Reader" },
                    color = nameColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (title != "none") {
                    Text(
                        text = title.removePrefix("title_").replace('_', ' ').replaceFirstChar { it.uppercase() },
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badge != "none") {
                    Text(
                        text = badge.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(top = 3.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun KodamiProfileAvatar(
    name: String,
    photoUri: String,
    frame: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val frameColors = when (frame) {
        "none" -> listOf(Color.Transparent, Color.Transparent)
        "glitch_red" -> listOf(Color(0xFFFF1744), Color(0xFF8E001C))
        "neon" -> listOf(Color(0xFF5DE7D8), Color(0xFF00A6FF))
        "hologram", "prismatic" -> listOf(Color(0xFFFF4E9E), Color(0xFF6CC6FF), Color(0xFFB388FF))
        "trinity_orbit" -> listOf(Color(0xFF64E8FF), Color(0xFF9C7CFF), Color(0xFFFFD36E))
        "deep_archive" -> listOf(Color(0xFF5DE7D8), Color(0xFF164A63))
        "hybrid_scroll" -> listOf(Color(0xFFFFD36E), Color(0xFF9C7CFF))
        "ascendant" -> listOf(Color(0xFFFFD36E), Color(0xFFFF8FB1), Color(0xFF9C7CFF))
        else -> listOf(
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        )
    }
    Box(
        modifier = modifier
            .size(size)
            .border(3.dp, Brush.sweepGradient(frameColors), CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUri.isNotBlank()) {
            AsyncImage(
                model = photoUri,
                contentDescription = "Profile photo",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "K",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
