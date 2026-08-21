package eu.kanade.domain.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, bounded local store for the new Komikku workflows.
 *
 * This deliberately lives outside the manga database and WebDAV backup format.
 * Operational records are capped and written atomically so they remain safe for
 * very large libraries and long-running imports.
 */
object KomikkuFeatureStore {
    private const val PREFS = "komikku_feature_store"
    private const val HISTORY_KEY = "import_history"
    private const val RECIPES_KEY = "import_recipes"
    private const val MAX_HISTORY = 80
    private const val MAX_RECIPES = 30
    private const val MAX_RECIPE_LINKS = 2_000
    private const val MAX_FAILED_LINKS = 10_000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun failedFile(context: Context) = File(context.filesDir, "komikku_failed_links.txt")

    fun extractLinks(text: String): List<String> {
        val regex = Regex("https?://[^\\s<>\\\"']+")
        return regex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .filter { link ->
                val lower = link.lowercase(Locale.ROOT)
                lower.contains("nhentai") || lower.contains("e-hentai") ||
                    lower.contains("exhentai") || lower.contains("/g/")
            }
            .distinct()
            .toList()
    }

    fun clipboardText(context: Context): String {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return ""
        val clip: ClipData = manager.primaryClip ?: return ""
        return buildString {
            for (index in 0 until clip.itemCount) {
                if (isNotEmpty()) append('\n')
                append(clip.getItemAt(index).coerceToText(context))
            }
        }
    }

    fun recordImport(context: Context, label: String, total: Int, added: Int, failed: Int, status: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val line = listOf(timestamp, label, total, added, failed, status)
            .joinToString("\t") { it.toString().replace('\n', ' ').replace('\t', ' ') }
        val records = prefs(context).getString(HISTORY_KEY, "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toMutableList()
        records.add(0, line)
        prefs(context).edit().putString(HISTORY_KEY, records.take(MAX_HISTORY).joinToString("\n")).apply()
    }

    fun history(context: Context): List<String> = prefs(context)
        .getString(HISTORY_KEY, "")
        .orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .take(MAX_HISTORY)
        .toList()

    fun rememberFailedLinks(context: Context, links: List<String>) {
        atomicWrite(failedFile(context), links.distinct().take(MAX_FAILED_LINKS).joinToString("\n"))
    }

    fun lastFailedLinks(context: Context): List<String> = runCatching {
        failedFile(context).takeIf(File::exists)?.readLines()?.filter(String::isNotBlank)?.take(MAX_FAILED_LINKS)
            .orEmpty()
    }.getOrDefault(emptyList())

    fun saveRecipe(context: Context, name: String, links: List<String>) {
        if (name.isBlank() || links.isEmpty()) return
        val safeName = name.trim().replace('|', ' ').replace('\n', ' ')
        val safeLinks = links.distinct().take(MAX_RECIPE_LINKS).joinToString(",")
        val records = prefs(context).getString(RECIPES_KEY, "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .filterNot { it.substringBefore('|') == safeName }
            .toMutableList()
        records.add(0, "$safeName|$safeLinks")
        prefs(context).edit().putString(RECIPES_KEY, records.take(MAX_RECIPES).joinToString("\n")).apply()
    }

    fun recipes(context: Context): List<Pair<String, List<String>>> = prefs(context)
        .getString(RECIPES_KEY, "")
        .orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .take(MAX_RECIPES)
        .mapNotNull { line ->
            val name = line.substringBefore('|').trim()
            val links = line.substringAfter('|', "").split(',').filter(String::isNotBlank)
            name.takeIf { it.isNotBlank() }?.let { it to links }
        }
        .toList()

    fun shareText(context: Context, title: String, text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun storageSummary(context: Context): String {
        fun bytes(file: File): Long = runCatching {
            if (file.isFile) file.length() else file.walkTopDown().filter(File::isFile).sumOf { it.length() }
        }.getOrDefault(0L)
        fun format(value: Long): String = when {
            value >= 1024L * 1024L -> "%.1f MB".format(Locale.US, value / (1024.0 * 1024.0))
            value >= 1024L -> "%.1f KB".format(Locale.US, value / 1024.0)
            else -> "$value B"
        }
        return "App files: ${format(bytes(context.filesDir))}\nCache: ${format(bytes(context.cacheDir))}\nFailed links: ${lastFailedLinks(context).size}"
    }

    private fun atomicWrite(file: File, content: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(content)
            if (!temporary.renameTo(file)) {
                file.writeText(content)
                temporary.delete()
            }
        }
    }
}
