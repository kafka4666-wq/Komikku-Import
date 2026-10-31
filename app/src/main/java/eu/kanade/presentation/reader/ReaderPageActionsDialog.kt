package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderPageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: (/* SY --> */useExtraPage: Boolean/* SY <-- */) -> Unit,
    onShare: (copyToClipboard: Boolean, /* SY --> */useExtraPage: Boolean/* SY <-- */) -> Unit,
    onSave: (/* SY --> */useExtraPage: Boolean/* SY <-- */) -> Unit,
    // SY -->
    onShareCombined: (copyToClipboard: Boolean) -> Unit,
    onSaveCombined: () -> Unit,
    hasExtraPage: Boolean,
    // SY <--
    pendingMergePageCount: Int,
    chapterPageCount: Int,
    isQueueingChapter: Boolean,
    queueingChapterProgress: Int,
    queueingChapterTotal: Int,
    onQueuePageForMerge: (useExtraPage: Boolean) -> Unit,
    onQueueEntireChapterForMerge: () -> Unit,
    onAppendQueuedPages: () -> Unit,
    onClearQueuedPages: () -> Unit,
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }
    var showMergeConfirmation by remember { mutableStateOf(false) }
    var showQueueChapterConfirmation by remember { mutableStateOf(false) }
    // SY -->
    var useExtraPage by remember { mutableStateOf(false) }
    // SY <--

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(
                        // SY -->
                        if (hasExtraPage) {
                            SYMR.strings.action_set_first_page_cover
                        } else {
                            MR.strings.set_as_cover
                        },
                        // SY <--
                    ),
                    icon = Icons.Outlined.Photo,
                    onClick = { showSetCoverDialog = true },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(
                        // SY -->
                        if (hasExtraPage) {
                            KMR.strings.action_copy_to_clipboard_first_page
                        } else {
                            MR.strings.action_copy_to_clipboard
                        },
                        // SY <--
                    ),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        // SY -->
                        onShare(true, false)
                        // SY <--
                        onDismissRequest()
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(
                        // SY -->
                        if (hasExtraPage) {
                            SYMR.strings.action_share_first_page
                        } else {
                            MR.strings.action_share
                        },
                        // SY <--
                    ),
                    icon = Icons.Outlined.Share,
                    onClick = {
                        // SY -->
                        onShare(false, false)
                        // SY <--
                        onDismissRequest()
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(
                        // SY -->
                        if (hasExtraPage) {
                            SYMR.strings.action_save_first_page
                        } else {
                            MR.strings.action_save
                        },
                        // SY <--
                    ),
                    icon = Icons.Outlined.Save,
                    onClick = {
                        // SY -->
                        onSave(false)
                        // SY <--
                        onDismissRequest()
                    },
                )
            }
            if (hasExtraPage) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(SYMR.strings.action_set_second_page_cover),
                        icon = Icons.Outlined.Photo,
                        onClick = {
                            showSetCoverDialog = true
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(KMR.strings.action_copy_to_clipboard_second_page),
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onShare(true, true)
                            onDismissRequest()
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(SYMR.strings.action_share_second_page),
                        icon = Icons.Outlined.Share,
                        onClick = {
                            onShare(false, true)
                            onDismissRequest()
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(SYMR.strings.action_save_second_page),
                        icon = Icons.Outlined.Save,
                        onClick = {
                            onSave(true)
                            onDismissRequest()
                        },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(KMR.strings.action_copy_to_clipboard_combined_page),
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onShareCombined(true)
                            onDismissRequest()
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(SYMR.strings.action_share_combined_page),
                        icon = Icons.Outlined.Share,
                        onClick = {
                            onShareCombined(false)
                            onDismissRequest()
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(SYMR.strings.action_save_combined_page),
                        icon = Icons.Outlined.Save,
                        onClick = {
                            onSaveCombined()
                            onDismissRequest()
                        },
                    )
                }
            }
            Text(
                "To merge chapters across books or sources: queue one page or the entire chapter here, open the destination chapter, then append the queue. Page images are copied; the original books are unchanged.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (hasExtraPage) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    TextButton(
                        enabled = !isQueueingChapter,
                        onClick = { onQueuePageForMerge(false); onDismissRequest() },
                    ) {
                        Text("Queue first page")
                    }
                    TextButton(
                        enabled = !isQueueingChapter,
                        onClick = { onQueuePageForMerge(true); onDismissRequest() },
                    ) {
                        Text("Queue second page")
                    }
                }
            } else {
                TextButton(
                    enabled = !isQueueingChapter,
                    onClick = { onQueuePageForMerge(false); onDismissRequest() },
                ) {
                    Text("Queue this page for merge")
                }
            }
            TextButton(
                enabled = chapterPageCount > 0 && !isQueueingChapter,
                onClick = { showQueueChapterConfirmation = true },
            ) {
                Text("Queue entire chapter ($chapterPageCount pages)")
            }
            if (isQueueingChapter) {
                Text(
                    "Copying chapter pages: $queueingChapterProgress / $queueingChapterTotal. Keep this chapter open until it finishes.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (pendingMergePageCount > 0) {
                Text(
                    "$pendingMergePageCount page(s) queued",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    TextButton(
                        enabled = !isQueueingChapter,
                        onClick = { showMergeConfirmation = true },
                    ) {
                        Text("Append queued pages here")
                    }
                    TextButton(
                        enabled = !isQueueingChapter,
                        onClick = { onClearQueuedPages(); onDismissRequest() },
                    ) {
                        Text("Clear queue")
                    }
                }
            }
        }
    }

    if (showMergeConfirmation) {
        AlertDialog(
            title = { Text("Append queued pages?") },
            text = {
                Text("Append $pendingMergePageCount copied page image(s) to the end of this chapter? This works across different sources and does not change either book's online chapters.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showMergeConfirmation = false
                    onAppendQueuedPages()
                    onDismissRequest()
                }) {
                    Text("Append pages")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirmation = false }) {
                    Text("Cancel")
                }
            },
            onDismissRequest = { showMergeConfirmation = false },
        )
    }

    if (showQueueChapterConfirmation) {
        AlertDialog(
            title = { Text("Queue the whole chapter?") },
            text = {
                Text("Copy all $chapterPageCount page images into the local merge queue? This can use significant storage and may take a while. Keep the reader open until the copy finishes.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showQueueChapterConfirmation = false
                    onQueueEntireChapterForMerge()
                }) {
                    Text("Queue all pages")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQueueChapterConfirmation = false }) {
                    Text("Cancel")
                }
            },
            onDismissRequest = { showQueueChapterConfirmation = false },
        )
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            onConfirm = {
                // SY -->
                onSetAsCover(useExtraPage)
                showSetCoverDialog = false
                useExtraPage = false
                // SY <--
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
}

@Composable
private fun SetCoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        text = {
            Text(stringResource(MR.strings.confirm_set_image_as_cover))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}
