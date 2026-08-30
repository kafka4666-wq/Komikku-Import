package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.DoujinCustomisationsPreferences
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

// SY -->
enum class NavBarType {
    VerticalRight,
    VerticalLeft,
    Bottom,
}
// SY <--

@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // SY -->
    isExhToolsVisible: Boolean,
    onSetExhUtilsVisibility: (Boolean) -> Unit,
    isAutoScroll: Boolean,
    isAutoScrollEnabled: Boolean,
    onToggleAutoscroll: (Boolean) -> Unit,
    autoScrollFrequency: String,
    onSetAutoScrollFrequency: (String) -> Unit,
    onClickAutoScrollHelp: () -> Unit,
    onClickRetryAll: () -> Unit,
    onClickRetryAllHelp: () -> Unit,
    onClickBoostPage: () -> Unit,
    onClickBoostPageHelp: () -> Unit,
    navBarType: NavBarType,
    currentPageText: String,
    enabledButtons: ImmutableSet<String>,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    // SY <--
) {
    val isRtl = viewer is R2LPagerViewer
    val preferences = androidx.compose.runtime.remember { DoujinCustomisationsPreferences(Injekt.get<PreferenceStore>()) }
    val toolbarTransparency by preferences.readerToolbarTransparency().collectAsState()
    val readerUiStyle by preferences.readerUiStyle().collectAsState()
    val animations by preferences.animations().collectAsState()
    val readerAnimation by preferences.readerAnimation().collectAsState()
    val animationSpeed by preferences.animationSpeed().collectAsState()
    val backgroundAlpha = ((100 - toolbarTransparency.coerceIn(0, 100)) / 100f) * if (isSystemInDarkTheme()) 0.9f else 0.95f
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(if (readerUiStyle == "minimal") 1.dp else 3.dp)
        .copy(alpha = backgroundAlpha.coerceIn(0.08f, 1f))
    val motionEnabled = animations && readerAnimation != "instant"
    val motionDuration = when (animationSpeed) { "slow" -> 320; "fast" -> 100; else -> 200 }
    val slideSpec = if (motionEnabled) tween<IntOffset>(if (readerAnimation == "smooth") motionDuration + 80 else motionDuration) else tween<IntOffset>(0)
    val fadeSpec = if (motionEnabled) tween<Float>(if (readerAnimation == "smooth") motionDuration else (motionDuration * 3) / 4) else tween<Float>(0)

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = slideSpec) +
                fadeIn(animationSpec = fadeSpec),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = slideSpec) +
                fadeOut(animationSpec = fadeSpec),
        ) {
            // SY -->
            Column {
                // SY <--
                ReaderTopBar(
                    modifier = Modifier
                        .background(backgroundColor)
                        .clickable(onClick = onClickTopAppBar),
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    navigateUp = navigateUp,
                    bookmarked = bookmarked,
                    onToggleBookmarked = onToggleBookmarked,
                    // SY -->
                    onOpenInWebView = null, // onOpenInWebView,
                    onOpenInBrowser = null, // onOpenInBrowser,
                    onShare = null, // onShare,
                    // SY <--
                )
                // SY -->
                ExhUtils(
                    isVisible = isExhToolsVisible,
                    onSetExhUtilsVisibility = onSetExhUtilsVisibility,
                    backgroundColor = backgroundColor,
                    isAutoScroll = isAutoScroll,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                    onToggleAutoscroll = onToggleAutoscroll,
                    autoScrollFrequency = autoScrollFrequency,
                    onSetAutoScrollFrequency = onSetAutoScrollFrequency,
                    onClickAutoScrollHelp = onClickAutoScrollHelp,
                    onClickRetryAll = onClickRetryAll,
                    onClickRetryAllHelp = onClickRetryAllHelp,
                    onClickBoostPage = onClickBoostPage,
                    onClickBoostPageHelp = onClickBoostPageHelp,
                )
            }
            // SY <--
        }

        // KMK -->
        when (navBarType) {
            NavBarType.VerticalLeft -> {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = slideSpec,
                    ) +
                        fadeIn(animationSpec = fadeSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = slideSpec,
                    ) +
                        fadeOut(animationSpec = fadeSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.Start),
                ) {
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        // SY -->
                        isVerticalSlider = true,
                        currentPageText = currentPageText,
                        // SY <--
                    )
                }
            }

            NavBarType.VerticalRight -> {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = slideSpec,
                    ) +
                        fadeIn(animationSpec = fadeSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = slideSpec,
                    ) +
                        fadeOut(animationSpec = fadeSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.End),
                ) {
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        // SY -->
                        isVerticalSlider = true,
                        currentPageText = currentPageText,
                        // SY <--
                    )
                }
            }
            // KMK <--
            else -> Spacer(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = slideSpec) +
                fadeIn(animationSpec = fadeSpec),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = slideSpec) +
                fadeOut(animationSpec = fadeSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                // SY -->
                if (navBarType == NavBarType.Bottom) {
                    // SY <--
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        // SY -->
                        isVerticalSlider = false,
                        currentPageText = currentPageText,
                        // SY <--
                    )
                }
                ReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    readingMode = readingMode,
                    onClickReadingMode = onClickReadingMode,
                    orientation = orientation,
                    onClickOrientation = onClickOrientation,
                    cropEnabled = cropEnabled,
                    onClickCropBorder = onClickCropBorder,
                    onClickSettings = onClickSettings,
                    // SY -->
                    enabledButtons = enabledButtons,
                    currentReadingMode = currentReadingMode,
                    dualPageSplitEnabled = dualPageSplitEnabled,
                    doublePages = doublePages,
                    onClickChapterList = onClickChapterList,
                    onClickWebView = onOpenInWebView,
                    onClickBrowser = onOpenInBrowser,
                    onClickShare = onShare,
                    onClickPageLayout = onClickPageLayout,
                    onClickShiftPage = onClickShiftPage,
                    // SY <--
                )
            }
        }
    }
}
