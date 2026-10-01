package exh

import android.content.Context
import androidx.core.net.toUri
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.NHentai
import exh.log.ResettableLogger
import exh.log.safeXLogStackTag
import exh.source.getMainSource
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GalleryAdder(
    private val updateManga: UpdateManga = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    // KMK -->
    private val logger = ResettableLogger { safeXLogStackTag() }
    // KMK <---

    private fun matchingSources(uri: android.net.Uri): List<HttpSource> {
        // Direct URL imports must not depend on the source catalogue filters.
        // A source can be hidden, unpinned, or have its language disabled while
        // its installed extension still provides the exact URL importer needed
        // for a pasted link. Catalogue visibility remains a UI concern; URL
        // matching is constrained by the source's own matchingHosts contract.
        val candidates = sourceManager.getOnlineSources()
            .mapNotNull { it.getMainSource<HttpSource>() }
            .filter { source ->
                try {
                    val urlImportable = source as? UrlImportableSource
                    urlImportable?.matchesUri(uri) == true ||
                        source.baseUrl.toUri().host.orEmpty().equals(uri.host.orEmpty(), ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
            }
            .distinctBy { it.id }

        // A direct nhentai.net URL can match more than one delegated source. The
        // source browser identifies library entries by (source ID, cleaned URL),
        // so always prefer the visible all-language Nhentai delegate first.
        if (uri.host.orEmpty().equals("nhentai.net", ignoreCase = true)) {
            return candidates.sortedByDescending {
                when {
                    it is NHentai && it.lang == "all" -> 3
                    it is NHentai -> 2
                    else -> 1
                }
            }
        }
        return candidates.sortedByDescending { it is UrlImportableSource }
    }

    fun pickSource(url: String): List<UrlImportableSource> = matchingSources(url.toUri())
        .mapNotNull { it as? UrlImportableSource }

    /** Returns the exact (source ID, cleaned URL) key used by source-library status lookups. */
    suspend fun canonicalMangaIdentity(url: String): Pair<Long, String>? {
        val uri = url.toUri()
        val source = matchingSources(uri).firstOrNull() ?: return null
        return if (source is UrlImportableSource) {
            val mappedUrl = source.mapUrlToMangaUrl(uri) ?: return null
            source.id to source.cleanMangaUrl(mappedUrl)
        } else {
            source.id to uri.toString().trimEnd('/')
        }
    }

    suspend fun addGallery(
        context: Context,
        url: String,
        fav: Boolean = false,
        forceSource: UrlImportableSource? = null,
        throttleFunc: suspend () -> Unit = {},
        retry: Int = 1,
    ): GalleryAddEvent {
        logger()?.d(context.stringResource(SYMR.strings.gallery_adder_importing_gallery, url, fav.toString(), forceSource?.toString().orEmpty()))
        try {
            val uri = url.toUri()

            // Find matching source
            val source: HttpSource = if (forceSource != null) {
                try {
                    if (forceSource.matchesUri(uri)) {
                        forceSource as? HttpSource
                            ?: return GalleryAddEvent.Fail.UnknownType(url, context)
                    } else {
                        return GalleryAddEvent.Fail.UnknownSource(url, context)
                    }
                } catch (e: Exception) {
                    logger()?.e(context.stringResource(SYMR.strings.gallery_adder_source_uri_must_match), e)
                    return GalleryAddEvent.Fail.UnknownType(url, context)
                }
            } else {
                matchingSources(uri).firstOrNull()
                    ?: return GalleryAddEvent.Fail.UnknownSource(url, context)
            }

            val urlImportableSource = source as? UrlImportableSource

            val realChapterUrl = try {
                urlImportableSource?.mapUrlToChapterUrl(uri)
            } catch (e: Exception) {
                logger()?.e(context.stringResource(SYMR.strings.gallery_adder_uri_map_to_chapter_error), e)
                null
            }

            val cleanedChapterUrl = if (realChapterUrl != null) {
                try {
                    urlImportableSource?.cleanChapterUrl(realChapterUrl)
                } catch (e: Exception) {
                    logger()?.e(context.stringResource(SYMR.strings.gallery_adder_uri_clean_error), e)
                    null
                }
            } else {
                null
            }

            val chapterMangaUrl = if (realChapterUrl != null && urlImportableSource != null) {
                urlImportableSource.mapChapterUrlToMangaUrl(realChapterUrl.toUri())
            } else {
                null
            }

            // Map URL to manga URL
            val realMangaUrl = try {
                chapterMangaUrl ?: urlImportableSource?.mapUrlToMangaUrl(uri) ?: uri.toString()
            } catch (e: Exception) {
                logger()?.e(context.stringResource(SYMR.strings.gallery_adder_uri_map_to_gallery_error), e)
                null
            } ?: return GalleryAddEvent.Fail.UnknownType(url, context)

            // Clean URL
            val cleanedMangaUrl = try {
                urlImportableSource?.cleanMangaUrl(realMangaUrl) ?: realMangaUrl.trimEnd('/')
            } catch (e: Exception) {
                logger()?.e(context.stringResource(SYMR.strings.gallery_adder_uri_clean_error), e)
                null
            } ?: return GalleryAddEvent.Fail.UnknownType(url, context)

            // Use manga in DB if possible, otherwise, make a new manga
            var manga = networkToLocalManga(
                Manga.create().copy(
                    source = source.id,
                    url = cleanedMangaUrl,
                ),
            )

            // Fetch and copy details
            manga = retry(retry) {
                updateMangaFromRemote(
                    manga = manga,
                    fetchDetails = true,
                    fetchChapters = true,
                    throttleFunc = throttleFunc,
                ).getOrThrow().manga
            }

            if (fav) {
                updateManga.awaitUpdateFavorite(manga.id, true)
                manga = manga.copy(favorite = true)
            }

            return if (cleanedChapterUrl != null) {
                val chapter = getChapter.await(cleanedChapterUrl, manga.id)
                if (chapter != null) {
                    GalleryAddEvent.Success(url, manga, context, chapter)
                } else {
                    GalleryAddEvent.Fail.Error(url, context.stringResource(SYMR.strings.gallery_adder_could_not_identify_chapter, url))
                }
            } else {
                GalleryAddEvent.Success(url, manga, context)
            }
        } catch (e: Exception) {
            logger()?.w(context.stringResource(SYMR.strings.gallery_adder_could_not_add_gallery, url), e)

            if (e is EHentai.GalleryNotFoundException) {
                return GalleryAddEvent.Fail.NotFound(url, context)
            }

            return GalleryAddEvent.Fail.Error(
                url,
                ((e.message ?: "Unknown error!") + " (Gallery: $url)").trim(),
            )
        }
    }

    private inline fun <T : Any> retry(retryCount: Int, block: () -> T): T {
        var result: T? = null
        var lastError: Exception? = null

        for (i in 1..retryCount) {
            try {
                result = block()
                break
            } catch (e: Exception) {
                if (e is EHentai.GalleryNotFoundException) {
                    throw e
                }
                lastError = e
            }
        }

        if (lastError != null) {
            throw lastError
        }

        return result!!
    }
}

sealed class GalleryAddEvent {
    abstract val logMessage: String
    abstract val galleryUrl: String
    open val galleryTitle: String? = null

    class Success(
        override val galleryUrl: String,
        val manga: Manga,
        val context: Context,
        val chapter: Chapter? = null,
    ) : GalleryAddEvent() {
        override val galleryTitle = manga.title
        override val logMessage = context.stringResource(SYMR.strings.batch_add_success_log_message, galleryTitle)
    }

    sealed class Fail : GalleryAddEvent() {
        class UnknownType(override val galleryUrl: String, val context: Context) : Fail() {
            override val logMessage = context.stringResource(SYMR.strings.batch_add_unknown_type_log_message, galleryUrl)
        }

        open class Error(
            override val galleryUrl: String,
            override val logMessage: String,
        ) : Fail()

        class NotFound(galleryUrl: String, context: Context) :
            Error(galleryUrl, context.stringResource(SYMR.strings.batch_add_not_exist_log_message, galleryUrl))

        class UnknownSource(override val galleryUrl: String, val context: Context) : Fail() {
            override val logMessage = context.stringResource(SYMR.strings.batch_add_unknown_source_log_message, galleryUrl)
        }
    }
}
