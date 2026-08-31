package com.arflix.tv.core.plugin

import com.arflix.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.arflix.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.arflix.tv.core.plugin.cloudstream.ExternalRepoParser
import com.arflix.tv.data.local.PluginDataStore
import com.arflix.tv.data.repository.CloudSyncInvalidationBus
import com.arflix.tv.domain.model.PluginRepository
import com.arflix.tv.domain.model.RepositoryType
import com.arflix.tv.domain.model.ScraperInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression test for the C2 cloud-sync bug: restoring plugin repositories/scrapers from a
 * cloud snapshot must actually download the .cs3 DEX file for EXTERNAL_DEX scrapers that are
 * missing locally, not just write the metadata into PluginDataStore (see docs/19 in the office
 * repo for the root-cause writeup).
 */
class PluginManagerCloudSyncTest {
    private val dataStore = mockk<PluginDataStore>()
    private val runtime = mockk<PluginRuntime>()
    private val externalRepoParser = mockk<ExternalRepoParser>()
    private val externalExtensionLoader = mockk<ExternalExtensionLoader>()
    private val externalExtensionRunner = mockk<ExternalExtensionRunner>()
    private val invalidationBus = mockk<CloudSyncInvalidationBus>()
    private lateinit var manager: PluginManager

    @Before
    fun setUp() {
        manager = PluginManager(
            dataStore,
            runtime,
            externalRepoParser,
            externalExtensionLoader,
            externalExtensionRunner,
            invalidationBus
        )
        coEvery { dataStore.saveRepositories(any()) } just io.mockk.Runs
        coEvery { dataStore.saveScrapers(any()) } just io.mockk.Runs
    }

    private fun scraper(id: String, type: RepositoryType = RepositoryType.EXTERNAL_DEX) = ScraperInfo(
        id = id,
        name = id,
        description = "",
        version = "1.0.0",
        filename = "https://example.com/$id.cs3",
        supportedTypes = listOf("movie", "tv"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo1",
        formats = null,
        type = type
    )

    @Test
    fun `missing local file triggers exactly one download per scraper`() = runBlocking {
        val missing = scraper("repo1:missing")
        every { externalExtensionLoader.hasLocalExtension("repo1:missing") } returns false
        coEvery { externalExtensionLoader.downloadExtension("repo1:missing", missing.filename) } returns
            File("dummy.cs3")

        manager.syncScrapersFromCloud(emptyList(), listOf(missing))

        coVerify(exactly = 1) { externalExtensionLoader.downloadExtension("repo1:missing", missing.filename) }
    }

    @Test
    fun `existing local file is not downloaded again`() = runBlocking {
        val existing = scraper("repo1:existing")
        every { externalExtensionLoader.hasLocalExtension("repo1:existing") } returns true

        manager.syncScrapersFromCloud(emptyList(), listOf(existing))

        coVerify(exactly = 0) { externalExtensionLoader.downloadExtension(any(), any()) }
    }

    @Test
    fun `non-EXTERNAL_DEX scrapers are never downloaded`() = runBlocking {
        val jsScraper = scraper("repo1:js", type = RepositoryType.NUVIO_JS)

        manager.syncScrapersFromCloud(emptyList(), listOf(jsScraper))

        coVerify(exactly = 0) { externalExtensionLoader.downloadExtension(any(), any()) }
    }

    @Test
    fun `repositories and scrapers are persisted to the data store`() = runBlocking {
        val repo = PluginRepository(id = "repo1", name = "Repo 1", url = "https://example.com/manifest.json")
        val existing = scraper("repo1:existing")
        every { externalExtensionLoader.hasLocalExtension("repo1:existing") } returns true

        manager.syncScrapersFromCloud(listOf(repo), listOf(existing))

        coVerify(exactly = 1) { dataStore.saveRepositories(listOf(repo)) }
        coVerify(exactly = 1) { dataStore.saveScrapers(listOf(existing)) }
    }
}
