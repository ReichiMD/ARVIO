package com.arflix.tv.ui.screens.details

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.StreamIntegrationRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.network.TmdbPriorityDispatcher
import com.arflix.tv.util.settingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Details loads the title without the IMDb rating (rows never need it) and fills the rating in
 * itself. It must not depend on its own /external_ids call alone: the details request already
 * cached the IMDb id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsImdbRatingTest {
    private val movie = MediaItem(id = 603, title = "The Matrix", mediaType = MediaType.MOVIE)
    private val store = ViewModelStore()
    private val media = mockk<MediaRepository>(relaxed = true)
    private val tmdb = mockk<TmdbApi>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val settings = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = data.value
        }
        mockkStatic("com.arflix.tv.util.DataStoresKt")
        every { any<Context>().settingsDataStore } returns settings
        every { media.episodeRatingsUpdated } returns MutableSharedFlow<Pair<Int, Int>>()
        every { media.getCachedFullItem(MediaType.MOVIE, movie.id) } returns movie
        coEvery { media.getMovieDetails(movie.id, any()) } returns movie
        coEvery { tmdb.getMovieExternalIds(movie.id, any()) } throws IOException("rate limited")
        every { media.getCachedImdbId(MediaType.MOVIE, movie.id) } returns "tt0133093"
        coEvery { media.getImdbRating(MediaType.MOVIE, movie.id, "tt0133093") } returns "8.7"
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkStatic("com.arflix.tv.util.DataStoresKt")
        unmockkStatic(Log::class)
    }

    @Test
    fun `rating comes from the cached IMDb id when external ids fail`() = runTest {
        val streams = mockk<StreamRepository>(relaxed = true)
        every { streams.installedAddons } returns flowOf(emptyList())
        val integrations = mockk<StreamIntegrationRepository>(relaxed = true)
        every { integrations.getUnifiedSourceOrderedIds() } returns flowOf(emptyList())
        val model = DetailsViewModel(
            context = mockk(relaxed = true),
            mediaRepository = media,
            mdbListRepository = mockk(relaxed = true),
            pluginManager = mockk(relaxed = true),
            profileManager = mockk(relaxed = true),
            traktRepository = mockk(relaxed = true),
            remoteSyncManager = mockk(relaxed = true),
            streamRepository = streams,
            networkMonitor = mockk(relaxed = true),
            tmdbPriorityDispatcher = TmdbPriorityDispatcher(),
            animeMapper = mockk(relaxed = true),
            tmdbApi = tmdb,
            watchHistoryRepository = mockk(relaxed = true),
            watchlistRepository = mockk(relaxed = true),
            cloudSyncRepository = mockk(relaxed = true),
            launcherContinueWatchingRepository = mockk(relaxed = true),
            streamIntegrationRepository = integrations
        )
        store.put("details", model)

        model.loadDetails(MediaType.MOVIE, movie.id)
        runCurrent()

        assertEquals("8.7", model.uiState.value.item?.imdbRating)
        assertEquals("tt0133093", model.uiState.value.imdbId)
        coVerify(exactly = 0) { media.getMovieDetails(movie.id, true) }
    }
}
