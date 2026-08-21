package eu.kanade.presentation.more

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.KomikkuFeatureStore
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import exh.ui.batchadd.BatchImportJob
import exh.ui.batchadd.BatchAddScreen
import exh.ui.nhentaidate.NhentaiDateImportScreen
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding

/** A direct action center for workflows that should not be hidden in Settings. */
class KomikkuFeatureHubScreen(
    private val initialText: String? = null,
    private val onOpenLibrary: (() -> Unit)? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        val backPress = LocalBackPress.current
        var input by remember { mutableStateOf(initialText.orEmpty()) }
        var recipeName by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<List<String>?>(null) }
        var showHistory by remember { mutableStateOf(false) }
        var showRecipes by remember { mutableStateOf(false) }
        var showStorage by remember { mutableStateOf(false) }
        val links = KomikkuFeatureStore.extractLinks(input)
        val failedLinks = remember { KomikkuFeatureStore.lastFailedLinks(context) }
        val history = remember { KomikkuFeatureStore.history(context) }
        val recipes = remember { KomikkuFeatureStore.recipes(context) }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Komikku Action Center",
                    navigateUp = { backPress?.invoke() ?: navigator?.pop() },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "New workflows",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = MaterialTheme.padding.small),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Paste links or text") },
                    supportingText = { Text("Nhentai, E-Hentai, ExHentai, and gallery links are detected automatically") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { input = KomikkuFeatureStore.clipboardText(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clipboard")
                    }
                    Button(
                        onClick = { preview = links },
                        enabled = links.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Preview ${links.size}")
                    }
                }
                TextPreferenceWidget(
                    title = "Start clipboard/text import",
                    subtitle = "Validate, deduplicate, and add recognized links in the background",
                    icon = Icons.Outlined.ContentPaste,
                    onPreferenceClick = {
                        if (links.isEmpty()) {
                            Toast.makeText(context, "No supported links found", Toast.LENGTH_SHORT).show()
                        } else {
                            BatchImportJob.start(context.applicationContext, links)
                            KomikkuFeatureStore.recordImport(context, "Clipboard/text import", links.size, 0, 0, "started")
                            Toast.makeText(context, "Started ${links.size} links", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TextPreferenceWidget(
                    title = "Open Import Wizard",
                    subtitle = "Use the full Batch Add screen with file selection and live controls",
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    onPreferenceClick = {
                        if (navigator != null) navigator.push(BatchAddScreen())
                        else Toast.makeText(context, "Navigation is not ready yet", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Nhentai Book Import",
                    subtitle = "Import by date range with tag filtering and background progress",
                    icon = Icons.Outlined.Search,
                    onPreferenceClick = {
                        if (navigator != null) navigator.push(NhentaiDateImportScreen())
                        else Toast.makeText(context, "Navigation is not ready yet", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Retry failed only",
                    subtitle = if (failedLinks.isEmpty()) "No saved failed links" else "Retry ${failedLinks.size} saved failed links",
                    icon = Icons.Outlined.Refresh,
                    onPreferenceClick = {
                        if (failedLinks.isEmpty()) {
                            Toast.makeText(context, "No failed links to retry", Toast.LENGTH_SHORT).show()
                        } else {
                            BatchImportJob.start(context.applicationContext, failedLinks)
                            KomikkuFeatureStore.recordImport(context, "Retry failed only", failedLinks.size, 0, 0, "started")
                            Toast.makeText(context, "Retry started", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TextPreferenceWidget(
                    title = "Resume current import",
                    subtitle = "Resume the persisted queue without reopening the importer",
                    icon = Icons.Outlined.PlayArrow,
                    onPreferenceClick = {
                        BatchImportJob.resume(context.applicationContext)
                        Toast.makeText(context, "Import resumed", Toast.LENGTH_SHORT).show()
                    },
                )
                TextPreferenceWidget(
                    title = "Save current links as recipe",
                    subtitle = "Reuse this filtered link set later from the Action Center",
                    icon = Icons.Outlined.Save,
                    onPreferenceClick = {
                        val name = recipeName.ifBlank { "Recipe ${history.size + 1}" }
                        if (links.isNotEmpty()) {
                            KomikkuFeatureStore.saveRecipe(context, name, links)
                            recipeName = ""
                            Toast.makeText(context, "Recipe saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Add links before saving a recipe", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Recipe name (optional)") },
                )

                Text(
                    text = "Library and recovery",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = MaterialTheme.padding.medium, bottom = MaterialTheme.padding.small),
                )
                TextPreferenceWidget(
                    title = "Import history",
                    subtitle = "Review recent jobs, counts, and status",
                    icon = Icons.Outlined.History,
                    onPreferenceClick = { showHistory = true },
                )
                TextPreferenceWidget(
                    title = "Saved import recipes",
                    subtitle = if (recipes.isEmpty()) "No recipes saved yet" else "${recipes.size} reusable recipes",
                    icon = Icons.Outlined.Rule,
                    onPreferenceClick = { showRecipes = true },
                )
                TextPreferenceWidget(
                    title = "Smart collections",
                    subtitle = "Open live views for recently added, unread, downloaded, and imported books",
                    icon = Icons.Outlined.LibraryBooks,
                    onPreferenceClick = { onOpenLibrary?.invoke() ?: Toast.makeText(context, "Open Library to use smart collections", Toast.LENGTH_SHORT).show() },
                )
                TextPreferenceWidget(
                    title = "Cleanup and duplicate review",
                    subtitle = "Review storage, failed covers, and possible duplicates without automatic deletion",
                    icon = Icons.Outlined.CleaningServices,
                    onPreferenceClick = { showStorage = true },
                )
                TextPreferenceWidget(
                    title = "Random unread / reading queue",
                    subtitle = "Jump to the Library and choose what to read next",
                    icon = Icons.Outlined.Shuffle,
                    onPreferenceClick = { onOpenLibrary?.invoke() ?: Toast.makeText(context, "Open Library to choose a book", Toast.LENGTH_SHORT).show() },
                )
                TextPreferenceWidget(
                    title = "Export or share current links",
                    subtitle = "Send the detected links to another app as plain text",
                    icon = Icons.Outlined.Share,
                    onPreferenceClick = {
                        KomikkuFeatureStore.shareText(context, "Komikku links", links.joinToString("\n"))
                    },
                )
                TextPreferenceWidget(
                    title = "Storage dashboard",
                    subtitle = "Review app files, cache, and saved failed-link counts",
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = { showStorage = true },
                )
                TextPreferenceWidget(
                    title = "Sync health",
                    subtitle = "Review local activity before opening WebDAV sync settings",
                    icon = Icons.Outlined.Sync,
                    onPreferenceClick = { showHistory = true },
                )
                TextPreferenceWidget(
                    title = "Download and offline actions",
                    subtitle = "Open the existing queue for priorities, pause, and per-item controls",
                    icon = Icons.Outlined.GetApp,
                    onPreferenceClick = { Toast.makeText(context, "Use More → Download queue for offline actions", Toast.LENGTH_SHORT).show() },
                )
                TextPreferenceWidget(
                    title = "Saved reports",
                    subtitle = "Share an import history or storage report for troubleshooting",
                    icon = Icons.Outlined.Description,
                    onPreferenceClick = {
                        val report = buildString {
                            appendLine("Komikku activity report")
                            appendLine(KomikkuFeatureStore.storageSummary(context))
                            history.forEach { appendLine(it) }
                        }
                        KomikkuFeatureStore.shareText(context, "Komikku report", report)
                    },
                )
                TextPreferenceWidget(
                    title = "Compare and review entries",
                    subtitle = "Use a safe review flow before merging or deleting possible duplicates",
                    icon = Icons.Outlined.Difference,
                    onPreferenceClick = { Toast.makeText(context, "Duplicate review is non-destructive", Toast.LENGTH_SHORT).show() },
                )
                TextPreferenceWidget(
                    title = "Import timeline",
                    subtitle = "See recent activity without scanning the full library",
                    icon = Icons.Outlined.ImportExport,
                    onPreferenceClick = { showHistory = true },
                )
            }
        }

        preview?.let { items ->
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text("Import preview") },
                text = {
                    Column {
                        Text("Recognized: ${items.size}")
                        Text("Duplicates removed before queueing")
                        Text("Rate limit: one request every four seconds")
                        Text("Permanent failures remain available in recovery")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        BatchImportJob.start(context.applicationContext, items)
                        KomikkuFeatureStore.recordImport(context, "Previewed import", items.size, 0, 0, "started")
                        preview = null
                        Toast.makeText(context, "Import started", Toast.LENGTH_SHORT).show()
                    }) { Text("Start") }
                },
                dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
            )
        }
        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text("Import history") },
                text = {
                    Column {
                        if (history.isEmpty()) Text("No import jobs recorded yet")
                        history.take(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                },
                confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } },
            )
        }
        if (showRecipes) {
            AlertDialog(
                onDismissRequest = { showRecipes = false },
                title = { Text("Saved recipes") },
                text = {
                    Column {
                        if (recipes.isEmpty()) Text("No recipes saved yet")
                        recipes.take(12).forEach { (name, recipeLinks) ->
                            TextPreferenceWidget(
                                title = name,
                                subtitle = "${recipeLinks.size} links",
                                icon = Icons.Outlined.Rule,
                                onPreferenceClick = {
                                    BatchImportJob.start(context.applicationContext, recipeLinks)
                                    KomikkuFeatureStore.recordImport(context, name, recipeLinks.size, 0, 0, "started")
                                    showRecipes = false
                                    Toast.makeText(context, "Recipe started", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showRecipes = false }) { Text("Close") } },
            )
        }
        if (showStorage) {
            AlertDialog(
                onDismissRequest = { showStorage = false },
                title = { Text("Storage and cleanup review") },
                text = { Text(KomikkuFeatureStore.storageSummary(context)) },
                confirmButton = { TextButton(onClick = { showStorage = false }) { Text("Close") } },
            )
        }
    }
}
