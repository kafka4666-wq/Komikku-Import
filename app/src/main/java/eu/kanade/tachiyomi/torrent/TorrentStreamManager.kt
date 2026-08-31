package eu.kanade.tachiyomi.torrent

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.jsoup.Jsoup
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.coroutines.cancellation.CancellationException

object TorrentImportControl {
    private const val PREFS = "torrent_import_controls"
    private const val PAUSED = "paused"
    private const val CANCELLED = "cancelled"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(PAUSED, false)
    fun isCancelled(context: Context): Boolean = prefs(context).getBoolean(CANCELLED, false)
    fun reset(context: Context) = prefs(context).edit().putBoolean(PAUSED, false).putBoolean(CANCELLED, false).apply()
    fun pause(context: Context) = prefs(context).edit().putBoolean(PAUSED, true).apply()
    fun resume(context: Context) = prefs(context).edit().putBoolean(PAUSED, false).apply()
    fun cancel(context: Context) = prefs(context).edit().putBoolean(CANCELLED, true).putBoolean(PAUSED, false).apply()

    suspend fun awaitResume(context: Context) {
        while (isPaused(context) && !isCancelled(context)) delay(250)
    }
}

/**
 * Selective Torrent reader for CBZ/ZIP files.
 *
 * Catalog metadata is retained separately from the transient torrent workspace. A cover or reader
 * request may retrieve only the ZIP directory, local header and requested image's torrent pieces.
 * Each request retrieves only the ZIP directory, local header, and image payload pieces needed
 * for that cover/page. The workspace is bounded temporary cache data; there is no full-file fallback
 * and no list-wide cover prefetch path in this manager.
 */
class TorrentStreamManager(
    private val context: Context,
) {
    private companion object {
        const val LOG_TAG = "TorrentStream"
        const val METADATA_FILE = "torrent.metainfo"
        const val BOOKS_FILE = "torrent.books.json"
        const val RECORD_FILE = "torrent.record.json"
        const val LIBRARY_FILE = "torrent.library.json"
        const val ZIP_EOCD_SCAN_BYTES = 65_557L
        const val LOCAL_HEADER_SCAN_BYTES = 65_536L
        const val MAX_ZIP_DIRECTORY_BYTES = 4L * 1024L * 1024L
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_COMPRESSED_PAGE_BYTES = 24L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_PAGE_BYTES = 48L * 1024L * 1024L
        // libtorrent4j 2.1.0-39 uses disk-backed storage for completed pieces. Keep the
        // workspace strictly bounded and remove it when the active request ends; this is not a
        // claim of memory-only operation.
        const val TEMPORARY_PIECE_BUDGET_BYTES = 32L * 1024L * 1024L
        // These are independent guards. A healthy transfer is allowed to continue while pieces
        // are arriving; the old two-by-five-second retry loop repeatedly abandoned live requests.
        const val INITIAL_PEER_TIMEOUT_MILLIS = 30_000L
        const val STALLED_TRANSFER_TIMEOUT_MILLIS = 30_000L
        const val PIECE_DEADLINE_MILLIS = 15_000
        // Includes native session setup and ZIP parsing around the selected-piece wait. This is a
        // final guard: a blocked native call must never leave the UI spinner forever.
        const val REQUEST_TOTAL_TIMEOUT_MILLIS = 180_000L
        const val INDEX_CACHE_ENTRIES = 12
    }

    private val lock = Mutex()
    private val sessionLock = Any()
    private val catalogRestoreLock = Any()
    private val catalogs = ConcurrentHashMap<String, TorrentCatalog>()
    private val persistedBookIndexes = ConcurrentHashMap<String, List<TorrentBook>>()
    private val startedCatalogs = ConcurrentHashMap.newKeySet<String>()
    private val archiveIndexes = LinkedHashMap<String, ArchiveIndex>(INDEX_CACHE_ENTRIES, 0.75f, true)
    private val archiveIndexLock = Any()
    private val streamLocks = ConcurrentHashMap<String, Mutex>()
    private val streamSemaphore = Semaphore(1)
    private val retryAfterMillis = ConcurrentHashMap<String, Long>()
    // Native handle removal is performed after the response bytes have been copied to memory so a
    // slow libtorrent shutdown cannot block Coil or the reader from receiving a real result.
    private val cleanupScope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob())
    private val activeStreamLock = Any()
    private var activeStream: ActiveStream? = null
    private val catalogRoot = File(context.filesDir, "torrent_catalog").apply { mkdirs() }
    private val torrentRoot = File(context.cacheDir, "torrent_stream").apply { mkdirs() }
    private val metadataScratch = File(context.cacheDir, "torrent_metadata").apply { mkdirs() }

    @Volatile
    private var session: SessionManager? = null

    data class TorrentBook(
        val key: String,
        val torrentHash: String,
        val fileIndex: Int,
        val path: String,
        val title: String,
        val artist: String?,
        val size: Long,
    )

    data class TorrentImportResult(
        val torrentHash: String,
        val torrentName: String,
        val books: List<TorrentBook>,
        val totalBytes: Long,
    )

    private data class ZipEntryData(
        val name: String,
        val flags: Int,
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    private data class ArchiveIndex(
        val pageNames: List<String>,
        val coverName: String,
        val entries: Map<String, ZipEntryData>,
    )

    private data class EndOfCentralDirectory(
        val entryCount: Int,
        val directoryOffset: Long,
        val directorySize: Long,
    )

    private data class TorrentCatalog(
        val hash: String,
        val info: TorrentInfo,
        val books: List<TorrentBook>,
        val saveDirectory: File,
        val sourceInput: String?,
    )

    private data class ActiveStream(
        val bookKey: String,
        val catalog: TorrentCatalog,
        val handle: TorrentHandle,
        val archive: File,
        val requestedPieces: MutableSet<Int> = linkedSetOf(),
        var nextDiagnosticNanos: Long = System.nanoTime(),
    )

    suspend fun importLink(input: String): TorrentImportResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val normalized = input.trim()
        require(normalized.isNotEmpty()) { "Enter a magnet, .torrent URL, or Sukebei/Nyaa detail URL." }
        lock.withLock {
            val torrentBytes = resolveMetainfo(normalized)
            val info = TorrentInfo(torrentBytes)
            require(info.isValid) { "The supplied link did not contain valid torrent metadata." }
            val hash = info.infoHash().toHex().lowercase(Locale.ROOT)
            val name = info.files().name().ifBlank { "Torrent $hash" }
            val books = buildBooks(hash, info)
            require(books.isNotEmpty()) { "No supported CBZ or ZIP doujin archives were found in this torrent." }

            val metadataDirectory = catalogDirectory(hash).apply { mkdirs() }
            File(metadataDirectory, METADATA_FILE).writeBytes(torrentBytes)
            writeBookIndex(hash, books)
            writeCatalogRecord(hash, normalized, books)
            val catalog = TorrentCatalog(hash, info, books, File(torrentRoot, hash), normalized)
            catalogs[hash] = catalog

            // Deliberately do not create a libtorrent handle here. A large catalog import must be
            // metadata-only and cannot allocate archive files, priority windows or cover jobs.
            TorrentImportResult(hash, name, books, info.totalSize())
        }
    }

    fun allBooks(): List<TorrentBook> {
        // Synchronous source browsing must stay metadata-only. It never starts libtorrent or a
        // cover transfer, which keeps the Library source tabs stable for large Torrent catalogs.
        migrateLegacyCatalogs()
        val persisted = catalogDirectories().flatMap { loadBookIndex(it.name) }
        return (catalogs.values.flatMap { it.books } + persisted).distinctBy { it.key }
    }

    fun findBook(key: String): TorrentBook? {
        val hash = key.substringBefore('/')
        return catalogs[hash]?.books?.firstOrNull { it.key == key }
            ?: loadBookIndex(hash).firstOrNull { it.key == key }
            ?: restoreCatalog(hash)?.books?.firstOrNull { it.key == key }
    }

    /** Kept for importer compatibility; Torrent covers are no longer written to a device cache. */
    fun registerLibraryManga(bookKey: String, mangaId: Long) {
        val hash = bookKey.substringBefore('/')
        val file = File(catalogDirectory(hash).apply { mkdirs() }, LIBRARY_FILE)
        val current = runCatching { JSONObject(if (file.isFile) file.readText() else "{}").apply { } }
            .getOrDefault(JSONObject())
        current.put(bookKey, mangaId)
        atomicWrite(file, current.toString())
    }

    suspend fun pageNames(bookKey: String): List<String> = withBookStream(bookKey) {
        val book = requireNotNull(restoreBook(bookKey)) { "Torrent session could not be restored; re-import this torrent." }
        ensureArchiveIndex(book).pageNames
    }

    suspend fun readCover(bookKey: String): Pair<ByteArray, String> = withBookStream(bookKey) {
        val book = requireNotNull(restoreBook(bookKey)) { "Torrent session could not be restored; re-import this torrent." }
        val index = ensureArchiveIndex(book)
        readEntry(book, index.coverName).also { (bytes, _) ->
            Log.d(LOG_TAG, "cover-ready book=$bookKey bytes=${bytes.size}")
        }
    }

    suspend fun readPage(bookKey: String, entryName: String): Pair<ByteArray, String> = withBookStream(bookKey) {
        val book = requireNotNull(restoreBook(bookKey)) { "Torrent session could not be restored; re-import this torrent." }
        readEntry(book, entryName).also { (bytes, _) ->
            Log.d(LOG_TAG, "page-ready book=$bookKey entry=$entryName bytes=${bytes.size}")
        }
    }

    private suspend fun <T> withBookStream(bookKey: String, block: suspend () -> T): T {
        val bookLock = streamLocks.getOrPut(bookKey) { Mutex() }
        return streamSemaphore.withPermit {
            bookLock.withLock {
                try {
                    val retryAfter = retryAfterMillis[bookKey] ?: 0L
                    require(System.currentTimeMillis() >= retryAfter) {
                        "Torrent data is temporarily unavailable. Retry this book in a few seconds; no archive was saved."
                    }
                    withTimeout(REQUEST_TOTAL_TIMEOUT_MILLIS) {
                        block()
                    }.also { retryAfterMillis.remove(bookKey) }
                } catch (error: TimeoutCancellationException) {
                    retryAfterMillis.remove(bookKey)
                    Log.w(LOG_TAG, "stream-timeout book=$bookKey totalMs=$REQUEST_TOTAL_TIMEOUT_MILLIS")
                    throw IllegalStateException(
                        "Torrent request timed out after 180 seconds. Retry this page or cover; no archive was downloaded in full.",
                        error,
                    )
                } catch (error: Throwable) {
                    retryAfterMillis.remove(bookKey)
                    throw error
                } finally {
                    // The response has already been copied to memory by this point. Removing the
                    // torrent and its directory prevents cover/page reads from accumulating.
                    releaseActiveStream(bookKey)
                }
            }
        }
    }

    suspend fun pauseAll() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        session?.pause()
    }

    suspend fun resumeAll() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        session?.resume()
    }

    suspend fun clearTemporaryCache() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        migrateLegacyCatalogs()
        releaseActiveStream()
        torrentRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        metadataScratch.listFiles().orEmpty().forEach { it.deleteRecursively() }
        synchronized(archiveIndexLock) { archiveIndexes.clear() }
    }

    private suspend fun resolveMetainfo(input: String): ByteArray {
        if (input.startsWith("magnet:", true)) {
            return fetchMagnetMetainfo(input)
        }
        val response = Injekt.get<NetworkHelper>().client.newCall(Request.Builder().url(input).build()).execute()
        response.use {
            require(it.isSuccessful) { "Torrent link returned HTTP ${it.code}." }
            val bytes = it.body?.bytes() ?: error("Torrent link returned an empty response.")
            val contentType = it.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val looksLikeHtml = contentType.contains("html") || input.contains("/view/", true)
            if (!looksLikeHtml && !input.endsWith(".html", true)) return bytes
            val document = Jsoup.parse(bytes.toString(Charsets.UTF_8), input)
            val magnet = document.selectFirst("a[href^=magnet:]")?.attr("href")
                ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            if (magnet.isNullOrBlank()) {
                val id = Regex("/view/(\\d+)").find(input)?.groupValues?.getOrNull(1)
                val fallback = id?.let { "${input.substringBefore("/view/")}/download/$it.torrent" }
                if (fallback != null) return resolveMetainfo(fallback)
                error("No magnet or .torrent link was found on the supplied Nyaa/Sukebei page.")
            }
            return fetchMagnetMetainfo(magnet)
        }
    }

    private fun fetchMagnetMetainfo(magnet: String): ByteArray = try {
        ensureSession().fetchMagnet(magnet, 45, metadataScratch)
            ?: error("Magnet metadata could not be resolved from the current peers/trackers.")
    } finally {
        metadataScratch.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    private fun buildBooks(hash: String, info: TorrentInfo): List<TorrentBook> {
        val storage = info.files()
        return buildList {
            for (index in 0 until storage.numFiles()) {
                val path = storage.filePath(index)
                val lower = path.lowercase(Locale.ROOT)
                if (!lower.endsWith(".cbz") && !lower.endsWith(".zip")) continue
                if (storage.fileSize(index) <= 0L) continue
                val fileName = storage.fileName(index)
                val title = fileName.substringBeforeLast('.', fileName).trim().ifBlank { fileName }
                val artist = Regex("^\\[([^]]+)]").find(title)?.groupValues?.getOrNull(1)?.trim()
                add(
                    TorrentBook(
                        key = "$hash/$index",
                        torrentHash = hash,
                        fileIndex = index,
                        path = path,
                        title = title,
                        artist = artist,
                        size = storage.fileSize(index),
                    ),
                )
            }
        }
    }

    private fun catalogDirectory(hash: String): File = File(catalogRoot, hash)
    private fun legacyCatalogDirectory(hash: String): File = File(torrentRoot, hash)

    private fun catalogDirectories(): List<File> =
        (catalogRoot.listFiles().orEmpty().filter(File::isDirectory) +
            torrentRoot.listFiles().orEmpty().filter(File::isDirectory))
            .distinctBy { it.name }

    private fun writeCatalogRecord(hash: String, sourceInput: String, books: List<TorrentBook>) {
        val record = JSONObject().apply {
            put("hash", hash)
            put("sourceInput", sourceInput)
            put("updatedAt", System.currentTimeMillis())
            put("books", books.size)
        }
        atomicWrite(File(catalogDirectory(hash).apply { mkdirs() }, RECORD_FILE), record.toString())
    }

    private fun atomicWrite(file: File, text: String) {
        atomicWriteBytes(file, text.toByteArray(Charsets.UTF_8))
    }

    private fun atomicWriteBytes(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Could not persist Torrent restoration metadata.")
        }
    }

    private fun writeBookIndex(hash: String, books: List<TorrentBook>) {
        val index = JSONArray()
        books.forEach { book ->
            index.put(JSONObject().apply {
                put("key", book.key)
                put("hash", book.torrentHash)
                put("fileIndex", book.fileIndex)
                put("path", book.path)
                put("title", book.title)
                put("artist", book.artist ?: JSONObject.NULL)
                put("size", book.size)
            })
        }
        val file = File(catalogDirectory(hash).apply { mkdirs() }, BOOKS_FILE)
        atomicWrite(file, index.toString())
        persistedBookIndexes[hash] = books
    }

    private fun loadBookIndex(hash: String): List<TorrentBook> {
        persistedBookIndexes[hash]?.let { return it }
        val file = listOf(
            File(catalogDirectory(hash), BOOKS_FILE),
            File(legacyCatalogDirectory(hash), BOOKS_FILE),
        ).firstOrNull { it.isFile } ?: return emptyList()
        return runCatching {
            val json = JSONArray(file.readText())
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        TorrentBook(
                            key = item.getString("key"),
                            torrentHash = item.getString("hash"),
                            fileIndex = item.getInt("fileIndex"),
                            path = item.getString("path"),
                            title = item.getString("title"),
                            artist = item.optString("artist").takeIf { it.isNotBlank() && it != "null" },
                            size = item.getLong("size"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList()).also { persistedBookIndexes[hash] = it }
    }

    private fun restoreCatalog(hash: String): TorrentCatalog? = synchronized(catalogRestoreLock) {
        catalogs[hash]?.let { return@synchronized it }
        val metadata = listOf(
            File(catalogDirectory(hash), METADATA_FILE),
            File(legacyCatalogDirectory(hash), METADATA_FILE),
        ).firstOrNull { it.isFile && it.length() > 0L } ?: return@synchronized null
        runCatching {
            val info = TorrentInfo(metadata.readBytes())
            require(info.isValid) { "Persisted torrent metadata is invalid." }
            val books = loadBookIndex(hash).ifEmpty { buildBooks(hash, info) }
            require(books.isNotEmpty()) { "Persisted torrent contains no supported archives." }
            TorrentCatalog(hash, info, books, File(torrentRoot, hash), loadSourceInput(hash)).also { catalog ->
                catalogs[hash] = catalog
            }
        }.getOrNull()
    }

    private fun loadSourceInput(hash: String): String? {
        val file = File(catalogDirectory(hash), RECORD_FILE)
        return runCatching {
            JSONObject(file.takeIf { it.isFile }?.readText() ?: return@runCatching null)
                .optString("sourceInput")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun restoreBook(bookKey: String): TorrentBook? {
        val hash = bookKey.substringBefore('/')
        val existing = findBook(bookKey)
        if (existing != null && catalogs.containsKey(hash)) return existing
        restoreCatalog(hash)?.books?.firstOrNull { it.key == bookKey }?.let { return it }
        val sourceInput = loadSourceInput(hash)
        if (!sourceInput.isNullOrBlank() && sourceInput.startsWith("magnet:", true)) {
            val bytes = runCatching { fetchMagnetMetainfo(sourceInput) }.getOrNull() ?: return null
            val info = runCatching { TorrentInfo(bytes).also { require(it.isValid) } }.getOrNull() ?: return null
            val resolvedHash = info.infoHash().toHex().lowercase(Locale.ROOT)
            if (resolvedHash != hash) return null
            val books = buildBooks(hash, info)
            if (books.isEmpty()) return null
            val directory = catalogDirectory(hash).apply { mkdirs() }
            atomicWriteBytes(File(directory, METADATA_FILE), bytes)
            writeBookIndex(hash, books)
            writeCatalogRecord(hash, sourceInput, books)
            val catalog = TorrentCatalog(hash, info, books, File(torrentRoot, hash), sourceInput)
            catalogs[hash] = catalog
            return books.firstOrNull { it.key == bookKey }
        }
        return null
    }

    private fun migrateLegacyCatalogs() {
        torrentRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            migrateLegacyCatalog(directory.name)
        }
    }

    private fun migrateLegacyCatalog(hash: String) {
        val legacy = legacyCatalogDirectory(hash)
        val destination = catalogDirectory(hash)
        if (!legacy.isDirectory) return
        val oldMetadata = File(legacy, METADATA_FILE)
        val oldBooks = File(legacy, BOOKS_FILE)
        if (oldMetadata.isFile && oldBooks.isFile) {
            destination.mkdirs()
            oldMetadata.copyTo(File(destination, METADATA_FILE), overwrite = false)
            oldBooks.copyTo(File(destination, BOOKS_FILE), overwrite = false)
        }
        // A directory from the previous implementation may contain whole CBZ/ZIP files or sparse
        // payloads. Once its small catalog copies are safe in filesDir, remove every legacy byte.
        if (File(destination, METADATA_FILE).isFile && File(destination, BOOKS_FILE).isFile) {
            legacy.deleteRecursively()
        }
    }

    private suspend fun ensureArchiveIndex(book: TorrentBook): ArchiveIndex {
        cachedArchiveIndex(book.key)?.let { return it }
        val active = openActiveStream(book)
        val eocd = ensureCentralDirectory(active, book)
        val index = parseArchiveIndex(book, active.archive, eocd)
        cacheArchiveIndex(book.key, index)
        return index
    }

    private suspend fun readEntry(book: TorrentBook, entryName: String): Pair<ByteArray, String> {
        val index = ensureArchiveIndex(book)
        val entry = index.entries[entryName] ?: error("Torrent page is not present in the archive.")
        val active = openActiveStream(book)
        val payload = readSelectedEntry(active, book, entry)
        return payload to imageType(entry.name)
    }

    private suspend fun openActiveStream(book: TorrentBook): ActiveStream {
        synchronized(activeStreamLock) {
            activeStream?.takeIf { it.bookKey == book.key }?.let { return it }
        }
        releaseActiveStream()
        val catalog = catalogs[book.torrentHash]
            ?: restoreCatalog(book.torrentHash)
            ?: error("Torrent session metadata is unavailable. Re-import the torrent to restore its session.")
        migrateLegacyCatalog(catalog.hash)
        // A previous request removes its transient workspace; recreate only the bounded cache
        // directory before attaching a fresh native handle.
        catalog.saveDirectory.mkdirs()
        startSelectiveSession(catalog)
        val handle = waitForHandle(catalog)
        // New downloads are auto-managed by libtorrent by default. A short-lived interactive
        // request must opt out of that queue, otherwise it can remain paused until the request
        // window expires without contacting any peers.
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        handle.queuePositionTop()
        runCatching { handle.forceReannounce() }
        Log.d(LOG_TAG, "torrent-announce book=${book.key}")
        handle.resume()
        Log.d(LOG_TAG, "stream-start book=${book.key} file=${book.fileIndex} bytes=${book.size}")
        val archive = File(catalog.info.files().filePath(book.fileIndex, catalog.saveDirectory.absolutePath))
        val active = ActiveStream(book.key, catalog, handle, archive)
        activateOnlyRequestedArchivePieces(active, book)
        synchronized(activeStreamLock) { activeStream = active }
        return active
    }

    /**
     * Use the activation sequence from the older APK that successfully opened torrent books.
     * Explicit range and deadline requests still limit what is waited for and read by the app.
     */
    private fun activateOnlyRequestedArchivePieces(active: ActiveStream, book: TorrentBook) {
        active.handle.filePriority(book.fileIndex, Priority.DEFAULT)
        active.handle.filePriority(book.fileIndex, Priority.TOP_PRIORITY)
        active.handle.resume()
        Log.d(LOG_TAG, "archive-activated book=${book.key} mode=legacy-readable fileIndex=${book.fileIndex}")
    }

    private fun startSelectiveSession(catalog: TorrentCatalog) {
        val manager = ensureSessionBlocking()
        if (manager.find(catalog.info.infoHash()) != null) {
            startedCatalogs += catalog.hash
            return
        }
        catalog.saveDirectory.mkdirs()
        val priorities = Priority.array(Priority.IGNORE, catalog.info.files().numFiles())
        manager.download(
            catalog.info,
            catalog.saveDirectory,
            null,
            priorities,
            null,
            TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE).or_(TorrentFlags.UPLOAD_MODE),
        )
        startedCatalogs += catalog.hash
    }

    private suspend fun ensureCentralDirectory(active: ActiveStream, book: TorrentBook): EndOfCentralDirectory {
        val tailLength = minOf(book.size, ZIP_EOCD_SCAN_BYTES)
        val tailOffset = book.size - tailLength
        return try {
            requestRange(active, book, tailOffset, tailLength, "ZIP directory")
            val eocd = parseEndOfCentralDirectory(book, active.archive)
            require(eocd.directorySize in 1..MAX_ZIP_DIRECTORY_BYTES) {
                "This CBZ/ZIP directory is too large for low-storage streaming. Open a different archive."
            }
            requestRange(active, book, eocd.directoryOffset, eocd.directorySize, "ZIP directory")
            eocd
        } catch (rangeError: Throwable) {
            if (rangeError is CancellationException) throw rangeError
            // Never promote or wait for the complete archive. A directory failure is a bounded
            // request failure; the caller can retry while the catalog metadata remains intact.
            Log.w(LOG_TAG, "directory-range-failed book=${book.key}: ${rangeError.message}")
            throw rangeError
        }
    }

    private fun parseEndOfCentralDirectory(book: TorrentBook, archive: File): EndOfCentralDirectory {
        require(archive.isFile) { "Torrent ZIP directory has not arrived yet." }
        RandomAccessFile(archive, "r").use { raf ->
            val length = raf.length()
            val scanLength = minOf(length, ZIP_EOCD_SCAN_BYTES).toInt()
            require(scanLength >= 22) { "The selected archive does not contain a readable ZIP directory." }
            val scanStart = length - scanLength
            val tail = ByteArray(scanLength)
            raf.seek(scanStart)
            raf.readFully(tail)
            val eocd = findSignatureFromEnd(tail, 0x06054b50L)
            require(eocd >= 0) { "This ZIP/CBZ layout cannot be selectively streamed." }
            val entries = readLeShort(tail, eocd + 10)
            val directorySize = readLeInt(tail, eocd + 12)
            val directoryOffset = readLeInt(tail, eocd + 16)
            require(entries != 0xFFFF && directorySize != 0xFFFFFFFFL && directoryOffset != 0xFFFFFFFFL) {
                "ZIP64 archives are not supported by low-storage streaming."
            }
            require(entries in 1..MAX_ZIP_ENTRIES) { "This ZIP/CBZ has too many entries for low-storage streaming." }
            require(directoryOffset >= 0L && directoryOffset + directorySize <= book.size) {
                "This ZIP/CBZ directory is malformed."
            }
            return EndOfCentralDirectory(entries, directoryOffset, directorySize)
        }
    }

    private fun parseArchiveIndex(book: TorrentBook, archive: File, eocd: EndOfCentralDirectory): ArchiveIndex {
        val entries = LinkedHashMap<String, ZipEntryData>()
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(eocd.directoryOffset)
            repeat(eocd.entryCount) {
                require(raf.readLeInt() == 0x02014b50L) { "This ZIP/CBZ directory is malformed." }
                raf.skipBytes(4)
                val flags = raf.readLeShort()
                val compression = raf.readLeShort()
                raf.skipBytes(4)
                raf.skipBytes(4)
                val compressedSize = raf.readLeInt()
                val uncompressedSize = raf.readLeInt()
                val nameLength = raf.readLeShort()
                val extraLength = raf.readLeShort()
                val commentLength = raf.readLeShort()
                raf.skipBytes(8)
                val localOffset = raf.readLeInt()
                val nameBytes = ByteArray(nameLength)
                raf.readFully(nameBytes)
                val name = String(nameBytes, if ((flags and 0x800) != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
                raf.skipBytes(extraLength + commentLength)
                if (!isImage(name)) return@repeat
                require(compressedSize != 0xFFFFFFFFL && uncompressedSize != 0xFFFFFFFFL && localOffset != 0xFFFFFFFFL) {
                    "ZIP64 image entries are not supported by low-storage streaming."
                }
                require(localOffset >= 0L && localOffset < book.size) { "This ZIP/CBZ entry is malformed." }
                entries[name] = ZipEntryData(name, flags, compression, compressedSize, uncompressedSize, localOffset)
            }
        }
        val pageNames = entries.keys.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        require(pageNames.isNotEmpty()) { "The selected archive contains no readable image pages." }
        return ArchiveIndex(pageNames, pageNames.first(), pageNames.associateWith { entries.getValue(it) })
    }

    private suspend fun readSelectedEntry(
        active: ActiveStream,
        book: TorrentBook,
        entry: ZipEntryData,
    ): ByteArray {
        require((entry.flags and 0x1) == 0) { "Encrypted ZIP/CBZ pages are not supported." }
        require(entry.compressedSize in 1..MAX_COMPRESSED_PAGE_BYTES) {
            "This page is too large for low-storage Torrent streaming."
        }
        require(entry.uncompressedSize in 1..MAX_UNCOMPRESSED_PAGE_BYTES) {
            "This page is too large to safely display from the Torrent stream."
        }
        requestRange(active, book, entry.localHeaderOffset, LOCAL_HEADER_SCAN_BYTES.coerceAtMost(book.size - entry.localHeaderOffset), "page header")
        val dataOffset = readLocalDataOffset(active.archive, entry, book)
        requestRange(active, book, dataOffset, entry.compressedSize, "page image")
        val compressedBytes = readArchiveRange(active.archive, dataOffset, entry.compressedSize)
        return when (entry.compressionMethod) {
            0 -> compressedBytes
            8 -> InflaterInputStream(compressedBytes.inputStream(), Inflater(true)).use {
                readBounded(it, MAX_UNCOMPRESSED_PAGE_BYTES)
            }
            else -> error("This ZIP/CBZ uses an unsupported image compression method.")
        }
    }

    private fun readLocalDataOffset(archive: File, entry: ZipEntryData, book: TorrentBook): Long {
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(entry.localHeaderOffset)
            require(raf.readLeInt() == 0x04034b50L) { "Torrent page header is not available yet." }
            raf.seek(entry.localHeaderOffset + 6)
            val flags = raf.readLeShort()
            val compression = raf.readLeShort()
            require((flags and 0x1) == 0 && compression == entry.compressionMethod) {
                "This ZIP/CBZ page header is unsupported."
            }
            raf.seek(entry.localHeaderOffset + 26)
            val nameLength = raf.readLeShort()
            val extraLength = raf.readLeShort()
            val dataOffset = entry.localHeaderOffset + 30L + nameLength + extraLength
            require(dataOffset + entry.compressedSize <= book.size) { "This ZIP/CBZ page range is malformed." }
            return dataOffset
        }
    }

    private suspend fun requestRange(
        active: ActiveStream,
        book: TorrentBook,
        offset: Long,
        length: Long,
        purpose: String,
    ) {
        require(length > 0L && offset >= 0L && offset + length <= book.size) { "Requested Torrent range is invalid." }
        val firstPiece = active.catalog.info.mapFile(book.fileIndex, offset, 1).piece()
        val lastPiece = active.catalog.info.mapFile(book.fileIndex, offset + length - 1L, 1).piece()
        val requested = (firstPiece..lastPiece).toList()
        val newPieces = requested.filterNot(active.requestedPieces::contains)
        val pieceLength = active.catalog.info.pieceLength().toLong().coerceAtLeast(1L)
        val prospectiveBytes = (active.requestedPieces.size.toLong() + newPieces.size.toLong()) * pieceLength
        require(prospectiveBytes <= TEMPORARY_PIECE_BUDGET_BYTES) {
            "This $purpose needs more than the 32 MiB temporary streaming limit. The archive will not be downloaded in full."
        }
        // The older working APK promoted the active archive file before individual pieces. This
        // keeps libtorrent materializing the sparse file; the range and piece deadline still limit
        // the data needed before ZIP parsing and page decoding can proceed.
        active.handle.filePriority(book.fileIndex, Priority.TOP_PRIORITY)
        newPieces.forEach { piece ->
            active.handle.piecePriority(piece, Priority.TOP_PRIORITY)
            // A deadline is the libtorrent streaming primitive: it moves each requested piece to
            // the front of the picker without making the rest of the archive eligible.
            active.handle.setPieceDeadline(piece, PIECE_DEADLINE_MILLIS)
            active.requestedPieces += piece
        }
        // This only narrows the sequential picker window; it does not replace the explicit piece
        // priorities. All required pieces are submitted before waiting, so the engine can request
        // them concurrently from different peers.
        active.handle.setSequentialRange(firstPiece, lastPiece)
        active.handle.resume()
        active.handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
        Log.d(LOG_TAG, "range-request purpose=$purpose book=${book.key} fileIndex=${book.fileIndex} fileSize=${book.size} pieceSize=$pieceLength offset=$offset length=$length firstPiece=$firstPiece lastPiece=$lastPiece requiredPieces=${requested.size} selected=${active.requestedPieces.size}")
        var reannounced = false
        var lastAvailable = requested.count(active.handle::havePiece)
        val requestStartedNanos = System.nanoTime()
        var lastProgressNanos = requestStartedNanos
        val initialDeadlineNanos = requestStartedNanos + INITIAL_PEER_TIMEOUT_MILLIS * 1_000_000L
        try {
            while (requested.any { !active.handle.havePiece(it) }) {
                TorrentImportControl.awaitResume(context)
                check(!TorrentImportControl.isCancelled(context)) { "Torrent streaming was canceled." }
                val now = System.nanoTime()
                val available = requested.count(active.handle::havePiece)
                if (available > lastAvailable) {
                    lastAvailable = available
                    lastProgressNanos = now
                }
                if (now >= active.nextDiagnosticNanos) {
                    val peerCount = runCatching { active.handle.peerInfo().size }.getOrDefault(-1)
                    val status = runCatching { active.handle.status() }.getOrNull()
                    val availability = runCatching { active.handle.pieceAvailability() }.getOrNull()
                    val requiredAvailability = availability?.let { pieces -> requested.map { pieces.getOrElse(it) { 0 } } }
                    val missing = requested.size - available
                    val state = when {
                        peerCount == 0 -> "finding-peers"
                        available == requested.size -> "ready"
                        available > 0 -> "receiving"
                        else -> "waiting-for-required-pieces"
                    }
                    Log.d(LOG_TAG, "range-wait purpose=$purpose book=${book.key} fileIndex=${book.fileIndex} fileSize=${book.size} pieceSize=$pieceLength offset=$offset length=$length firstPiece=$firstPiece lastPiece=$lastPiece requiredPieces=${requested.size} peers=${status?.numPeers() ?: peerCount} seeds=${status?.numSeeds() ?: -1} downloadSpeed=${status?.downloadPayloadRate() ?: -1} uploadSpeed=${status?.uploadPayloadRate() ?: -1} pieceAvailability=${requiredAvailability ?: "unknown"} dht=${session?.isDhtRunning() == true} availableRequiredPieces=$available missingRequiredPieces=$missing bytesReceived=${available * pieceLength} state=$state elapsedMs=${(now - requestStartedNanos) / 1_000_000L} stalledMs=${(now - lastProgressNanos) / 1_000_000L}")
                    active.nextDiagnosticNanos = now + 5_000_000_000L
                }
                if (!reannounced && now >= initialDeadlineNanos) {
                    reannounced = true
                    runCatching { active.handle.forceReannounce() }
                    active.handle.resume()
                    Log.w(LOG_TAG, "range-reannounce purpose=$purpose book=${book.key} availableRequiredPieces=$available")
                }
                val deadline = if (lastAvailable == 0 && !reannounced) initialDeadlineNanos else lastProgressNanos + STALLED_TRANSFER_TIMEOUT_MILLIS * 1_000_000L
                if (now >= deadline) {
                    val peerCount = runCatching { active.handle.peerInfo().size }.getOrDefault(-1)
                    val state = if (peerCount == 0) "no-peers" else "data-stalled"
                    error("Torrent $purpose unavailable ($state): $lastAvailable/${requested.size} required pieces received. Retry this page or cover; no archive was downloaded in full.")
                }
                delay(50)
            }
            Log.d(LOG_TAG, "range-ready purpose=$purpose book=${book.key} requiredPieces=${requested.size} bytesReceived=${requested.size * pieceLength}")
        } finally {
            requested.forEach { piece -> runCatching { active.handle.resetPieceDeadline(piece) } }
            active.handle.setFlags(TorrentFlags.UPLOAD_MODE)
        }
    }

    private suspend fun waitForPiecePriority(handle: TorrentHandle, piece: Int, expected: Priority, stage: String) {
        repeat(180) {
            if (handle.piecePriority(piece) == expected) return
            delay(25)
        }
        error("Torrent $stage setup did not complete. No archive data was downloaded.")
    }

    private fun readArchiveRange(archive: File, offset: Long, length: Long): ByteArray {
        require(length in 1..MAX_COMPRESSED_PAGE_BYTES) { "Requested page range exceeds the low-storage limit." }
        return RandomAccessFile(archive, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length.toInt()).also(raf::readFully)
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= limit) { "Decompressed Torrent page exceeds the safety limit." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun cachedArchiveIndex(bookKey: String): ArchiveIndex? = synchronized(archiveIndexLock) {
        archiveIndexes[bookKey]
    }

    private fun cacheArchiveIndex(bookKey: String, index: ArchiveIndex) = synchronized(archiveIndexLock) {
        archiveIndexes[bookKey] = index
        while (archiveIndexes.size > INDEX_CACHE_ENTRIES) {
            archiveIndexes.entries.iterator().run {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
    }

    private suspend fun waitForHandle(catalog: TorrentCatalog): TorrentHandle = withContext(kotlinx.coroutines.Dispatchers.IO) {
        repeat(50) {
            findHandle(catalog)?.let { return@withContext it }
            delay(100)
        }
        error("Torrent session did not start.")
    }

    private fun findHandle(catalog: TorrentCatalog): TorrentHandle? = synchronized(sessionLock) {
        session?.find(catalog.info.infoHash())
    }

    private fun ensureSession(): SessionManager = ensureSessionBlocking()

    private fun ensureSessionBlocking(): SessionManager = synchronized(sessionLock) {
        session?.let { return@synchronized it }
        SessionManager(false).also {
            it.start()
            // SessionManager.start() configures DHT bootstrap nodes but does not start DHT. Start
            // it once for this short-lived interactive session so tracker-light torrents can find
            // peers before their selected range is requested.
            it.startDht()
            session = it
        }
    }

    private suspend fun releaseActiveStream(bookKey: String? = null) {
        val active = synchronized(activeStreamLock) {
            val current = activeStream ?: return
            if (bookKey != null && current.bookKey != bookKey) return
            activeStream = null
            current
        }
        // libtorrent4j writes completed pieces to disk. The catalog metadata remains in filesDir,
        // while this cache directory is strictly transient and removed after every active request.
        runCatching { active.handle.pause() }
        runCatching { session?.remove(active.handle) }
        runCatching { active.catalog.saveDirectory.deleteRecursively() }
        Log.d(LOG_TAG, "stream-release book=${active.bookKey} pieces=${active.requestedPieces.size} temporary=true diskCacheDeleted=true")
    }

    private fun findSignatureFromEnd(bytes: ByteArray, signature: Long): Int {
        for (index in bytes.size - 4 downTo 0) {
            if (readLeInt(bytes, index) == signature) return index
        }
        return -1
    }

    private fun readLeShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(bytes: ByteArray, offset: Int): Long =
        (readLeShort(bytes, offset).toLong() and 0xFFFF) or ((readLeShort(bytes, offset + 2).toLong() and 0xFFFF) shl 16)

    private fun RandomAccessFile.readLeShort(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readLeInt(): Long =
        readLeShort().toLong() or (readLeShort().toLong() shl 16)

    private fun imageType(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        else -> "image/jpeg"
    }

    private fun isImage(name: String): Boolean = name.lowercase(Locale.ROOT).let {
        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
            it.endsWith(".webp") || it.endsWith(".gif")
    }
}
