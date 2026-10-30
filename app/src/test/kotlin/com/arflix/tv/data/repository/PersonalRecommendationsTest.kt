package com.arflix.tv.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
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

class PersonalRecommendationsWatchedTest {
    @Test
    fun startedShowsAreReadFromEpisodeKeys() {
        assertEquals(
            setOf(5, 6),
            startedShowIdsFromEpisodeKeys(setOf("show_tmdb:5:1:2", "show_tmdb:6:1:1", "trakt:99", "show_tmdb:x:1:1"))
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PersonalRecommendationsRepositoryTest {
    private val state = MutableStateFlow(emptyPreferences())
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val trakt = mockk<TraktRepository>(relaxed = true)
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
        repository = PersonalRecommendationsRepository(context, trakt, watchlist, profiles)
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
    fun withoutTraktThereIsNoRow() = runBlocking {
        coEvery { trakt.hasTrakt() } returns false
        assertTrue(repository.refsFor(movies).isEmpty())
        coVerify(exactly = 0) { trakt.getRecommendations(any(), any()) }
    }

    @Test
    fun disconnectingTraktEmptiesTheCachedRow() = runBlocking {
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.MOVIE, any()) } returns listOf(ids(1))
        assertEquals(listOf(1), repository.refsFor(movies).map { it.second })

        coEvery { trakt.hasTrakt() } returns false
        assertTrue(repository.refsFor(movies).isEmpty())

        // Connected again (maybe another account): Trakt is asked afresh.
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.MOVIE, any()) } returns listOf(ids(2))
        assertEquals(listOf(2), repository.refsFor(movies).map { it.second })
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
    fun aFailedTraktRequestIsAskedAgainOnTheNextVisit() = runBlocking {
        coEvery { trakt.hasTrakt() } returns true
        coEvery { trakt.getRecommendations(MediaType.TV, any()) } returns null
        assertTrue(repository.refsFor(shows).isEmpty())

        coEvery { trakt.getRecommendations(MediaType.TV, any()) } returns listOf(ids(81), ids(70))
        // 70 is a started show.
        assertEquals(listOf(81), repository.refsFor(shows).map { it.second })
    }

    private fun ids(tmdb: Int) = TraktIds(trakt = tmdb + 1000, tmdb = tmdb)
}
