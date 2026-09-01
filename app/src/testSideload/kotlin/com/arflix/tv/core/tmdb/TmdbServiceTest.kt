package com.arflix.tv.core.tmdb

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbExternalIds
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Regression test for the C3 bug: tmdbToImdb() was a stub that always returned null.
 */
class TmdbServiceTest {
    private val tmdbApi = mockk<TmdbApi>()
    private val service = TmdbService(tmdbApi)

    @Test
    fun `movie type resolves via movie external ids endpoint`() = runBlocking {
        coEvery { tmdbApi.getMovieExternalIds(603, any()) } returns TmdbExternalIds(imdbId = "tt0133093")

        val result = service.tmdbToImdb(603, "movie")

        assertEquals("tt0133093", result)
    }

    @Test
    fun `tv type resolves via tv external ids endpoint`() = runBlocking {
        coEvery { tmdbApi.getTvExternalIds(1396, any()) } returns TmdbExternalIds(imdbId = "tt0903747")

        val result = service.tmdbToImdb(1396, "tv")

        assertEquals("tt0903747", result)
    }

    @Test
    fun `network failure returns null instead of throwing`() = runBlocking {
        coEvery { tmdbApi.getMovieExternalIds(603, any()) } throws IOException("timeout")

        val result = service.tmdbToImdb(603, "movie")

        assertNull(result)
    }
}
