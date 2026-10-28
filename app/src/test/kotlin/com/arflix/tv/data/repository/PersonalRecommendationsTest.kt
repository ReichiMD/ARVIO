package com.arflix.tv.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbListResponse
import com.arflix.tv.data.api.TmdbMediaItem
import com.arflix.tv.data.api.TraktIds
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.settingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

class PersonalRecommendationsRankingTest {
    @Test
    fun titlesRecommendedForSeveralSeedsRiseAndExcludedOnesAreDropped() {
        val ranked = rankSeedRecommendations(
            perSeed = listOf(listOf(10, 20, 30), listOf(30, 40), listOf(30, 50)),
            excluded = setOf(20),
            limit = 10
        )
        assertEquals(30, ranked.first())
        assertTrue(20 !in ranked)
        assertEquals(ranked.size, ranked.distinct().size)
    }

    @Test
    fun earlierSeedsAndHigherPlacesWinTiesKeepFirstAppearance() {
        assertEquals(listOf(1, 2, 3, 4), rankSeedRecommendations(listOf(listOf(1, 2), listOf(3, 4)), emptySet(), 10))
        assertEquals(listOf(1, 2), rankSeedRecommendations(listOf(listOf(1, 2, 3)), emptySet(), 2))
    }

    @Test
    fun seedsComeFromNewestHistoryFirstThenOtherWatchedTitles() {
        val history = listOf(
            entry("tv", 7), entry("movie", 1), entry("movie", 1), entry("movie", 2), entry("movie", 0)
        )
        assertEquals(listOf(1, 2, 9), pickRecommendationSeeds(history, MediaType.MOVIE, listOf(2, 9, 8), 3))
        assertEquals(listOf(7), pickRecommendationSeeds(history, MediaType.TV, emptyList(), 3))
    }

    @Test
    fun startedShowsAreReadFromEpisodeKeys() {
        assertEquals(
            setOf(5, 6),
            startedShowIdsFromEpisodeKeys(setOf("show_tmdb:5:1:2", "show_tmdb:6:1:1", "trakt:99", "show_tmdb:x:1:1"))
        )
    }

    private fun entry(type: String, id: Int) = WatchHistoryEntry(user_id = "u", media_type = type, show_tmdb_id = id)
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PersonalRecommendationsRepositoryTest {
    private val state = MutableStateFlow(emptyPreferences())
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val trakt = mockk<TraktRepository>(relaxed = true)
    private val tmdb = mockk<TmdbApi>()
    private val history = mockk<WatchHistoryRepository>()
    private val watchlist = mockk<WatchlistRepository>()
    private val profiles = mockk<ProfileManager>()
    private lateinit var repository: PersonalRecommendationsRepository

    private val movies = PersonalRecommendations.defaultCatalogs().first { it.id == PersonalRecommendations.MOVIES_CATALOG_ID }
    private val shows = PersonalRecommendations.defaultCatalogs().first { it.id == PersonalRecommendations.SHOWS_CATALOG_ID }

    @Before
    fun setup() {
        val store = object : DataStore<Preferences> {
            override val data = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
        mockkStatic("com.arflix.tv.util.DataStoresKt")
        every { any<Context>().settingsDataStore } returns store
        every { profiles.getProfileIdSync() } returns "p1"
        every { profiles.profileStringKey(any()) } answers { stringPreferencesKey("profile_p1_${firstArg<String>()}") }
        every { profiles.profileStringKeyFor(any(), any()) } answers {
            stringPreferencesKey("profile_${firstArg<String>()}_${secondArg<String>()}")
        }
        every { trakt.getWatchedMoviesFromCache() } returns setOf(3)
        every { trakt.getWatchedEpisodesFromCache() } returns setOf("show_tmdb:70:1:1")
        coEvery { watchlist.exportWatchlistForProfile("p1") } returns listOf(LocalWatchlistItem(4, "movie", "On list"))
        coEvery { history.getWatchHistory() } returns emptyList()
        repository = PersonalRecommendationsRepository(context, trakt, tmdb, history, watchlist, profiles)
    }

    @After
    fun cleanup() {
        unmockkStatic("com.arflix.tv.util.DataStoresKt")
    }

    @Test
    fun traktPicksLeaveOutWatchedAndWatchlistedTitles() = runBlocking {
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.MOVIE, any()) } returns
            listOf(ids(1), ids(3), ids(4), ids(2), TraktIds(trakt = 99))

        assertEquals(listOf(MediaType.MOVIE to 1, MediaType.MOVIE to 2), repository.refsFor(movies))
    }

    @Test
    fun withoutTraktTheRowIsBuiltFromTheWatchedTitles() = runBlocking {
        coEvery { trakt.hasTrakt() } returns false
        coEvery { history.getWatchHistory() } returns
            listOf(WatchHistoryEntry(user_id = "u", media_type = "tv", show_tmdb_id = 80))
        coEvery { tmdb.getRecommendations("tv", 80, any(), any()) } returns tmdbList(70, 81, 82)
        coEvery { tmdb.getRecommendations("tv", 70, any(), any()) } returns tmdbList(82, 80, 83)

        // 70 and 80 are watched seeds, 82 is recommended for both.
        assertEquals(listOf(82, 81, 83), repository.refsFor(shows).map { it.second })
    }

    @Test
    fun noHistoryMeansNoRow() = runBlocking {
        coEvery { trakt.hasTrakt() } returns false
        every { trakt.getWatchedMoviesFromCache() } returns emptySet()
        assertTrue(repository.refsFor(movies).isEmpty())
    }

    @Test
    fun notInterestedRemovesTheTitleAndTellsTrakt() = runBlocking {
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.MOVIE, any()) } returns listOf(ids(1), ids(2))
        coEvery { trakt.hideRecommendation(any(), any()) } returns true
        repository.refsFor(movies)

        repository.hide(MediaType.MOVIE, 1)

        assertEquals(listOf(MediaType.MOVIE to 2), repository.refsFor(movies))
        coVerify { trakt.hideRecommendation(MediaType.MOVIE, ids(1)) }
    }

    @Test
    fun titlesWatchedOrListedAfterLoadingLeaveTheCachedRow() = runBlocking {
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.MOVIE, any()) } returns listOf(ids(1), ids(2), ids(5))
        assertEquals(listOf(1, 2, 5), repository.refsFor(movies).map { it.second })

        every { trakt.getWatchedMoviesFromCache() } returns setOf(3, 1)
        coEvery { watchlist.exportWatchlistForProfile("p1") } returns listOf(LocalWatchlistItem(5, "movie", "Later"))

        assertEquals(listOf(2), repository.refsFor(movies).map { it.second })
        coVerify(exactly = 1) { trakt.getRecommendations(MediaType.MOVIE, any()) }
    }

    @Test
    fun aFailedTraktRequestFallsBackToHistoryAndAsksTraktAgainLater() = runBlocking {
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.TV, any()) } returns null
        coEvery { history.getWatchHistory() } returns
            listOf(WatchHistoryEntry(user_id = "u", media_type = "tv", show_tmdb_id = 80))
        coEvery { tmdb.getRecommendations("tv", any(), any(), any()) } returns tmdbList(81)

        assertEquals(listOf(81), repository.refsFor(shows).map { it.second })
    }

    private fun ids(tmdb: Int) = TraktIds(trakt = tmdb + 1000, tmdb = tmdb)

    private fun tmdbList(vararg ids: Int) = TmdbListResponse(results = ids.map { TmdbMediaItem(id = it) })
}
