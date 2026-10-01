package eu.kanade.presentation.more.settings.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.more.KodamiTreasuryBackdrop
import eu.kanade.presentation.more.settings.widget.AppThemePreviewItem
import eu.kanade.presentation.theme.TachiyomiTheme
import tachiyomi.presentation.core.i18n.stringResource

private data class TreasuryThemeCardSpec(
    val theme: AppTheme,
    val rarity: String,
    val tagline: String,
    val accent: Color,
)

private val treasuryThemeCards = listOf(
    TreasuryThemeCardSpec(
        theme = AppTheme.ONYX_GOLD,
        rarity = "MYTHIC",
        tagline = "Black-diamond surfaces with royal gold and a bronze trophy accent",
        accent = Color(0xFFFFD36E),
    ),
    TreasuryThemeCardSpec(
        theme = AppTheme.SAKURA_NOIR,
        rarity = "SECRET",
        tagline = "Midnight sakura neon, plum noir cards, and jade hidden-hall glow",
        accent = Color(0xFFAD1060),
    ),
    TreasuryThemeCardSpec(
        theme = AppTheme.NEBULA_TIDE,
        rarity = "TRANSCENDENT",
        tagline = "Deep-indigo abyss with rose-nebula drift and plasma-cyan sparks",
        accent = Color(0xFF5DE7D8),
    ),
    TreasuryThemeCardSpec(
        theme = AppTheme.EVENT_HORIZON,
        rarity = "MYTHIC",
        tagline = "Deep-blue accretion bands, violet plasma, X-ray white controls, and cut-glass void surfaces",
        accent = Color(0xFF1A4FE0),
    ),
    TreasuryThemeCardSpec(
        theme = AppTheme.VOID_RED,
        rarity = "MYTHIC",
        tagline = "Meltdown at core unit 01",
        accent = Color(0xFFB0003C),
    ),
    TreasuryThemeCardSpec(
        theme = AppTheme.AURORA_PRIME,
        rarity = "MYTHIC",
        tagline = "Ethereal cosmic glow of the Northern Lights",
        accent = Color(0xFFB6F04C),
    ),
    TreasuryThemeCardSpec(
        theme = AppTheme.LATTICE_PROTOCOL,
        rarity = "MYTHIC",
        tagline = "The frame beneath the shell",
        accent = Color(0xFF0095AE),
    ),
)

@Composable
internal fun KodamiTreasuryVaultHeader(
    themeName: String,
    selectedEffect: String,
    accent: Color,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF171326), Color(0xFF0B0A12), Color(0xFF151020)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.48f), shape),
    ) {
        KodamiTreasuryBackdrop(
            modifier = Modifier.matchParentSize(),
            effectOverride = selectedEffect,
            animateOverride = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF08070D).copy(alpha = 0.08f), Color(0xFF08070D).copy(alpha = 0.72f)),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "KODAMI TREASURY",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = "A vault of looks",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$themeName · all 7 themes, 8 effects, and profile styles available. No achievements required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.92f), accent.copy(alpha = 0.9f), accent.copy(alpha = 0.12f)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.52f), CircleShape),
            )
        }
    }
}

@Composable
internal fun KodamiTreasuryThemeSelector(
    selectedTheme: AppTheme,
    amoled: Boolean,
    onThemeSelected: (AppTheme) -> Unit,
) {
    Column {
        Text(
            text = "Swipe the unlocked posters and tap one to apply its complete Kodami palette.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(treasuryThemeCards, key = { "kodami-treasury-theme-${it.theme.name}" }) { spec ->
                TreasuryThemePoster(
                    spec = spec,
                    selected = selectedTheme == spec.theme,
                    amoled = amoled,
                    onClick = { onThemeSelected(spec.theme) },
                )
            }
        }
    }
}

@Composable
private fun TreasuryThemePoster(
    spec: TreasuryThemeCardSpec,
    selected: Boolean,
    amoled: Boolean,
    onClick: () -> Unit,
) {
    val borderStrength by animateFloatAsState(
        targetValue = if (selected) 1f else 0.28f,
        animationSpec = tween(durationMillis = 320),
        label = "treasury-theme-border-${spec.theme.name}",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.975f,
        animationSpec = tween(durationMillis = 260),
        label = "treasury-theme-scale-${spec.theme.name}",
    )
    val shape = RoundedCornerShape(26.dp)
    val title = spec.theme.titleRes?.let { stringResource(it) } ?: spec.theme.name

    Column(
        modifier = Modifier
            .width(238.dp)
            .height(448.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(spec.accent.copy(alpha = 0.28f), Color(0xFF101019), Color(0xFF08080D)),
                ),
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        spec.accent.copy(alpha = borderStrength),
                        Color.White.copy(alpha = if (selected) 0.62f else 0.14f),
                        spec.accent.copy(alpha = borderStrength * 0.72f),
                    ),
                ),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = spec.rarity,
            style = MaterialTheme.typography.labelSmall,
            color = spec.accent,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(224.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.34f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            TachiyomiTheme(appTheme = spec.theme, amoled = amoled) {
                Box(modifier = Modifier.width(124.dp)) {
                    AppThemePreviewItem(
                        selected = selected,
                        onClick = onClick,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 23.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = spec.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (selected) "ACTIVE · EXCLUSIVE" else "UNLOCKED · EXCLUSIVE",
                style = MaterialTheme.typography.labelMedium,
                color = spec.accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

private data class BackgroundOption(
    val key: String,
    val label: String,
    val description: String,
    val accent: Color,
)

private val backgroundOptions = listOf(
    BackgroundOption("none", "Off", "A clean, still backdrop with no ambient motion.", Color(0xFFB6B4C2)),
    BackgroundOption("petal_storm", "Petal Storm", "A fleeting dance of petals in a silent storm.", Color(0xFFFF8FB1)),
    BackgroundOption("neon_orbit", "Neon Orbit", "Luminous rings guarding the core of the digital abyss.", Color(0xFF6EF6FF)),
    BackgroundOption("trinity_constellation", "Trinity Constellation", "Three radiant cores bound in an endless cosmic waltz.", Color(0xFF9C7CFF)),
    BackgroundOption("deep_space_archive", "Deep Space Archive", "Ancient folios drifting silently through the cosmic void.", Color(0xFF5DE7D8)),
    BackgroundOption("shadow_realm", "Shadow Realm", "A swirling abyss where light and time lose their meaning.", Color(0xFFB36BFF)),
    BackgroundOption("event_horizon_library", "Event Horizon Library", "A cinematic singularity with lensing rings and drifting archive shards.", Color(0xFF1A4FE0)),
    BackgroundOption("void_weeping_red", "Weeping Void", "A crimson rain falling through the dark.", Color(0xFFFF1E27)),
    BackgroundOption("ink_water", "Ink in Water", "Living ink blooming through water across the app.", Color(0xFF8998FF)),
)

@Composable
internal fun KodamiTreasuryBackgroundSelector(
    selectedEffect: String,
    onEffectSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Tap any row to preview and apply it. The active effect keeps moving behind the Library and More screens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        backgroundOptions.forEach { option ->
            BackgroundEffectRow(
                option = option,
                selected = selectedEffect == option.key,
                onClick = { onEffectSelected(option.key) },
            )
        }
    }
}

@Composable
private fun BackgroundEffectRow(
    option: BackgroundOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderStrength by animateFloatAsState(
        targetValue = if (selected) 1f else 0.22f,
        animationSpec = tween(durationMillis = 320),
        label = "treasury-effect-border-${option.key}",
    )
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(shape)
            .background(Color(0xFF0B0A12))
            .border(
                width = if (selected) 2.dp else 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(option.accent.copy(alpha = borderStrength), Color.White.copy(alpha = 0.08f), option.accent.copy(alpha = borderStrength * 0.64f)),
                ),
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) {
        KodamiTreasuryBackdrop(
            modifier = Modifier.matchParentSize(),
            effectOverride = option.key,
            animateOverride = selected,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF08080D).copy(alpha = 0.32f), Color(0xFF08080D).copy(alpha = 0.74f)),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(option.accent.copy(alpha = if (selected) 0.26f else 0.13f))
                    .border(1.dp, option.accent.copy(alpha = if (selected) 0.9f else 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label.first().uppercase(),
                    color = option.accent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = option.label.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (selected) "ACTIVE · PREVIEWING" else "TAP TO APPLY",
                    style = MaterialTheme.typography.labelSmall,
                    color = option.accent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = option.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (selected) 3.dp else 1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, option.accent.copy(alpha = if (selected) 0.96f else 0.28f), Color.Transparent),
                    ),
                ),
        )
    }
}

private data class ProfileChoice(
    val key: String,
    val title: String,
    val description: String,
    val accent: Color,
)

@Composable
internal fun KodamiTreasuryProfileSelector(
    selectedTitle: String,
    selectedNameEffect: String,
    selectedFrame: String,
    selectedBadge: String,
    onTitleSelected: (String) -> Unit,
    onNameEffectSelected: (String) -> Unit,
    onFrameSelected: (String) -> Unit,
    onBadgeSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ProfileChoiceGroup(
            title = "PROFILE TITLES",
            selected = selectedTitle,
            onSelect = onTitleSelected,
            options = listOf(
                ProfileChoice("none", "No title", "Keep the profile name clean and unadorned.", Color(0xFFB5B3C2)),
                ProfileChoice("title_trinity_initiate", "Trinity Initiate", "A violet mark for the start of a new reading path.", Color(0xFF9C7CFF)),
                ProfileChoice("title_finisher", "Finisher", "A warm gold title for seeing a series through.", Color(0xFFFFD36E)),
                ProfileChoice("title_closer", "Closer", "An amber accent for readers who finish what they start.", Color(0xFFFFB86B)),
                ProfileChoice("title_deep_reader", "Deep Reader", "A cyan sign for long, immersive reading sessions.", Color(0xFF5DE7D8)),
                ProfileChoice("title_rank_4", "Rank 4", "A white-gold prestige rank beside your name.", Color(0xFFFFE08A)),
            ),
        )
        ProfileChoiceGroup(
            title = "NAME EFFECTS",
            selected = selectedNameEffect,
            onSelect = onNameEffectSelected,
            options = listOf(
                ProfileChoice("none", "Off", "Show your profile name without an effect.", Color(0xFFB5B3C2)),
                ProfileChoice("aurora_crown", "Aurora Crown", "A white-gold aurora glow around the name.", Color(0xFFFFD54F)),
                ProfileChoice("glitch_rune", "Glitch Rune", "Cyan signal fragments flicker beside your name.", Color(0xFF40C4FF)),
                ProfileChoice("glitch_rune_red", "Crimson Glitch", "A scarlet digital distortion effect.", Color(0xFFFF003C)),
                ProfileChoice("cipher", "Cipher", "A soft green coded shimmer.", Color(0xFF69F0AE)),
                ProfileChoice("trinity_prism", "Trinity Prism", "Violet light refracts through the profile name.", Color(0xFF9C7CFF)),
                ProfileChoice("shadow_crown", "Shadow Crown", "A dark violet crown effect for the name.", Color(0xFFB36BFF)),
                ProfileChoice("rank_sigils", "Rank Sigils", "Small rank marks frame your profile name.", Color(0xFFFFE08A)),
            ),
        )
        ProfileChoiceGroup(
            title = "AVATAR FRAMES",
            selected = selectedFrame,
            onSelect = onFrameSelected,
            options = listOf(
                ProfileChoice("none", "Off", "Use the avatar without a decorative frame.", Color(0xFFB5B3C2)),
                ProfileChoice("glitch_red", "Glitch Red", "A sharp red signal frame.", Color(0xFFFF1E27)),
                ProfileChoice("neon", "Neon", "A bright neon edge around the avatar.", Color(0xFF6EF6FF)),
                ProfileChoice("hologram", "Hologram", "A cool holographic outline.", Color(0xFF40C4FF)),
                ProfileChoice("prismatic", "Prismatic", "A shifting spectrum-inspired frame.", Color(0xFFB36BFF)),
                ProfileChoice("trinity_orbit", "Trinity Orbit", "Three luminous arcs orbit the avatar.", Color(0xFF9C7CFF)),
                ProfileChoice("deep_archive", "Deep Archive", "A blue-gold archive frame for long sessions.", Color(0xFF5DE7D8)),
                ProfileChoice("hybrid_scroll", "Hybrid Scroll", "A warm frame inspired by illustrated scrolls.", Color(0xFFFFB86B)),
                ProfileChoice("ascendant", "Ascendant", "A white-gold frame for a high-rank profile.", Color(0xFFFFE08A)),
            ),
        )
        ProfileChoiceGroup(
            title = "PROFILE BADGES",
            selected = selectedBadge,
            onSelect = onBadgeSelected,
            options = listOf(
                ProfileChoice("none", "Off", "Hide the profile badge.", Color(0xFFB5B3C2)),
                ProfileChoice("orbit", "Orbit", "A blue orbit ring around your profile avatar.", Color(0xFF64B5F6)),
                ProfileChoice("crown", "Crown", "A warm crown accent for the profile header.", Color(0xFFFFC107)),
                ProfileChoice("shuriken", "Shuriken", "A crisp red manga-inspired badge.", Color(0xFFEF5350)),
                ProfileChoice("trinity", "Trinity", "A violet emblem with three connected points.", Color(0xFF9C7CFF)),
                ProfileChoice("finisher", "Finisher", "A bright gold badge for completed series.", Color(0xFFFFD36E)),
                ProfileChoice("immersion", "Immersion", "A cyan badge for deep reading sessions.", Color(0xFF5DE7D8)),
                ProfileChoice("ascendant", "Ascendant", "A luminous white-gold profile badge.", Color(0xFFFFE08A)),
            ),
        )
    }
}

@Composable
private fun ProfileChoiceGroup(
    title: String,
    selected: String,
    onSelect: (String) -> Unit,
    options: List<ProfileChoice>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.1.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        options.forEach { option ->
            ProfileChoiceRow(
                option = option,
                selected = selected == option.key,
                onClick = { onSelect(option.key) },
            )
        }
    }
}

@Composable
private fun ProfileChoiceRow(
    option: ProfileChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderStrength by animateFloatAsState(
        targetValue = if (selected) 1f else 0.18f,
        animationSpec = tween(durationMillis = 280),
        label = "treasury-profile-border-${option.key}",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.994f,
        animationSpec = tween(durationMillis = 220),
        label = "treasury-profile-scale-${option.key}",
    )
    val shape = RoundedCornerShape(17.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                if (selected) option.accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
            )
            .border(
                width = if (selected) 1.7.dp else 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(option.accent.copy(alpha = borderStrength), Color.White.copy(alpha = 0.07f), option.accent.copy(alpha = borderStrength * 0.55f)),
                ),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(option.accent.copy(alpha = if (selected) 0.24f else 0.12f))
                .border(1.dp, option.accent.copy(alpha = if (selected) 0.92f else 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = option.title.firstOrNull()?.uppercase() ?: "•",
                style = MaterialTheme.typography.titleSmall,
                color = option.accent,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = option.accent,
                modifier = Modifier.size(21.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(19.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}
