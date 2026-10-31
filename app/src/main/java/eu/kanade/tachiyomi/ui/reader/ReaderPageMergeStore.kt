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
    private val stagingDirectory = File(root, "staging")
    private val chaptersDirectory = File(root, "chapters")

    @Synchronized
    fun pendingCount(): Int = synchronized(STORE_LOCK) {
        cleanupStaleStaging()
        pageFiles(pendingDirectory).size
    }

    /** Copies one selected page directly into the persistent pending queue. */
    @Synchronized
    fun queuePage(image: InputStream): Int = synchronized(STORE_LOCK) {
        ensureDirectory(pendingDirectory, "Could not create the page merge queue")
        writePage(pendingDirectory, image)
        pendingCount()
    }

    /** Creates a private, invisible staging directory for an all-or-nothing chapter copy. */
    @Synchronized
    fun beginChapterBatch(): File = synchronized(STORE_LOCK) {
        cleanupStaleStaging()
        ensureDirectory(stagingDirectory, "Could not create chapter merge staging")
        val batch = File(stagingDirectory, "chapter-${UUID.randomUUID()}")
        ensureDirectory(batch, "Could not create chapter merge staging")
        batch
    }

    /** Adds one copied page to a chapter batch without exposing it in the pending queue. */
    @Synchronized
    fun queuePageInChapterBatch(batch: File, image: InputStream): Int = synchronized(STORE_LOCK) {
        validateStagingBatch(batch)
        writePage(batch, image)
        pageFiles(batch).size
    }

    /**
     * Atomically publishes every staged page by renaming the complete directory into pending.
     * Page sequence numbers are allocated at commit time so concurrent single-page additions
     * cannot interleave with the chapter's original page order.
     */
    @Synchronized
    fun commitChapterBatch(batch: File): Int = synchronized(STORE_LOCK) {
        validateStagingBatch(batch)
        val stagedPages = pageFiles(batch)
        if (stagedPages.isEmpty()) {
            batch.deleteRecursively()
            return@synchronized pendingCount()
        }

        ensureDirectory(pendingDirectory, "Could not create the page merge queue")
        var sequence = nextSequence(pendingDirectory)
        val orderedPages = mutableListOf<Pair<File, File>>()
        for (stagedPage in stagedPages) {
            val token = pageToken(stagedPage)
            val temporaryPage = File(batch, ".reorder-${UUID.randomUUID()}.tmp")
            val orderedPage = File(
                batch,
                String.format(Locale.ROOT, "%08d-%s.page", sequence++, token),
            )
            if (!stagedPage.renameTo(temporaryPage)) {
                throw IOException("Could not order the copied chapter pages")
            }
            orderedPages += temporaryPage to orderedPage
        }
        for ((temporaryPage, orderedPage) in orderedPages) {
            if (!temporaryPage.renameTo(orderedPage)) {
                throw IOException("Could not order the copied chapter pages")
            }
        }

        val committedBatch = File(pendingDirectory, "batch-${UUID.randomUUID()}")
        if (!batch.renameTo(committedBatch)) {
            throw IOException("Could not add the complete chapter to the merge queue")
        }
        pendingCount()
    }

    /** Removes an uncommitted chapter batch after failure or cancellation. */
    @Synchronized
    fun discardChapterBatch(batch: File) = synchronized(STORE_LOCK) {
        validateStagingBatch(batch)
        batch.deleteRecursively()
        Unit
    }

    /**
     * Atomically appends all pending pages to the destination chapter. Page bytes are first
     * copied to a private staging folder; pending originals are deleted only after the complete
     * destination batch has been published. Token checks make retries idempotent.
     */
    @Synchronized
    fun appendPendingPages(mangaId: Long, chapterId: Long): List<File> = synchronized(STORE_LOCK) {
        val pending = pageFiles(pendingDirectory)
        if (pending.isEmpty()) return@synchronized emptyList()

        val destinationDirectory = chapterDirectory(mangaId, chapterId)
        ensureDirectory(destinationDirectory, "Could not create storage for the destination chapter")
        ensureDirectory(stagingDirectory, "Could not create chapter merge staging")

        val existingTokens = pageFiles(destinationDirectory)
            .map { pageToken(it) }
            .toMutableSet()
        val stagedTokens = mutableSetOf<String>()
        var sequence = nextSequence(destinationDirectory)
        val appendBatch = File(stagingDirectory, "append-${UUID.randomUUID()}")
        ensureDirectory(appendBatch, "Could not stage pages for the destination chapter")

        try {
            for (pendingFile in pending) {
                val token = pageToken(pendingFile)
                if (token in existingTokens || token in stagedTokens) continue

                val stagedPage = File(
                    appendBatch,
                    String.format(Locale.ROOT, "%08d-%s.page", sequence++, token),
                )
                copyPageFile(pendingFile, stagedPage)
                stagedTokens += token
            }

            if (stagedTokens.isEmpty()) {
                appendBatch.deleteRecursively()
                pending.filter { pageToken(it) in existingTokens }.forEach(File::delete)
                cleanupEmptyPendingDirectories()
                emptyList()
            } else {
                val committedBatch = File(destinationDirectory, "batch-${UUID.randomUUID()}")
                if (!appendBatch.renameTo(committedBatch)) {
                    throw IOException("Could not add the complete page batch to the destination chapter")
                }

                // The destination now contains the entire batch. If the process stops before these
                // deletes finish, a retry will recognize the existing tokens and remove only residue.
                pending.filter { pageToken(it) in existingTokens || pageToken(it) in stagedTokens }
                    .forEach(File::delete)
                cleanupEmptyPendingDirectories()
                pageFiles(committedBatch)
            }
        } catch (error: Throwable) {
            appendBatch.deleteRecursively()
            throw error
        }
    }

    /** Returns the merged page images in the order they should be appended. */
    @Synchronized
    fun pagesForChapter(mangaId: Long, chapterId: Long): List<File> = synchronized(STORE_LOCK) {
        pageFiles(chapterDirectory(mangaId, chapterId))
    }

    @Synchronized
    fun clearPending() = synchronized(STORE_LOCK) {
        if (!pendingDirectory.exists()) return@synchronized pendingCount()

        val retiredDirectory = File(root, "pending-cleared-${UUID.randomUUID()}")
        if (!pendingDirectory.renameTo(retiredDirectory)) {
            throw IOException("Could not clear the page merge queue")
        }
        if (!retiredDirectory.deleteRecursively()) {
            throw IOException("The queue was cleared, but some page files could not yet be removed")
        }
        pendingCount()
    }

    private fun chapterDirectory(mangaId: Long, chapterId: Long): File =
        File(File(chaptersDirectory, mangaId.toString()), chapterId.toString())

    private fun writePage(directory: File, image: InputStream) {
        val sequence = nextSequence(directory)
        val token = UUID.randomUUID().toString()
        val filename = String.format(Locale.ROOT, "%08d-%s.page", sequence, token)
        val temporaryFile = File(directory, ".$filename.tmp")
        val pageFile = File(directory, filename)
        try {
            image.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (temporaryFile.length() <= 0L) throw IOException("The selected page image is empty")
            if (!temporaryFile.renameTo(pageFile)) throw IOException("Could not save the selected page image")
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun copyPageFile(source: File, destination: File) {
        val temporaryFile = File(destination.parentFile, ".${destination.name}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (temporaryFile.length() <= 0L) throw IOException("A queued page image is empty")
            if (!temporaryFile.renameTo(destination)) {
                throw IOException("Could not stage a page for the destination chapter")
            }
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun pageToken(file: File): String =
        file.name.substringAfter('-', "").removeSuffix(".page")

    private fun pageFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        return directory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".page") }
            .sortedBy { it.name }
            .toList()
    }

    private fun nextSequence(directory: File): Long =
        (pageFiles(directory).mapNotNull { it.name.substringBefore('-').toLongOrNull() }.maxOrNull() ?: -1L) + 1L

    private fun ensureDirectory(directory: File, errorMessage: String) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException(errorMessage)
    }

    private fun validateStagingBatch(batch: File) {
        if (!batch.exists() || !batch.isDirectory || batch.canonicalFile.parentFile != stagingDirectory.canonicalFile) {
            throw IOException("The chapter merge staging session is no longer available")
        }
    }

    private fun cleanupStaleStaging() {
        val staleBefore = System.currentTimeMillis() - STALE_STAGING_AGE_MS
        stagingDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.lastModified() < staleBefore }
            .forEach { it.deleteRecursively() }
        pendingDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".page.tmp") }
            .forEach { it.delete() }
        root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("pending-cleared-") }
            .forEach { it.deleteRecursively() }
    }

    private fun cleanupEmptyPendingDirectories() {
        pendingDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.listFiles().isNullOrEmpty() }
            .forEach(File::delete)
    }

    companion object {
        const val PAGE_URL_PREFIX = "komikku-merged-page://"
        private val STORE_LOCK = Any()
        private const val STALE_STAGING_AGE_MS = 6L * 60 * 60 * 1000
    }
}
