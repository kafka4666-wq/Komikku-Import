package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MangaCoverHide
import eu.kanade.presentation.manga.components.RatioSwitchToPanorama
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import exh.debug.DebugToggles
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

object CommonMangaItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp

    @Suppress("ConstPropertyName")
    const val BrowseFavoriteCoverAlpha = 0.34f
}

private val ContinueReadingButtonSizeSmall = 28.dp
private val ContinueReadingButtonSizeLarge = 32.dp

private val ContinueReadingButtonIconSizeSmall = 16.dp
private val ContinueReadingButtonIconSizeLarge = 20.dp

private val ContinueReadingButtonGridPadding = 6.dp
private val ContinueReadingButtonListSpacing = 8.dp

internal const val GRID_SELECTED_COVER_ALPHA = 0.76f

/**
 * Layout of grid list item with title overlaying the cover.
 * Accepts null [title] for a cover-only view.
 */
@Composable
fun MangaCompactGridItem(
    coverData: MangaCoverModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    title: String? = null,
    onClickContinueReading: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    coverBadgeStart: @Composable (RowScope.() -> Unit)? = null,
    coverBadgeEnd: @Composable (RowScope.() -> Unit)? = null,
    // KMK -->
    libraryColored: Boolean = true,
    appearance: DoujinCardAppearance? = null,
    // KMK <--
) {
    // KMK -->
    val cardAppearance = appearance ?: rememberDoujinCardAppearance()
    val cornerRadius = cardAppearance.cornerRadius
    val cardStyle = cardAppearance.cardStyle
    val dynamicCoverColors = cardAppearance.dynamicCoverColors
    val coverFade = cardAppearance.coverFade
    val coverGradient = cardAppearance.coverGradient
    val cardShadow = cardAppearance.cardShadow
    val coverShadow = cardAppearance.coverShadow
    val coverHighlight = cardAppearance.coverHighlight
    val compactMode = cardAppearance.compactMode
    val titlePosition = cardAppearance.titlePosition
    val coverSize = cardAppearance.coverSize
    val customCoverSize = cardAppearance.customCoverSize
    val metadataDensity = cardAppearance.metadataDensity
    val coverWidth = when (coverSize) { "small" -> 0.84f; "large" -> 1f; "custom" -> customCoverSize.coerceIn(70, 100) / 100f; else -> 0.92f }
    val cardShape = RoundedCornerShape(cornerRadius.coerceIn(0, 32).dp)
    val useDynamicColors = libraryColored && dynamicCoverColors
    val coverAlphaForStyle = if (coverFade) coverAlpha * 0.92f else coverAlpha
    val bgColor = coverData.dominantCoverColors?.first?.let { Color(it) }.takeIf { useDynamicColors }
    val onBgColor = coverData.dominantCoverColors?.second.takeIf { useDynamicColors }
    val coverEffectModifier = Modifier
        .then(if (coverShadow) Modifier.shadow(1.dp, cardShape) else Modifier)
        .then(if (coverHighlight) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), cardShape) else Modifier)
    // KMK <--
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        shape = cardShape,
        shadowElevation = if (cardStyle == "minimal" || !cardShadow || compactMode) 0.dp else 2.dp,
        cardSurface = when (cardStyle) {
            "glass" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            "editorial" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
            "detailed" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            else -> Color.Transparent
        },
    ) {
        MangaGridCover(
            modifier = Modifier.fillMaxWidth(coverWidth).then(coverEffectModifier),
            cover = {
                // KMK -->
                if (DebugToggles.HIDE_COVER_IMAGE_ONLY_SHOW_COLOR.enabled) {
                    MangaCoverHide.Book(
                        modifier = Modifier
                            .fillMaxWidth(),
                        bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                        tint = onBgColor,
                    )
                } else {
                    // KMK <--
                    MangaCover.Book(
                        modifier = Modifier
                            // KMK -->
                            // .alpha(if (isSelected) GridSelectedCoverAlpha else coverAlpha)
                            // KMK <--
                            .fillMaxWidth(),
                        data = coverData,
                        // KMK -->
                        alpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlphaForStyle,
                        shape = cardShape,
                        bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                        tint = onBgColor,
                        // KMK <--
                    )
                }
            },
            badgesStart = coverBadgeStart.takeIf { cardStyle != "minimal" },
            badgesEnd = coverBadgeEnd.takeIf { cardStyle != "minimal" },
            content = {
                if (title != null && titlePosition != "hidden") {
                    CoverTextOverlay(
                        title = title,
                        showGradient = coverGradient,
                        onClickContinueReading = onClickContinueReading,
                    )
                } else if (onClickContinueReading != null) {
                    ContinueReadingButton(
                        size = ContinueReadingButtonSizeLarge,
                        iconSize = ContinueReadingButtonIconSizeLarge,
                        onClick = onClickContinueReading,
                        modifier = Modifier
                            .padding(ContinueReadingButtonGridPadding)
                            .align(Alignment.BottomEnd),
                    )
                }
            },
        )
            if (title != null && titlePosition == "below") {
                GridItemTitle(
                    modifier = Modifier.padding(4.dp),
                    title = title,
                    style = MaterialTheme.typography.titleSmall,
                    minLines = 1,
                    maxLines = when (metadataDensity) { "minimal" -> 1; "detailed" -> 3; else -> 2 },
                )
            }
        }
    }

/**
 * Title overlay for [MangaCompactGridItem]
 */
@Composable
private fun BoxScope.CoverTextOverlay(
    title: String,
    showGradient: Boolean,
    onClickContinueReading: (() -> Unit)? = null,
) {
    if (showGradient) Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color(0xAA000000),
                ),
            )
            .fillMaxHeight(0.33f)
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
    )
    Row(
        modifier = Modifier.align(Alignment.BottomStart),
        verticalAlignment = Alignment.Bottom,
    ) {
        GridItemTitle(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            title = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = Color.White,
                shadow = Shadow(
                    color = Color.Black,
                    blurRadius = 4f,
                ),
            ),
            minLines = 1,
        )
        if (onClickContinueReading != null) {
            ContinueReadingButton(
                size = ContinueReadingButtonSizeSmall,
                iconSize = ContinueReadingButtonIconSizeSmall,
                onClick = onClickContinueReading,
                modifier = Modifier.padding(
                    end = ContinueReadingButtonGridPadding,
                    bottom = ContinueReadingButtonGridPadding,
                ),
            )
        }
    }
}

/**
 * Layout of grid list item with title below the cover.
 */
@Composable
fun MangaComfortableGridItem(
    coverData: MangaCoverModel,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    titleMaxLines: Int = 2,
    coverAlpha: Float = 1f,
    coverBadgeStart: @Composable (RowScope.() -> Unit)? = null,
    coverBadgeEnd: @Composable (RowScope.() -> Unit)? = null,
    onClickContinueReading: (() -> Unit)? = null,
    // KMK -->
    libraryColored: Boolean = true,
    coverRatio: MutableFloatState = remember { mutableFloatStateOf(1f) },
    usePanoramaCover: Boolean,
    fitToPanoramaCover: Boolean = false,
    preserveCoverAspect: Boolean = false,
    appearance: DoujinCardAppearance? = null,
    // KMK <—
) {
    // KMK -->
    val cardAppearance = appearance ?: rememberDoujinCardAppearance()
    val cornerRadius = cardAppearance.cornerRadius
    val cardStyle = cardAppearance.cardStyle
    val dynamicCoverColors = cardAppearance.dynamicCoverColors
    val coverFade = cardAppearance.coverFade
    val cardShadow = cardAppearance.cardShadow
    val coverShadow = cardAppearance.coverShadow
    val coverHighlight = cardAppearance.coverHighlight
    val compactMode = cardAppearance.compactMode
    val titlePosition = cardAppearance.titlePosition
    val coverSize = cardAppearance.coverSize
    val customCoverSize = cardAppearance.customCoverSize
    val metadataDensity = cardAppearance.metadataDensity
    val coverWidth = when (coverSize) { "small" -> 0.84f; "large" -> 1f; "custom" -> customCoverSize.coerceIn(70, 100) / 100f; else -> 0.92f }
    val cardShape = RoundedCornerShape(cornerRadius.coerceIn(0, 32).dp)
    val coverIsWide = coverRatio.floatValue <= RatioSwitchToPanorama
    val useDynamicColors = libraryColored && dynamicCoverColors
    val coverAlphaForStyle = if (coverFade) coverAlpha * 0.92f else coverAlpha
    val bgColor = coverData.dominantCoverColors?.first?.let { Color(it) }.takeIf { useDynamicColors }
    val onBgColor = coverData.dominantCoverColors?.second.takeIf { useDynamicColors }
    val coverEffectModifier = Modifier
        .then(if (coverShadow) Modifier.shadow(1.dp, cardShape) else Modifier)
        .then(if (coverHighlight) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), cardShape) else Modifier)
    // KMK <--
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        shape = cardShape,
        shadowElevation = if (cardStyle == "minimal" || !cardShadow || compactMode) 0.dp else 2.dp,
        cardSurface = when (cardStyle) {
            "glass" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            "editorial" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
            "detailed" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            else -> Color.Transparent
        },
    ) {
        Column {
            MangaGridCover(
                modifier = Modifier.fillMaxWidth(coverWidth).then(coverEffectModifier),
                cover = {
                    // KMK -->
                    if (DebugToggles.HIDE_COVER_IMAGE_ONLY_SHOW_COLOR.enabled) {
                        MangaCoverHide.Book(
                            modifier = Modifier
                                .fillMaxWidth(),
                            bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                            tint = onBgColor,
                        )
                    } else {
                        if (fitToPanoramaCover && usePanoramaCover && coverIsWide) {
                            MangaCover.Panorama(
                                modifier = Modifier
                                    // KMK -->
                                    // .alpha(if (isSelected) GridSelectedCoverAlpha else coverAlpha)
                                    // KMK <--
                                    .fillMaxWidth(),
                                data = coverData,
                                // KMK -->
                                alpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlphaForStyle,
                                shape = cardShape,
                                bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                                tint = onBgColor,
                                onCoverLoaded = { _, result ->
                                    val image = result.result.image
                                    coverRatio.floatValue = image.height.toFloat() / image.width
                                },
                                // KMK <--
                            )
                        } else {
                            // KMK <--
                            MangaCover.Book(
                                modifier = Modifier
                                    // KMK -->
                                    // .alpha(if (isSelected) GridSelectedCoverAlpha else coverAlpha)
                                    // KMK <--
                                    .fillMaxWidth(),
                                data = coverData,
                                // KMK -->
                                alpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlphaForStyle,
                                shape = cardShape,
                                bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                                tint = onBgColor,
                                onCoverLoaded = { _, result ->
                                    val image = result.result.image
                                    coverRatio.floatValue = image.height.toFloat() / image.width
                                },
                                scale = if (usePanoramaCover && coverIsWide) {
                                    ContentScale.Fit
                                } else {
                                    ContentScale.Crop
                                },
                                // KMK <--
                            )
                        }
                    }
                },
                // KMK -->
                ratio = if (preserveCoverAspect) {
                    coverRatio.floatValue.coerceIn(0.45f, 2.4f)
                } else if (fitToPanoramaCover && usePanoramaCover && coverIsWide) {
                    MangaCover.Panorama.ratio
                } else {
                    MangaCover.Book.ratio
                },
                // KMK <--
                badgesStart = coverBadgeStart.takeIf { cardStyle != "minimal" },
                badgesEnd = coverBadgeEnd.takeIf { cardStyle != "minimal" },
                content = {
                    if (onClickContinueReading != null) {
                        ContinueReadingButton(
                            size = ContinueReadingButtonSizeLarge,
                            iconSize = ContinueReadingButtonIconSizeLarge,
                            onClick = onClickContinueReading,
                            modifier = Modifier
                                .padding(ContinueReadingButtonGridPadding)
                                .align(Alignment.BottomEnd),
                        )
                    }
                },
            )
            if (titlePosition != "hidden") GridItemTitle(
                modifier = Modifier.padding(4.dp),
                title = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = if (metadataDensity == "minimal") 1 else 2,
                maxLines = if (metadataDensity == "minimal") 1 else if (metadataDensity == "detailed") 3 else titleMaxLines,
            )
        }
    }
}

/**
 * Common cover layout to add contents to be drawn on top of the cover.
 */
@Composable
private fun MangaGridCover(
    modifier: Modifier = Modifier,
    cover: @Composable BoxScope.() -> Unit = {},
    // KMK -->
    ratio: Float = MangaCover.Book.ratio,
    // KMK <--
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio),
    ) {
        cover()
        content?.invoke(this)
        if (badgesStart != null) {
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopStart),
                content = badgesStart,
            )
        }

        if (badgesEnd != null) {
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopEnd),
                content = badgesEnd,
            )
        }
    }
}

@Composable
private fun GridItemTitle(
    title: String,
    style: TextStyle,
    minLines: Int,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

/**
 * Wrapper for grid items to handle selection state, click and long click.
 */
@Composable
private fun GridItemSelectable(
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small,
    shadowElevation: Dp = 0.dp,
    cardSurface: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(cardSurface, shape)
            .shadow(shadowElevation, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                selected = isSelected
            }
            .selectedOutline(isSelected = isSelected, color = MaterialTheme.colorScheme.secondary)
            .padding(4.dp),
    ) {
        val contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onSecondary
        } else {
            LocalContentColor.current
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * @see GridItemSelectable
 */
private fun Modifier.selectedOutline(
    isSelected: Boolean,
    color: Color,
) = drawBehind { if (isSelected) drawRect(color = color) }

/**
 * Layout of list item.
 */
@Composable
fun MangaListItem(
    coverData: MangaCoverModel,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    badge: @Composable (RowScope.() -> Unit),
    isSelected: Boolean = false,
    coverAlpha: Float = 1f,
    onClickContinueReading: (() -> Unit)? = null,
    // KMK -->
    libraryColored: Boolean = true,
    appearance: DoujinCardAppearance? = null,
    // KMK <--
) {
    // KMK -->
    val cardAppearance = appearance ?: rememberDoujinCardAppearance()
    val cornerRadius = cardAppearance.cornerRadius
    val dynamicCoverColors = cardAppearance.dynamicCoverColors
    val coverFade = cardAppearance.coverFade
    val cardShadow = cardAppearance.cardShadow
    val coverShadow = cardAppearance.coverShadow
    val coverHighlight = cardAppearance.coverHighlight
    val compactMode = cardAppearance.compactMode
    val cardShape = RoundedCornerShape(cornerRadius.coerceIn(0, 32).dp)
    val useDynamicColors = libraryColored && dynamicCoverColors
    val bgColor = coverData.dominantCoverColors?.first?.let { Color(it) }.takeIf { useDynamicColors }
    val onBgColor = coverData.dominantCoverColors?.second.takeIf { useDynamicColors }
    val coverEffectModifier = Modifier
        .then(if (coverShadow) Modifier.shadow(1.dp, cardShape) else Modifier)
        .then(if (coverHighlight) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), cardShape) else Modifier)
    // KMK <--
    Row(
        modifier = Modifier
            .selectedBackground(isSelected)
            .clip(cardShape)
            .shadow(if (cardShadow) 2.dp else 0.dp, cardShape)
            .height(56.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // KMK -->
        if (DebugToggles.HIDE_COVER_IMAGE_ONLY_SHOW_COLOR.enabled) {
            MangaCoverHide.Square(
                modifier = Modifier
                    .fillMaxHeight(),
                bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                tint = onBgColor,
            )
        } else {
            // KMK <--
            MangaCover.Square(
                modifier = Modifier
                    // KMK -->
                    // .alpha(coverAlpha)
                    // KMK <--
                    .fillMaxHeight(),
                data = coverData,
                // KMK -->
                alpha = if (coverFade) coverAlpha * 0.92f else coverAlpha,
                shape = cardShape,
                bgColor = bgColor ?: MaterialTheme.colorScheme.surface.takeIf { isSelected },
                tint = onBgColor,
                size = MangaCover.Size.Big,
                // KMK <--
            )
        }
        Text(
            text = title,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        BadgeGroup(content = badge)
        if (onClickContinueReading != null) {
            ContinueReadingButton(
                size = ContinueReadingButtonSizeSmall,
                iconSize = ContinueReadingButtonIconSizeSmall,
                onClick = onClickContinueReading,
                modifier = Modifier.padding(start = ContinueReadingButtonListSpacing),
            )
        }
    }
}

@Composable
private fun ContinueReadingButton(
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
            ),
            modifier = Modifier.size(size),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(MR.strings.action_resume),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
