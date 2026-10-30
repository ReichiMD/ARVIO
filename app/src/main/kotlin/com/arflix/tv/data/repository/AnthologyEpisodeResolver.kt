package com.arflix.tv.data.repository

import android.util.Log
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Where an addon finds a TMDB episode on IMDb: the parent series and its own numbering. */
internal data class AnthologyEpisodeTarget(
    val seriesImdbId: String,
    val season: Int,
    val episode: Int
) {
    val contentId: String get() = "$seriesImdbId:$season:$episode"
}

/** What TMDB says about one episode, enough to recognise it in a Cinemeta series. */
internal data class EpisodeFingerprint(
    val name: String?,
    val airDate: String?,
    val tvdbEpisodeId: Int?
)

internal data class CinemetaEpisode(
    val season: Int,
    val episode: Int,
    val name: String?,
    val released: String?,
    val tvdbEpisodeId: Int?
)

/**
 * Anthology series are one show on IMDb but a separate show per season on TMDB. Netflix's
 * "Monster" is tt13207736 with four seasons on IMDb (Dahmer, Menendez, Ed Gein, Lizzie Borden),
 * while TMDB lists four shows, each with a season 1 and no IMDb id. ARVIO then asks addons for
 * `tmdb:299939:1:1`, and addons that only know IMDb either skip the title or guess by name and
 * return "Monster S01E01" — the Dahmer season.
 *
 * TMDB still links every single episode to TVDB, and Cinemeta lists the TVDB id of every episode
 * of the IMDb series, so the episode can be found there exactly: search Cinemeta by the show's
 * name, then look for the episode with the same TVDB id (or the same title on the same day).
 * Nothing is guessed: without such a match the lookup returns null and the request stays as it was.
 */
internal class AnthologyEpisodeResolver(
    private val fetchJson: suspend (String) -> String?,
    private val tmdbApiKey: () -> String = { Constants.TMDB_API_KEY },
    // The lookup runs here, not in the caller: a cancelled prefetch must not hand the episode it
    // started looking up to the player as "not found" while the player waits on the same lookup.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    constructor(okHttpClient: OkHttpClient) : this(fetchJson = fetcher(okHttpClient))

    private data class CachedTarget(val target: AnthologyEpisodeTarget?, val createdAtMs: Long, val ttlMs: Long)
    private data class CachedSeries(val imdbIds: List<String>, val createdAtMs: Long)

    private val targets = ConcurrentHashMap<String, CachedTarget>()
    private val inFlight = ConcurrentHashMap<String, Deferred<AnthologyEpisodeTarget?>>()
    // Per TMDB show, the Cinemeta series worth checking: one search per show, not per episode.
    private val candidateSeries = ConcurrentHashMap<Int, CachedSeries>()
    private val cinemetaEpisodes = ConcurrentHashMap<String, List<CinemetaEpisode>>()

    /**
     * The IMDb episode for TMDB episode [season]x[episode] of show [tmdbId], or null when the show
     * cannot be matched. Only meant for shows TMDB links to no IMDb id; callers check that.
     */
    suspend fun resolve(tmdbId: Int, season: Int, episode: Int, title: String): AnthologyEpisodeTarget? {
        if (tmdbId <= 0 || season < 0 || episode <= 0) return null
        val key = "$tmdbId:$season:$episode"
        targets[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAtMs < cached.ttlMs) return cached.target
        }
        // Started only once it is in the map, so the lookup's own clean-up always finds it there.
        val lookup = inFlight.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) { lookupAndCache(key, tmdbId, season, episode, title) }
        }
        lookup.start()
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS + 500L) { lookup.await() }
    }

    private suspend fun lookupAndCache(
        key: String,
        tmdbId: Int,
        season: Int,
        episode: Int,
        title: String
    ): AnthologyEpisodeTarget? {
        var finished = false
        var result: AnthologyEpisodeTarget? = null
        try {
            result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                lookup(tmdbId, season, episode, title).also { finished = true }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "tmdb:$key lookup failed: ${e.javaClass.simpleName}")
        } finally {
            inFlight.remove(key)
        }
        if (targets.size > MAX_CACHED_TARGETS) targets.clear()
        // A lookup cut short says little about the show: keep it only briefly, so the next
        // source search does not wait again at once but a later one tries again.
        val ttl = when {
            result != null -> FOUND_TTL_MS
            finished -> NOT_FOUND_TTL_MS
            else -> FAILED_TTL_MS
        }
        targets[key] = CachedTarget(result, System.currentTimeMillis(), ttl)
        // Warning level: release builds strip info logs, and this line says why a show got its sources.
        Log.w(TAG, "tmdb:$key -> ${result?.contentId ?: "no IMDb match"}${if (finished) "" else " (timeout or error)"}")
        return result
    }

    private suspend fun lookup(tmdbId: Int, season: Int, episode: Int, title: String): AnthologyEpisodeTarget? {
        val apiKey = tmdbApiKey().takeIf { it.isNotBlank() } ?: return null
        val episodeJson = fetchJson(
            "$TMDB_BASE/tv/$tmdbId/season/$season/episode/$episode?api_key=$apiKey&language=en-US&append_to_response=external_ids"
        )?.let(::JSONObject) ?: return null
        val fingerprint = EpisodeFingerprint(
            name = episodeJson.optString("name").takeIf { it.isNotBlank() },
            airDate = episodeJson.optString("air_date").takeIf { it.isNotBlank() },
            tvdbEpisodeId = episodeJson.optJSONObject("external_ids")?.optInt("tvdb_id", 0)?.takeIf { it > 0 }
        )
        if (fingerprint.tvdbEpisodeId == null && (fingerprint.name == null || fingerprint.airDate == null)) return null

        for (imdbId in candidateSeries(tmdbId, apiKey, title)) {
            val episodes = cinemetaEpisodes(imdbId) ?: continue
            val match = matchCinemetaEpisode(episodes, fingerprint) ?: continue
            return AnthologyEpisodeTarget(imdbId, match.season, match.episode)
        }
        return null
    }

    private suspend fun candidateSeries(tmdbId: Int, apiKey: String, title: String): List<String> {
        candidateSeries[tmdbId]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAtMs < FOUND_TTL_MS) return cached.imdbIds
        }
        // English names: Cinemeta knows titles in English, the app may show them translated.
        val show = fetchJson("$TMDB_BASE/tv/$tmdbId?api_key=$apiKey&language=en-US")?.let(::JSONObject)
            ?: return emptyList()
        val found = linkedSetOf<String>()
        for (query in anthologySearchQueries(listOf(show.optString("name"), show.optString("original_name"), title))) {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val metas = fetchJson("$CINEMETA_BASE/catalog/series/top/search=$encoded.json")
                ?.let { JSONObject(it).optJSONArray("metas") } ?: continue
            for (index in 0 until minOf(metas.length(), CANDIDATES_PER_QUERY)) {
                metas.optJSONObject(index)?.optString("id")
                    ?.takeIf { it.matches(IMDB_SERIES_ID) }
                    ?.let(found::add)
            }
            if (found.size >= MAX_CANDIDATE_SERIES) break
        }
        val imdbIds = found.take(MAX_CANDIDATE_SERIES)
        if (candidateSeries.size > MAX_CACHED_SERIES) candidateSeries.clear()
        candidateSeries[tmdbId] = CachedSeries(imdbIds, System.currentTimeMillis())
        return imdbIds
    }

    private suspend fun cinemetaEpisodes(imdbId: String): List<CinemetaEpisode>? {
        cinemetaEpisodes[imdbId]?.let { return it }
        val videos = fetchJson("$CINEMETA_BASE/meta/series/$imdbId.json")
            ?.let { JSONObject(it).optJSONObject("meta")?.optJSONArray("videos") } ?: return null
        return parseCinemetaEpisodes(videos).also {
            if (cinemetaEpisodes.size > MAX_CACHED_SERIES) cinemetaEpisodes.clear()
            cinemetaEpisodes[imdbId] = it
        }
    }

    companion object {
        private const val TAG = "AnthologyEpisodes"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
        private const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
        private const val LOOKUP_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 2_500L
        private const val FOUND_TTL_MS = 24 * 60 * 60_000L
        private const val NOT_FOUND_TTL_MS = 30 * 60_000L
        private const val FAILED_TTL_MS = 2 * 60_000L
        private const val CANDIDATES_PER_QUERY = 3
        private const val MAX_CANDIDATE_SERIES = 4
        private const val MAX_CACHED_SERIES = 32
        private const val MAX_CACHED_TARGETS = 512
        private val IMDB_SERIES_ID = Regex("tt\\d+")

        private fun fetcher(okHttpClient: OkHttpClient): suspend (String) -> String? {
            // Its own call limit: the shared client waits up to 30 s, the lookup has 5 s in all.
            val client = okHttpClient.newBuilder()
                .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            return { url ->
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", OkHttpProvider.userAgentOr("Mozilla/5.0 (Android TV; ARVIO)"))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }
            }
        }
    }
}

/**
 * Names to search Cinemeta with, best first: the full show names, then the part before a
 * subtitle ("Monster" from "Monster: The Ed Gein Story", "DAHMER" from "DAHMER - Monster: …").
 */
internal fun anthologySearchQueries(names: List<String?>): List<String> {
    val full = names.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val prefixes = full.flatMap { name ->
        listOf(": ", " - ", " – ").mapNotNull { separator ->
            name.substringBefore(separator, missingDelimiterValue = "").trim().takeIf { it.length >= 3 }
        }
    }
    return (full + prefixes).distinctBy { it.lowercase(Locale.ROOT) }.take(4)
}

internal fun parseCinemetaEpisodes(videos: JSONArray): List<CinemetaEpisode> = buildList {
    for (index in 0 until videos.length()) {
        val video = videos.optJSONObject(index) ?: continue
        val season = video.optInt("season", -1)
        val episode = video.optInt("episode", -1).takeIf { it > 0 } ?: video.optInt("number", -1)
        if (season < 0 || episode <= 0) continue
        add(
            CinemetaEpisode(
                season = season,
                episode = episode,
                name = video.optString("name").takeIf { it.isNotBlank() }
                    ?: video.optString("title").takeIf { it.isNotBlank() },
                released = video.optString("released").takeIf { it.isNotBlank() }
                    ?: video.optString("firstAired").takeIf { it.isNotBlank() },
                tvdbEpisodeId = video.optInt("tvdb_id", 0).takeIf { it > 0 }
            )
        )
    }
}

/**
 * The Cinemeta episode that is [fingerprint]: the same TVDB episode id, or else the same title
 * aired on the same day (one day apart allowed for time zones). Ambiguous matches count as none.
 */
internal fun matchCinemetaEpisode(episodes: List<CinemetaEpisode>, fingerprint: EpisodeFingerprint): CinemetaEpisode? {
    fingerprint.tvdbEpisodeId?.let { tvdbId ->
        episodes.filter { it.tvdbEpisodeId == tvdbId }.singleOrNull()?.let { return it }
    }
    // "Episode 3" names nothing: on a show with a similar name it would match the wrong series.
    val name = fingerprint.name?.let(::normalizeEpisodeName)
        ?.takeIf { it.isNotEmpty() && !GENERIC_EPISODE_NAME.matches(it) } ?: return null
    val airDate = fingerprint.airDate?.let(::parseDay) ?: return null
    return episodes.filter { candidate ->
        val released = candidate.released?.let(::parseDay) ?: return@filter false
        candidate.name?.let(::normalizeEpisodeName) == name &&
            abs(released.toEpochDay() - airDate.toEpochDay()) <= 1
    }.singleOrNull()
}

private val GENERIC_EPISODE_NAME = Regex("(episode|chapter|part|ep)\\s*\\d+")

private fun normalizeEpisodeName(name: String): String =
    name.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun parseDay(value: String): LocalDate? =
    runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
