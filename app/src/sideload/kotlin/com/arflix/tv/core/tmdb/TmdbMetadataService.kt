package com.arflix.tv.core.tmdb

import android.util.Log
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.domain.model.ContentType
import com.arflix.tv.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"

// Primary lookup stays English: most CloudStream providers index by English/original
// title, so that is the best single first guess.
private const val LANGUAGE = "en-US"

/**
 * The device's language tag in TMDB form ("de-DE"), or null when it adds nothing over
 * the English primary. Regional providers list a film under its local release title —
 * a German site has no "The Super Mario Bros. Movie", only "Der Super Mario Bros. Film"
 * — so the localized title has to be among the candidates or those providers can never
 * match. Device locale rather than a hardcoded language: the same gap exists for every
 * non-English provider set, not just the German one.
 */
private fun localeLanguageTag(): String? {
    val locale = java.util.Locale.getDefault()
    val language = locale.language.lowercase()
    if (language.isBlank() || language == "en") return null
    val country = locale.country.uppercase()
    return if (country.isBlank()) language else "$language-$country"
}

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
    suspend fun fetchEnrichment(tmdbId: String, contentType: ContentType): TmdbEnrichment? {
        val id = tmdbId.toIntOrNull() ?: return null
        return try {
            if (contentType == ContentType.MOVIE) {
                val details = tmdbApi.getMovieDetails(id, Constants.TMDB_API_KEY, language = LANGUAGE)
                TmdbEnrichment(
                    localizedTitle = details.title.ifBlank { null },
                    releaseInfo = details.releaseDate,
                    originalTitle = details.originalTitle,
                    alternativeTitles = movieAlternativeTitles(id, details.title)
                )
            } else {
                val details = tmdbApi.getTvDetails(id, Constants.TMDB_API_KEY, language = LANGUAGE)
                TmdbEnrichment(
                    localizedTitle = details.name.ifBlank { null },
                    releaseInfo = details.firstAirDate,
                    originalTitle = details.originalName,
                    alternativeTitles = tvAlternativeTitles(id, details.name)
                )
            }
        } catch (e: retrofit2.HttpException) {
            Log.w(TAG, "fetchEnrichment($tmdbId, $contentType): HTTP ${e.code()}")
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "fetchEnrichment($tmdbId, $contentType): ${e.message}")
            null
        }
    }

    /**
     * Localized release title plus TMDB's alternative titles, deduplicated against the
     * English primary. Each lookup is optional: a failure here must not cost the caller
     * its enrichment, since the English title alone still works for most providers.
     * ExternalExtensionRunner does the Latin-script filtering and capping.
     */
    private suspend fun movieAlternativeTitles(id: Int, primary: String): List<String> {
        val localized = localeLanguageTag()?.let { tag ->
            runCatchingTitle { tmdbApi.getMovieDetails(id, Constants.TMDB_API_KEY, language = tag).title }
        }
        val alternatives = runCatchingTitles {
            tmdbApi.getMovieAlternativeTitles(id, Constants.TMDB_API_KEY).titles.forCurrentCountry()
        }
        return mergeTitles(primary, localized, alternatives)
    }

    private suspend fun tvAlternativeTitles(id: Int, primary: String): List<String> {
        val localized = localeLanguageTag()?.let { tag ->
            runCatchingTitle { tmdbApi.getTvDetails(id, Constants.TMDB_API_KEY, language = tag).name }
        }
        val alternatives = runCatchingTitles {
            tmdbApi.getTvAlternativeTitles(id, Constants.TMDB_API_KEY).results.forCurrentCountry()
        }
        return mergeTitles(primary, localized, alternatives)
    }

    /**
     * Keep only titles released in the user's own country. TMDB returns every country's
     * variant — for one film that was 8 candidates including Azerbaijani, Catalan and
     * Croatian ones. Since each candidate costs one search request PER PROVIDER, and they
     * are issued in parallel, the untrimmed list made Welt answer HTTP 429 on the
     * 02.09.2026 device run. A Croatian title cannot help a German provider anyway, so
     * this is both lighter and more accurate.
     */
    private fun List<com.arflix.tv.data.api.TmdbAlternativeTitle>.forCurrentCountry(): List<String> {
        val country = java.util.Locale.getDefault().country.uppercase()
        if (country.isBlank()) return emptyList()
        return filter { it.country?.uppercase() == country }.map { it.title }
    }

    // Localized title first: it is the one a regional provider is most likely to index.
    private fun mergeTitles(primary: String, localized: String?, alternatives: List<String>): List<String> =
        (listOfNotNull(localized) + alternatives)
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
