package eu.kanade.domain.ui

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Local, achievement-free Treasury cosmetics for Komikku.
 * These values intentionally stay outside the manga database and backup format.
 */
class KodamiTreasuryPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun backgroundEffect() = preferenceStore.getString("kodami_treasury_background_effect", "none")
    fun profileName() = preferenceStore.getString("kodami_treasury_profile_name", "Komikku Reader")
    fun profileTagline() = preferenceStore.getString("kodami_treasury_profile_tagline", "Weekend = manga time!")
    fun profileTitle() = preferenceStore.getString("kodami_treasury_profile_title", "none")
    fun nicknameEffect() = preferenceStore.getString("kodami_treasury_nickname_effect", "none")
    fun avatarFrame() = preferenceStore.getString("kodami_treasury_avatar_frame", "none")
    fun homeBadge() = preferenceStore.getString("kodami_treasury_home_badge", "none")
    fun profilePhotoUri() = preferenceStore.getString("kodami_treasury_profile_photo_uri", "")

    fun dailyCacheDate() = preferenceStore.getString("kodami_daily_cache_date", "")
    fun dailyCacheMangaIds() = preferenceStore.getString("kodami_daily_cache_manga_ids", "")
    fun dailyCacheChapterRecords() = preferenceStore.getString("kodami_daily_cache_chapter_records", "")
    fun dailyCacheStatus() = preferenceStore.getString("kodami_daily_cache_status", "")
    fun dailyCacheFilter() = preferenceStore.getString("kodami_daily_cache_filter", "DISABLED")
}
