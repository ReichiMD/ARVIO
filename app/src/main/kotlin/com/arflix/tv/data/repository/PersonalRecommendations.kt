package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.data.api.TraktIds
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two "For You" rows on Home: Trakt's own recommendations for the signed-in profile. Without
 * Trakt the rows stay empty, and Home leaves empty rows out. Watched titles, titles on the
 * watchlist and titles the user marked "Not interested" are left out.
 */
object PersonalRecommendations {
    const val MOVIES_CATALOG_ID = "for_you_movies"
    const val SHOWS_CATALOG_ID = "for_you_shows"
    const val SOURCE_REF_PREFIX = "arvio_for_you:"

    val catalogIds = setOf(MOVIES_CATALOG_ID, SHOWS_CATALOG_ID)

    fun defaultCatalogs(): List<CatalogConfig> = listOf(
        CatalogConfig(
            id = MOVIES_CATALOG_ID,
            title = "For You: Movies",
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            sourceRef = "${SOURCE_REF_PREFIX}movie"
        ),
        CatalogConfig(
            id = SHOWS_CATALOG_ID,
            title = "For You: Shows",
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            sourceRef = "${SOURCE_REF_PREFIX}tv"
        )
    )

    /** The media type a "For You" catalog shows, or null for any other catalog. */
    fun mediaTypeOf(catalog: CatalogConfig): MediaType? = when (catalog.id) {
        MOVIES_CATALOG_ID -> MediaType.MOVIE
        SHOWS_CATALOG_ID -> MediaType.TV
        else -> null
    }
}

/** Show ids from watched-episode keys (`show_tmdb:<id>:<season>:<episode>`). */
internal fun startedShowIdsFromEpisodeKeys(keys: Set<String>): Set<Int> =
    keys.mapNotNullTo(HashSet()) { key ->
        if (key.startsWith("show_tmdb:")) {
            key.removePrefix("show_tmdb:").substringBefore(':', "").toIntOrNull()
        } else null
    }

@Singleton
class PersonalRecommendationsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktRepository: TraktRepository,
    private val watchlistRepository: WatchlistRepository,
    private val profileManager: ProfileManager
) {
    private data class CacheEntry(val ids: List<Int>, val loadedAt: Long) {
        fun isFresh() = System.currentTimeMillis() - loadedAt < CACHE_TTL_MS
    }

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    // Trakt ids of what Trakt recommended, so "Not interested" can be sent back to Trakt.
    private val traktIdsByTmdb = ConcurrentHashMap<String, TraktIds>()
    private val loadMutex = Mutex()

    private fun hiddenKey() = profileManager.profileStringKey(HIDDEN_KEY_NAME)

    private fun cacheKey(profileId: String, mediaType: MediaType) = "$profileId:${mediaType.name}"

    private fun titleKey(profileId: String, mediaType: MediaType, tmdbId: Int) =
        "$profileId:${mediaType.name}:$tmdbId"

    /** The row's titles as (type, TMDB id), best first. Empty when there is nothing to recommend. */
    suspend fun refsFor(catalog: CatalogConfig): List<Pair<MediaType, Int>> {
        val mediaType = PersonalRecommendations.mediaTypeOf(catalog) ?: return emptyList()
        return idsFor(mediaType).map { mediaType to it }
    }

    private suspend fun idsFor(mediaType: MediaType): List<Int> = withContext(Dispatchers.IO) {
        val profileId = profileManager.getProfileIdSync()
        val key = cacheKey(profileId, mediaType)
        // Checked on every read, so the rows leave as soon as Trakt is disconnected and come
        // back as soon as it is connected; a later account starts without the old picks.
        if (!traktRepository.hasTrakt()) {
            cache.remove(key)
            return@withContext emptyList()
        }
        val ids = cache[key]?.takeIf { it.isFresh() }?.ids ?: loadMutex.withLock {
            // Movies and shows load together on Home; one at a time keeps the watched-cache reads shared.
            cache[key]?.takeIf { it.isFresh() }?.ids ?: run {
                val loaded = try {
                    load(profileId, mediaType)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Loading recommendations failed", e)
                    null
                }
                // A failed load is not cached, so the next Home visit tries again.
                if (loaded != null && profileManager.getProfileIdSync() == profileId) {
                    cache[key] = CacheEntry(loaded, System.currentTimeMillis())
                }
                loaded.orEmpty()
            }
        }
        // Applied on every read, not only when loading: a title watched, put on the watchlist or
        // marked "Not interested" since then (even while the load was running) leaves at once.
        val excluded = excludedIds(profileId, mediaType)
        ids.filterNot { it in excluded }
    }

    private suspend fun watchedIds(mediaType: MediaType): Set<Int> {
        traktRepository.initializeWatchedCache()
        return if (mediaType == MediaType.MOVIE) {
            traktRepository.getWatchedMoviesFromCache()
        } else {
            startedShowIdsFromEpisodeKeys(traktRepository.getWatchedEpisodesFromCache())
        }
    }

    private suspend fun excludedIds(profileId: String, mediaType: MediaType): Set<Int> {
        val type = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val onWatchlist = watchlistRepository.exportWatchlistForProfile(profileId)
            .filter { it.mediaType == type }
            .mapTo(HashSet()) { it.tmdbId }
        return watchedIds(mediaType) + onWatchlist + hiddenIds(mediaType)
    }

    private suspend fun load(profileId: String, mediaType: MediaType): List<Int>? {
        // Null: Trakt could not be asked.
        val fromTrakt = traktRepository.getRecommendations(mediaType, TRAKT_LIMIT) ?: return null
        val excluded = excludedIds(profileId, mediaType)
        val picks = fromTrakt
            .mapNotNull { ids -> ids.tmdb?.takeIf { it > 0 }?.let { it to ids } }
            .filter { (tmdbId, _) -> tmdbId !in excluded }
            .distinctBy { it.first }
        picks.forEach { (tmdbId, ids) -> traktIdsByTmdb[titleKey(profileId, mediaType, tmdbId)] = ids }
        return picks.take(ROW_LIMIT).map { it.first }
    }

    /**
     * "Not interested": the title leaves the row and stays out for this profile. When
     * Trakt recommended it, Trakt is told too, so its other apps stop suggesting it.
     */
    suspend fun hide(mediaType: MediaType, tmdbId: Int) = withContext(Dispatchers.IO) {
        val profileId = profileManager.getProfileIdSync()
        val entry = "${mediaType.name}:$tmdbId"
        context.settingsDataStore.edit { prefs ->
            val current = decodeHidden(prefs[profileManager.profileStringKeyFor(profileId, HIDDEN_KEY_NAME)])
            if (entry !in current) {
                prefs[profileManager.profileStringKeyFor(profileId, HIDDEN_KEY_NAME)] =
                    gson.toJson((current + entry).takeLast(HIDDEN_LIMIT))
            }
        }
        traktIdsByTmdb.remove(titleKey(profileId, mediaType, tmdbId))?.let { ids ->
            if (!traktRepository.hideRecommendation(mediaType, ids)) {
                AppLogger.breadcrumb(TAG, "trakt_hide_failed media_type=${mediaType.name}", "warning")
            }
        }
    }

    private suspend fun hiddenIds(mediaType: MediaType): Set<Int> {
        val prefix = "${mediaType.name}:"
        return decodeHidden(context.settingsDataStore.data.first()[hiddenKey()])
            .mapNotNullTo(HashSet()) { entry ->
                if (entry.startsWith(prefix)) entry.removePrefix(prefix).toIntOrNull() else null
            }
    }

    private fun decodeHidden(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<String>>(json, hiddenListType).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val TAG = "PersonalRecommendations"
        const val HIDDEN_KEY_NAME = "for_you_hidden_v1"
        const val CACHE_TTL_MS = 3 * 60 * 60 * 1000L
        const val TRAKT_LIMIT = 100
        // Home shows the first page; "View all" pages through the rest.
        const val ROW_LIMIT = 100
        // Enough for years of "Not interested"; the oldest entries fall off first.
        const val HIDDEN_LIMIT = 1000
        val hiddenListType = TypeToken.getParameterized(List::class.java, String::class.java).type
    }
}
