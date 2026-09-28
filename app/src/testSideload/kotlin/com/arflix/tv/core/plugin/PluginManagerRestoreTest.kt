package com.arflix.tv.core.plugin

import android.util.Log
import com.arflix.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.arflix.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.arflix.tv.core.plugin.cloudstream.ExternalRepoParseResult
import com.arflix.tv.core.plugin.cloudstream.ExternalRepoParser
import com.arflix.tv.data.local.PluginDataStore
import com.arflix.tv.data.repository.CloudSyncInvalidationBus
import com.arflix.tv.domain.model.ExternalPluginEntry
import com.arflix.tv.domain.model.PluginRepository
import com.arflix.tv.domain.model.RepositoryType
import com.arflix.tv.domain.model.ScraperInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PluginManagerRestoreTest {
    private val repo = PluginRepository(
        id = "repo1",
        name = "Repo",
        url = "https://example.invalid/repo.json",
        type = RepositoryType.EXTERNAL_DEX
    )

    private fun dexScraper(name: String, enabled: Boolean = true) = ScraperInfo(
        id = "repo1:$name",
        name = name,
        description = "",
        version = "1",
        filename = "https://example.invalid/$name.cs3",
        supportedTypes = listOf("movie", "tv"),
        enabled = enabled,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo1",
        formats = null,
        type = RepositoryType.EXTERNAL_DEX
    )

    private val dataStore = mockk<PluginDataStore>(relaxed = true)
    private val loader = mockk<ExternalExtensionLoader>(relaxed = true)
    private val parser = mockk<ExternalRepoParser>(relaxed = true)

    private fun manager(scrapers: List<ScraperInfo>): PluginManager {
        every { dataStore.repositories } returns flowOf(listOf(repo))
        every { dataStore.scrapers } returns flowOf(scrapers)
        every { dataStore.pluginsEnabled } returns flowOf(true)
        every { dataStore.groupStreamsByRepository } returns flowOf(false)
        return PluginManager(
            dataStore = dataStore,
            runtime = mockk(relaxed = true),
            externalRepoParser = parser,
            externalExtensionLoader = loader,
            externalExtensionRunner = mockk<ExternalExtensionRunner>(relaxed = true),
            invalidationBus = mockk<CloudSyncInvalidationBus>(relaxed = true)
        )
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `missing extension file is downloaded again from its url`() = runBlocking<Unit> {
        val scraper = dexScraper("Alpha")
        every { loader.hasExtensionFile(scraper.id) } returns false
        coEvery { loader.downloadExtension(any(), any()) } returns File("Alpha.cs3")

        manager(listOf(scraper)).restoreMissingScraperFiles(listOf(scraper))

        coVerify(exactly = 1) { loader.downloadExtension(scraper.id, scraper.filename) }
        verify { loader.evictCache(scraper.id) }
    }

    @Test
    fun `present extension file is not downloaded`() = runBlocking<Unit> {
        val scraper = dexScraper("Alpha")
        every { loader.hasExtensionFile(scraper.id) } returns true

        manager(listOf(scraper)).restoreMissingScraperFiles(listOf(scraper))

        coVerify(exactly = 0) { loader.downloadExtension(any(), any()) }
    }

    @Test
    fun `dead download url is tried only once per session`() = runBlocking<Unit> {
        val scraper = dexScraper("Alpha")
        every { loader.hasExtensionFile(scraper.id) } returns false
        coEvery { loader.downloadExtension(any(), any()) } returns null
        val manager = manager(listOf(scraper))

        manager.restoreMissingScraperFiles(listOf(scraper))
        manager.restoreMissingScraperFiles(listOf(scraper))
        manager.restoreMissingScraperFiles(listOf(scraper))

        coVerify(exactly = 1) { loader.downloadExtension(any(), any()) }
    }

    @Test
    fun `parallel searches download a missing file only once`() = runBlocking<Unit> {
        val scraper = dexScraper("Alpha")
        every { loader.hasExtensionFile(scraper.id) } returns false
        coEvery { loader.downloadExtension(any(), any()) } coAnswers {
            delay(50)
            File("Alpha.cs3")
        }
        val manager = manager(listOf(scraper))

        List(4) { async { manager.restoreMissingScraperFiles(listOf(scraper)) } }.awaitAll()

        coVerify(exactly = 1) { loader.downloadExtension(any(), any()) }
    }

    @Test
    fun `repository refresh allows another restore attempt`() = runBlocking<Unit> {
        val scraper = dexScraper("Alpha")
        every { loader.hasExtensionFile(scraper.id) } returns false
        coEvery { loader.downloadExtension(any(), any()) } returns null
        every { parser.tryParse(repo.url) } returns null
        val manager = manager(listOf(scraper))

        manager.restoreMissingScraperFiles(listOf(scraper))
        manager.refreshRepository(repo.id)
        manager.restoreMissingScraperFiles(listOf(scraper))

        coVerify(exactly = 2) { loader.downloadExtension(any(), any()) }
    }

    @Test
    fun `repository refresh keeps a disabled extension disabled`() = runBlocking<Unit> {
        val disabled = dexScraper("Alpha", enabled = false)
        every { parser.tryParse(repo.url) } returns ExternalRepoParseResult(
            name = "Repo",
            description = null,
            plugins = listOf(
                ExternalPluginEntry(name = "Alpha", internalName = "Alpha", url = disabled.filename),
                ExternalPluginEntry(name = "Beta", internalName = "Beta", url = "https://example.invalid/Beta.cs3")
            )
        )
        coEvery { loader.downloadExtension(any(), any()) } returns File("x.cs3")
        val saved = slot<List<ScraperInfo>>()
        coEvery { dataStore.saveScrapers(capture(saved)) } returns Unit

        val result = manager(listOf(disabled)).refreshRepository(repo.id)

        assertTrue(result.isSuccess)
        val byName = saved.captured.associateBy { it.name }
        assertEquals(2, byName.size)
        assertFalse(byName.getValue("Alpha").enabled)
        assertTrue(byName.getValue("Beta").enabled)
    }
}
