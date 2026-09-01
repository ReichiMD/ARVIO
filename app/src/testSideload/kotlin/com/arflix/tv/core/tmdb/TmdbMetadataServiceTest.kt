package com.arflix.tv.core.tmdb

import android.util.Log
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.api.TmdbTvDetails
import com.arflix.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Regression test for the C3 bug: fetchEnrichment() was a stub that always returned null,
 * so every non-TmdbProvider CloudStream plugin failed to search for anything (see docs/19
 * in the office repo). It must now actually call TMDB and map the response.
 */
class TmdbMetadataServiceTest {
    private val tmdbApi = mockk<TmdbApi>()
    private val service = TmdbMetadataService(tmdbApi)

    @Before
    fun setUp() {
        // android.util.Log isn't mocked by default in plain JVM unit tests (no
        // Robolectric here) and throws when called — stub it for the error-path tests.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @Test
    fun `movie enrichment maps title, release date and original title`() = runBlocking {
        coEvery { tmdbApi.getMovieDetails(603, any(), language = "en-US") } returns TmdbMovieDetails(
            id = 603,
            title = "The Matrix",
            originalTitle = "The Matrix",
            releaseDate = "1999-03-30"
        )

        val result = service.fetchEnrichment("603", ContentType.MOVIE)

        assertEquals("The Matrix", result?.localizedTitle)
        assertEquals("1999-03-30", result?.releaseInfo)
        assertEquals("The Matrix", result?.originalTitle)
    }

    @Test
    fun `tv enrichment maps name and first air date`() = runBlocking {
        coEvery { tmdbApi.getTvDetails(1396, any(), language = "en-US") } returns TmdbTvDetails(
            id = 1396,
            name = "Breaking Bad",
            originalName = "Breaking Bad",
            firstAirDate = "2008-01-20"
        )

        val result = service.fetchEnrichment("1396", ContentType.SERIES)

        assertEquals("Breaking Bad", result?.localizedTitle)
        assertEquals("2008-01-20", result?.releaseInfo)
    }

    @Test
    fun `non-numeric tmdbId returns null without calling the API`() = runBlocking {
        val result = service.fetchEnrichment("not-a-number", ContentType.MOVIE)

        assertNull(result)
    }

    @Test
    fun `network failure returns null instead of throwing`() = runBlocking {
        coEvery { tmdbApi.getMovieDetails(603, any(), language = "en-US") } throws IOException("timeout")

        val result = service.fetchEnrichment("603", ContentType.MOVIE)

        assertNull(result)
    }
}
