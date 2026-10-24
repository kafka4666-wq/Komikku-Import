package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source as DomainSource
import tachiyomi.domain.track.model.Track
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A small, app-private display snapshot for process recreation. Room remains the source of truth;
 * this only prevents the library tab from becoming a blank loading screen while Room rehydrates.
 */
internal object LibraryStateSnapshot {
    private const val TAG = "LibraryStateSnapshot"
    private const val VERSION = 1
    private const val FILE_NAME = "library-screen-v1.json.gz"
    private const val MAX_ITEMS = 3_000
    private const val MAX_ITEMS_IN_ACTIVE_GROUP = 2_500
    private const val MAX_ITEMS_IN_OTHER_GROUP = 125

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun read(context: Context): LibraryScreenModel.State? = withContext(Dispatchers.IO) {
        val atomicFile = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
        if (!atomicFile.baseFile.exists()) return@withContext null

        runCatching {
            GZIPInputStream(BufferedInputStream(atomicFile.openRead())).bufferedReader().use { reader ->
                json.decodeFromString(PersistedLibraryState.serializer(), reader.readText())
            }
                .takeIf { it.version == VERSION }
                ?.toState()
        }.onFailure {
            Log.w(TAG, "Ignoring an unreadable library screen snapshot", it)
            atomicFile.delete()
        }.getOrNull()
    }

    suspend fun write(context: Context, state: LibraryScreenModel.State): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = state.toPersistedState()
            val payload = json.encodeToString(PersistedLibraryState.serializer(), snapshot)
                .toByteArray(Charsets.UTF_8)
            val atomicFile = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
            val output = atomicFile.startWrite()
            try {
                val gzip = GZIPOutputStream(BufferedOutputStream(output))
                gzip.write(payload)
                gzip.finish()
                gzip.flush()
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }.onFailure {
            Log.w(TAG, "Could not save the library screen snapshot", it)
        }
        Unit
    }

    private fun LibraryScreenModel.State.toPersistedState(): PersistedLibraryState {
        val favoritesById = libraryData.favoritesById
        val activeId = activeCategory?.id
        val orderedCategories = displayedCategories.sortedBy { it.id != activeId }
        val storedItems = LinkedHashMap<Long, LibraryItem>()
        val storedGroups = ArrayList<PersistedGroup>(orderedCategories.size)

        for (category in orderedCategories) {
            val ids = ArrayList<Long>()
            val perGroupLimit = if (category.id == activeId) {
                MAX_ITEMS_IN_ACTIVE_GROUP
            } else {
                MAX_ITEMS_IN_OTHER_GROUP
            }
            for (id in getItemIdsForCategory(category)) {
                if (ids.size >= perGroupLimit || storedItems.size >= MAX_ITEMS) break
                val item = favoritesById[id] ?: continue
                ids += id
                storedItems.putIfAbsent(id, item)
            }
            storedGroups += PersistedGroup(PersistedCategory.from(category), ids)
        }

        val storedIds = storedItems.keys
        val tracks = libraryData.tracksMap
            .filterKeys { it in storedIds }
            .values
            .flatten()
            .map { PersistedTrack.from(it) }

        return PersistedLibraryState(
            version = VERSION,
            items = storedItems.values.map { PersistedLibraryItem.from(it) },
            categories = libraryData.categories.map { PersistedCategory.from(it) },
            groups = storedGroups,
            tracks = tracks,
            loggedInTrackerIds = libraryData.loggedInTrackerIds.toList(),
            isInitialized = isInitialized,
            searchQuery = searchQuery,
            hasActiveFilters = hasActiveFilters,
            showCategoryTabs = showCategoryTabs,
            showMangaCount = showMangaCount,
            showMangaContinueButton = showMangaContinueButton,
            activeCategoryIndex = coercedActiveCategoryIndex,
            activeCategoryId = activeId,
            showSyncExh = showSyncExh,
            isSyncEnabled = isSyncEnabled,
            groupType = groupType,
            filterCategory = filterCategory,
            includedCategories = includedCategories.toList(),
            excludedCategories = excludedCategories.toList(),
        )
    }

    private fun PersistedLibraryState.toState(): LibraryScreenModel.State {
        val restoredItems = items.map(PersistedLibraryItem::toLibraryItem)
        val restoredTracks = tracks.map(PersistedTrack::toTrack).groupBy(Track::mangaId)
        val restoredGroups = linkedMapOf<Category, List<Long>>()
        groups.forEach { group ->
            restoredGroups[group.category.toCategory()] = group.itemIds
        }

        val restoredCategories = categories.map(PersistedCategory::toCategory)
        if (restoredGroups.isEmpty() && restoredItems.isNotEmpty()) {
            val fallback = restoredCategories.firstOrNull { it.isSystemCategory }
                ?: Category(Category.UNCATEGORIZED_ID, "Default", 0, 0, false)
            restoredGroups[fallback] = restoredItems.map { it.id }
        }

        return LibraryScreenModel.State(
            isInitialized = isInitialized,
            isLoading = false,
            searchQuery = searchQuery,
            selection = emptySet(),
            hasActiveFilters = hasActiveFilters,
            showCategoryTabs = showCategoryTabs,
            showMangaCount = showMangaCount,
            showMangaContinueButton = showMangaContinueButton,
            dialog = null,
            libraryData = LibraryScreenModel.LibraryData(
                isInitialized = true,
                categories = restoredCategories,
                favorites = restoredItems,
                tracksMap = restoredTracks,
                loggedInTrackerIds = loggedInTrackerIds.toSet(),
            ),
            activeCategoryIndex = activeCategoryIndex,
            activeCategoryId = activeCategoryId,
            groupedFavorites = restoredGroups,
            showSyncExh = showSyncExh,
            isSyncEnabled = isSyncEnabled,
            groupType = groupType,
            filterCategory = filterCategory,
            includedCategories = includedCategories.toImmutableSet(),
            excludedCategories = excludedCategories.toImmutableSet(),
        )
    }

    @Serializable
    private data class PersistedLibraryState(
        val version: Int,
        val items: List<PersistedLibraryItem>,
        val categories: List<PersistedCategory>,
        val groups: List<PersistedGroup>,
        val tracks: List<PersistedTrack>,
        val loggedInTrackerIds: List<Long>,
        val isInitialized: Boolean,
        val searchQuery: String?,
        val hasActiveFilters: Boolean,
        val showCategoryTabs: Boolean,
        val showMangaCount: Boolean,
        val showMangaContinueButton: Boolean,
        val activeCategoryIndex: Int,
        val activeCategoryId: Long?,
        val showSyncExh: Boolean,
        val isSyncEnabled: Boolean,
        val groupType: Int,
        val filterCategory: Boolean,
        val includedCategories: List<Long>,
        val excludedCategories: List<Long>,
    )

    @Serializable
    private data class PersistedLibraryItem(
        val manga: PersistedManga,
        val categories: List<Long>,
        val totalChapters: Long,
        val readCount: Long,
        val bookmarkCount: Long,
        val bookmarkReadCount: Long,
        val chapterFlags: Long,
        val latestUpload: Long,
        val chapterFetchedAt: Long,
        val lastRead: Long,
        val downloadCount: Long,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
        val useLangIcon: Boolean,
        val source: PersistedSource?,
    ) {
        fun toLibraryItem(): LibraryItem {
            val libraryManga = LibraryManga(
                manga = manga.toManga(),
                categories = categories,
                totalChapters = totalChapters,
                readCount = readCount,
                bookmarkCount = bookmarkCount,
                bookmarkReadCount = bookmarkReadCount,
                chapterFlags = chapterFlags,
                latestUpload = latestUpload,
                chapterFetchedAt = chapterFetchedAt,
                lastRead = lastRead,
            )
            return LibraryItem(
                libraryManga = libraryManga,
                downloadCount = downloadCount,
                unreadCount = unreadCount,
                isLocal = isLocal,
                sourceLanguage = sourceLanguage,
                useLangIcon = useLangIcon,
                source = source?.toDomainSource(),
            )
        }

        companion object {
            fun from(item: LibraryItem) = PersistedLibraryItem(
                manga = PersistedManga.from(item.libraryManga.manga),
                categories = item.libraryManga.categories,
                totalChapters = item.libraryManga.totalChapters,
                readCount = item.libraryManga.readCount,
                bookmarkCount = item.libraryManga.bookmarkCount,
                bookmarkReadCount = item.libraryManga.bookmarkReadCount,
                chapterFlags = item.libraryManga.chapterFlags,
                latestUpload = item.libraryManga.latestUpload,
                chapterFetchedAt = item.libraryManga.chapterFetchedAt,
                lastRead = item.libraryManga.lastRead,
                downloadCount = item.downloadCount,
                unreadCount = item.unreadCount,
                isLocal = item.isLocal,
                sourceLanguage = item.sourceLanguage,
                useLangIcon = item.useLangIcon,
                source = item.source?.let { PersistedSource.from(it) },
            )
        }
    }

    @Serializable
    private data class PersistedManga(
        val id: Long,
        val source: Long,
        val favorite: Boolean,
        val lastUpdate: Long,
        val nextUpdate: Long,
        val fetchInterval: Int,
        val dateAdded: Long,
        val viewerFlags: Long,
        val chapterFlags: Long,
        val coverLastModified: Long,
        val url: String,
        val title: String,
        val artist: String?,
        val author: String?,
        val thumbnailUrl: String?,
        val description: String?,
        val genre: List<String>?,
        val status: Long,
        val updateStrategy: String,
        val initialized: Boolean,
        val lastModifiedAt: Long,
        val favoriteModifiedAt: Long?,
        val version: Long,
    ) {
        fun toManga() = Manga(
            id = id,
            source = source,
            favorite = favorite,
            lastUpdate = lastUpdate,
            nextUpdate = nextUpdate,
            fetchInterval = fetchInterval,
            dateAdded = dateAdded,
            viewerFlags = viewerFlags,
            chapterFlags = chapterFlags,
            coverLastModified = coverLastModified,
            url = url,
            ogTitle = title,
            ogArtist = artist,
            ogAuthor = author,
            ogThumbnailUrl = thumbnailUrl,
            ogDescription = description,
            ogGenre = genre,
            ogStatus = status,
            updateStrategy = runCatching {
                eu.kanade.tachiyomi.source.model.UpdateStrategy.valueOf(updateStrategy)
            }.getOrDefault(eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE),
            initialized = initialized,
            lastModifiedAt = lastModifiedAt,
            favoriteModifiedAt = favoriteModifiedAt,
            version = version,
            notes = "",
            memo = JsonObject(emptyMap()),
        )

        companion object {
            fun from(manga: Manga) = PersistedManga(
                id = manga.id,
                source = manga.source,
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                nextUpdate = manga.nextUpdate,
                fetchInterval = manga.fetchInterval,
                dateAdded = manga.dateAdded,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                url = manga.url,
                title = manga.title,
                artist = manga.artist,
                author = manga.author,
                thumbnailUrl = manga.thumbnailUrl,
                description = manga.description,
                genre = manga.genre,
                status = manga.status,
                updateStrategy = manga.updateStrategy.name,
                initialized = manga.initialized,
                lastModifiedAt = manga.lastModifiedAt,
                favoriteModifiedAt = manga.favoriteModifiedAt,
                version = manga.version,
            )
        }
    }

    @Serializable
    private data class PersistedCategory(
        val id: Long,
        val name: String,
        val order: Long,
        val flags: Long,
        val hidden: Boolean,
    ) {
        fun toCategory() = Category(id, name, order, flags, hidden)

        companion object {
            fun from(category: Category) = PersistedCategory(
                id = category.id,
                name = category.name,
                order = category.order,
                flags = category.flags,
                hidden = category.hidden,
            )
        }
    }

    @Serializable
    private data class PersistedGroup(
        val category: PersistedCategory,
        val itemIds: List<Long>,
    )

    @Serializable
    private data class PersistedTrack(
        val id: Long,
        val mangaId: Long,
        val trackerId: Long,
        val remoteId: Long,
        val libraryId: Long?,
        val title: String,
        val lastChapterRead: Double,
        val totalChapters: Long,
        val status: Long,
        val score: Double,
        val remoteUrl: String,
        val startDate: Long,
        val finishDate: Long,
        val isPrivate: Boolean,
    ) {
        fun toTrack() = Track(
            id = id,
            mangaId = mangaId,
            trackerId = trackerId,
            remoteId = remoteId,
            libraryId = libraryId,
            title = title,
            lastChapterRead = lastChapterRead,
            totalChapters = totalChapters,
            status = status,
            score = score,
            remoteUrl = remoteUrl,
            startDate = startDate,
            finishDate = finishDate,
            private = isPrivate,
        )

        companion object {
            fun from(track: Track) = PersistedTrack(
                id = track.id,
                mangaId = track.mangaId,
                trackerId = track.trackerId,
                remoteId = track.remoteId,
                libraryId = track.libraryId,
                title = track.title,
                lastChapterRead = track.lastChapterRead,
                totalChapters = track.totalChapters,
                status = track.status,
                score = track.score,
                remoteUrl = track.remoteUrl,
                startDate = track.startDate,
                finishDate = track.finishDate,
                isPrivate = track.private,
            )
        }
    }

    @Serializable
    private data class PersistedSource(
        val id: Long,
        val lang: String,
        val name: String,
        val isStub: Boolean,
    ) {
        fun toDomainSource() = DomainSource(id, lang, name, supportsLatest = false, isStub = isStub)

        companion object {
            fun from(source: DomainSource) = PersistedSource(
                id = source.id,
                lang = source.lang,
                name = source.name,
                isStub = source.isStub,
            )
        }
    }
}
