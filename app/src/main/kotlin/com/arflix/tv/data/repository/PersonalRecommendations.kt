package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TraktIds
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.Constants
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two "For You" rows on Home. With Trakt connected they show Trakt's own recommendations;
 * without it (or when Trakt has nothing), they are built from TMDB's recommendations for the
 * titles the profile watched last. Either way, watched titles, titles on the watchlist and titles
 * the user marked "Not interested" are left out.
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

/**
 * Orders TMDB recommendation lists into one row. Each list belongs to one seed title, most
 * recently watched first; a title gains more the earlier its seed was watched and the higher
 * TMDB placed it, so titles recommended for several seeds rise to the top. Ties keep the order
 * in which the titles first appeared.
 */
internal fun rankSeedRecommendations(
    perSeed: List<List<Int>>,
    excluded: Set<Int>,
    limit: Int
): List<Int> {
    val scores = LinkedHashMap<Int, Double>()
    perSeed.forEachIndexed { seedIndex, ids ->
        val seedWeight = 1.0 / (1.0 + seedIndex * 0.3)
        ids.distinct().forEachIndexed { rank, id ->
            if (id <= 0 || id in excluded) return@forEachIndexed
            scores[id] = (scores[id] ?: 0.0) + seedWeight / (1.0 + rank * 0.1)
        }
    }
    val firstSeen = scores.keys.withIndex().associate { (index, id) -> id to index }
    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { firstSeen[it.key] })
        .take(limit)
        .map { it.key }
}

/**
 * Seeds for the TMDB fallback: titles from the ordered history first (newest first), then other
 * watched titles. Duplicates and anything not of [mediaType] are skipped.
 */
internal fun pickRecommendationSeeds(
    history: List<WatchHistoryEntry>,
    mediaType: MediaType,
    otherWatched: Collection<Int>,
    limit: Int
): List<Int> {
    val type = if (mediaType == MediaType.MOVIE) "movie" else "tv"
    val seeds = LinkedHashSet<Int>()
    history.asSequence()
        .filter { it.media_type == type && it.show_tmdb_id > 0 }
        .forEach { if (seeds.size < limit) seeds += it.show_tmdb_id }
    otherWatched.asSequence()
        .filter { it > 0 }
        .forEach { if (seeds.size < limit) seeds += it }
    return seeds.toList()
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
    private val tmdbApi: TmdbApi,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val profileManager: ProfileManager
) {
    private data class CacheEntry(val ids: List<Int>, val loadedAt: Long, val ttlMs: Long) {
        fun isFresh() = System.currentTimeMillis() - loadedAt < ttlMs
    }

    private data class Loaded(val ids: List<Int>, val ttlMs: Long)

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    // Trakt ids of what Trakt recommended, so "Not interested" can be sent back to Trakt.
    private val traktIdsByTmdb = ConcurrentHashMap<String, TraktIds>()
    private val loadMutex = Mutex()
    private val tmdbSemaphore = Semaphore(4)

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
        val ids = cache[key]?.takeIf { it.isFresh() }?.ids ?: loadMutex.withLock {
            // Movies and shows load together on Home; one at a time keeps the history reads shared.
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
                    cache[key] = CacheEntry(loaded.ids, System.currentTimeMillis(), loaded.ttlMs)
                }
                loaded?.ids.orEmpty()
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

    private suspend fun load(profileId: String, mediaType: MediaType): Loaded? {
        val type = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val watched = watchedIds(mediaType)
        val excluded = excludedIds(profileId, mediaType)

        val hasTrakt = traktRepository.hasTrakt()
        val fromTrakt = if (hasTrakt) traktRepository.getRecommendations(mediaType, TRAKT_LIMIT) else null
        val traktIds = fromTrakt.orEmpty()
            .mapNotNull { ids -> ids.tmdb?.takeIf { it > 0 }?.let { it to ids } }
            .filter { (tmdbId, _) -> tmdbId !in excluded }
            .distinctBy { it.first }
        if (traktIds.isNotEmpty()) {
            traktIds.forEach { (tmdbId, ids) -> traktIdsByTmdb[titleKey(profileId, mediaType, tmdbId)] = ids }
            return Loaded(traktIds.take(ROW_LIMIT).map { it.first }, CACHE_TTL_MS)
        }
        // Trakt could not be asked: fill the row from history for now, and ask Trakt again soon.
        val ttlMs = if (hasTrakt && fromTrakt == null) RETRY_TTL_MS else CACHE_TTL_MS

        // No Trakt, or Trakt has nothing new: build the row from the profile's own history.
        val history = runCatching { watchHistoryRepository.getWatchHistory() }
            .getOrElse { if (it is CancellationException) throw it else emptyList() }
        val seeds = pickRecommendationSeeds(history, mediaType, watched, SEED_LIMIT)
        if (seeds.isEmpty()) return Loaded(emptyList(), ttlMs)
        val perSeed = coroutineScope {
            seeds.map { seedId ->
                async {
                    tmdbSemaphore.withPermit {
                        try {
                            tmdbApi.getRecommendations(type, seedId, Constants.TMDB_API_KEY).results.map { it.id }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }
        // Every request failing is a failed load, not an empty row.
        if (perSeed.all { it.isEmpty() }) return null
        return Loaded(rankSeedRecommendations(perSeed, excluded + seeds, ROW_LIMIT), ttlMs)
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
        const val RETRY_TTL_MS = 10 * 60 * 1000L
        const val TRAKT_LIMIT = 100
        const val ROW_LIMIT = 40
        const val SEED_LIMIT = 8
        // Enough for years of "Not interested"; the oldest entries fall off first.
        const val HIDDEN_LIMIT = 1000
        val hiddenListType = TypeToken.getParameterized(List::class.java, String::class.java).type
    }
}
