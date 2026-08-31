package com.arflix.tv.core.plugin

import com.arflix.tv.data.local.PluginDataStore
import com.arflix.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.arflix.tv.domain.model.PluginRepository
import com.arflix.tv.domain.model.RepositoryType
import com.arflix.tv.domain.model.ScraperInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PluginManagerCloudSyncTest {

    @Test
    fun `syncScrapersFromCloud saves repos and scrapers`() {
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        every { loader.extensionFileExists(any()) } returns true

        val manager = createTestPluginManager(dataStore, loader)

        val repo = PluginRepository(
            id = "test-repo",
            name = "Test Repo",
            manifestUrl = "https://example.com/manifest.json"
        )
        val scraper = ScraperInfo(
            id = "test-repo:test-scraper",
            name = "Test Scraper",
            description = "Test",
            version = "1.0",
            filename = "https://example.com/test.cs3",
            supportedTypes = listOf("movie"),
            enabled = true,
            manifestEnabled = true,
            logo = null,
            contentLanguage = emptyList(),
            repositoryId = "test-repo",
            formats = null,
            type = RepositoryType.EXTERNAL_DEX
        )

        runBlocking {
            manager.syncScrapersFromCloud(listOf(repo), listOf(scraper))
        }

        verify { dataStore.saveRepositories(listOf(repo)) }
        verify { dataStore.saveScrapers(listOf(scraper)) }
    }

    @Test
    fun `syncScrapersFromCloud downloads missing EXTERNAL_DEX scrapers`() {
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        every { loader.extensionFileExists("test-repo:test-scraper") } returns false
        coEvery { loader.downloadExtension(any(), any()) } returns mockk()

        val manager = createTestPluginManager(dataStore, loader)

        val scraper = ScraperInfo(
            id = "test-repo:test-scraper",
            name = "Test Scraper",
            description = "Test",
            version = "1.0",
            filename = "https://example.com/test.cs3",
            supportedTypes = listOf("movie"),
            enabled = true,
            manifestEnabled = true,
            logo = null,
            contentLanguage = emptyList(),
            repositoryId = "test-repo",
            formats = null,
            type = RepositoryType.EXTERNAL_DEX
        )

        runBlocking {
            manager.syncScrapersFromCloud(emptyList(), listOf(scraper))
        }

        coVerify(exactly = 1) {
            loader.downloadExtension("test-repo:test-scraper", "https://example.com/test.cs3")
        }
    }

    @Test
    fun `syncScrapersFromCloud skips re-download of existing files`() {
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        every { loader.extensionFileExists("test-repo:test-scraper") } returns true
        coEvery { loader.downloadExtension(any(), any()) } returns mockk()

        val manager = createTestPluginManager(dataStore, loader)

        val scraper = ScraperInfo(
            id = "test-repo:test-scraper",
            name = "Test Scraper",
            description = "Test",
            version = "1.0",
            filename = "https://example.com/test.cs3",
            supportedTypes = listOf("movie"),
            enabled = true,
            manifestEnabled = true,
            logo = null,
            contentLanguage = emptyList(),
            repositoryId = "test-repo",
            formats = null,
            type = RepositoryType.EXTERNAL_DEX
        )

        runBlocking {
            manager.syncScrapersFromCloud(emptyList(), listOf(scraper))
        }

        coVerify(exactly = 0) { loader.downloadExtension(any(), any()) }
    }

    @Test
    fun `syncScrapersFromCloud ignores non-EXTERNAL_DEX scrapers`() {
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        coEvery { loader.downloadExtension(any(), any()) } returns mockk()

        val manager = createTestPluginManager(dataStore, loader)

        val scraper = ScraperInfo(
            id = "test-repo:test-nuvio",
            name = "Test Nuvio",
            description = "Test",
            version = "1.0",
            filename = "script.js",
            supportedTypes = listOf("movie"),
            enabled = true,
            manifestEnabled = true,
            logo = null,
            contentLanguage = emptyList(),
            repositoryId = "test-repo",
            formats = null,
            type = RepositoryType.NUVIO_JS
        )

        runBlocking {
            manager.syncScrapersFromCloud(emptyList(), listOf(scraper))
        }

        coVerify(exactly = 0) { loader.downloadExtension(any(), any()) }
        verify { dataStore.saveScrapers(listOf(scraper)) }
    }

    @Test
    fun `syncScrapersFromCloud handles empty lists gracefully`() {
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)

        val manager = createTestPluginManager(dataStore, loader)

        runBlocking {
            manager.syncScrapersFromCloud(emptyList(), emptyList())
        }

        verify(exactly = 0) { dataStore.saveRepositories(any()) }
        verify(exactly = 0) { dataStore.saveScrapers(any()) }
    }

    private fun createTestPluginManager(
        dataStore: PluginDataStore,
        externalExtensionLoader: ExternalExtensionLoader
    ): PluginManager {
        val runtime = mockk<PluginRuntime>(relaxed = true)
        val externalRepoParser = mockk<ExternalRepoParser>(relaxed = true)
        val externalExtensionRunner = mockk<ExternalExtensionRunner>(relaxed = true)
        val invalidationBus = mockk<CloudSyncInvalidationBus>(relaxed = true)

        return PluginManager(
            dataStore = dataStore,
            runtime = runtime,
            externalRepoParser = externalRepoParser,
            externalExtensionLoader = externalExtensionLoader,
            externalExtensionRunner = externalExtensionRunner,
            invalidationBus = invalidationBus
        )
    }
}
