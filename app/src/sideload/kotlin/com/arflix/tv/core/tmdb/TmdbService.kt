package com.arflix.tv.core.tmdb

import android.util.Log
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"

@Singleton
class TmdbService @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        return try {
            val ids = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)) {
                tmdbApi.getTvExternalIds(tmdbId, Constants.TMDB_API_KEY)
            } else {
                tmdbApi.getMovieExternalIds(tmdbId, Constants.TMDB_API_KEY)
            }
            ids.imdbId
        } catch (e: retrofit2.HttpException) {
            Log.w(TAG, "tmdbToImdb($tmdbId, $mediaType): HTTP ${e.code()}")
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "tmdbToImdb($tmdbId, $mediaType): ${e.message}")
            null
        }
    }
}
