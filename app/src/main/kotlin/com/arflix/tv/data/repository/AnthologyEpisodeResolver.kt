package com.arflix.tv.data.repository

import android.util.Log
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
    private val tmdbApiKey: () -> String = { Constants.TMDB_API_KEY }
) {
    constructor(okHttpClient: OkHttpClient) : this({ url -> fetchBody(okHttpClient, url) })

    private data class CachedTarget(val target: AnthologyEpisodeTarget?, val createdAtMs: Long)

    private val targets = ConcurrentHashMap<String, CachedTarget>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<AnthologyEpisodeTarget?>>()
    private val cinemetaEpisodes = ConcurrentHashMap<String, List<CinemetaEpisode>>()

    /**
     * The IMDb episode for TMDB episode [season]x[episode] of show [tmdbId], or null when the show
     * cannot be matched. Only meant for shows TMDB links to no IMDb id; callers check that.
     */
    suspend fun resolve(tmdbId: Int, season: Int, episode: Int, title: String): AnthologyEpisodeTarget? {
        if (tmdbId <= 0 || season < 0 || episode <= 0) return null
        val key = "$tmdbId:$season:$episode"
        val now = System.currentTimeMillis()
        targets[key]?.let { cached ->
            val ttl = if (cached.target != null) FOUND_TTL_MS else NOT_FOUND_TTL_MS
            if (now - cached.createdAtMs < ttl) return cached.target
        }
        val mine = CompletableDeferred<AnthologyEpisodeTarget?>()
        inFlight.putIfAbsent(key, mine)?.let { return it.await() }
        var result: AnthologyEpisodeTarget? = null
        try {
            var finished = false
            result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                lookup(tmdbId, season, episode, title).also { finished = true }
            }
            // A lookup cut short by the timeout says nothing about the show; try again next time.
            if (finished) targets[key] = CachedTarget(result, System.currentTimeMillis())
            Log.i(TAG, "tmdb:$key -> ${result?.contentId ?: "no IMDb match"}${if (finished) "" else " (timeout)"}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "tmdb:$key lookup failed: ${e.javaClass.simpleName}")
        } finally {
            mine.complete(result)
            inFlight.remove(key, mine)
        }
        return result
    }

    private suspend fun lookup(tmdbId: Int, season: Int, episode: Int, title: String): AnthologyEpisodeTarget? {
        val apiKey = tmdbApiKey().takeIf { it.isNotBlank() } ?: return null
        // English names: Cinemeta knows titles in English, the app may show them translated.
        val show = fetchJson("$TMDB_BASE/tv/$tmdbId?api_key=$apiKey&language=en-US")
            ?.let(::JSONObject) ?: return null
        val episodeJson = fetchJson(
            "$TMDB_BASE/tv/$tmdbId/season/$season/episode/$episode?api_key=$apiKey&language=en-US&append_to_response=external_ids"
        )?.let(::JSONObject) ?: return null
        val fingerprint = EpisodeFingerprint(
            name = episodeJson.optString("name").takeIf { it.isNotBlank() },
            airDate = episodeJson.optString("air_date").takeIf { it.isNotBlank() },
            tvdbEpisodeId = episodeJson.optJSONObject("external_ids")?.optInt("tvdb_id", 0)?.takeIf { it > 0 }
        )
        if (fingerprint.tvdbEpisodeId == null && (fingerprint.name == null || fingerprint.airDate == null)) return null

        val queries = anthologySearchQueries(
            listOf(show.optString("name"), show.optString("original_name"), title)
        )
        val checked = mutableSetOf<String>()
        for (query in queries) {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val metas = fetchJson("$CINEMETA_BASE/catalog/series/top/search=$encoded.json")
                ?.let { JSONObject(it).optJSONArray("metas") } ?: continue
            for (index in 0 until minOf(metas.length(), CANDIDATES_PER_QUERY)) {
                val imdbId = metas.optJSONObject(index)?.optString("id").orEmpty()
                if (!imdbId.matches(IMDB_SERIES_ID) || !checked.add(imdbId)) continue
                val episodes = cinemetaEpisodes(imdbId) ?: continue
                val match = matchCinemetaEpisode(episodes, fingerprint) ?: continue
                return AnthologyEpisodeTarget(imdbId, match.season, match.episode)
            }
        }
        return null
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
        private const val LOOKUP_TIMEOUT_MS = 6_000L
        private const val FOUND_TTL_MS = 24 * 60 * 60_000L
        private const val NOT_FOUND_TTL_MS = 30 * 60_000L
        private const val CANDIDATES_PER_QUERY = 3
        private const val MAX_CACHED_SERIES = 32
        private val IMDB_SERIES_ID = Regex("tt\\d+")

        private suspend fun fetchBody(client: OkHttpClient, url: String): String? = withContext(Dispatchers.IO) {
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
    val name = fingerprint.name?.let(::normalizeEpisodeName)?.takeIf { it.isNotEmpty() } ?: return null
    val airDate = fingerprint.airDate?.let(::parseDay) ?: return null
    return episodes.filter { candidate ->
        val released = candidate.released?.let(::parseDay) ?: return@filter false
        candidate.name?.let(::normalizeEpisodeName) == name &&
            abs(released.toEpochDay() - airDate.toEpochDay()) <= 1
    }.singleOrNull()
}

private fun normalizeEpisodeName(name: String): String =
    name.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun parseDay(value: String): LocalDate? =
    runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
