package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.os.SystemClock
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(private val reader: ArchiveReader) : PageLoader() {
    private val mutex = Mutex()
    private val context: Application by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()
    private val archiveReaderMode = readerPreferences.archiveReaderMode().get()
    private val tmpDir = File(context.externalCacheDir, "reader_${reader.archiveHashCode}_${System.nanoTime()}")

    init {
        reader.wrongPassword?.let { wrongPassword ->
            if (wrongPassword) {
                error("Incorrect archive password")
            }
        }
        if (archiveReaderMode == ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK) {
            tmpDir.mkdirs()
        }
    }

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val startedAt = SystemClock.elapsedRealtime()
        val pages = reader.useEntries { entries ->
            entries
                .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                .sortedWith { first, second -> first.name.compareToCaseInsensitiveNaturalOrder(second.name) }
                .mapIndexed { index, entry ->
                    val imageBytesDeferred: Deferred<ByteArray>? =
                        if (archiveReaderMode == ReaderPreferences.ArchiveReaderMode.LOAD_INTO_MEMORY) {
                            CoroutineScope(Dispatchers.IO).async {
                                mutex.withLock {
                                    reader.getInputStream(entry.name)!!.buffered().use { stream ->
                                        stream.readBytes()
                                    }
                                }
                            }
                        } else {
                            null
                        }
                    val imageBytes by lazy { runBlocking { imageBytesDeferred?.await() } }
                    val streamFn: () -> InputStream = when (archiveReaderMode) {
                        ReaderPreferences.ArchiveReaderMode.LOAD_INTO_MEMORY -> {
                            { imageBytes?.copyOf()?.inputStream() ?: reader.getInputStream(entry.name)!! }
                        }
                        ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK -> {
                            { openCachedEntry(entry.name) }
                        }
                        else -> {
                            { reader.getInputStream(entry.name)!! }
                        }
                    }
                    ReaderPage(index).apply {
                        stream = streamFn
                        status = Page.State.Ready
                    }
                }
                .toList()
        }
        logcat {
            "Reader archive page list ready (pages=${pages.size}, mode=$archiveReaderMode, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms)"
        }
        return pages
    }

    private fun openCachedEntry(entryName: String): InputStream {
        val cachedFile = File(tmpDir, entryName.substringAfterLast('/'))
        runBlocking(Dispatchers.IO) {
            mutex.withLock {
                if (!cachedFile.isFile) {
                    cachedFile.parentFile?.mkdirs()
                    try {
                        reader.getInputStream(entryName)!!.buffered().use { input ->
                            cachedFile.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                    } catch (error: Throwable) {
                        cachedFile.delete()
                        throw error
                    }
                }
            }
        }
        return cachedFile.inputStream()
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
        if (archiveReaderMode == ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK && tmpDir.exists()) {
            CoroutineScope(Dispatchers.IO).launch { tmpDir.deleteRecursively() }
        }
    }
}
