package com.arflix.tv.ui.screens.details

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the source picker's empty state rests on.
 *
 * Both were wrong for a setup whose only provider is IPTV: the spinner was
 * never switched off when the provider answered with nothing, and when it was,
 * the text told the user to go install a streaming addon.
 */
class StreamSpinnerAndProviderStateTest {

    @Test
    fun `last source job finding nothing stops the spinner`() {
        assertTrue(
            shouldStopStreamSpinner(
                isLoadingStreams = true,
                hasStreams = false,
                pluginScrapersLoading = false,
                otherSourceJobsActive = false
            )
        )
    }

    @Test
    fun `spinner keeps running while another source job is still working`() {
        assertFalse(
            shouldStopStreamSpinner(
                isLoadingStreams = true,
                hasStreams = false,
                pluginScrapersLoading = false,
                otherSourceJobsActive = true
            )
        )
    }

    @Test
    fun `spinner keeps running while plugin scrapers are still working`() {
        assertFalse(
            shouldStopStreamSpinner(
                isLoadingStreams = true,
                hasStreams = false,
                pluginScrapersLoading = true,
                otherSourceJobsActive = false
            )
        )
    }

    @Test
    fun `sources already on screen are never taken as a reason to touch the flag`() {
        assertFalse(
            shouldStopStreamSpinner(
                isLoadingStreams = true,
                hasStreams = true,
                pluginScrapersLoading = false,
                otherSourceJobsActive = false
            )
        )
    }

    @Test
    fun `an IPTV provider alone counts as a stream provider`() {
        assertTrue(
            hasAnyStreamProvider(
                streamingAddonCount = 0,
                hasHomeServerConnections = false,
                hasIptvVodProviders = true
            )
        )
    }

    @Test
    fun `no addon no home server and no IPTV provider is the only empty setup`() {
        assertFalse(
            hasAnyStreamProvider(
                streamingAddonCount = 0,
                hasHomeServerConnections = false,
                hasIptvVodProviders = false
            )
        )
        assertTrue(
            hasAnyStreamProvider(
                streamingAddonCount = 1,
                hasHomeServerConnections = false,
                hasIptvVodProviders = false
            )
        )
        assertTrue(
            hasAnyStreamProvider(
                streamingAddonCount = 0,
                hasHomeServerConnections = true,
                hasIptvVodProviders = false
            )
        )
    }
}
