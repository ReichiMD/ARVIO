package com.arflix.tv.core.tmdb

import android.util.Log
import com.arflix.tv.data.api.TmdbAlternativeTitle
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.domain.model.ContentType
import com.arflix.tv.util.Constants
import com.arflix.tv.util.ContentLanguage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"

// Primary lookup stays English: most CloudStream providers index by English/original
// title, so that is the best single first guess.
private const val LANGUAGE = "en-US"

/** Enrichments held for one scraper run — every provider asks for the same film. */
private const val MAX_CACHED_ENRICHMENTS = 128

data class TmdbEnrichment(
    val localizedTitle: String?,
    val releaseInfo: String?,
    val originalTitle: String?,
    val alternativeTitles: List<String>
)

/**
 * Bridges ARVIO's TMDB-ID-based content model to the title-based search API that
 * externally-loaded CloudStream .cs3 plugins expect (see ExternalExtensionRunner).
 */
@Singleton
class TmdbMetadataService @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()

    /**
     * @param providerLanguage the language the calling provider indexes in, from
     *   CloudStream's `MainAPI.lang` ("de", "es", ...). A German site lists a film only
     *   under its German release title, whatever language the app itself is set to, so
     *   the provider's own language earns a title candidate independently of the user's
     *   content-language setting.
     */
    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        providerLanguage: String? = null
    ): TmdbEnrichment? {
        val id = tmdbId.toIntOrNull() ?: return null
        val tags = searchLanguageTags(providerLanguage)
        val cacheKey = "$id|$contentType|${tags.joinToString(",")}"
        enrichmentCache[cacheKey]?.let { return it }

        val enrichment = try {
            if (contentType == ContentType.MOVIE) {
                val details = tmdbApi.getMovieDetails(id, Constants.TMDB_API_KEY, language = LANGUAGE)
                TmdbEnrichment(
                    localizedTitle = details.title.ifBlank { null },
                    releaseInfo = details.releaseDate,
                    originalTitle = details.originalTitle,
                    alternativeTitles = movieAlternativeTitles(id, details.title, tags)
                )
            } else {
                val details = tmdbApi.getTvDetails(id, Constants.TMDB_API_KEY, language = LANGUAGE)
                TmdbEnrichment(
                    localizedTitle = details.name.ifBlank { null },
                    releaseInfo = details.firstAirDate,
                    originalTitle = details.originalName,
                    alternativeTitles = tvAlternativeTitles(id, details.name, tags)
                )
            }
        } catch (e: retrofit2.HttpException) {
            Log.w(TAG, "fetchEnrichment($tmdbId, $contentType): HTTP ${e.code()}")
            return null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "fetchEnrichment($tmdbId, $contentType): ${e.message}")
            return null
        }

        Log.i(
            TAG,
            "fetchEnrichment($tmdbId, $contentType) languages=$tags " +
                "-> ${enrichment.alternativeTitles.size} alternative titles"
        )
        // Every provider in a scraper run asks for the same film, so without this the
        // TMDB calls multiply by the number of installed plugins — that is what pushed
        // one provider into HTTP 429 on the 02.09.2026 run.
        if (enrichmentCache.size >= MAX_CACHED_ENRICHMENTS) enrichmentCache.clear()
        enrichmentCache[cacheKey] = enrichment
        return enrichment
    }

    /**
     * The languages worth asking TMDB for, in priority order: the user's content-language
     * setting first, then the provider's own language.
     *
     * English is deliberately absent — [LANGUAGE] already fetches the English title as the
     * primary candidate, so an "en" entry here would only duplicate it.
     */
    private fun searchLanguageTags(providerLanguage: String?): List<String> {
        val tags = LinkedHashSet<String>()
        normalizeTag(ContentLanguage.tag)?.let(tags::add)
        normalizeTag(providerLanguage)?.let(tags::add)
        return tags.toList()
    }

    /** "de_DE" / "de-DE" / "de" -> "de-DE" or "de"; null for blank and for English. */
    private fun normalizeTag(raw: String?): String? {
        val tag = raw?.trim()?.replace('_', '-').orEmpty()
        if (tag.isBlank()) return null
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language.lowercase()
        if (language.isBlank() || language == "en") return null
        val country = locale.country.uppercase()
        return if (country.isBlank()) language else "$language-$country"
    }

    /**
     * Countries whose alternative titles are worth keeping: the regions named by the
     * language tags, plus every other country that speaks those languages, taken from
     * the platform's own locale data.
     *
     * The previous version filtered on the device country alone, which handed a German
     * speaker in Austria or Switzerland exactly zero alternative titles. Cyrillic, CJK
     * and other non-matching scripts are still dropped downstream by the runner, and
     * MAX_ALT_TITLES caps how many survive, so widening this does not reopen the request
     * flood that the country filter was introduced to stop.
     */
    private fun acceptedRegions(tags: List<String>): Set<String> {
        val regions = LinkedHashSet<String>()
        for (tag in tags) {
            val locale = Locale.forLanguageTag(tag)
            locale.country.uppercase().takeIf { it.isNotBlank() }?.let(regions::add)
            regions += regionsForLanguage(locale.language)
        }
        return regions
    }

    private fun regionsForLanguage(language: String): Set<String> {
        if (language.isBlank()) return emptySet()
        return Locale.getAvailableLocales()
            .asSequence()
            .filter { it.language.equals(language, ignoreCase = true) && it.country.isNotBlank() }
            .map { it.country.uppercase() }
            .toSet()
    }

    /**
     * Localized release titles plus TMDB's alternative titles, deduplicated against the
     * English primary. Each lookup is optional: a failure here must not cost the caller
     * its enrichment, since the English title alone still works for most providers.
     * ExternalExtensionRunner does the Latin-script filtering and capping.
     */
    private suspend fun movieAlternativeTitles(
        id: Int,
        primary: String,
        tags: List<String>
    ): List<String> {
        val localized = tags.mapNotNull { tag ->
            runCatchingTitle { tmdbApi.getMovieDetails(id, Constants.TMDB_API_KEY, language = tag).title }
        }
        val regions = acceptedRegions(tags)
        val alternatives = if (regions.isEmpty()) {
            emptyList()
        } else {
            runCatchingTitles {
                tmdbApi.getMovieAlternativeTitles(id, Constants.TMDB_API_KEY).titles.inRegions(regions)
            }
        }
        return mergeTitles(primary, localized, alternatives)
    }

    private suspend fun tvAlternativeTitles(
        id: Int,
        primary: String,
        tags: List<String>
    ): List<String> {
        val localized = tags.mapNotNull { tag ->
            runCatchingTitle { tmdbApi.getTvDetails(id, Constants.TMDB_API_KEY, language = tag).name }
        }
        val regions = acceptedRegions(tags)
        val alternatives = if (regions.isEmpty()) {
            emptyList()
        } else {
            runCatchingTitles {
                tmdbApi.getTvAlternativeTitles(id, Constants.TMDB_API_KEY).results.inRegions(regions)
            }
        }
        return mergeTitles(primary, localized, alternatives)
    }

    private fun List<TmdbAlternativeTitle>.inRegions(regions: Set<String>): List<String> =
        filter { it.country?.uppercase() in regions }.map { it.title }

    // Localized titles first: they are the ones a regional provider is most likely to index.
    private fun mergeTitles(
        primary: String,
        localized: List<String>,
        alternatives: List<String>
    ): List<String> =
        (localized + alternatives)
            .map(String::trim)
            .filter { it.isNotBlank() && !it.equals(primary.trim(), ignoreCase = true) }
            .distinctBy { it.lowercase() }

    private suspend fun runCatchingTitle(block: suspend () -> String): String? = try {
        block().ifBlank { null }
    } catch (e: retrofit2.HttpException) {
        Log.w(TAG, "localized title lookup failed: HTTP ${e.code()}")
        null
    } catch (e: java.io.IOException) {
        Log.w(TAG, "localized title lookup failed: ${e.message}")
        null
    }

    private suspend fun runCatchingTitles(block: suspend () -> List<String>): List<String> = try {
        block()
    } catch (e: retrofit2.HttpException) {
        Log.w(TAG, "alternative titles lookup failed: HTTP ${e.code()}")
        emptyList()
    } catch (e: java.io.IOException) {
        Log.w(TAG, "alternative titles lookup failed: ${e.message}")
        emptyList()
    }
}
