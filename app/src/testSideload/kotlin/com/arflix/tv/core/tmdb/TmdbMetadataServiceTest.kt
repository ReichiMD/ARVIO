package com.arflix.tv.core.tmdb

import android.util.Log
import com.arflix.tv.data.api.TmdbAlternativeTitle
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbMovieAlternativeTitles
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.api.TmdbTvDetails
import com.arflix.tv.domain.model.ContentType
import com.arflix.tv.util.ContentLanguage
import io.mockk.coEvery
import io.mockk.coVerify
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
        every { Log.i(any(), any<String>()) } returns 0
        // Global state shared with the running app; every test states what it needs.
        ContentLanguage.tag = "en-US"
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

    @Test
    fun `provider language contributes a title even when the app runs in English`() = runBlocking {
        ContentLanguage.tag = "en-US"
        coEvery { tmdbApi.getMovieDetails(502356, any(), language = "en-US") } returns TmdbMovieDetails(
            id = 502356,
            title = "The Super Mario Bros. Movie",
            originalTitle = "The Super Mario Bros. Movie",
            releaseDate = "2023-04-05"
        )
        coEvery { tmdbApi.getMovieDetails(502356, any(), language = "de") } returns TmdbMovieDetails(
            id = 502356,
            title = "Der Super Mario Bros. Film",
            originalTitle = "The Super Mario Bros. Movie",
            releaseDate = "2023-04-05"
        )
        coEvery { tmdbApi.getMovieAlternativeTitles(502356, any()) } returns TmdbMovieAlternativeTitles(
            id = 502356,
            titles = emptyList()
        )

        val result = service.fetchEnrichment("502356", ContentType.MOVIE, providerLanguage = "de")

        assertEquals(listOf("Der Super Mario Bros. Film"), result?.alternativeTitles)
    }

    @Test
    fun `alternative titles are kept for every region that speaks the language`() = runBlocking {
        ContentLanguage.tag = "de-DE"
        coEvery { tmdbApi.getMovieDetails(603, any(), language = "en-US") } returns TmdbMovieDetails(
            id = 603,
            title = "The Matrix",
            originalTitle = "The Matrix",
            releaseDate = "1999-03-30"
        )
        coEvery { tmdbApi.getMovieDetails(603, any(), language = "de-DE") } returns TmdbMovieDetails(
            id = 603,
            title = "Matrix",
            originalTitle = "The Matrix",
            releaseDate = "1999-03-30"
        )
        coEvery { tmdbApi.getMovieAlternativeTitles(603, any()) } returns TmdbMovieAlternativeTitles(
            id = 603,
            titles = listOf(
                TmdbAlternativeTitle(country = "AT", title = "Matrix - Der Film"),
                TmdbAlternativeTitle(country = "HR", title = "Matrica")
            )
        )

        val result = service.fetchEnrichment("603", ContentType.MOVIE)

        // Austria counts as a German-speaking region; Croatia does not.
        assertEquals(listOf("Matrix", "Matrix - Der Film"), result?.alternativeTitles)
    }

    @Test
    fun `English content language asks TMDB for no extra language`() = runBlocking {
        ContentLanguage.tag = "en-US"
        coEvery { tmdbApi.getMovieDetails(550, any(), language = "en-US") } returns TmdbMovieDetails(
            id = 550,
            title = "Fight Club",
            originalTitle = "Fight Club",
            releaseDate = "1999-10-15"
        )

        val result = service.fetchEnrichment("550", ContentType.MOVIE)

        // No language tag left over once English is dropped, so neither the localized
        // lookup nor the alternative-titles call is worth making.
        assertEquals(emptyList<String>(), result?.alternativeTitles)
        coVerify(exactly = 0) { tmdbApi.getMovieAlternativeTitles(550, any()) }
    }
}
