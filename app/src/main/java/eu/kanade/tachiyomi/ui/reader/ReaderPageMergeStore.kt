package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * Stores copied reader images for the cross-source chapter merge feature.
 * The page bytes are kept locally, so the source that supplied a page does not
 * need to be installed or reachable when the destination chapter is read.
 */
internal class ReaderPageMergeStore(context: Context) {

    private val root = File(context.filesDir, "reader-page-merges")
    private val pendingDirectory = File(root, "pending")
    private val chaptersDirectory = File(root, "chapters")

    @Synchronized
    fun pendingCount(): Int = pageFiles(pendingDirectory).size

    /** Copies the selected image stream into the persistent pending queue. */
    @Synchronized
    fun queuePage(image: InputStream): Int {
        if (!pendingDirectory.exists() && !pendingDirectory.mkdirs()) {
            throw IOException("Could not create the page merge queue")
        }
        val sequence = nextSequence(pendingDirectory)
        val token = UUID.randomUUID().toString()
        val filename = String.format(Locale.ROOT, "%08d-%s.page", sequence, token)
        val temporaryFile = File(pendingDirectory, ".$filename.tmp")
        val pageFile = File(pendingDirectory, filename)
        try {
            image.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (temporaryFile.length() <= 0L) throw IOException("The selected page image is empty")
            if (!temporaryFile.renameTo(pageFile)) throw IOException("Could not save the selected page image")
            return pageFiles(pendingDirectory).size
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    /**
     * Moves pending page images into a folder keyed by the destination manga and chapter IDs.
     * The move is idempotent: if a process stops after copying but before deleting a pending
     * file, its UUID prevents that image from being inserted twice on retry.
     */
    @Synchronized
    fun appendPendingPages(mangaId: Long, chapterId: Long): List<File> {
        val pending = pageFiles(pendingDirectory)
        if (pending.isEmpty()) return emptyList()

        val destinationDirectory = chapterDirectory(mangaId, chapterId)
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw IOException("Could not create storage for the destination chapter")
        }

        var sequence = nextSequence(destinationDirectory)
        val appended = mutableListOf<File>()
        for (pendingFile in pending) {
            val token = pendingFile.name.substringAfter('-', "").removeSuffix(".page")
            val existingCopy = pageFiles(destinationDirectory).firstOrNull {
                it.name.endsWith("-$token.page")
            }
            if (existingCopy != null) {
                pendingFile.delete()
                continue
            }

            val destination = File(
                destinationDirectory,
                String.format(Locale.ROOT, "%08d-%s.page", sequence++, token),
            )
            transfer(pendingFile, destination)
            appended += destination
        }
        return appended
    }

    /** Returns the merged page images in the order they should be appended. */
    @Synchronized
    fun pagesForChapter(mangaId: Long, chapterId: Long): List<File> =
        pageFiles(chapterDirectory(mangaId, chapterId))

    @Synchronized
    fun clearPending() {
        pageFiles(pendingDirectory).forEach(File::delete)
        pendingDirectory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tmp") }
            ?.forEach(File::delete)
    }

    private fun chapterDirectory(mangaId: Long, chapterId: Long): File =
        File(File(chaptersDirectory, mangaId.toString()), chapterId.toString())

    private fun pageFiles(directory: File): List<File> =
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".page") }
            .sortedBy { it.name }

    private fun nextSequence(directory: File): Long =
        (pageFiles(directory).mapNotNull { it.name.substringBefore('-').toLongOrNull() }.maxOrNull() ?: -1L) + 1L

    private fun transfer(source: File, destination: File) {
        if (source.renameTo(destination)) return

        val temporaryFile = File(destination.parentFile, ".${destination.name}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!temporaryFile.renameTo(destination)) {
                throw IOException("Could not add a selected page to the destination chapter")
            }
            source.delete()
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    companion object {
        const val PAGE_URL_PREFIX = "komikku-merged-page://"
    }
}
