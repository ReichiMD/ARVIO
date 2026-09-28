package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbExternalIds
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.api.TmdbTvDetails
import com.arflix.tv.data.model.MediaType
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Collections

/**
 * Row loading asks TMDB once per title (details with `external_ids` appended) and skips the
 * Cinemeta IMDb rating; hero and details still get the rating on their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MediaDetailsRequestsTest {
    private val tmdb = mockk<TmdbApi>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val http = mockk<OkHttpClient> {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            requests += request.url.toString()
            val json = """{"meta":{"imdbRating":"8.7"}}"""
            mockk<okhttp3.Call> {
                every { execute() } returns Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(json.toResponseBody("application/json".toMediaType())).build()
            }
        }
    }
    private val media = MediaRepository(mockk(relaxed = true), tmdb, mockk(), mockk(), http, mockk(), mockk())

    private fun movie(id: Int, imdbId: String? = "tt0133093") = TmdbMovieDetails(
        id = id,
        title = "Movie $id",
        externalIds = imdbId?.let { TmdbExternalIds(imdbId = it) }
    )

    private fun cinemetaRequests() = requests.count { "cinemeta" in it }

    @Test fun rowLoadAsksTmdbOnceAndSkipsCinemeta() = runBlocking {
        coEvery { tmdb.getMovieDetails(603, any(), any(), any()) } returns movie(603)

        val item = media.getMovieDetails(603, withImdbRating = false)

        assertEquals("Movie 603", item.title)
        assertEquals("", item.imdbRating)
        coVerify(exactly = 1) { tmdb.getMovieDetails(603, any(), "release_dates,external_ids", any()) }
        coVerify(exactly = 0) { tmdb.getMovieExternalIds(any(), any()) }
        assertEquals(0, cinemetaRequests())
    }

    @Test fun tvRowLoadAppendsExternalIdsToContentRatings() = runBlocking {
        coEvery { tmdb.getTvDetails(1399, any(), any(), any()) } returns
            TmdbTvDetails(id = 1399, name = "Show", externalIds = TmdbExternalIds(imdbId = "tt0944947"))

        val item = media.getTvDetails(1399, withImdbRating = false)

        assertEquals("Show", item.title)
        assertEquals("tt0944947", media.getCachedImdbId(MediaType.TV, 1399))
        coVerify(exactly = 1) { tmdb.getTvDetails(1399, any(), "content_ratings,external_ids", any()) }
        coVerify(exactly = 0) { tmdb.getTvExternalIds(any(), any()) }
        assertEquals(0, cinemetaRequests())
    }

    @Test fun appendedImdbIdIsCachedForLaterRatingLookups() = runBlocking {
        coEvery { tmdb.getMovieDetails(603, any(), any(), any()) } returns movie(603)

        media.getMovieDetails(603, withImdbRating = false)

        assertEquals("tt0133093", media.getCachedImdbId(MediaType.MOVIE, 603))
        assertEquals("8.7", media.getImdbRating(MediaType.MOVIE, 603))
        coVerify(exactly = 0) { tmdb.getMovieExternalIds(any(), any()) }
        assertEquals(1, cinemetaRequests())
    }

    @Test fun cachedTitleWithoutRatingReturnsAtOnceUnlessTheRatingIsWanted() = runBlocking {
        coEvery { tmdb.getMovieDetails(603, any(), any(), any()) } returns movie(603)
        media.getMovieDetails(603, withImdbRating = false)

        val again = media.getMovieDetails(603, withImdbRating = false)
        assertEquals("", again.imdbRating)
        assertEquals(0, cinemetaRequests())

        val withRating = media.getMovieDetails(603)
        assertEquals("8.7", withRating.imdbRating)
        assertEquals(1, cinemetaRequests())
        // The cached title was reused: still one TMDB details request, no /external_ids.
        coVerify(exactly = 1) { tmdb.getMovieDetails(603, any(), any(), any()) }
        coVerify(exactly = 0) { tmdb.getMovieExternalIds(any(), any()) }

        // Once fetched, the rating stays with the cached title for row callers too.
        assertEquals("8.7", media.getMovieDetails(603, withImdbRating = false).imdbRating)
    }

    @Test fun coldLoadWithRatingUsesTheAppendedId() = runBlocking {
        coEvery { tmdb.getMovieDetails(603, any(), any(), any()) } returns movie(603)

        val item = media.getMovieDetails(603)

        assertEquals("8.7", item.imdbRating)
        coVerify(exactly = 0) { tmdb.getMovieExternalIds(any(), any()) }
        assertEquals(1, cinemetaRequests())
    }

    @Test fun rowLoadKeepsARatingAlreadyInMemory() = runBlocking {
        coEvery { tmdb.getMovieDetails(603, any(), any(), any()) } returns movie(603)
        // The hero fetched the rating first (e.g. its own lookup raced the row).
        assertEquals("8.7", media.getImdbRating(MediaType.MOVIE, 603, "tt0133093"))

        val row = media.getMovieDetails(603, withImdbRating = false)

        assertEquals("8.7", row.imdbRating)
        assertEquals(1, cinemetaRequests())
    }

    @Test fun cachedRowTitleTakesARatingFetchedSince() = runBlocking {
        coEvery { tmdb.getMovieDetails(603, any(), any(), any()) } returns movie(603)
        media.getMovieDetails(603, withImdbRating = false)
        // The banner looked the rating up on its own after the row had loaded.
        media.getImdbRating(MediaType.MOVIE, 603)

        assertEquals("8.7", media.getMovieDetails(603, withImdbRating = false).imdbRating)
        assertEquals(1, cinemetaRequests())
        coVerify(exactly = 1) { tmdb.getMovieDetails(603, any(), any(), any()) }
    }

    @Test fun titleWithoutImdbIdIsNotLookedUpAgain() = runBlocking {
        coEvery { tmdb.getMovieDetails(606, any(), any(), any()) } returns
            TmdbMovieDetails(id = 606, title = "Unlisted", externalIds = TmdbExternalIds(imdbId = ""))

        val item = media.getMovieDetails(606)

        assertEquals("", item.imdbRating)
        coVerify(exactly = 0) { tmdb.getMovieExternalIds(any(), any()) }
        assertEquals(0, cinemetaRequests())
    }

    @Test fun appendedExternalIdsAreReadFromTheTmdbJson() {
        val gson = Gson()
        val movie = gson.fromJson(
            """{"id":603,"title":"The Matrix","release_dates":{"results":[]},
               "external_ids":{"imdb_id":"tt0133093","tvdb_id":null}}""",
            TmdbMovieDetails::class.java
        )
        val show = gson.fromJson(
            """{"id":1399,"name":"Game of Thrones","content_ratings":{"results":[]},
               "external_ids":{"imdb_id":"tt0944947","tvdb_id":121361}}""",
            TmdbTvDetails::class.java
        )
        assertEquals("tt0133093", movie.externalIds?.imdbId)
        assertEquals("tt0944947", show.externalIds?.imdbId)
        assertEquals(121361, show.externalIds?.tvdbId)
        assertNull(gson.fromJson("""{"id":1,"title":"x"}""", TmdbMovieDetails::class.java).externalIds)
    }

    @Test fun missingAppendedBlockStillReturnsTheTitle() = runBlocking {
        coEvery { tmdb.getMovieDetails(604, any(), any(), any()) } returns movie(604, imdbId = null)
        coEvery { tmdb.getMovieDetails(605, any(), any(), any()) } returns movie(605, imdbId = null)
        coEvery { tmdb.getMovieExternalIds(605, any()) } returns TmdbExternalIds(imdbId = "tt0234215")

        val row = media.getMovieDetails(604, withImdbRating = false)
        assertEquals("Movie 604", row.title)
        assertNull(media.getCachedImdbId(MediaType.MOVIE, 604))
        coVerify(exactly = 0) { tmdb.getMovieExternalIds(604, any()) }

        // A caller that wants the rating falls back to /external_ids, as before.
        val hero = media.getMovieDetails(605)
        assertEquals("8.7", hero.imdbRating)
        coVerify(exactly = 1) { tmdb.getMovieExternalIds(605, any()) }
    }
}
