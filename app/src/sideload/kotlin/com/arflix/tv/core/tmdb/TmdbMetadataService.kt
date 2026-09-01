package com.arflix.tv.core.tmdb

import android.util.Log
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.domain.model.ContentType
import com.arflix.tv.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"

// Fixed to English: candidate-title matching in ExternalExtensionRunner already falls
// back through originalTitle, and most CloudStream providers' own search indexes are
// keyed off English/original titles rather than the user's display-language preference.
private const val LANGUAGE = "en-US"

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
                    alternativeTitles = emptyList()
                )
            } else {
                val details = tmdbApi.getTvDetails(id, Constants.TMDB_API_KEY, language = LANGUAGE)
                TmdbEnrichment(
                    localizedTitle = details.name.ifBlank { null },
                    releaseInfo = details.firstAirDate,
                    originalTitle = details.originalName,
                    alternativeTitles = emptyList()
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
}
