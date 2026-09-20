package com.arflix.tv.data.repository

import com.arflix.tv.data.api.StalkerApi
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.StalkerCatalogKind
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where a Stalker portal's catalog category names come from.
 *
 * They are fetched by the ordinary channel load, inside the session that
 * fetches the channels, and kept on disk from there on — the settings screen
 * reads that store and never asks the portal itself. The reason is measured:
 * this portal closes an idle connection after ten seconds, and a request sent
 * into that closed socket is lost without an answer and without a retry, which
 * is what made the movie categories show up empty on first open while the live
 * TV groups — which ride along on the channels — were always there.
 */
class IptvRepositoryStalkerCategoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newRepository(): IptvRepository {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.filesDir } returns folder.root
        val okHttpClient = mockk<okhttp3.OkHttpClient>(relaxed = true)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.getProfileIdSync() } returns "profile-1"
        val invalidationBus = mockk<CloudSyncInvalidationBus>(relaxed = true)
        return IptvRepository(context, okHttpClient, profileManager, invalidationBus)
    }

    private fun portal(id: String, url: String = "http://portal.invalid/c") = StalkerPortalEntry(
        id = id,
        name = id,
        portalUrl = url,
        macAddress = "00:1A:79:00:00:01"
    )

    private fun category(id: String, title: String) = StalkerApi.StalkerCategory(id, title)

    /**
     * A moment in the future, so [IptvRepository.loadStalkerChannels] must
     * actually fetch instead of reusing the answer it remembered a few lines
     * earlier. The loader keeps a portal's answer for five minutes, which is
     * what a second call inside one test would otherwise hit.
     */
    private fun forceFresh() = System.currentTimeMillis() + 60_000L

    private fun channel(id: String) = IptvChannel(
        id = id,
        name = id,
        logo = null,
        group = "Group",
        streamUrl = "http://stream.invalid/$id"
    )

    private fun answer(
        portalId: String,
        movies: List<StalkerApi.StalkerCategory>?,
        series: List<StalkerApi.StalkerCategory>?,
        channels: List<IptvChannel> = listOf(channel("$portalId-1"))
    ) = IptvRepository.StalkerPortalChannels(
        portalId = portalId,
        api = mockk(relaxed = true),
        channels = channels,
        movieCategories = movies,
        seriesCategories = series
    )

    // ── The load fills the store ──────────────────────────────────────────

    @Test
    fun `a channel load stores both category lists of the portal it loaded`() = runTest {
        val repository = newRepository()
        val entry = portal("portal-a")
        val movies = listOf(category("848", "DE KINO 2026"), category("849", "DE KINO 2026 4K"))
        val series = listOf(category("120", "DE NETFLIX"))

        repository.loadStalkerChannels(listOf(entry)) { answer(it.id, movies, series) }

        val stored = repository.readStalkerCategoryStore().getValue("portal-a")
        assertEquals(movies, stored.movies)
        assertEquals(series, stored.series)
    }

    @Test
    fun `the stored names survive a fresh repository, so the screen needs no portal`() = runTest {
        val entry = portal("portal-a")
        val movies = listOf(category("848", "DE KINO 2026"))
        newRepository().loadStalkerChannels(listOf(entry)) { answer(it.id, movies, emptyList()) }

        // A new instance reads the file rather than any in-memory leftovers —
        // this is what an app restart does.
        val reopened = newRepository()

        assertEquals(movies, reopened.readStalkerCategoryStore().getValue("portal-a").movies)
    }

    @Test
    fun `one portal's load leaves another portal's stored names alone`() = runTest {
        val repository = newRepository()
        val first = listOf(category("848", "DE KINO 2026"))
        val second = listOf(category("120", "DE NETFLIX"))
        repository.loadStalkerChannels(listOf(portal("portal-a"))) { answer(it.id, first, first) }

        repository.loadStalkerChannels(listOf(portal("portal-b", "http://other.invalid/c"))) {
            answer(it.id, second, second)
        }

        val store = repository.readStalkerCategoryStore()
        assertEquals(first, store.getValue("portal-a").movies)
        assertEquals(second, store.getValue("portal-b").movies)
    }

    // ── A failed fetch must never look like an answer ─────────────────────

    @Test
    fun `a portal that did not answer stores nothing at all`() = runTest {
        val repository = newRepository()

        repository.loadStalkerChannels(listOf(portal("portal-a"))) { answer(it.id, null, null) }

        // Not an empty entry: an entry is what the screen reads as "asked and
        // this portal has none", and a lost request is not that.
        assertFalse(repository.readStalkerCategoryStore().containsKey("portal-a"))
    }

    @Test
    fun `a later failure does not wipe the names a good load stored`() = runTest {
        val repository = newRepository()
        val entry = portal("portal-a")
        val movies = listOf(category("848", "DE KINO 2026"))
        val series = listOf(category("120", "DE NETFLIX"))
        repository.loadStalkerChannels(listOf(entry)) { answer(it.id, movies, series) }

        repository.loadStalkerChannels(listOf(entry), forceFresh()) { answer(it.id, null, null) }

        val stored = repository.readStalkerCategoryStore().getValue("portal-a")
        assertEquals(movies, stored.movies)
        assertEquals(series, stored.series)
    }

    @Test
    fun `when only one of the two calls fails the other list is still kept`() = runTest {
        val repository = newRepository()
        val entry = portal("portal-a")
        val movies = listOf(category("848", "DE KINO 2026"))
        val series = listOf(category("120", "DE NETFLIX"))
        repository.loadStalkerChannels(listOf(entry)) { answer(it.id, movies, series) }

        // The movie call answered, the series call did not.
        repository.loadStalkerChannels(listOf(entry), forceFresh()) { answer(it.id, emptyList(), null) }

        val stored = repository.readStalkerCategoryStore().getValue("portal-a")
        assertEquals(emptyList<StalkerApi.StalkerCategory>(), stored.movies)
        assertEquals(series, stored.series)
    }

    @Test
    fun `a portal that reports no categories stores an empty, present entry`() = runTest {
        val repository = newRepository()

        repository.loadStalkerChannels(listOf(portal("portal-a"))) {
            answer(it.id, emptyList(), emptyList())
        }

        val stored = repository.readStalkerCategoryStore().getValue("portal-a")
        assertTrue(stored.movies.isEmpty())
        assertTrue(stored.fingerprint.isNotBlank())
    }

    // ── What the screen is told ───────────────────────────────────────────

    @Test
    fun `an unknown portal reads as not loaded, never as having no categories`() {
        val repository = newRepository()

        val snapshot = repository.stalkerCategorySnapshotOf(null, "abc", StalkerCatalogKind.MOVIES)

        assertFalse(snapshot.loaded)
        assertTrue(snapshot.categories.isEmpty())
    }

    @Test
    fun `a portal that answered with nothing reads as loaded and empty`() {
        val repository = newRepository()
        val stored = IptvRepository.StalkerCategoryStoreEntry(fingerprint = "abc")

        val snapshot = repository.stalkerCategorySnapshotOf(stored, "abc", StalkerCatalogKind.MOVIES)

        assertTrue(snapshot.loaded)
        assertTrue(snapshot.categories.isEmpty())
    }

    @Test
    fun `each kind reads its own list`() {
        val repository = newRepository()
        val movies = listOf(category("848", "DE KINO 2026"))
        val series = listOf(category("120", "DE NETFLIX"))
        val stored = IptvRepository.StalkerCategoryStoreEntry("abc", 1L, movies, series)

        assertEquals(
            movies,
            repository.stalkerCategorySnapshotOf(stored, "abc", StalkerCatalogKind.MOVIES).categories
        )
        assertEquals(
            series,
            repository.stalkerCategorySnapshotOf(stored, "abc", StalkerCatalogKind.SERIES).categories
        )
    }

    @Test
    fun `a portal re-pointed at another server drops the previous names`() {
        val repository = newRepository()
        val stored = IptvRepository.StalkerCategoryStoreEntry(
            fingerprint = "old-server",
            movies = listOf(category("848", "DE KINO 2026"))
        )

        val snapshot =
            repository.stalkerCategorySnapshotOf(stored, "new-server", StalkerCatalogKind.MOVIES)

        assertFalse(snapshot.loaded)
        assertTrue(snapshot.categories.isEmpty())
    }
}
